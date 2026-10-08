#!/usr/bin/env python3
"""Produce deterministic offline packages of only this project's controller libraries."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import struct
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
TARGETS = {
    "2026": {"wpilib": "2026.2.1", "java": 17, "controller": "roboRIO"},
    "2027": {"wpilib": "2027.0.0-alpha-7", "java": 25, "controller": "Systemcore"},
}
POM_NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def stable_json(value):
    if isinstance(value, dict):
        return {key: stable_json(value[key]) for key in sorted(value)}
    if isinstance(value, list):
        return [stable_json(item) for item in value]
    return value


def inspect_jar(path: Path, maximum_major: int, sources: bool) -> None:
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        for name in names:
            if name.startswith("/") or ".." in Path(name).parts:
                raise ValueError(f"unsafe jar entry: {path.name}")
            if name.endswith(".class"):
                if sources or not name.startswith("org/customvision/"):
                    raise ValueError(f"unexpected class in {path.name}: {name}")
                data = archive.read(name)
                if data[:4] != b"\xca\xfe\xba\xbe" or struct.unpack(">H", data[6:8])[0] > maximum_major:
                    raise ValueError(f"wrong Java class version in {path.name}: {name}")
            elif not name.endswith("/") and not name.startswith("META-INF/"):
                if not (sources and name.startswith("org/customvision/") and name.endswith(".java")):
                    raise ValueError(f"unexpected packaged resource in {path.name}: {name}")
        expected = ".java" if sources else ".class"
        if not any(name.endswith(expected) for name in names):
            raise ValueError(f"empty library: {path.name}")


def inspect_pom(path: Path, artifact: str, version: str) -> list[dict[str, str]]:
    root = ET.parse(path).getroot()
    for field, expected in [("groupId", "org.customvision"), ("artifactId", artifact), ("version", version)]:
        if root.findtext(f"m:{field}", namespaces=POM_NS) != expected:
            raise ValueError(f"wrong {field} in {path.name}")
    if root.find("m:repositories", POM_NS) is not None:
        raise ValueError("candidate POM must not embed repositories")
    dependencies = []
    for node in root.findall("m:dependencies/m:dependency", POM_NS):
        item = {field: node.findtext(f"m:{field}", namespaces=POM_NS) or "" for field in ("groupId", "artifactId", "version", "scope")}
        if not item["version"] or any(token in item["version"] for token in ("+", "[", "]", "LATEST", "RELEASE")):
            raise ValueError("all candidate POM dependencies must use exact versions")
        dependencies.append(item)
    return dependencies


def inspect_module(path: Path, artifact: str, version: str) -> None:
    metadata = json.loads(path.read_text())
    component = metadata.get("component", {})
    if any(component.get(key) != value for key, value in
           (("group", "org.customvision"), ("module", artifact), ("version", version))):
        raise ValueError(f"wrong Gradle module coordinates: {path.name}")
    for variant in metadata.get("variants", []):
        for file in variant.get("files", []):
            if file.get("url") not in (f"{artifact}-{version}.jar", f"{artifact}-{version}-sources.jar"):
                raise ValueError(f"unexpected Gradle module file URL: {path.name}")
        for dependency in variant.get("dependencies", []):
            required = dependency.get("version", {}).get("requires", "")
            if not required or any(token in required for token in ("+", "[", "]", "LATEST", "RELEASE")):
                raise ValueError(f"unversioned/floating Gradle module dependency: {path.name}")


def verify(directory: Path) -> dict:
    manifest = json.loads((directory / "manifest.json").read_text())
    expected = {item["path"] for item in manifest["files"]}
    actual = {path.relative_to(directory).as_posix() for path in directory.rglob("*") if path.is_file()} - {"manifest.json"}
    if expected != actual:
        raise ValueError("bundle files do not match manifest")
    for item in manifest["files"]:
        relative = Path(item["path"])
        if relative.is_absolute() or ".." in relative.parts:
            raise ValueError("unsafe manifest path")
        path = directory / relative
        if path.is_symlink() or path.stat().st_size != item["bytes"] or sha(path) != item["sha256"]:
            raise ValueError(f"bundle checksum mismatch: {relative}")
    return manifest


def build(profile: str, version: str) -> tuple[Path, Path]:
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?", version):
        raise ValueError("explicit non-floating candidate version required")
    info = TARGETS[profile]
    parent = ROOT / "build" / "install-bundles"
    directory = parent / f"customvision-{profile}-{version}"
    staging = parent / f".customvision-{profile}-{version}-staging"
    if staging.exists():
        shutil.rmtree(staging)
    staging.mkdir(parents=True)
    coordinates = []
    for module in ("protocol", "api", "controls", f"wpilib{profile}"):
        artifact = f"customvision-{module}"
        relative = Path("org/customvision") / artifact / version
        source = ROOT / "build" / "candidate-maven" / relative
        destination = staging / "maven" / relative
        destination.mkdir(parents=True)
        filename = f"{artifact}-{version}"
        pom = source / f"{filename}.pom"
        dependencies = inspect_pom(pom, artifact, version)
        inspect_module(source / f"{filename}.module", artifact, version)
        maximum_major = info["java"] + 44 if module.startswith("wpilib") else 61
        for suffix in (".pom", ".module", ".jar", "-sources.jar"):
            original = source / f"{filename}{suffix}"
            if suffix.endswith(".jar"):
                inspect_jar(original, maximum_major, suffix == "-sources.jar")
            target = destination / original.name
            if suffix == ".module":
                # Same common GAV must not differ merely because Gradle8/9 made it.
                # Keep resolver data; remove only the informational generator label.
                metadata = json.loads(original.read_text())
                metadata.pop("createdBy", None)
                metadata = stable_json(metadata)
                # Gradle's module parser requires this discriminator before all
                # other top-level values; ordinary sorted JSON is not sufficient.
                ordered = {"formatVersion": metadata.pop("formatVersion"), **metadata}
                target.write_text(json.dumps(ordered, indent=2) + "\n")
            else:
                shutil.copyfile(original, target)
        coordinates.append({"groupId": "org.customvision", "artifactId": artifact, "version": version, "dependencies": dependencies})
    shutil.copyfile(ROOT / "tools/install_controller.py", staging / "install.py")
    shutil.copyfile(ROOT / "docs/INSTALLATION.md", staging / "INSTALLATION.md")
    shutil.copyfile(ROOT / "THIRD_PARTY_NOTICES.md", staging / "THIRD_PARTY_NOTICES.md")
    example_root = ROOT / "examples" / f"install-{profile}"
    shutil.copytree(example_root, staging / "example")
    entries = [{"path": path.relative_to(staging).as_posix(), "bytes": path.stat().st_size, "sha256": sha(path)}
               for path in sorted(staging.rglob("*")) if path.is_file()]
    manifest = {
        "format": "customvision-controller-offline-v1", "candidate": True,
        "version": version, "profile": profile, **info,
        "javaCommon": 17, "coordinates": coordinates,
        "excluded": ["WPILib/dependency binaries and native libraries", "Python/Jetson/model services", "GUI/server", "datasets/weights", "fixtures/reference/test tools"],
        "files": entries,
    }
    (staging / "manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
    verify(staging)
    if directory.exists():
        shutil.rmtree(directory)
    staging.rename(directory)
    archive_path = parent / f"{directory.name}.zip"
    with zipfile.ZipFile(archive_path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path in sorted(directory.rglob("*")):
            if path.is_file():
                relative = path.relative_to(directory).as_posix()
                item = zipfile.ZipInfo(relative, date_time=(1980, 1, 1, 0, 0, 0))
                item.compress_type = zipfile.ZIP_DEFLATED
                item.external_attr = 0o100644 << 16
                archive.writestr(item, path.read_bytes())
    (parent / f"{archive_path.name}.sha256").write_text(f"{sha(archive_path)}  {archive_path.name}\n")
    return directory, archive_path


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profile", choices=TARGETS)
    parser.add_argument("--version")
    parser.add_argument("--verify", type=Path)
    args = parser.parse_args()
    if args.verify:
        result = verify(args.verify)
        print(f"Verified {len(result['files'])} files for {result['profile']} {result['version']}")
    elif args.profile and args.version:
        directory, archive = build(args.profile, args.version)
        print(f"Created {directory.relative_to(ROOT)} and {archive.relative_to(ROOT)} SHA256 {sha(archive)}")
    else:
        parser.error("provide --profile and --version, or --verify")


if __name__ == "__main__":
    main()
