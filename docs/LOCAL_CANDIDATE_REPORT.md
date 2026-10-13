# Local controller candidate validation

`feature/captain-api` delivers `0.2.0-local.1`. Public `main` and `origin/main`
remain at `ae67886da70223e9ee73683ee2bcd00dc3221469`. The candidate has no push,
release, hosted Maven endpoint or online vendordep. The final implementation pin
and exact source/artifact hashes are in [api-manifest.json](api-manifest.json).

The candidate adds actual immutable admitted observations, retaining the exact
packet, transport sample, lifecycle result and full clock result. Current origin,
generation, source health and lifetime gates protect caller-retained observations
through invalidation and recovery. The camera/pose facade adds consume-once
callbacks/drains, explicit calibrated pose policy and pinned WPILib representations.
The independent controller module prepares requests through injected bindings
and constructs an unscheduled application-owned command. Persistent selection,
field projection, pickup control, estimator insertion and command execution stay
with their respective consumers. See [facade usage](CAPTAIN_API.md),
[admission bridge](ADMISSION_BRIDGE.md) and [controller boundary](../controls/README.md).

All current software checks passed sequentially on the authorized macOS arm64
desktop, using Temurin Java17, Temurin Java25+36, Gradle8.11 and Gradle9.4.1 with
cached checksum-pinned dependencies. No GPU or other project workload was used.

| Check | Result |
| --- | --- |
| Pure protocol/replay | 101 decoder, 445 golden, 223 lifecycle, 49 client assertions; 27 exact producer fixtures, 6 invalid examples, 5 legacy strings; time/history/reorder passed |
| Common facade | 124 assertions; strict Java17 compilation |
| Controller request contracts | 141 assertions; zero drive/follower/scheduler execution |
| Profile adapters | 26 adapter + 14 facade assertions per profile |
| Unpacked package examples | Both profile no-motion examples compiled with `-Xlint:all -Werror` |
| Actual offline Maven installation | Both disposable consumers resolved all four own coordinates from staged files and exact matched external graphs; strict compilation passed |
| Packaging | Both archives reproduced byte-for-byte; 12 common coordinate files equal across profiles; 6 corruption/staging/idempotence/conflict/profile/symlink gates passed |
| Actual NT admission to World-State | 61 assertions per profile, fresh source compilation with `-Xlint:all -Werror`, 128MiB native-test heap; source hashes unchanged across compile/run |
| Read-only boundary review | No unresolved concrete correctness findings; whitespace checks passed |

The World-State native check pins clean commit
`d24f2bbf11a4f9b3b73c7d3a05f3b6f416204117`. Actual NT packets pass queue,
decoder, source/session admission, clock mapping, consume-once draining, live
delivery gating, normalization, capture-time projection and persistent tracking.
An unforwarded retained envelope cannot first enter an empty tracker after its
source invalidates, recovers or changes boot. Tests preserve original golden
geometry and mark accepted timing/epoch evidence explicitly synthetic. Original
unverified correction metadata correctly rejects. Source loss leaves unrelated
persistent world tracks intact.

Both native profiles preserve received raw metadata. Alpha-7's released binary
uses nanosecond Java metadata while the observed NT4 round trip quantizes a
remote publication timestamp to whole microseconds. The harness records sender
and receiver values and checks that behavior. JSON `_us` remains integer
microseconds in both profiles. This is pinned desktop behavior, with no claim of
submicrosecond wire precision or independently obtained C++ source quotation.

The final receipts are [native bridge evidence](evidence/native-admission-world-state.json),
[installed coordinates](evidence/offline-installed-coordinates.json),
[package gates](evidence/controller-packaging.json) and
[public dependency closure](evidence/public-dependency-closure.json).
The existing [Python interoperability report](PYTHON_INTEROP.md) retains the
earlier pinned-Python transport/decoder checks; those were not rerun for this
facade-only increment. Its transport/decoder sources are unchanged.

Installation verification found and resolved late Gradle publishing-plugin
configuration, invalid module-metadata key order and missing public WPILib
dependency closure. Native verification corrected harness assumptions about the
reorder window, disconnected packet shape and alpha-7 wire precision. No core
validation was weakened to make those checks pass. No current software check is
failed or pending within the local candidate scope.

Reproduce the two profiles separately using [installation instructions](INSTALLATION.md).
Run the native bridge with explicit World-State checkout and cached native inputs
as documented in [interop/README](../interop/README.md). Verify source pins with
`python3 tools/check_api_manifest.py`; add `--artifacts` after building the exact
candidate locally. Ordinary builds never download floating producer source.

Hardware/controller performance, camera calibration, measured capture correction,
team image/Driver Station/vendor compatibility, a real drivetrain/follower and
actual robot integration remain unrun. No supported official 2026/Systemcore
target is provided. The 2026/roboRIO and Java25/alpha-7/Systemcore profiles stay
separate. A heading-assisted solver, online vendordep/schema/hosting validation,
public release and deployment also remain outside this local milestone. See
[hardware checks](HARDWARE_CHECKS.md) and the [future publication audit](INSTALLATION.md).
