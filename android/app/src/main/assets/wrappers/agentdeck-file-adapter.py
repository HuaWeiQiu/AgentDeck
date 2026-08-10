#!/usr/bin/env python3
import argparse
import json
import os
import re
import resource
import stat
import subprocess
import sys
import tempfile
import zipfile
from pathlib import PurePosixPath
from xml.etree import ElementTree


MAX_SOURCE_BYTES = 20 * 1024 * 1024
MAX_OUTPUT_BYTES = 512 * 1024
MAX_ZIP_ENTRIES = 4096
MAX_ZIP_EXPANDED_BYTES = 64 * 1024 * 1024
MAX_ZIP_MEMBER_BYTES = 16 * 1024 * 1024
MAX_COMPRESSION_RATIO = 200
PDF_TEXT_LIMIT_BYTES = 2 * 1024 * 1024
PRIVATE_PATH = re.compile(
    r"/root/projects/\.agentdeck-attachments/[a-f0-9]{1,16}/"
    r"[a-f0-9-]{36}[.A-Za-z0-9-]{0,32}\Z"
)


class AdapterError(Exception):
    pass


class TextCollector:
    def __init__(self) -> None:
        self.parts = []
        self.bytes_used = 0
        self.truncated = False

    def add(self, value: str) -> None:
        if self.truncated or not value:
            return
        encoded = value.encode("utf-8")
        remaining = MAX_OUTPUT_BYTES - self.bytes_used
        if len(encoded) <= remaining:
            self.parts.append(value)
            self.bytes_used += len(encoded)
            return
        self.parts.append(encoded[:remaining].decode("utf-8", errors="ignore"))
        self.bytes_used = MAX_OUTPUT_BYTES
        self.truncated = True

    def result(self) -> tuple[str, bool]:
        return "".join(self.parts), self.truncated


def fail(message: str) -> None:
    print(f"agentdeck: {message}", file=sys.stderr)
    raise SystemExit(1)


def validate_paths(source: str, output: str) -> None:
    if not PRIVATE_PATH.fullmatch(source) or output != source + ".agentdeck.txt":
        raise AdapterError("invalid private attachment path")


def read_regular_file(path: str, limit: int = MAX_SOURCE_BYTES) -> bytes:
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
    descriptor = os.open(path, flags)
    try:
        info = os.fstat(descriptor)
        if not stat.S_ISREG(info.st_mode) or info.st_size > limit:
            raise AdapterError("source file is invalid or too large")
        value = bytearray()
        while len(value) <= limit:
            chunk = os.read(descriptor, min(128 * 1024, limit + 1 - len(value)))
            if not chunk:
                break
            value.extend(chunk)
        if len(value) > limit:
            raise AdapterError("source file is too large")
        return bytes(value)
    finally:
        os.close(descriptor)


