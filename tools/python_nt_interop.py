#!/usr/bin/env python3
"""Own consumer-library NT4 interoperability check: exact pinned fixture bytes over localhost.

Uses pyntcore 2024.3.2.1 as a test sender. Java receivers use Custom-Vision-Java's
NtResultQueue and ProtocolDecoder; no producer receiver, HAL, robot code or hardware.
Fixed golden capture times are replay provenance, not eligibility for live fusion.
"""
import argparse
import base64
from collections import Counter, defaultdict, deque
from datetime import datetime, timezone
import hashlib
from importlib import metadata
import json
import os
from pathlib import Path
import platform
import queue
import socket
import subprocess
import sys
import threading
import time
import zipfile

ROOT = Path(__file__).resolve().parents[1]
LEGACY_PIN = "2aee0fc1794b31539b02000d16791d4eec8df29a"
LEGACY_PUBLISHER_SHA = "0b156bd4929d00183593d46cbe66dbc70d962622c77ec4a6a4e68992b05e0565"
PINS = {"2026": ("2026.2.1", "WPILIB_2026_MICROSECONDS", 1000),
        "2027": ("2027.0.0-alpha-7", "WPILIB_2027_ALPHA7_NANOSECONDS", 1)}


def check(condition, detail):
    if not condition:
        raise AssertionError(detail)


def read_cases():
    cases = []
    manifests = {}
    wanted = ["single_tag", "same_frame_watchdog", "empty_tags",
              "objects_compact_covariance_selection", "empty_objects", "poi_camera_only"]
    for name, profile in [("fixture-manifest.json", "custom-vision-schema2-2026.1"),
                          ("legacy-manifest.json", "legacy-schema2")]:
        path = ROOT / "fixtures" / name
        raw = path.read_bytes()
        manifest = json.loads(raw)
        check(manifest["profile"] == profile, "manifest profile")
        if profile == "legacy-schema2":
            check(manifest["producer_revision"] == LEGACY_PIN, "legacy commit pin")
            check(hashlib.sha256((ROOT / "reference/legacy-publisher.py").read_bytes()).hexdigest()
                  == LEGACY_PUBLISHER_SHA == manifest["publisher_sha256"], "legacy actual Publisher source pin")
        else:
            check(manifest["contract_status"] == "matched_runtime", "producer matched contract milestone")
            check(len(manifest["producer_revision"]) == 40, "exact producer commit")
        manifests[name] = {"sha256": hashlib.sha256(raw).hexdigest(),
                           "producer_revision": manifest["producer_revision"], "profile": profile}
        for entry in manifest["fixtures"]:
            if entry["name"] not in wanted:
                continue
            data = (path.parent / entry["path"]).read_bytes()
            check(hashlib.sha256(data).hexdigest() == entry["sha256"], "fixture SHA256: " + entry["path"])
            check(len(data) == entry["byte_count"], "fixture byte count: " + entry["path"])
            payload = json.loads(data)
            check(payload.get("protocol_profile", "legacy-schema2") == profile, "fixture wire profile")
            cases.append({"name": entry["name"], "path": str((path.parent / entry["path"]).relative_to(ROOT)),
                          "sha256": entry["sha256"], "byte_count": len(data), "text": data.decode("utf-8"),
                          "payload": payload, "profile": profile})
    check(len(cases) == 11, "expected six additive and five legacy fixtures")
    return cases, manifests


