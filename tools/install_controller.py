#!/usr/bin/env python3
"""Verify a candidate bundle and copy its own libraries into an explicit project."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import shutil


def verify(bundle: Path) -> dict:
    manifest = json.loads((bundle / "manifest.json").read_text())
    if manifest.get("format") != "customvision-controller-offline-v1":
        raise ValueError("unsupported bundle manifest")
    expected = {item["path"] for item in manifest["files"]}
    actual = {p.relative_to(bundle).as_posix() for p in bundle.rglob("*") if p.is_file()} - {"manifest.json"}
    if expected != actual:
        raise ValueError("bundle files do not match manifest")
    for item in manifest["files"]:
        relative = Path(item["path"])
        if relative.is_absolute() or ".." in relative.parts:
            raise ValueError("unsafe manifest path")
        path = bundle / relative
        if path.is_symlink() or path.stat().st_size != item["bytes"] or hashlib.sha256(path.read_bytes()).hexdigest() != item["sha256"]:
            raise ValueError(f"checksum mismatch: {relative}")
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", type=Path, default=Path(__file__).resolve().parent)
    parser.add_argument("--project", type=Path)
    parser.add_argument("--verify-only", action="store_true")
    args = parser.parse_args()
    bundle = args.bundle.resolve()
    manifest = verify(bundle)
    if args.verify_only:
        print(f"Verified {manifest['profile']} {manifest['version']}; no project modified")
        return
    if not args.project:
        parser.error("--project must name the caller's project, or use --verify-only")
    project = args.project.resolve()
    if not project.is_dir() or not (project / "build.gradle").is_file():
        raise ValueError("explicit existing Gradle project required")
    destination = project / "customvision" / "maven"
    for ancestor in (project / "customvision", destination):
        if ancestor.is_symlink():
            raise ValueError("refusing an installation destination through a symlink")
    for previous in (project / "customvision").glob("installed-*.json"):
        if json.loads(previous.read_text()).get("profile") != manifest["profile"]:
            raise ValueError("a different controller profile is already installed; use a separate project")
    receipt = project / "customvision" / f"installed-{manifest['profile']}-{manifest['version']}.json"
    encoded_receipt = json.dumps(manifest, indent=2, sort_keys=True) + "\n"
    if receipt.is_symlink() or (receipt.exists() and receipt.read_text() != encoded_receipt):
        raise ValueError("refusing to replace a different installation receipt for the same version")
    if destination.exists():
        for source in (bundle / "maven").rglob("*"):
            if source.is_file():
                target = destination / source.relative_to(bundle / "maven")
                if any(parent.is_symlink() for parent in target.parents if parent != project):
                    raise ValueError("refusing an installation destination through a symlink")
                if target.exists() and (target.is_symlink() or target.read_bytes() != source.read_bytes()):
                    raise ValueError(f"refusing to overwrite different file: {target.relative_to(project)}")
    shutil.copytree(bundle / "maven", destination, dirs_exist_ok=True)
    receipt.write_text(encoded_receipt)
    print(f"Installed exact own Maven artifacts under {destination.relative_to(project)}")
    print("Apply the bundled example/dependencies.gradle snippet explicitly; build.gradle was not changed.")


if __name__ == "__main__":
    main()
