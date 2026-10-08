# Python NT4 interoperability with Custom-Vision-Java

Both consumer profiles passed on this task's authorized CPU desktop environment. Each run delivered 13 steady publications: six additive fixtures, five legacy fixtures and two identical duplicate publications, across two configured sources. Two additional golden packets established topic readiness before those assertions. The Java receiver instantiated this library's `NtResultQueue` and `ProtocolDecoder`; it did not use the producer project's Java receiver.

The recorded result is [evidence/python-nt-interop.json](evidence/python-nt-interop.json). It contains fixture-manifest SHA256 values, exact producer revisions, per-packet SHA256 receipts, decoded source/boot/frame/profile/sequence/capture-us identity, raw NT timestamps, and hashes of the actual consumer source and compiled classes. No robot hardware qualification follows from this desktop test.

## Pins and environment

| Component | Checked pin |
|---|---|
| Python sender | Python 3.12.14; `pyntcore==2024.3.2.1` |
| Immediate consumer | WPILib `2026.2.1`; Temurin `17.0.20.1+1`; `edu.wpi.first.*` |
| Separate consumer | WPILib `2027.0.0-alpha-7`; Temurin `25+36`; `org.wpilib.*` |
| Host | macOS 26.6.2, build 25G83, arm64 |
| Additive fixture producer | `61b2e636b11d4556097ee586d594e086d9d69dc4`, `matched_runtime` manifest |
| Legacy serializer | Custom-Vision `2aee0fc1794b31539b02000d16791d4eec8df29a` |

The baseline serializer is this checkout's `reference/legacy-publisher.py`, SHA256 `0b156bd4929d00183593d46cbe66dbc70d962622c77ec4a6a4e68992b05e0565`. Before native transport, the runner executes `tools/generate_legacy_fixtures.py --check`, which reproduces all five legacy fixture strings through that exact serializer and checks byte equality. The NT sender then publishes the copied golden UTF-8 strings unchanged. Every selected additive/legacy fixture must match its manifest SHA256 and byte count; no producer main download occurs.

The sender uses an isolated Python environment with the exact dependency pin. The original run read an existing environment; a fresh checkout can create its own environment below. `PYTHONDONTWRITEBYTECODE=1` prevents imports from writing bytecode into a shared environment. The runner, receivers, reports, classes and native libraries belong to this repository's workspace. It creates no controller connection, camera configuration, HAL initialization, estimator mutation or GPU workload.

## Reproduce

Run from the Custom-Vision-Java repository root with Python 3.12 and locally installed Java 17 and Java 25 JDKs. Create a repository-local Python environment, or override `CVJ_PYTHON` with an existing environment containing the exact pin. Installing `pyntcore` may require a supported platform wheel; this desktop example does not install Jetson or controller libraries. Set `JAVA_HOME` to the Java 17 JDK and `CVJ_JAVA25` to the Java 25 JDK; no sibling checkout is required.

```sh
python3.12 -m venv .venv-interop
.venv-interop/bin/python -m pip install 'pyntcore==2024.3.2.1'
CVJ_PYTHON="$PWD/.venv-interop/bin/python"
: "${JAVA_HOME:?Set JAVA_HOME to a Java 17 JDK}"
: "${CVJ_JAVA25:?Set CVJ_JAVA25 to a Java 25 JDK}"

PYTHONDONTWRITEBYTECODE=1 "$CVJ_PYTHON" -c 'import importlib.metadata; assert importlib.metadata.version("pyntcore") == "2024.3.2.1"'

./gradlew --gradle-user-home .gradle-user --no-daemon --max-workers=1 :wpilib2026:pythonInteropClasspath
./gradlew2027 --gradle-user-home .gradle2027-user --no-daemon --max-workers=1 -Dorg.gradle.java.installations.paths="$CVJ_JAVA25" :wpilib2027:pythonInteropClasspath

PYTHONDONTWRITEBYTECODE=1 "$CVJ_PYTHON" tools/python_nt_interop.py \
  --classpath-2026 build/python-nt-interop/classpath2026.txt \
  --classpath-2027 build/python-nt-interop/classpath2027.txt \
  --native-2026 build/adapters/native2026 \
  --native-2027 build/adapters/native2027 \
  --java-2026 "$JAVA_HOME/bin/java" \
  --java-2027 "$CVJ_JAVA25/bin/java"
```