class Receiver:
    def __init__(self, java, classpath, native, version, port, sources):
        self.responses = queue.Queue(maxsize=128)
        self.logs = deque(maxlen=100)
        args = [java, "-Xmx96m"]
        if version == "2027":
            args += ["--enable-native-access=ALL-UNNAMED"]
        args += ["-Djava.library.path=" + str(native), "-cp", classpath,
                 "org.customvision.wpilib" + version + ".PythonInteropReceiver", str(port)]
        for namespace, pipeline, kind in sources:
            args += [namespace, pipeline, kind]
        environment = dict(os.environ, DYLD_LIBRARY_PATH=str(native))
        self.process = subprocess.Popen(args, cwd=ROOT, env=environment, stdin=subprocess.PIPE,
                                        stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                        text=True, encoding="utf-8", bufsize=1)
        threading.Thread(target=self._read, daemon=True).start()
        try:
            ready = self.response("READY", 8)
            check(ready[2:] == list(PINS[version][:2]), "Java receiver target/time version pin")
        except Exception:
            self.close()
            raise

    def _read(self):
        for line in self.process.stdout:
            if line.startswith("CVJ_INTEROP\t"):
                try:
                    self.responses.put_nowait(line.rstrip("\n").split("\t"))
                except queue.Full:
                    self.logs.append("test response queue overflow")
                    return
            else:
                self.logs.append(line.rstrip("\n"))

    def response(self, kind, timeout=3):
        try:
            result = self.responses.get(timeout=timeout)
        except queue.Empty:
            raise RuntimeError(f"Java {kind} response absent; exit={self.process.poll()}; logs={list(self.logs)[-8:]}")
        check(result[1] == kind, f"expected Java {kind}, got {result[1]}")
        return result

    def request(self, command):
        self.process.stdin.write(command + "\n")
        self.process.stdin.flush()
        if command != "DRAIN":
            return self.response("CLOSED" if command == "CLOSE" else command)
        samples = []
        while True:
            result = self.responses.get(timeout=3)
            if result[1] == "DRAIN_END":
                check(int(result[3]) == 0, "Java adapter overload")
                check(int(result[2]) == len(samples), "Java complete accepted receipt count")
                return samples
            check(result[1] != "REJECT", "actual Java decoder rejected SHA " + result[2])
            check(result[1] == "SAMPLE", "unexpected Java receipt")
            decode = lambda index: base64.b64decode(result[index]).decode("utf-8")
            samples.append({"sha256": result[2], "namespace": decode(3), "pipeline": decode(4),
                            "type": decode(5), "profile": decode(6), "frame_id": int(result[7]),
                            "boot_id": decode(8), "packet_seq": int(result[9]),
                            "nt_timestamp_raw": int(result[10]), "nt_server_time_raw": int(result[11]),
                            "java_nt_now_raw": int(result[12]), "capture_server_us": int(result[13])})

    def close(self):
        if self.process.poll() is None:
            try:
                self.request("CLOSE")
                self.process.wait(timeout=3)
            except Exception:
                self.process.terminate()
                try:
                    self.process.wait(timeout=2)
                except subprocess.TimeoutExpired:
                    self.process.kill()
                    self.process.wait(timeout=2)


def eventually(predicate, detail):
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        value = predicate()
        if value:
            return value
        time.sleep(.01)
    raise AssertionError("timed out: " + detail)


