#!/usr/bin/env python3
"""Replace each Modrinth version changelog with only its CHANGELOG.md section.

Dry-run by default. Pass --apply to PATCH matching versions.

Environment:
  MODRINTH_TOKEN       token with VERSION_WRITE
  MODRINTH_PROJECT_ID  project id or slug
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

API = "https://api.modrinth.com/v2"
HEADING = re.compile(r"^##\s+(\S+)(?:\s+.*)?$")


def changelog_sections(path: Path) -> dict[str, str]:
    sections: dict[str, str] = {}
    current = None
    body: list[str] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        match = HEADING.match(line.strip())
        if match:
            if current is not None:
                sections[current] = "\n".join(body).strip()
            current = match.group(1)
            body = []
        elif current is not None:
            body.append(line)
    if current is not None:
        sections[current] = "\n".join(body).strip()
    return sections


def request(url: str, token: str, method: str = "GET", payload: dict | None = None) -> bytes:
    data = None if payload is None else json.dumps(payload).encode()
    req = urllib.request.Request(
        url,
        data=data,
        method=method,
        headers={
            "Authorization": token,
            "User-Agent": "railway-changelog-fix",
            "Content-Type": "application/json",
        },
    )
    with urllib.request.urlopen(req, timeout=30) as response:
        return response.read()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apply", action="store_true", help="PATCH Modrinth versions")
    parser.add_argument("--changelog", default="CHANGELOG.md")
    args = parser.parse_args()

    token = os.environ.get("MODRINTH_TOKEN", "").strip()
    project = os.environ.get("MODRINTH_PROJECT_ID", "").strip()
    if not token or not project:
        print("Set MODRINTH_TOKEN and MODRINTH_PROJECT_ID", file=sys.stderr)
        return 2

    sections = changelog_sections(Path(args.changelog))
    try:
        versions = json.loads(request(f"{API}/project/{project}/version", token))
    except urllib.error.HTTPError as error:
        print(f"Cannot read project {project}: HTTP {error.code}", file=sys.stderr)
        return 1

    changed = 0
    for version in versions:
        number = version.get("version_number")
        section = sections.get(number)
        if not section:
            print(f"skip {number}: no matching CHANGELOG.md section")
            continue
        if (version.get("changelog") or "").strip() == section:
            print(f"ok   {number}: already correct")
            continue
        changed += 1
        print(f"fix  {number}: {len(section)} chars")
        if not args.apply:
            continue
        try:
            request(f"{API}/version/{version['id']}", token, "PATCH", {"changelog": section})
        except urllib.error.HTTPError as error:
            body = error.read().decode(errors="replace")
            print(f"failed {number}: HTTP {error.code} {body}", file=sys.stderr)
            return 1
        print(f"done {number}")

    if not args.apply and changed:
        print("Dry run only; re-run with --apply")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