The native directories in that command contain matching extracted official desktop libraries. For this macOS host, the artifact classifier is `osxuniversal`: NTCore/WPIUtil/WPINet `2026.2.1` for the immediate profile; NTCore/WPIUtil/WPINet/DataLog `2027.0.0-alpha-7` for the separate profile. Use the release repository's `edu.wpi.first.<component>:<component>-cpp` artifacts for 2026 and `org.wpilib.<component>:<component>-cpp` artifacts for alpha-7. Place each archive's `.dylib` files together in its own profile directory. Do not mix native versions or use controller-native binaries on the desktop. The exact Java dependency pins are in each module's `build.gradle`; [targets.md](targets.md) records the target/toolchain boundary.

`--profiles 2026` or `--profiles 2027` runs one chosen profile. `--output <path>` changes the report location. The default runs both and returns a nonzero exit status if either fails; an unavailable target is recorded as a failure, never silently counted as passed.

The task's final native run used the current built `protocol` JAR on each receiver classpath. Hash collection supports both Gradle JAR dependencies and direct compiled-class directories. The receivers' public library APIs, rather than a copied protocol parser, decode the received strings.

## Readiness and ordering

`isConnected()` and a present time offset do not establish every topic's publish/subscribe handshake. The first harness run sent ordered fixtures immediately after those checks and received 11 of 13 expected publications on both versions; the first two publications were absent. That startup timing was a harness defect, not evidence of a decoder incompatibility.

The final harness creates each configured publisher, sends one unchanged golden packet per source and requires an actual `NtResultQueue`/`ProtocolDecoder` receipt for each source. It drains those priming receipts before starting the ordered 13-publication check. All steady packets must arrive, all hashes must be known, and each source's receipt hash list must exactly match its publication list. Two identical packets exercise `SEND_ALL`/`KEEP_DUPLICATES` behavior. A subsequent empty drain verifies that repeated reads cannot reinsert the cached last packet.

This readiness barrier establishes the point from which the ordering assertion applies. It does not promise lossless delivery before subscription establishment, across disconnects or under overload. Production source/session eligibility, tombstones, advancing-publication requirements and expiration remain enforced by the library's separate lifecycle tests and robot-loop owner.

## Units and replay limits

Python `ntcore._now()` and `getServerTimeOffset()` use integer microseconds in the pinned 2024 sender. The 2026 Java receiver's raw NT metadata also uses microseconds. Alpha-7 Java metadata uses nanoseconds; values from the 2024 NT4 wire have microsecond resolution, so received alpha-7 timestamps are divisible by 1000. The Java server's synchronized offset is present zero and remotely published server-side `timestamp`/`serverTime` values agree. The harness checks native clock proximity using the selected profile's unit factor, which would reject a thousandfold unit mismatch.

JSON `capture_server_us` remains integer microseconds in both profiles. Each receipt must preserve the golden packet's decoded value and the SHA of its entire coherent string. Golden capture timestamps are fixed replay provenance: receipt at the present time does not make them fresh. This transport/decoder check does not submit them to live fusion or bypass `ClockMapper`/`SourceSession` freshness and epoch policy.

The harness is deliberately small: Java heaps are capped at 96 MiB; native polling storage is 64 entries per source; handoff is 128 entries; a drain accepts at most 32 packets per source. The response queue holds at most 128 lines and captured logs at most 100 lines. Connection/topic checks have five-second deadlines, command responses three-second deadlines, initial Java readiness eight seconds, and shutdown uses bounded terminate/kill fallbacks. Each profile binds an ephemeral localhost port and runs sequentially. These are test workload bounds, not a controller performance benchmark or a bound on NTCore's earlier string allocation.
