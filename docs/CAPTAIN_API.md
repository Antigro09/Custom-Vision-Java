# Camera and pose facade

Use the selected profile's `CustomVisionRig.create` with your shared NT instance,
named `CameraConfig` sources, a `ClockMapper` bound to the verified robot epoch,
the robot monotonic nanosecond clock and an explicit `SyncEvidenceProvider`.
The provider sees the pinned NT time version, raw server offset and connection
epoch. It must preserve these values and supply independently verified epoch
mapping and uncertainty. Offset availability alone cannot verify synchronization.
The compile-tested [2026](../examples/install-2026/NoMotionInstall.java) and
[2027](../examples/install-2027/NoMotionInstall.java) examples show the exact calls.

Call `periodic()` on one robot-loop thread every cycle. All sources share one
capture reorder window. Each camera starts in `OBSERVATION_ONLY`; use
`drainObservations()` once, or bind an explicit observation callback, which
exclusively owns delivery. Status, diagnostics and latest-pose views are inspection
channels. They cannot insert a second measurement. Source disconnection requires
a source-specific boundary through `resetConnection(cameraName)`; another NT peer
remaining connected cannot keep a missing source's observations fresh.

For localization candidates, install an explicit `PosePolicy` and switch that
camera to `FUSION_CANDIDATES`. Its thresholds declare allowed methods, tag count
and reprojection bounds, with optional required ambiguity, distance and decision
margin bounds. Missing required quality inputs reject. Its calibration callback
returns optional standard deviations in meters, meters and radians, with
calibration/tuning identities. An empty result rejects; no pixel residual is
converted into estimator noise. Standard deviations must have finite positive
squared variances. Calibrating this policy requires separate measured evidence.

`drainPoseEstimates()` returns consume-once `WpilibPoseEstimate` candidates with
`pose2d()`, `captureSeconds()`, `standardDeviations()` and full `provenance()`.
Only the producer's validated `field_to_robot` role can supply a robot field pose.
Methods are `SINGLE_TAG_PNP` and `MULTITAG_PNP`, retaining used tags and raw quality.
There is no heading-assisted solver or assumed Limelight MT1/MT2 equivalence.
Geometry remains fixed-origin NWU meters/WXYZ; no alliance flip is applied.
The robot owns estimator policy and any insertion. Avoid inserting both a joint
candidate and its correlated per-tag views.

Mode, trust and callback changes discard pending outputs and fence already
admitted packets. The fence does not claim knowledge of packets still unseen in
NTCore. Invalidation, parse/clock failures, overload and expiry also invalidate
retained outputs. A recovered source cannot revive a pre-invalidation envelope.
Callbacks run on the owning loop and cannot reenter configuration, drains or
close. They must complete promptly; this library cannot preempt application code.

World-State's optional live bridge accepts the narrow delivery gate:

```java
var bridge = new VisionTrackBridge(configuredAdapter, worldEngine,
    vision.observationDeliveryGate());
vision.rig().setObservationConsumer(bridge::acceptRobotNanoseconds);
```

`configuredAdapter` and `worldEngine` belong to World-State and require explicit
history, mounting, robot/world/reset identities and policy. The bridge rechecks
current delivery eligibility before normalizing robot nanoseconds into core
microseconds. It then projects at capture time and returns normalization and
tracking decisions separately. This is an optional dependency direction from
World-State to CVJ; CVJ never imports World-State. See
[admission provenance](ADMISSION_BRIDGE.md) and [native bridge checks](../interop/README.md).

Motion requests use the independent `controls` module's injected planner,
state, follower, drive, limits, cancellation and possession contracts. It can
prepare a validated request and construct an unscheduled application-owned
command. World-State supplies persistent-object or useful-cluster selection and
its existing pickup controller. CVJ has no second selector, scheduler or task
controller. See [the execution boundary](../controls/README.md); missing bindings
reject explicitly. No actual drivetrain is integrated by these examples.
