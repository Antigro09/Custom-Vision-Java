# API and robot handoff recipe

The local `0.2.0-local.1` implementation is pinned to commit
`12ee0ab5ede1e0d51c561f8a3d245a4e0822c926` on `feature/captain-api`.
The subsequent handoff commit changes metadata only. Public `main` remains
`ae67886da70223e9ee73683ee2bcd00dc3221469`, which lacks this candidate's new
admitted-envelope and delivery-gate APIs. Neither candidate commit is pushed.

The separate Java17 protocol artifact is
`protocol/build/libs/customvision-protocol-0.2.0-local.1.jar`, SHA256
`62d5a16c558a2fc11855f1f675fc09efe0f3b878eb0bc5e61d64ae4ac8bcc85f`.
Its source JAR SHA256 is
`bba10e7acd69dd5eec7128c6970f7a9f6039c4f69ee62c8e8f606bbe94662dc2`.
The protocol source-entry manifest SHA256 is
`f25ec7090d1a36192ee4780465835ee6c6112bdf6b8caafa374bff50b7d3d8af`;
[api-manifest.json](api-manifest.json) defines the encoding and all exact file pins.
Run `python3 tools/check_api_manifest.py --artifacts` after building this candidate.

`Measurement.packet()/transport()/admission()/clock()/capture()` and
`DeliveryGate.isDeliverable(Measurement,long nowRobotNs)` are frozen for this
milestone. Bind World-State's live bridge to
`CustomVisionRig.observationDeliveryGate()` and install its
`acceptRobotNanoseconds` method as the exclusive observation callback. The direct
client bridge remains available to applications that own a `VisionClient`.
Actual native end-to-end checks passed 61 assertions per profile against clean
World-State `d24f2bbf11a4f9b3b73c7d3a05f3b6f416204117`.
See [admission lifecycle](ADMISSION_BRIDGE.md), [candidate outcomes](LOCAL_CANDIDATE_REPORT.md)
and [offline controller packages](INSTALLATION.md). This is a local library handoff;
actual robot-code integration remains on hold.

The dependency direction is `robot / optional World-State adapter -> protocol`.
The versioned NT modules also depend on protocol. Protocol depends on neither
WPILib nor World-State. World-State owns historical field projection, covariance
propagation, persistent field tracks and planner/predictor contracts. Robot code
owns fusion thresholds, estimator history and motion. This document is a recipe;
no actual robot repository has been edited or integrated.

`SourceKey(namespace,pipeline,type)` contains the entire configured namespace plus
expected payload identity. `resultTopic()` appends `/result`. Source namespace is
not inferred from a payload address. `Packet.observationId()` is source/boot/frame;
`trackId(localId)` is source/boot/local track. Raw packet fields are deep immutable,
with Long integer timestamps and Double decimal values. Decoder-produced packets
are validated; constructing DTOs by hand does not make them validated input.

`ProtocolDecoder.decode(source,string)` returns one complete coherent packet or a
structured `DecodeException` with reason/path/offset. No half measurement survives a
rejection. Default bounds: 262144 payload UTF-16 characters, 4096 characters/string,
32768 tokens, nesting 24, 4096 array entries, 256 object fields and 256 detections /
canonical object targets. Bounds apply after NTCore has already allocated the string;
they do not protect that earlier allocation. Unknown bounded additive fields stay
opaque. Failure packets may omit nonactionable diagnostics/error fields; absence
never reuses prior geometry.

Typed getters expose timestamps, localization, pose candidates, POI camera optical
and optional robot NWU coordinates, canonical object targets/selection, XY covariance,
uncertainty and diagnostics. Raw OpenCV/Rodrigues diagnostics retain their axes.
AprilTag yaw and robot object bearing are left-positive; legacy object camera yaw
and POI camera tx are right-positive. Geometry conversion only changes Java
representation of named NWU meters/WXYZ; it does not transform, alliance flip or
synthesize mounting. Camera-only localization cannot become a robot field pose.

Construct one profile's `NtResultQueue` per configured result topic, sharing the
robot application's existing NetworkTableInstance. Provide the same verified robot
nanosecond clock to that queue and the production `VisionClient` five-argument
constructor. Supply a current `SyncSnapshot` with the adapter's `timeVersion()`,
server-minus-local offset, independently verified NT-local-to-robot epoch offset,
uncertainty, connection epoch and verification evidence. Offset presence alone is
insufficient. The four-argument client constructor is for explicitly stamped offline
replay only; live readers observe timestamps after poll entry.

Call `client.poll()` every robot loop, even when no input arrives. It samples the
verified clock again after queue reads, decoding and sync retrieval; these later
receipt/dequeue times are never substituted for capture time. Publication status is
applied before observation deduplication. `statuses(now)` reports activity, accepted
publication age, usable-observation age and capture age separately. Duplicate data
and invalid heartbeat status cannot refresh a reusable measurement. Use a peer-level
connection epoch notification and per-source expiry; aggregate server isConnected
can stay true when one Jetson disappears.

`drainReceipts()` contains accepted raw diagnostics with mapping success/rejection;
it is not an estimator insertion channel. `drainMeasurements(now)` is the only
consume-once, clock-gated, globally capture-ordered measurement channel. Repeated
getters cannot emit another pose. Default client limits: 8 sources, at most 16 decoded
packets/source and 64 packet slots/cycle, output depth 64, reorder depth 128 and a
30ms window. Queue saturation, handoff loss, output saturation and parse failures
are observable and invalidate that source until fresh acceptable input. Cross-camera
late arrivals outside the closed window drop; capture age is rechecked at release,
each cycle and drain, including when a live source advances. Tune bounds from actual
measured controller/camera/network evidence.

For each drained measurement, robot policy selects a validated `localization.field_to_robot`
candidate only if that role exists and meets quality gates. Candidates from one
Packet observation share a correlation group. Field-camera, field-robot, joint and
per-tag/field-enriched views of the same tags must not all be inserted. Preserve
`PoseCandidate.provenance`, used IDs and raw pose-source/quality fields. Pixel
reprojection errors are not estimator standard deviations; this library supplies no
estimator covariance or automatic fusion uncertainty. Camera fusion policy remains
robot-owned. Use `capture.estimatorSeconds()` once; producer capture corrections
are already applied and are never subtracted twice.

To project object/POI observations, the optional World-State adapter first checks
`HistoricalPoseProvider.checkedSample` coverage, robot epoch and reset identity.
Out-of-range, holes and pre-reset samples reject before a WPILib sampler can clamp.
Only that adapter performs capture-time field projection and covariance propagation.
It must not guess absent mounting or localization, delete unrelated source tracks,
or treat approach displacement as an obstacle-checked path. On estimator reset,
create a new epoch-bound client/history provider, clear affected source queues and
reestablish advancing-publication eligibility. Local object tracks are associations,
not permanent physical identity through occlusion.

Close the client/queues on their owning loop. Only owned subscribers close; the
robot's shared NetworkTableInstance is never closed or reconfigured. There are no
NT-thread estimator callbacks. Jetson/controller deployment, actuation and actual
robot-code integration require separate hardware validation and execution scope.
