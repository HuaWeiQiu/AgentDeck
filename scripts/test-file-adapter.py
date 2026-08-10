#!/usr/bin/env python3
import importlib.util
import os
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from types import SimpleNamespace
from unittest import mock


REPO_ROOT = Path(__file__).resolve().parent.parent
SPEC = importlib.util.spec_from_file_location(
    "agentdeck_file_adapter",
    REPO_ROOT / "wrappers" / "agentdeck-file-adapter.py",
)
ADAPTER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ADAPTER)


def create_pdf(path: Path) -> None:
    stream = b"BT /F1 18 Tf 72 720 Td (AgentDeck PDF fixture) Tj ET"
    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
        b"/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        b"<< /Length " + str(len(stream)).encode("ascii") + b" >>\nstream\n" +
        stream + b"\nendstream",
    ]
    document = bytearray(b"%PDF-1.4\n")
    offsets = [0]
    for index, value in enumerate(objects, start=1):
        offsets.append(len(document))
        document.extend(f"{index} 0 obj\n".encode("ascii"))
        document.extend(value)
        document.extend(b"\nendobj\n")
    xref = len(document)
    document.extend(f"xref\n0 {len(objects) + 1}\n".encode("ascii"))
    document.extend(b"0000000000 65535 f \n")
    for offset in offsets[1:]:
        document.extend(f"{offset:010d} 00000 n \n".encode("ascii"))
    document.extend(
        f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\n"
        f"startxref\n{xref}\n%%EOF\n".encode("ascii"),
    )
    path.write_bytes(document)


def create_fixtures(destination: Path) -> None:
    destination.mkdir(parents=True, exist_ok=True)
    (destination / "AgentDeck-fixture.txt").write_text(
        "AgentDeck text fixture\n第二行\n",
        encoding="utf-8",
    )
    create_pdf(destination / "AgentDeck-fixture.pdf")
    document_xml = (
        '<w:document xmlns:w="http://schemas.openxmlformats.org/'
        'wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>'
        'AgentDeck DOCX fixture</w:t></w:r></w:p></w:body></w:document>'
    )
    with zipfile.ZipFile(destination / "AgentDeck-fixture.docx", "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("word/document.xml", document_xml)
    sheet_xml = (
        '<worksheet xmlns="http://schemas.openxmlformats.org/'
        'spreadsheetml/2006/main"><sheetData><row r="1">'
        '<c r="A1" t="inlineStr"><is><t>AgentDeck XLSX fixture</t></is></c>'
        '<c r="B1"><f>1+1</f><v>2</v></c>'
        '</row></sheetData></worksheet>'
    )
    with zipfile.ZipFile(destination / "AgentDeck-fixture.xlsx", "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("xl/worksheets/sheet1.xml", sheet_xml)


class FileAdapterTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)

    def tearDown(self):
        self.directory.cleanup()

    def test_text_normalizes_utf16_and_line_endings(self):
        source = self.root / "notes.txt"
        source.write_bytes("第一行\r\nsecond".encode("utf-16"))
        text, truncated = ADAPTER.extract("text", str(source))
        self.assertEqual("第一行\nsecond", text)
        self.assertFalse(truncated)

    def test_text_output_is_bounded(self):
        source = self.root / "large.txt"
        source.write_bytes(b"a" * (ADAPTER.MAX_OUTPUT_BYTES + 100))
        text, truncated = ADAPTER.extract("text", str(source))
        self.assertEqual(ADAPTER.MAX_OUTPUT_BYTES, len(text.encode("utf-8")))
        self.assertTrue(truncated)

    def test_binary_content_is_rejected_even_with_text_extension(self):
        source = self.root / "renamed.txt"
        source.write_bytes(bytes(range(1, 32)) * 20)
        with self.assertRaisesRegex(ADAPTER.AdapterError, "binary"):
            ADAPTER.extract("text", str(source))

    def test_docx_extracts_paragraphs_without_executing_content(self):
        source = self.root / "brief.docx"
        xml = (
            '<w:document xmlns:w="http://schemas.openxmlformats.org/'
            'wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>'
            '角色说明</w:t></w:r></w:p></w:body></w:document>'
        )
        with zipfile.ZipFile(source, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("word/document.xml", xml)
            archive.writestr("word/vbaProject.bin", b"never execute")
        text, truncated = ADAPTER.extract("docx", str(source))
        self.assertEqual("角色说明\n", text)
        self.assertFalse(truncated)

    def test_xlsx_emits_cells_and_formula_as_inert_text(self):
        source = self.root / "data.xlsx"
        sheet = (
            '<worksheet xmlns="http://schemas.openxmlformats.org/'
            'spreadsheetml/2006/main"><sheetData><row r="1">'
            '<c r="A1" t="inlineStr"><is><t>名称</t></is></c>'
            '<c r="B1"><f>1+1</f><v>2</v></c>'
            '</row></sheetData></worksheet>'
        )
        with zipfile.ZipFile(source, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("xl/worksheets/sheet1.xml", sheet)
        text, truncated = ADAPTER.extract("xlsx", str(source))
        self.assertIn("A=名称", text)
        self.assertIn("公式文本=1+1; 缓存值=2", text)
        self.assertFalse(truncated)

    def test_office_zip_rejects_path_traversal(self):
        source = self.root / "unsafe.docx"
        with zipfile.ZipFile(source, "w") as archive:
            archive.writestr("../word/document.xml", "unsafe")
        with self.assertRaisesRegex(ADAPTER.AdapterError, "unsafe entry path"):
            ADAPTER.extract("docx", str(source))

    def test_office_zip_rejects_compression_bomb_ratio(self):
        source = self.root / "bomb.docx"
        with zipfile.ZipFile(source, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("word/document.xml", b"0" * (1024 * 1024))
        with self.assertRaisesRegex(ADAPTER.AdapterError, "compression ratio"):
            ADAPTER.extract("docx", str(source))

    def test_pdf_uses_bounded_poppler_output(self):
        source = self.root / "report.pdf"
        source.write_bytes(b"%PDF-safe-fixture")

        def fake_run(arguments, **_kwargs):
            Path(arguments[-1]).write_text("PDF content", encoding="utf-8")
            return SimpleNamespace(returncode=0, stderr=b"")

        with mock.patch.object(ADAPTER.subprocess, "run", side_effect=fake_run):
            text, truncated = ADAPTER.extract("pdf", str(source))
        self.assertEqual("PDF content", text)
        self.assertFalse(truncated)

    def test_cli_paths_are_limited_to_private_attachment_pair(self):
        source = "/root/projects/.agentdeck-attachments/abcdef12/" + "a" * 36 + ".pdf"
        ADAPTER.validate_paths(source, source + ".agentdeck.txt")
        with self.assertRaises(ADAPTER.AdapterError):
            ADAPTER.validate_paths("/etc/passwd", "/tmp/output")


if __name__ == "__main__":
    if len(sys.argv) == 3 and sys.argv[1] == "--write-fixtures":
        create_fixtures(Path(sys.argv[2]))
    else:
        unittest.main()