def decode_text(value: bytes) -> str:
    encodings = []
    if value.startswith((b"\xff\xfe", b"\xfe\xff")):
        encodings.append("utf-16")
    encodings.extend(("utf-8-sig", "utf-8", "gb18030"))
    for encoding in encodings:
        try:
            text = value.decode(encoding)
            if "\x00" in text:
                continue
            controls = sum(
                1 for character in text
                if ord(character) < 32 and character not in "\n\r\t\f"
            )
            if controls > max(2, len(text) // 100):
                continue
            return normalize_text(text)
        except UnicodeDecodeError:
            continue
    raise AdapterError("text encoding is unsupported or file is binary")


def normalize_text(value: str) -> str:
    value = value.replace("\r\n", "\n").replace("\r", "\n")
    return "".join(
        character
        for character in value
        if character in "\n\t" or ord(character) >= 32
    )


def bounded_text(value: str, inherited_truncation: bool = False) -> tuple[str, bool]:
    encoded = value.encode("utf-8")
    if len(encoded) <= MAX_OUTPUT_BYTES:
        return value, inherited_truncation
    return encoded[:MAX_OUTPUT_BYTES].decode("utf-8", errors="ignore"), True


def validate_zip(archive: zipfile.ZipFile) -> dict[str, zipfile.ZipInfo]:
    entries = archive.infolist()
    if len(entries) > MAX_ZIP_ENTRIES:
        raise AdapterError("archive has too many entries")
    expanded = 0
    result = {}
    for entry in entries:
        path = PurePosixPath(entry.filename)
        if path.is_absolute() or ".." in path.parts or "\\" in entry.filename:
            raise AdapterError("archive contains an unsafe entry path")
        if entry.flag_bits & 0x1:
            raise AdapterError("encrypted Office files are unsupported")
        expanded += entry.file_size
        if expanded > MAX_ZIP_EXPANDED_BYTES or entry.file_size > MAX_ZIP_MEMBER_BYTES:
            raise AdapterError("archive expands beyond the safety limit")
        if entry.file_size and entry.file_size / max(entry.compress_size, 1) > MAX_COMPRESSION_RATIO:
            raise AdapterError("archive compression ratio exceeds the safety limit")
        result[entry.filename] = entry
    return result


def read_xml(archive: zipfile.ZipFile, entries: dict[str, zipfile.ZipInfo], name: str):
    entry = entries.get(name)
    if entry is None:
        raise AdapterError(f"Office document is missing {name}")
    value = archive.read(entry)
    if b"<!DOCTYPE" in value.upper() or b"<!ENTITY" in value.upper():
        raise AdapterError("Office XML declarations are unsafe")
    try:
        return ElementTree.fromstring(value)
    except ElementTree.ParseError as error:
        raise AdapterError("Office XML is malformed") from error


def extract_docx(path: str) -> tuple[str, bool]:
    collector = TextCollector()
    with zipfile.ZipFile(path) as archive:
        entries = validate_zip(archive)
        root = read_xml(archive, entries, "word/document.xml")
        namespace = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"
        for paragraph in root.iter(namespace + "p"):
            text = "".join(node.text or "" for node in paragraph.iter(namespace + "t"))
            if text:
                collector.add(text + "\n")
    return collector.result()


def column_name(reference: str) -> str:
    match = re.match(r"[A-Za-z]+", reference)
    return match.group(0).upper() if match else "?"


def extract_xlsx(path: str) -> tuple[str, bool]:
    collector = TextCollector()
    with zipfile.ZipFile(path) as archive:
        entries = validate_zip(archive)
        shared = []
        if "xl/sharedStrings.xml" in entries:
            root = read_xml(archive, entries, "xl/sharedStrings.xml")
            namespace = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"
            for item in root.iter(namespace + "si"):
                shared.append("".join(node.text or "" for node in item.iter(namespace + "t")))
        sheets = sorted(
            name for name in entries
            if re.fullmatch(r"xl/worksheets/sheet[0-9]+\.xml", name)
        )
        if not sheets:
            raise AdapterError("XLSX document has no worksheets")
        namespace = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"
        for sheet_index, name in enumerate(sheets, start=1):
            collector.add(f"[工作表 {sheet_index}]\n")
            root = read_xml(archive, entries, name)
            for row in root.iter(namespace + "row"):
                cells = []
                for cell in row.findall(namespace + "c"):
                    reference = cell.attrib.get("r", "?")
                    value_node = cell.find(namespace + "v")
                    inline_node = cell.find(namespace + "is")
                    formula_node = cell.find(namespace + "f")
                    raw = value_node.text if value_node is not None and value_node.text else ""
                    if cell.attrib.get("t") == "s" and raw.isdigit():
                        index = int(raw)
                        raw = shared[index] if index < len(shared) else ""
                    elif cell.attrib.get("t") == "inlineStr" and inline_node is not None:
                        raw = "".join(node.text or "" for node in inline_node.iter(namespace + "t"))
                    if formula_node is not None:
                        formula = formula_node.text or ""
                        raw = f"公式文本={formula}; 缓存值={raw}"
                    cells.append(f"{column_name(reference)}={normalize_text(raw)}")
                if cells:
                    collector.add("\t".join(cells) + "\n")
    return collector.result()


def pdf_limits() -> None:
    resource.setrlimit(resource.RLIMIT_CPU, (20, 20))
    resource.setrlimit(resource.RLIMIT_FSIZE, (PDF_TEXT_LIMIT_BYTES, PDF_TEXT_LIMIT_BYTES))
    if hasattr(resource, "RLIMIT_AS"):
        resource.setrlimit(resource.RLIMIT_AS, (512 * 1024 * 1024, 512 * 1024 * 1024))


def extract_pdf(path: str) -> tuple[str, bool]:
    temporary = tempfile.NamedTemporaryFile(
        prefix=".agentdeck-pdf-",
        dir=os.path.dirname(path),
        delete=False,
    )
    temporary_path = temporary.name
    temporary.close()
    try:
        result = subprocess.run(
            ["/usr/bin/pdftotext", "-layout", "-nopgbrk", path, temporary_path],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
            timeout=22,
            check=False,
            preexec_fn=pdf_limits,
        )
        size = os.path.getsize(temporary_path)
        limited = size >= PDF_TEXT_LIMIT_BYTES
        if result.returncode != 0 and not limited:
            detail = result.stderr.decode("utf-8", errors="replace").strip()[-160:]
            raise AdapterError("PDF extraction failed" + (f": {detail}" if detail else ""))
        value = read_regular_file(temporary_path, PDF_TEXT_LIMIT_BYTES)
        return bounded_text(decode_text(value), limited)
    except subprocess.TimeoutExpired as error:
        raise AdapterError("PDF extraction timed out") from error
    finally:
        try:
            os.unlink(temporary_path)
        except FileNotFoundError:
            pass


def extract(kind: str, path: str) -> tuple[str, bool]:
    if kind == "text":
        return bounded_text(decode_text(read_regular_file(path)))
    if kind == "pdf":
        read_regular_file(path)
        return extract_pdf(path)
    if kind == "docx":
        read_regular_file(path)
        return extract_docx(path)
    if kind == "xlsx":
        read_regular_file(path)
        return extract_xlsx(path)
    raise AdapterError("unsupported adapter kind")


def write_output(path: str, value: str) -> None:
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0)
    descriptor = os.open(path, flags, 0o600)
    try:
        data = value.encode("utf-8")
        offset = 0
        while offset < len(data):
            offset += os.write(descriptor, data[offset:])
    finally:
        os.close(descriptor)


def main() -> None:
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument("--kind", choices=("text", "pdf", "docx", "xlsx"), required=True)
    parser.add_argument("--source", required=True)
    parser.add_argument("--output", required=True)
    arguments = parser.parse_args()
    validate_paths(arguments.source, arguments.output)
    text, truncated = extract(arguments.kind, arguments.source)
    if not text.strip():
        raise AdapterError("document contains no extractable text")
    write_output(arguments.output, text)
    print(json.dumps(
        {
            "kind": arguments.kind,
            "output": arguments.output,
            "truncated": truncated,
            "bytes": len(text.encode("utf-8")),
        },
        separators=(",", ":"),
    ))


if __name__ == "__main__":
    try:
        main()
    except (AdapterError, OSError, ValueError, zipfile.BadZipFile) as error:
        fail(str(error))