def run_profile(args, version, cases):
    import ntcore
    pin, unit_name, unit_ns = PINS[version]
    classpath = Path(getattr(args, "classpath_" + version)).read_text().strip()
    native = Path(getattr(args, "native_" + version)).resolve()
    java = str(Path(getattr(args, "java_" + version)).resolve())
    java_info = subprocess.run([java, "-version"], capture_output=True, text=True, check=True, timeout=5).stderr.splitlines()
    check((('"17.' in java_info[0]) if version == "2026" else ('"25' in java_info[0])), "pinned Java language version")
    sources = sorted({("/CustomVisionJavaInterop/" + case["payload"]["pipeline"],
                       case["payload"]["pipeline"], case["payload"]["type"]) for case in cases})
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
    receiver = None
    instance = None
    publishers = {}
    try:
        receiver = Receiver(java, classpath, native, version, port, sources)
        instance = ntcore.NetworkTableInstance.create()
        instance.setServer("127.0.0.1", port)
        instance.startClient4("Custom-Vision-Java-interop-pyntcore-2024")
        eventually(instance.isConnected, "Python client -> own Java server")
        eventually(lambda: instance.getServerTimeOffset() is not None, "Python client microsecond synchronization")
        status = receiver.request("STATUS")
        check(status[3] == "0" and status[4] == "true", "Java server present-zero offset and connected peer")
        python_nt_now_us = ntcore._now()
        python_offset_us = instance.getServerTimeOffset()
        java_now_ns = int(status[2]) * unit_ns
        check(abs((python_nt_now_us + python_offset_us) * 1000 - java_now_ns) < 2_000_000_000,
              "Python microseconds map to pinned Java metadata units")
        expected = defaultdict(list)
        by_sha = {case["sha256"]: case for case in cases}
        # NT is asynchronous: connected/time-sync does not prove each topic's subscription handshake.
        # Prime each configured source, then require actual adapter+decoder acknowledgments before
        # asserting steady-state SEND_ALL ordering. Golden bytes remain unchanged.
        prime_by_source = {}
        for case in cases:
            namespace = "/CustomVisionJavaInterop/" + case["payload"]["pipeline"]
            prime_by_source.setdefault(namespace, case)
        for namespace, case in prime_by_source.items():
            options = ntcore.PubSubOptions(periodic=.01, sendAll=True, keepDuplicates=True)
            publishers[namespace] = instance.getStringTopic(namespace + "/result").publish(options)
            publishers[namespace].set(case["text"])
            instance.flush()
        priming_receipts = []
        ready_sources = set()
        prime_deadline = time.monotonic() + 5
        while ready_sources != set(prime_by_source) and time.monotonic() < prime_deadline:
            batch = receiver.request("DRAIN")
            priming_receipts += batch
            for receipt in batch:
                check(receipt["sha256"] == prime_by_source[receipt["namespace"]]["sha256"], "source readiness exact fixture")
                ready_sources.add(receipt["namespace"])
            time.sleep(.01)
        check(ready_sources == set(prime_by_source), "topic publication/subscription readiness")
        check(not receiver.request("DRAIN"), "priming handoff drained before ordered publication check")
        publications = cases + [cases[0], cases[0]]
        for case in publications:
            payload = case["payload"]
            namespace = "/CustomVisionJavaInterop/" + payload["pipeline"]
            if namespace not in publishers:
                options = ntcore.PubSubOptions(periodic=.01, sendAll=True, keepDuplicates=True)
                publishers[namespace] = instance.getStringTopic(namespace + "/result").publish(options)
            publishers[namespace].set(case["text"])
            expected[namespace].append(case["sha256"])
            instance.flush()
            time.sleep(.025)
        received = []
        deadline = time.monotonic() + 5
        while len(received) < len(publications) and time.monotonic() < deadline:
            received += receiver.request("DRAIN")
            time.sleep(.01)
        actual_counts = Counter(sample["sha256"] for sample in received)
        expected_counts = Counter(case["sha256"] for case in publications)
        missing = {by_sha[sha]["path"]: count - actual_counts[sha] for sha, count in expected_counts.items() if count > actual_counts[sha]}
        check(len(received) == len(publications), f"all coherent strings and duplicates: expected={len(publications)}, actual={len(received)}, missing={missing}")
        observed = defaultdict(list)
        for sample in received:
            case = by_sha.get(sample["sha256"])
            check(case is not None, "unknown or changed wire bytes")
            payload = case["payload"]
            check(sample["pipeline"] == payload["pipeline"] and sample["type"] == payload["type"], "source decode identity")
            check(sample["profile"] == case["profile"] and sample["frame_id"] == payload["frame_id"]
                  and sample["boot_id"] == payload["boot_id"], "profile/observation decode identity")
            check(sample["packet_seq"] == payload.get("packet_seq", -1), "additive sequence / legacy absence")
            check(sample["capture_server_us"] == (payload["capture_server_us"] or 0), "JSON _us value preserved without ns coercion")
            check(sample["nt_timestamp_raw"] == sample["nt_server_time_raw"] > 1, "actual server-side remote timestamp epoch")
            check(abs(sample["java_nt_now_raw"] - sample["nt_timestamp_raw"]) * unit_ns < 3_000_000_000,
                  "actual metadata units / thousandfold mismatch")
            if version == "2027":
                check(sample["nt_timestamp_raw"] % 1000 == 0, "2024 NT4 microsecond wire -> alpha7 Java nanoseconds")
            observed[sample["namespace"]].append(sample["sha256"])
        check(dict(observed) == dict(expected), "per-source publication order and duplicate bytes")
        check(not receiver.request("DRAIN"), "drain cannot reinsert retained last value")
        source_paths = [ROOT / "protocol/src/main/java/org/customvision/protocol/ProtocolDecoder.java",
                        ROOT / "protocol/src/main/java/org/customvision/protocol/Json.java",
                        ROOT / f"wpilib{version}/src/main/java/org/customvision/wpilib{version}/NtResultQueue.java",
                        ROOT / f"wpilib{version}/src/test/java/org/customvision/wpilib{version}/PythonInteropReceiver.java"]
        source_hashes = {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest() for path in source_paths}
        class_hashes = {}
        for name in [f"org/customvision/wpilib{version}/PythonInteropReceiver.class",
                     f"org/customvision/wpilib{version}/NtResultQueue.class",
                     "org/customvision/protocol/ProtocolDecoder.class"]:
            class_bytes = None
            for entry in classpath.split(os.pathsep):
                location = Path(entry)
                if (location / name).is_file():
                    class_bytes = (location / name).read_bytes()
                    break
                if location.is_file() and location.suffix == ".jar":
                    with zipfile.ZipFile(location) as archive:
                        try:
                            class_bytes = archive.read(name)
                            break
                        except KeyError:
                            continue
            check(class_bytes is not None, "own compiled consumer class present: " + name)
            class_hashes[name] = hashlib.sha256(class_bytes).hexdigest()
        return {"passed": True, "wpilib_version": pin, "java_version": java_info,
                "consumer_source_sha256": source_hashes, "consumer_class_sha256": class_hashes,
                "metadata_unit": unit_name, "source_count": len(sources), "publication_count": len(publications),
                "python_offset_us": python_offset_us, "python_nt_now_us": python_nt_now_us,
                "java_nt_now_ns": java_now_ns, "priming_receipts": priming_receipts, "receipts": received,
                "checks": ["fixture_manifest_sha_and_byte_count", "legacy_pinned_actual_publisher_source_sha",
                           "actual_python_nt4_to_own_java_adapter_decoder", "additive_and_legacy_profiles",
                           "whole_bytes_sha_and_decoded_source_observation_identity", "topic_readiness_acknowledgments", "send_all_keep_duplicates_order",
                           "server_offset_and_us_ns_time_mapping", "no_getter_reinsertion"]}
    finally:
        for publisher in publishers.values():
            publisher.close()
        if instance is not None:
            instance.stopClient()
            ntcore.NetworkTableInstance.destroy(instance)
        if receiver is not None:
            receiver.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profiles", choices=["2026", "2027", "both"], default="both")
    for year in PINS:
        parser.add_argument("--classpath-" + year, default=str(ROOT / f"build/adapters/classpath{year}.txt"))
        parser.add_argument("--native-" + year, default=str(ROOT / f"build/adapters/native{year}"))
        parser.add_argument("--java-" + year, default="/usr/bin/java" if year == "2026" else str(ROOT / ".toolchains/jdk-25+36/Contents/Home/bin/java"))
    parser.add_argument("--output", default=str(ROOT / "docs/evidence/python-nt-interop.json"))
    args = parser.parse_args()
    check(metadata.version("pyntcore") == "2024.3.2.1", "test sender must use pinned pyntcore2024.3.2.1")
    cases, manifests = read_cases()
    # Reproduce each legacy fixture through this task's exact baseline Publisher source;
    # --check only reads committed fixtures and reports byte equality.
    subprocess.run([sys.executable, str(ROOT / "tools/generate_legacy_fixtures.py"), "--check"],
                   cwd=ROOT, check=True, timeout=10)
    report = {"checked_utc": datetime.now(timezone.utc).isoformat(), "hardware": False,
              "scope": "CPU desktop localhost consumer-library adapter/decoder; fixed golden capture provenance, no live fusion qualification",
              "environment": {"platform": platform.platform(), "architecture": platform.machine(),
                              "python": sys.version.split()[0], "pyntcore": metadata.version("pyntcore")},
              "manifests": manifests, "legacy_serializer_reproduction": True, "profiles": {}}
    profiles = list(PINS) if args.profiles == "both" else [args.profiles]
    failed = False
    for version in profiles:
        try:
            report["profiles"][version] = run_profile(args, version, cases)
            print(f"Custom-Vision-Java Python NT4 -> WPILib {PINS[version][0]} PASS: 11 pinned fixtures + 2 duplicates; actual adapter+decoder; SHA/identity/order/us-ns metadata")
        except Exception as error:
            failed = True
            report["profiles"][version] = {"passed": False, "error": str(error)}
            print(f"Custom-Vision-Java profile {version} FAIL: {error}", file=sys.stderr)
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2) + "\n")
    return int(failed)


if __name__ == "__main__":
    raise SystemExit(main())
