# Custom-Vision-Java

A local Java consumer for Custom-Vision's coherent `result` string. Common code is
Java 17 and has no HAL, NT natives, robot-code or World-State dependency. It decodes
capture-relative observations, applies per-source lifecycle rules and returns
consume-once measurements to robot-owned policy. It never inserts estimator poses
or commands motion.

| Profile | Pin | Immediate use |
| --- | --- | --- |
| `protocol` | Java 17, schema 2 / `custom-vision-schema2-2026.1` | CPU replay and shared immutable DTOs |
| `api` / `controls` | Java 17, `0.2.0-local.1` candidate | Camera/pose facade and injected motion-request boundary |
| `wpilib2026` | WPILib 2026.2.1, Java 17, Gradle 8.11 | Separate roboRIO adapter |
| `wpilib2027` | WPILib 2027.0.0-alpha-7, Java 25, Gradle 9.4.1 | Separate Systemcore adapter |

Official SystemcoreTesting lists no supported WPILib 2026 + Systemcore target.
The library build pins are explicit; the team's actual image, Driver Station and
vendor inventory still need confirmation before hardware work. The offseason game
year does not select the controller toolchain. Actual robot integration is on hold.

Run without downloads/native sockets using installed Java 17 and Python 3:

```sh
./tools/check_protocol.sh
javac --release 17 -cp build/direct -d build/direct examples/NoMotionReplay.java
java -cp build/direct NoMotionReplay
```

Reproducible Gradle checks:

```sh
./gradlew --no-daemon :protocol:check :api:check :controls:check :wpilib2026:check :wpilib2026:installExampleCheck
./gradlew2027 --no-daemon -Dorg.gradle.java.installations.paths="$CVJ_JAVA25" :protocol:check :api:check :controls:check :wpilib2027:check :wpilib2027:installExampleCheck
```

Install Java 17 and Java 25 locally; set `JAVA_HOME` to Java 17 and `CVJ_JAVA25`
to the Java 25 JDK directory before the separate 2027 command. Toolchain downloads
are not automatic. The wrappers verify official distribution SHA256. Dependencies use exact versions,
checked-in lockfiles and SHA256 verification metadata. No published Maven/vendordep
URL exists for this local component. Producer fixtures are committed bytes; ordinary
builds never fetch producer main. Generated jars remain local in module `build/libs`.
The manual Java main suites run through `replayTest` / `adapterTest`; Gradle's JUnit
`test` discovery task is disabled because no JUnit runtime is used.

The producer contract is copied verbatim under [fixtures](fixtures/README.md), with
`matched_runtime` manifest pinned to producer core commit
`61b2e636b11d4556097ee586d594e086d9d69dc4`; final producer handoff is
`e9efef9e534f3841acacf782131c927485b7b4f2`. Legacy reference Publisher is pinned to
`2aee0fc1794b31539b02000d16791d4eec8df29a`. The legacy generation script checks its
source hash and reproduces exact bytes with CPU observations and injected NT clocks.
Physical capture correction in fixtures is synthetic metadata, never hardware evidence.

See [API and robot handoff](docs/HANDOFF.md), [target/API pins](docs/targets.md),
[camera/pose facade](docs/CAPTAIN_API.md), [actual admission bridge](docs/ADMISSION_BRIDGE.md),
[offline installation](docs/INSTALLATION.md), [drivetrain request contracts](controls/README.md),
[lifecycle](docs/lifecycle.md), [time/history/ordering](docs/time-history-ordering.md),
[validation report](docs/VALIDATION.md), [Python interoperability](docs/PYTHON_INTEROP.md),
and [separate hardware checks](docs/HARDWARE_CHECKS.md).

[Third-party notices](THIRD_PARTY_NOTICES.md) identify the copied producer contract,
legacy reference and Gradle wrappers. This source repository does not publish Maven
artifacts. An optional consumer must pin a public Git commit or explicitly supply
its own local checkout and verify [source/artifact hashes](docs/api-manifest.json).
