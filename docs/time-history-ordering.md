# Capture time, history and capture ordering

The protocol module is independent of controllers and WPILib. JSON `capture_server_us`
is an integer count of **microseconds**, including when the transport adapter is
WPILib 2027 alpha-7. It is not Unix time or the producer's local monotonic clock.
Transport samples retain their selected adapter's raw metadata; local observation
and robot-loop dequeue times are separate nanosecond fields. `firstObservedRobotNs`
is the instant Java observes `readQueue`, not a claim about network ingress.

`TimeVersion.WPILIB_2026_MICROSECONDS` converts NT timestamp, server timestamp and
server-minus-local offset with checked multiplication by 1,000.
`WPILIB_2027_ALPHA7_NANOSECONDS` leaves those raw values in nanoseconds. Both
profiles multiply JSON `_us` by 1,000. The [alpha-7 TimestampedString source](https://github.com/wpilibsuite/allwpilib/blob/v2027.0.0-alpha-7/ntcore/src/generated/main/java/org/wpilib/networktables/TimestampedString.java)
and [alpha-7 offset source](https://github.com/wpilibsuite/allwpilib/blob/v2027.0.0-alpha-7/ntcore/src/generated/main/java/org/wpilib/networktables/NetworkTableInstance.java)
explicitly name nanoseconds. The 2026 profile is separately pinned and tested by
its adapter at [WPILib v2026.2.1](https://github.com/wpilibsuite/allwpilib/tree/v2026.2.1);
no game-year inference selects a time profile.

## Required mapping evidence

`ClockMapper.map(packet, transport, syncSnapshot, nowRobotNs)` returns either an
immutable `MappedCapture` or a structured rejection. Mapping requires all of:

- The producer declares `time_sync_valid` and a positive `capture_server_us`.
- The consumer has an available offset in the selected NT unit, sampled for the
  same local connection epoch, within its configured age limit.
- The consumer supplies a named robot monotonic epoch, a verified NT-local to
  robot-epoch offset, and bounded uncertainty. An NTCore offset alone cannot
  verify the robot epoch. Desktop/Unix/Jetson/dequeue clocks do not substitute.
- The additive timing block names `nt_server`, `us`, and
  `host_frame_read_complete`, with capture correction verified and uncertainty
  supplied. A legacy packet needs explicitly configured, measured legacy
  correction evidence; absent evidence conservatively rejects a fusion timestamp.
- Publication metadata agrees with the pinned offset, and mapped capture time
  passes future, age and uncertainty bounds. Local publication sentinel server
  timestamps `0` and `1` are rejected as remote producer evidence.

The pinned NT API defines `server = local + serverMinusLocal`. This mapper computes:

```text
captureServerNs = checked(capture_server_us * 1_000)
captureRobotNs = checked(captureServerNs - serverMinusLocalNs + localToRobotOffsetNs)
estimatorSeconds = captureRobotNs / 1_000_000_000.0
```

The producer already subtracts its measured camera capture-latency correction.
This library does not apply it again. `MappedCapture` preserves raw capture,
raw NT timestamp/server timestamp/offset, metadata version, local observation and
dequeue times, snapshot identity and verification, and separate synchronization,
capture-correction and total uncertainty. `Packet.fields()` retains the original
timing block and all raw producer provenance. Already mapped values never change
when a later synchronization snapshot changes. Rejecting a mapping does not skip
status/invalidation processing: lifecycle ordering is a separate concern.

The defaults are policy starting values for CPU tests: 250 ms maximum capture age,
zero permitted future lead, 1 s maximum synchronization-evidence age and 5 ms maximum
total uncertainty. They are not hardware qualification. The robot owner must
measure its epoch relationship, camera delay and jitter, and choose explicit limits.
Capture-correction uncertainty is converted to integer nanoseconds by rounding up;
fractional nanoseconds never understate uncertainty.

## Pose-history handoff

`HistoricalPoseProvider<P>` is implemented by the robot/World-State consumer using
its own immutable pose type. Its atomic `Window` declares a robot epoch, reset
identity, reset instant and at most 128 ordered, nonoverlapping inclusive coverage
spans. Separate spans encode interpolation holes. `checkedSample` checks epoch,
reset identity, pre-reset time, oldest/newest range and holes **before** calling
`sampleAt`. This prevents a clamping estimator sampler from inventing coverage.
It also rejects reset identity/instant changes while sampling, or a missing sample.
The reset instant itself is valid only when explicit post-reset coverage includes
it. Strictly earlier capture times are rejected.

Calls belong to one robot-loop owner. No estimator is mutated on an NT thread.
The provider is only a history contract: field projection, transform composition,
covariance propagation and fusion acceptance belong to the World-State adapter and
robot estimator policy. `field_to_camera` is not a robot pose. NWU meter vectors,
WXYZ quaternions, optical/Rodrigues values and legacy yaw signs remain distinct;
there is no alliance flip or fixed-origin transform inside the time/history module.

## Across-camera ordering

Use one `CaptureReorderBuffer<T>` across every configured camera feeding a given
estimator. Apply source status/invalidation in publication order first, then offer
new, clock-accepted measurement groups. An `Entry` names its full source, boot/frame
observation identity and candidate/correlation group as needed. It holds the mapped
capture time and caller-owned immutable measurement value.

Each robot cycle calls `drain(nowRobotNs, maxWork)`. The buffer closes capture times
through `nowRobotNs - reorderWindowNs`, including quiet cycles. It emits at most
`maxWork` removals in global capture order and never reopens released intervals.
Delayed packets at or below an already closed interval or emitted time return
`LATE`; this deliberately also drops equal-time packets arriving after that instant
has been emitted. Equal-time packets queued together are deterministically ordered
by observation identity. Pending duplicate identity is emitted once. Packet/frame
deduplication and invalidation tombstones are also enforced by source lifecycle.

The capacity, window, future bound and per-cycle work are explicit. `OVERLOAD` is
observable and never silently replaces a half-measurement. The owning client must
invalidate affected actionable state and discard that source's pending entries
until a fresh acceptable publication arrives. `discard(predicate)` and
`removeSource(source)` purge invalidated frames/boots/sources without purging other
cameras. `clearPending` preserves the release watermark; an actual robot-clock
epoch reset requires a new buffer. Source invalidation never changes global
capture ordering into status ordering.

`TimeTests.run()` contains CPU-only synthetic tests for both unit profiles,
microsecond/nanosecond/second conversions, offset changes and raw provenance,
unavailable synchronization, epoch/connection mismatch, future/stale input,
overflow and correction verification. It also checks history boundaries/gaps/reset
races and cross-camera late arrival across robot cycles, duplicate delivery,
quiet-cycle watermark expiry, source-specific removal, overload and bounded work.
These are software contract tests; they make no controller-performance or hardware
timing claim.
It also decodes exact producer `measured_zero_correction` fixture bytes, checks the
manifest SHA-256, pinned SHA-256 and byte count, and maps its raw
`capture_server_us:1234569890123` through both transport units. The producer fixture
was generated with CPU synthetic geometry and an injected clock, so its verified
correction marker is test input rather than evidence about any deployed camera.
