# Target pins and execution boundary

Checked 2026-10-08 against official release source and the current SystemcoreTesting compatibility matrix. The chosen library build pins are separate from the team's actual controller inventory, which has not been supplied or inspected. No image, Driver Station, vendor or hardware compatibility has been established by desktop tests. Robot integration remains on hold.

| Library profile | Intended controller | Java | WPILib / packages | Build tooling |
|---|---|---|---|---|
| `wpilib2026` (immediate) | roboRIO | 17 | `2026.2.1`, `edu.wpi.first.*` | pinned Gradle 8.11 wrapper |
| `wpilib2027` (separate) | Systemcore alpha/beta | 25 | `2027.0.0-alpha-7`, `org.wpilib.*` | separate pinned Gradle 9.4.1 wrapper |
| Requested 2026 + Systemcore | **No official supported target found** | — | No compatibility profile is provided | — |

The [2026.2.1 release](https://github.com/wpilibsuite/allwpilib/releases/tag/v2026.2.1) is an official 2026 season release and states compatibility with the kickoff 2026 roboRIO image. The [SystemcoreTesting matrix](https://github.com/wpilibsuite/SystemcoreTesting#software-compatibility) lists only 2027 alpha toolchains. Its posted alpha software is incompatible with roboRIO. There is no official 2026 Systemcore runtime/toolchain in that matrix; supporting it would require work outside this library's adapter scope. The offseason game year does not select a controller toolchain.

For alpha-7, the [release](https://github.com/wpilibsuite/allwpilib/releases/tag/v2027.0.0-alpha-7) requires image 14 and FIRST Driver Station Alpha 7 and says earlier alpha vendordeps are incompatible. The current matrix lists image >=14 with >=alpha-7 and notes NI DS compatibility with limited features. For an eventual team handoff, use the release's matched image 14/DS alpha-7 baseline and record exact image/hardware revision and DS build before controller qualification. This library depends on no vendor library. If the robot application adds vendors, pin the matrix's alpha-7-specific releases individually; do not import a floating dev vendor or assume alpha6 compatibility. Actual team roboRIO image/DS/vendor pins also remain to be recorded before integration.

The [alpha-7 build source](https://github.com/wpilibsuite/allwpilib/blob/v2027.0.0-alpha-7/build.gradle) uses Java release 25 and Gradle 9.4.1. The [Gradle compatibility matrix](https://docs.gradle.org/current/userguide/compatibility.html#java_runtime) first supports Java 25 toolchains in Gradle 9.1.0. Gradle 8.11 is used only for the Java 17/2026 profile; do not describe 8.11/Java 25 as a supported build.

## Explicit units

| Value | 2026.2.1 Java API | 2027 alpha-7 Java API |
|---|---|---|
| JSON `capture_server_us` and other `_us` fields | integer microseconds | integer microseconds |
| `TimestampedString.timestamp` / `serverTime` | integer microseconds | integer nanoseconds |
| `NetworkTableInstance.getServerTimeOffset()` | optional integer microseconds | optional integer nanoseconds |
| `TransportSample.firstObservedRobotNs` / `dequeueRobotNs` | robot monotonic nanoseconds | robot monotonic nanoseconds |
| robot estimator time argument | seconds | seconds |

Raw NT metadata is copied without unit conversion. Use the adapter's `timeVersion()` when building a `SyncSnapshot`; this exposes its pinned raw metadata unit. The versioned `ClockMapper` applies checked us-to-ns conversion and subtracts the server-minus-local offset, then uses an explicitly verified local-to-robot epoch mapping. Alpha-7's wire/file microsecond convention does not change its Java API's nanosecond metadata. Local/server metadata `0`/`1` sentinels are not remote synchronized evidence. A present server offset by itself does not establish the robot estimator epoch. Do not use Unix time, Jetson monotonic time, dequeue time or a second latency compensation as capture time.

Pinned sources: [2026 TimestampedString](https://github.com/wpilibsuite/allwpilib/blob/v2026.2.1/ntcore/src/generated/main/java/edu/wpi/first/networktables/TimestampedString.java), [2026 instance](https://github.com/wpilibsuite/allwpilib/blob/v2026.2.1/ntcore/src/generated/main/java/edu/wpi/first/networktables/NetworkTableInstance.java), [alpha-7 TimestampedString](https://github.com/wpilibsuite/allwpilib/blob/v2027.0.0-alpha-7/ntcore/src/generated/main/java/org/wpilib/networktables/TimestampedString.java), [alpha-7 instance](https://github.com/wpilibsuite/allwpilib/blob/v2027.0.0-alpha-7/ntcore/src/generated/main/java/org/wpilib/networktables/NetworkTableInstance.java).

## NT queue ownership and limits

Construct `NtResultQueue` with the robot's existing `NetworkTableInstance`, the absolute configured coherent `/result` topic and a verified robot monotonic nanosecond clock. Default settings are periodic 0.01 seconds, polling storage 32, handoff 64. Allowed period is 0.005–1 seconds, polling storage 2–1024 and handoff 1–1024. `read(maxPackets)` accepts 1–1024 and returns at most that many complete coherent strings in publication queue order. The bounded native read may move up to the configured polling depth into handoff; decoding is separately bounded by the caller's per-cycle count.

The 2026 subscriber uses `sendAll(true)` and `keepDuplicates(true)`. Alpha-7 uses the actual `PubSubOption.SEND_ALL` and `PubSubOption.KEEP_DUPLICATES` constants. Both set explicit `periodic` and `pollStorage`. Convenience topics are never combined into measurements. The adapter has no estimator reference or NT-thread listener and binds reads/epoch changes to one robot-loop thread. Repeated reads do not reinsert the last retained value.

`firstObservedRobotNs` records the local time immediately after `readQueue`, not true network ingress; pending entries keep it unchanged across later loop dequeues. The adapter preserves native `timestamp` and `serverTime`, and status-publication order stays separate from later capture-time measurement reordering.

A native queue containing its full configured capacity might already have overwritten entries. NT readQueue does not expose a dropped-update counter here, so this implementation conservatively reports overload and discards the entire native batch and previous handoff. Handoff overflow also discards the whole batch. Counters report observed overloads and a lower bound of discarded packets. The protocol owner must clear actionable source state on `Batch.overload()` and recover only with a fresh acceptable packet. Full capacity without actual loss can therefore cause a deliberate conservative invalidation.

Native NTCore allocates/receives the string before this adapter or parser sees it. Parser payload/string/token/nesting limits and polling storage do **not** bound an earlier native/network string allocation. Overload accounting cannot recover losses earlier in transport. Hardware bandwidth/load qualification is a separate check.

`advanceConnectionEpoch()` clears handoff and already queued values and increments a local epoch. The robot-loop caller also clears that source's protocol state. A retained packet delivered later is still subject to protocol adoption/advancing-publication rules. Per-source heartbeat/activity expiration remains mandatory: aggregate `instanceConnectedForDiagnostics()` can stay true because another peer is connected. Closing the adapter closes its owned subscriber exactly once; it never closes or reconfigures the supplied shared instance. It registers no listeners.

## Geometry boundary

`GeometryConversions.toPose3d(Packet.Pose)` revalidates the exact `wpilib_nwu` frame, finite meter values and unit WXYZ quaternion (norm tolerance 1e-5, matching six-decimal producer rounding). It converts representation without transforming axes, optical/Rodrigues/legacy yaw data, alliance flipping, field projection or covariance propagation. Preserve the original DTO and pose-candidate role/provenance alongside the WPILib object. Missing geometry remains absent. Capture-time projection and pose-history coverage checks belong to World-State; fusion policy belongs to robot code.

## Desktop checks and reproducibility

The repository does not contain a published Maven artifact/vendordep URL for this library. Gradle resolves only exact official dependency versions specified in each module. Ordinary builds do not fetch producer main or arbitrary source snapshots.

Run Java 17 protocol and 2026 adapter checks with `./gradlew :protocol:check :wpilib2026:check`. Run the separate Java 25 profile with `./gradlew2027 :wpilib2027:check`, pointing toolchain discovery at a locally installed Java 25 when needed (`-Dorg.gradle.java.installations.paths=<JDK25_HOME>`). Install the JDKs before building; this repository does not automatically provision them.

For an explicitly chosen desktop-native check, unpack matching official native archives into a task-local directory and run `:wpilib2026:nativeLoopback -PnativeDir=<directory>` or the same task under `gradlew2027` for alpha-7. The macOS classifiers are `osxuniversal`. Required native components are ntcore/wpiutil/wpinet for 2026 and ntcore/wpiutil/wpinet/datalog for alpha-7. A different OS must use its exact official classifier; do not load controller-native libraries on the desktop. `-PfixturePath=<pinned JSON fixture>` checks exact retained fixture text. Tests create owned test instances, bind only ephemeral localhost ports and never initialize HAL or robot code.

The task's direct Java 25 compiler is Temurin 25+36 macOS AArch64, [immutable release asset](https://github.com/adoptium/temurin25-binaries/releases/tag/jdk-25%2B36), SHA256 `6630ea0f19db61843a8fa84a84b2c71cd120c4155bb5a0e42a74593b0d70fee4`. It is task-local, ignored and not a replacement for the team's WPILib-installed JDK. Java 17 desktop compiler is Temurin 17.0.20.1+1. These desktop checks do not measure controller latency or qualify hardware.

Before hardware work, separately record matched controller hardware/image/DS/vendor versions; verify robot monotonic epoch, sync uncertainty and capture timestamp units using controller-side evidence; check source disconnect/overload/no-motion behavior; measure bounded queue/decoder load on that controller; and obtain explicit integration/deployment scope. No flashing, Jetson deployment, camera configuration or actuation was performed here.

## Python sender to this consumer library

`tools/python_nt_interop.py` uses pinned `pyntcore==2024.3.2.1` in an existing isolated Python environment and this repository's own `PythonInteropReceiver` for each Java profile. The receiver instantiates `NtResultQueue` and `ProtocolDecoder` on an owned localhost server. It does not reuse the producer project's receiver. The sender publishes exact copied golden UTF-8 strings after verifying manifest SHA256/byte counts and reproducing the five legacy strings through the checked baseline `reference/legacy-publisher.py` with `--check`.

The checked case set is six additive fixtures and five legacy fixtures, plus two identical duplicate publications on two configured sources per profile. Packet receipts retain SHA256, decoded source/boot/frame/sequence/profile/capture-us identity, original NT metadata, and source/class hashes. Native client/server clock-offset probes establish Python's microsecond API and the Java profile's raw microsecond/nanosecond unit. Fixed golden capture timestamps are replay provenance; this check does not claim they are currently eligible for fusion.

NT connection and time synchronization alone do not establish each topic's subscription handshake. This harness first sends one golden packet per source and requires actual adapter/decoder acknowledgments, then checks steady-state whole-string ordering and duplicates. Publications made before that barrier can be coalesced during topic startup, as the initial harness run demonstrated. Source lifecycle and advancing-publication eligibility remain the protocol owner's responsibility.

Generate reproducible receiver classpaths with `./gradlew :wpilib2026:pythonInteropClasspath` and `./gradlew2027 :wpilib2027:pythonInteropClasspath`. Then run the pinned Python interpreter with `PYTHONDONTWRITEBYTECODE=1`, passing `--classpath-2026 build/python-nt-interop/classpath2026.txt --classpath-2027 build/python-nt-interop/classpath2027.txt`, matched `--native-2026`/`--native-2027` desktop library directories and explicit `--java-2026`/`--java-2027` executables if needed. The runner binds ephemeral ports only on localhost, caps Java heaps at 96 MiB, uses bounded command queues/deadlines, closes owned instances/processes and writes its evidence into this task. The isolated Python environment may be read-only; Python bytecode writes are disabled by the invocation.

The task result is recorded in `docs/evidence/python-nt-interop.json`. This is CPU desktop NT4 interoperability evidence for both pinned library profiles, including additive and actual-baseline legacy serialization; no controller, HAL, GPU, camera or motion is involved.
