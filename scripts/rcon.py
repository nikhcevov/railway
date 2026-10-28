#!/usr/bin/env python3
"""Minimal Source RCON client used by the fixture harness.

Usage: rcon.py HOST PORT PASSWORD COMMAND
Exit status 0 means the command was accepted and its response printed.
No third-party packages are required.
"""

import os
import socket
import struct
import sys

AUTH = 3
COMMAND = 2
RESPONSE = 0


def packet(request_id, packet_type, body):
    payload = struct.pack("<ii", request_id, packet_type) + body.encode("utf-8") + b"\x00\x00"
    return struct.pack("<i", len(payload)) + payload


def receive(sock):
    header = sock.recv(4)
    if len(header) != 4:
        raise ConnectionError("RCON connection closed while reading a packet length")
    length = struct.unpack("<i", header)[0]
    body = b""
    while len(body) < length:
        chunk = sock.recv(length - len(body))
        if not chunk:
            raise ConnectionError("RCON connection closed while reading a packet body")
        body += chunk
    request_id, packet_type = struct.unpack("<ii", body[:8])
    return request_id, packet_type, body[8:-2].decode("utf-8", "replace")


def main():
    if len(sys.argv) != 5:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    host, port, password, command = sys.argv[1], int(sys.argv[2]), sys.argv[3], sys.argv[4]
    timeout = float(os.environ.get("FIXTURE_RCON_TIMEOUT", "300"))
    with socket.create_connection((host, port), timeout=5) as sock:
        sock.settimeout(timeout)
        sock.sendall(packet(1, AUTH, password))
        request_id, _, _ = receive(sock)
        if request_id == -1:
            print("RCON authentication failed", file=sys.stderr)
            return 1
        sock.sendall(packet(2, COMMAND, command))
        sock.settimeout(timeout)
        lines = []
        while True:
            try:
                request_id, packet_type, body = receive(sock)
            except socket.timeout:
                break
            if request_id == -1:
                break
            if packet_type == RESPONSE:
                lines.append(body)
                sock.settimeout(0.25)
            if not body:
                break
        output = "\n".join(line for line in lines if line)
        if output:
            print(output)
    return 0


if __name__ == "__main__":
    sys.exit(main())
