#!/usr/bin/env python3
import argparse
import base64
import binascii
import json
import os
import re
import socket
import sys


TOKEN_PATTERN = re.compile(r"[a-f0-9]{64}\Z")
CREDENTIAL_REF_PATTERN = re.compile(r"[A-Za-z0-9._-]{1,80}\Z")
MAX_RESPONSE_BYTES = 12 * 1024
MAX_SECRET_BYTES = 8 * 1024


def fail() -> None:
    print("agentdeck: model provider credential is unavailable", file=sys.stderr)
    raise SystemExit(1)


def read_token(path: str) -> str:
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
    descriptor = os.open(path, flags)
    try:
        value = os.read(descriptor, 129).decode("ascii").strip()
        if os.read(descriptor, 1):
            fail()
    finally:
        os.close(descriptor)
    if not TOKEN_PATTERN.fullmatch(value):
        fail()
    return value


def request_secret(port: int, token: str, credential_ref: str) -> bytearray:
    payload = json.dumps(
        {"token": token, "credential_ref": credential_ref},
        separators=(",", ":"),
    ).encode("utf-8") + b"\n"
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as connection:
        connection.settimeout(5)
        connection.connect(("127.0.0.1", port))
        connection.sendall(payload)
        response = bytearray()
        while len(response) <= MAX_RESPONSE_BYTES:
            chunk = connection.recv(min(4096, MAX_RESPONSE_BYTES + 1 - len(response)))
            if not chunk:
                break
            response.extend(chunk)
            if b"\n" in chunk:
                break
    if len(response) > MAX_RESPONSE_BYTES or b"\n" not in response:
        fail()
    try:
        body = json.loads(bytes(response).split(b"\n", 1)[0].decode("utf-8"))
        if set(body) != {"ok", "api_key_b64"} or body.get("ok") is not True:
            fail()
        secret = bytearray(base64.b64decode(body["api_key_b64"], validate=True))
    except (KeyError, TypeError, ValueError, UnicodeError, binascii.Error, json.JSONDecodeError):
        fail()
    finally:
        response[:] = b"\0" * len(response)
    if not 0 < len(secret) <= MAX_SECRET_BYTES or any(value in secret for value in (0, 10, 13)):
        secret[:] = b"\0" * len(secret)
        fail()
    return secret


def main() -> None:
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--token-file", required=True)
    parser.add_argument("--credential-ref", required=True)
    arguments = parser.parse_args()
    if not 1 <= arguments.port <= 65535:
        fail()
    if not CREDENTIAL_REF_PATTERN.fullmatch(arguments.credential_ref):
        fail()
    token = read_token(arguments.token_file)
    secret = request_secret(arguments.port, token, arguments.credential_ref)
    try:
        sys.stdout.buffer.write(secret)
        sys.stdout.buffer.flush()
    finally:
        secret[:] = b"\0" * len(secret)


if __name__ == "__main__":
    try:
        main()
    except (OSError, UnicodeError):
        fail()
