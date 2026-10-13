# Software validation

Desktop validation environment: macOS 26.6.2 / Darwin arm64, Temurin
17.0.20.1+1 and checksum-verified Temurin 25+36. Work is CPU bounded; the small
benchmarks use one thread; no GPU, Jetson, controller or camera is involved.
No robot code is integrated or deployed, and no release artifact is published.

Passed pure Java 17 `--release 17 -Xlint:all -Werror` and aggregate main suites:
101 adversarial decoder assertions, 445 golden assertions covering 27 exact-byte
producer strings and six hashed malformed examples, five actual pinned legacy
Publisher strings, 223 lifecycle assertions, 49 client orchestration assertions,
and clock/history/global reorder tests. `tools/check_protocol.sh` reproduces these
without downloads/native sockets; `generate_legacy_fixtures.py --check` reproduces
baseline Python serialization and checks source and byte hashes.

Both profile adapters passed 26 synthetic assertions plus actual bidirectional
native localhost NT tests: exact fixture text, metadata unit/precision, publication
order and duplicates, offset availability, retained values after publisher closure,
and subscriber closure preserving shared test instances. These tests use ephemeral
localhost ports and no HAL. Native macOS components are matching version pins.
Own Python NT4 interoperability also passed with pyntcore 2024.3.2.1 (Python 3.12.14)
sending 13 exact packets/profile (11 fixture strings plus two identical duplicates)
to our NtResultQueue+ProtocolDecoder receivers under both Java profiles. Two sources
cover tags/localization, POI and canonical objects, additive and legacy formats.
SHA receipts verify complete bytes and decoded identity/provenance; native metadata
and server offset verify each API's unit. [Evidence](evidence/python-nt-interop.json)
records source/class/fixture pins and observed values. A per-source decoded receipt
readiness barrier avoids assuming NT connection/sync means topic readiness; startup
loss before subscription readiness is not hidden. Fixed old capture timestamps are
replay provenance, not live fusion qualification. See [commands and test limits](PYTHON_INTEROP.md).

Gradle 8.11 is used with Java 17/2026 and Gradle 9.4.1 with Java 25/alpha-7.

Review exposed and fixed rounded-quaternion tolerance disagreement, ambiguous
source identity serialization, later dequeued entries escaping output overload,
entry-time clock causing false future receipts/sync, malformed follow-on packet
clock regression, and measurements aging beyond limits while reordered/queued.
The regressions now pass. Reduced producer invalidation families are accepted
without fabricating omitted diagnostics. The initial fixture-count and Gradle 9
JUnit-discovery failures were corrected; manual suites are explicitly wired to
`check`, with no unconfigured JUnit execution claimed.

The handoff-only queue benchmark [queue-mac.json](benchmarks/queue-mac.json) uses
250 warmup and 1000 eight-packet batches through the 2026 adapter's injected reader.
It measures bounded handoff/TransportSample work and JVM allocation, with preexisting
strings/metadata. It excludes native readQueue and network allocation; it is a small
CPU smoke benchmark, not a loaded NT/controller benchmark.

The small decoder benchmark [decoder-mac.json](benchmarks/decoder-mac.json) uses
250 warmup and 1000 2882-byte synthetic packets, a 256MiB heap and one CPU thread.
It measures parsing/validation/immutable copying and per-thread JVM allocation.
It excludes NT's earlier allocation, controller load, camera processing and robot
estimator. Its p50/p95/p99/max are observations on this desktop, not controller
performance limits. Parser/queue count limits bound work; they do not establish a
hardware latency deadline. Larger scene/controller load tests remain unrun.

Team images, DS/vendor inventory, real sync/capture correction, calibration,
controller performance, estimator behavior on hardware and physical actuation are
unrun. The library has no published Maven/vendordep URL.

Measured decoder p50/p95/p99 were 27.875/59.833/123.125 microseconds; maximum
782.458 microseconds and about 60506 allocated bytes per packet in that run. Queue
handoff p50/p95/p99 were 0.458/1.792/4.792 microseconds per eight-packet batch,
maximum 293.959 microseconds and 856 allocated bytes/batch. Scheduler/JVM noise and
this small fixture limit extrapolation. No controller deadline is established.

Dependencies and producer bytes are pinned locally; actual robot integration remains unrun.

The local `0.2.0-local.1` facade/controller candidate adds 124 common facade
assertions, 141 motion-request contract assertions and 14 wrapper assertions per
WPILib profile. Both separately pinned Gradle builds passed, including the
unpacked controller-JAR no-motion examples. Live admission results, full clock
results and receipt identity survive into the private-constructed measurement.
Origin/generation gates reject retained observations after invalidation, clock
failure, overload or configuration changes, including invalidation and recovery
within one poll. The synchronization-binding exception path fails closed before
the explicit error is rethrown. A read-only review found no unresolved correctness
issues in these boundaries. No motion ports or scheduler were invoked by tests.

See the [local candidate report](LOCAL_CANDIDATE_REPORT.md) for the final actual
Maven-coordinate install, packaging and native World-State bridge outcomes. The
old published source baseline does not supply the candidate's new admitted
envelope/delivery-gate API. This candidate remains local and unpushed.
