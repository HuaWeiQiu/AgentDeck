#!/usr/bin/env python3
"""Single-client authenticated loopback bridge for `codex app-server --stdio`."""

import argparse
import hmac
import json
import os
import re
import secrets
import signal
import socket
import subprocess
import threading
from typing import BinaryIO


HOST = "127.0.0.1"
MAX_LINE_BYTES = 1024 * 1024
AUTH_TIMEOUT_SECONDS = 10
ACCEPT_TIMEOUT_SECONDS = 60
IDLE_TIMEOUT_SECONDS = 30 * 60


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--cwd", required=True)
    parser.add_argument("--bootstrap", required=True)
    parser.add_argument("--instance-key", required=True)
    parser.add_argument("--lease", required=True)
    return parser.parse_args()


def read_stream_line(stream: BinaryIO) -> bytes:
    raw = stream.readline(MAX_LINE_BYTES + 1)
    if not raw:
        raise EOFError("client disconnected")
    if not raw.endswith(b"\n"):
        raise ValueError("JSON line exceeds size limit")
    return raw[:-1]


def normalize_json_line(raw: bytes) -> bytes:
    if len(raw) > MAX_LINE_BYTES:
        raise ValueError("JSON line exceeds size limit")
    value = json.loads(raw.decode("utf-8"))
    if not isinstance(value, dict):
        raise ValueError("JSON-RPC payload must be an object")
    return json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8") + b"\n"


def write_bootstrap(path: str, port: int, token: str) -> None:
    payload = json.dumps(
        {"port": port, "token": token, "pid": os.getpid()},
        separators=(",", ":"),
    ).encode("ascii") + b"\n"
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    descriptor = os.open(path, flags, 0o600)
    with os.fdopen(descriptor, "wb") as output:
        output.write(payload)
        output.flush()
        os.fsync(output.fileno())


def write_lease(path: str) -> None:
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    descriptor = os.open(path, flags, 0o600)
    with os.fdopen(descriptor, "w", encoding="ascii") as output:
        output.write(f"{os.getpid()}\n")
        output.flush()
        os.fsync(output.fileno())


def remove_owned_lease(path: str) -> None:
    try:
        if os.path.islink(path):
            return
        with open(path, encoding="ascii") as lease:
            owner = lease.read(32).strip()
        if owner == str(os.getpid()):
            os.unlink(path)
    except (FileNotFoundError, OSError, UnicodeError):
        pass


def forward_client(
    connection: socket.socket,
    client_input: BinaryIO,
    server_input: BinaryIO,
    process: subprocess.Popen[bytes],
) -> None:
    try:
        while True:
            server_input.write(normalize_json_line(read_stream_line(client_input)))
            server_input.flush()
    except (EOFError, OSError, UnicodeError, ValueError, json.JSONDecodeError):
        pass
    finally:
        try:
            client_input.close()
        except OSError:
            pass
        try:
            server_input.close()
        except OSError:
            pass
        if process.poll() is None:
            process.terminate()


def run_bridge(cwd: str, bootstrap: str, instance_key: str, lease: str) -> None:
    if not os.path.isabs(cwd) or "\x00" in cwd:
        raise ValueError("workspace must be an absolute path")
    if re.fullmatch(r"[a-f0-9]{1,16}", instance_key) is None:
        raise ValueError("invalid instance key")
    if not os.path.isabs(lease) or "\x00" in lease:
        raise ValueError("lease must be an absolute path")
    os.makedirs(cwd, exist_ok=True)

    write_lease(lease)
    try:
        token = secrets.token_urlsafe(32)
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
            listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            listener.bind((HOST, 0))
            listener.listen(1)
            listener.settimeout(ACCEPT_TIMEOUT_SECONDS)
            write_bootstrap(bootstrap, listener.getsockname()[1], token)

            connection, address = listener.accept()
            with connection:
                if address[0] != HOST:
                    raise PermissionError("non-loopback client rejected")
                connection.settimeout(AUTH_TIMEOUT_SECONDS)
                client_input = connection.makefile("rb")
                auth = json.loads(read_stream_line(client_input).decode("utf-8"))
                supplied = auth.get("token") if isinstance(auth, dict) else None
                if not isinstance(supplied, str) or not hmac.compare_digest(token, supplied):
                    raise PermissionError("invalid bridge token")
                connection.sendall(b'{"ok":true}\n')
                connection.settimeout(IDLE_TIMEOUT_SECONDS)

                process = subprocess.Popen(
                    [
                        "codex",
                        "-c",
                        "check_for_update_on_startup=false",
                        "app-server",
                        "--listen",
                        "stdio://",
                    ],
                    cwd=cwd,
                    stdin=subprocess.PIPE,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.DEVNULL,
                )
                if process.stdin is None or process.stdout is None:
                    process.kill()
                    raise RuntimeError("failed to open app-server pipes")

                client_thread = threading.Thread(
                    target=forward_client,
                    args=(connection, client_input, process.stdin, process),
                    daemon=True,
                )
                client_thread.start()
                try:
                    while True:
                        raw = process.stdout.readline(MAX_LINE_BYTES + 1)
                        if not raw:
                            break
                        if not raw.endswith(b"\n"):
                            raise ValueError("app-server emitted an oversized or incomplete line")
                        connection.sendall(normalize_json_line(raw[:-1]))
                finally:
                    if process.poll() is None:
                        process.terminate()
                    try:
                        process.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=5)
    finally:
        remove_owned_lease(lease)


def handle_shutdown(signum: int, _frame: object) -> None:
    raise SystemExit(128 + signum)


def main() -> None:
    signal.signal(signal.SIGTERM, handle_shutdown)
    signal.signal(signal.SIGINT, handle_shutdown)
    arguments = parse_args()
    run_bridge(
        arguments.cwd,
        arguments.bootstrap,
        arguments.instance_key,
        arguments.lease,
    )


if __name__ == "__main__":
    main()
