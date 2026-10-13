#!/usr/bin/env python3
"""Sequential native admission bridge check, with explicitly supplied sibling checkout.

Compiles all fresh sources into this repository's build/interop only. It never edits
World-State, resolves floating sources, invokes Gradle, downloads, or uses hardware.
The two native profiles need their previously verified classpath and native library
directories; these are explicit inputs instead of private task absolute defaults.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
VERSIONS = {"2026": ("2026.2.1", 17), "2027": ("2027.0.0-alpha-7", 25)}


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def check(condition, detail):
    if not condition:
        raise RuntimeError(detail)


def sources(world, profile):
    result = sorted((ROOT / "protocol/src/main/java").rglob("*.java"))
    result += sorted((world / "src/main/java").rglob("*.java"))
    result += sorted((world / "adapters/custom-vision-java/src/main/java").rglob("*.java"))
    result += sorted((ROOT / "interop/src/main/java").rglob("*.java"))
    result += sorted((ROOT / ("interop/src/profile" + profile + "/java")).rglob("*.java"))
    result += [ROOT / ("wpilib" + profile + "/src/main/java/org/customvision/wpilib"
                        + profile + "/NtResultQueue.java")]
    check(all(path.is_file() for path in result), "source input missing")
    return result


def source_revision(world):
    revision = subprocess.run(["git", "rev-parse", "HEAD"], cwd=world, capture_output=True, text=True)
    changed = subprocess.run(["git", "status", "--porcelain", "--", "src/main/java",
                              "adapters/custom-vision-java/src/main/java"],
                             cwd=world, capture_output=True, text=True)
    return {"base_commit": revision.stdout.strip() if revision.returncode == 0 else None,
            "source_working_tree_changed": bool(changed.stdout.strip()) if changed.returncode == 0 else None,
            "pin_policy": "actual source hashes below are authoritative for this local-only interoperability check"}


def run_profile(args, world, profile):
    version, java_version = VERSIONS[profile]
    java_home = Path(getattr(args, "java_home" + profile)).resolve()
    cp_file = Path(getattr(args, "classpath" + profile)).resolve()
    natives = Path(getattr(args, "native" + profile)).resolve()
    check((java_home / "bin/javac").is_file() and (java_home / "bin/java").is_file(), "required Java toolchain missing")
    check(cp_file.is_file() and natives.is_dir(), "required cached classpath/native input missing")
    # Drop stale local class directories and our old JAR. The exact current source is compiled below.
    own_artifacts = ("protocol-", "api-", "wpilib2026-", "wpilib2027-", "custom-vision-", "customvision-")
    jars = [Path(part) for part in cp_file.read_text().strip().split(os.pathsep)
            if part.endswith(".jar") and not Path(part).name.startswith(own_artifacts)]
    check(jars and all(path.is_file() for path in jars), "external dependency JAR missing")
    check(any(path.name == "ntcore-java-" + version + ".jar" or profile == "2027"
              and path.name == "alpha7-ntcore-java.jar" for path in jars), "pinned NTCore version absent")
    check(any(path.name == "wpiutil-java-" + version + ".jar" or profile == "2027"
              and path.name == "alpha7-wpiutil-java.jar" for path in jars), "pinned WPIUtil version absent")
    output = ROOT / "build/interop/world-state" / profile
    classes = output / "classes"
    if classes.exists():
        shutil.rmtree(classes)
    classes.mkdir(parents=True, exist_ok=True)
    source_list = sources(world, profile)
    source_hashes_before = {path: sha(path) for path in source_list}
    classpath = os.pathsep.join(map(str, jars))
    compile_command = [str(java_home / "bin/javac"), "--release", str(java_version), "-Xlint:all", "-Werror",
                       "-cp", classpath, "-d", str(classes)] + list(map(str, source_list))
    subprocess.run(compile_command, cwd=ROOT, check=True)
    runtime = [str(java_home / "bin/java"), "-Xmx128m"]
    if profile == "2027":
        runtime += ["--enable-native-access=ALL-UNNAMED"]
    runtime += ["-Djava.library.path=" + str(natives), "-cp", str(classes) + os.pathsep + classpath,
                "org.customvision.wpilib" + profile + ".NativeAdmissionBridgeTests",
                str(ROOT / "fixtures/fixtures/objects_compact_covariance_selection.json")]
    started = time.monotonic()
    result = subprocess.run(runtime, cwd=ROOT, env=dict(os.environ, DYLD_LIBRARY_PATH=str(natives)),
                            capture_output=True, text=True, timeout=45)
    (output / "stdout.txt").write_text(result.stdout)
    (output / "stderr.txt").write_text(result.stderr)
    changed_sources = [str(path.relative_to(ROOT) if path.is_relative_to(ROOT) else path.relative_to(world))
                       for path in source_list if sha(path) != source_hashes_before[path]]
    check(not changed_sources, "source changed between compile and native verification; rerun after freeze: "
          + ", ".join(changed_sources))
    if result.returncode:
        # Bounded native test logs contain no credentials or hardware addresses.
        print(result.stdout[-8000:])
        print(result.stderr[-8000:])
        raise RuntimeError("native admission bridge failed: " + profile)
    check("CVJ_ADMISSION_BRIDGE\tPASS\t" in result.stdout, "native assertion completion receipt missing")
    print(result.stdout.strip())
    local = {}
    upstream = {}
    for path in source_list:
        try:
            local[str(path.relative_to(ROOT))] = source_hashes_before[path]
        except ValueError:
            upstream[str(path.relative_to(world))] = source_hashes_before[path]
    evidence = {"passed": True, "wpilib": version, "java_release": java_version,
                "world_state_revision": source_revision(world),
                "java_runtime": subprocess.check_output([str(java_home / "bin/java"), "-version"],
                                                        stderr=subprocess.STDOUT, text=True).strip(),
                "fixture_sha256": sha(ROOT / "fixtures/fixtures/objects_compact_covariance_selection.json"),
                "empty_objects_fixture_sha256": sha(ROOT / "fixtures/fixtures/empty_objects.json"),
                "local_source_sha256": local, "world_state_source_sha256": upstream,
                "source_snapshot_verified_unchanged_after_run": True,
                "dependency_jar_sha256": {path.name: sha(path) for path in jars},
                "native_library_sha256": {path.name: sha(path) for path in sorted(natives.glob("*.dylib"))},
                "hardware": False, "timing_evidence": "explicitly synthetic loopback verification only",
                "elapsed_seconds": time.monotonic() - started,
                "stdout": result.stdout, "stderr": result.stderr}
    (output / "evidence.json").write_text(json.dumps(evidence, indent=2) + "\n")
    return evidence


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--world-state-checkout", required=True,
                        help="explicit local checkout with the Measurement adapter overload; read-only")
    parser.add_argument("--profile", choices=["2026", "2027", "both"], default="both")
    for profile in VERSIONS:
        parser.add_argument("--java-home" + profile)
        parser.add_argument("--classpath" + profile, help="file containing cached dependency classpath")
        parser.add_argument("--native" + profile, help="directory of previously verified pinned native libraries")
    args = parser.parse_args()
    world = Path(args.world_state_checkout).resolve()
    check((world / "src/main/java/org/frcworldstate/core/WorldEngine.java").is_file(),
          "explicit World-State checkout is not a compatible source tree")
    chosen = list(VERSIONS) if args.profile == "both" else [args.profile]
    for profile in chosen:
        check(all(getattr(args, name + profile) for name in ["java_home", "classpath", "native"]),
              "profile requires --java-home, --classpath and --native inputs: " + profile)
    results = [run_profile(args, world, profile) for profile in chosen]  # Deliberately sequential.
    receipt = {"passed": True, "host": {"system": platform.system(), "release": platform.release(),
                "machine": platform.machine()}, "profiles": results, "hardware": False,
               "actual_robot_integration": "on hold", "world_state_checkout_override": True}
    output = ROOT / "build/interop/world-state/evidence.json"
    output.write_text(json.dumps(receipt, indent=2) + "\n")
    print("Evidence: " + str(output.relative_to(ROOT)))


if __name__ == "__main__":
    main()
