#!/usr/bin/env python3
"""Check the pinned local source contract; optionally verify built candidate artifacts."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def verify(entry: dict) -> None:
    relative = Path(entry["path"])
    if relative.is_absolute() or ".." in relative.parts:
        raise ValueError("manifest path must remain inside checkout")
    path = ROOT / relative
    contents = path.read_bytes()
    if len(contents) != entry["bytes"] or hashlib.sha256(contents).hexdigest() != entry["sha256"]:
        raise ValueError("manifest mismatch: " + str(relative))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--artifacts", action="store_true",
                        help="require the locally built jars/bundles in addition to committed sources")
    args = parser.parse_args()
    manifest = json.loads((ROOT / "docs/api-manifest.json").read_text())
    entries = manifest["files"]
    if args.artifacts:
        entries += manifest["artifacts"] + manifest["bundles"]
    for entry in entries:
        verify(entry)
    print(f"Verified {len(entries)} exact source/artifact pins for {manifest['api_version']}")


if __name__ == "__main__":
    main()
