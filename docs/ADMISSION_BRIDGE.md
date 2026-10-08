# Admitted observation bridge

`VisionClient.drainMeasurements(nowRobotNs)` returns immutable
`VisionClient.Measurement` objects created only inside `VisionClient`:

```java
Packet packet = observation.packet();
TransportSample originalReceipt = observation.transport();
SourceSession.Result actualAdmission = observation.admission();
ClockMapper.Result actualMapping = observation.clock();
ClockMapper.MappedCapture capture = observation.capture();
```

The admission and mapping are the exact results of this client's
`SourceSession.accept` and `ClockMapper.map` calls. They are not reconstructed
success results. The private constructor requires an accepted lifecycle result
whose new measurement is the exact packet, an accepted full clock result, and
matching original capture/NT/receipt/connection-epoch metadata. There is no public
constructor to fabricate an admitted observation from a hand-built packet.

Global capture ordering, source invalidation, age/epoch gates and consume-once
draining remain in the client. Historical admission does not make a retained
envelope perpetually fresh. A downstream consumer must also validate its current
world/reset identity, explicit binding, current time and history coverage.
`client.isDeliverable(observation, nowRobotNs)` revalidates a retained facade output
against its originating client, private source generation, current source/boot/
revision/connection epoch and capture lifetime. The generation changes on an
invalidation, clock failure or family loss, even if a newer packet restores the
source before that poll returns. Inspecting only the final source status cannot
detect that transition. Ordinary advancing frames preserve other still-fresh
admitted captures; getters never make a historical envelope newly accepted again.

The optional World-State adapter can expose
`adapt(VisionClient.Measurement observation, long nowUs)`, delegating to its
existing `adapt(observation.packet(), observation.admission(), observation.clock(), nowUs)`.
That dependency remains one-way: World-State's optional bridge sees this library;
the protocol and facade do not import World-State or own persistent field tracks,
field projection, target selection, planner policy or the pickup controller.

A facade consumer receives `(VisionClient.Measurement observation, long nowRobotNs)`.
It is called on the single owning robot-loop thread. A shared rig chooses an
explicit consumer callback or a manual observation drain; consumers must not
insert both the callback and a duplicate manual view. Pose-estimate candidates
are a separate robot-owned fusion policy view of the same correlated observation.
Raw receipts are diagnostics, not an insertion/World-State input channel.

`VisionRig.observationDeliveryGate()` and each profile's `CustomVisionRig` expose a
`VisionClient.DeliveryGate` without exposing the rig's owned client. It is safe to
call on the owning thread from the observation callback. The facade checks its
configured source, mode and callback binding boundaries, then the client's current
origin/generation and lifetime gates. It also checks immediately before invoking
the callback. A live World-State bridge should receive this gate and call
`isDeliverable(observation, nowRobotNs)` before normalization or insertion. Keep
nanoseconds at that boundary; convert to World-State microseconds only after the
gate accepts. The standalone adapter overload is historical normalization and
does not replace this live gate.

Facade output saturation calls `reportDeliveryOverload` on the originating client,
which fences caller-retained envelopes as well as queued copies. A fresh advancing
publication can restore source actionability, but cannot restore those old
envelopes. A synchronization-provider exception similarly clears actionable
outputs before propagating an explicit binding error. Repaired synchronization
requires fresh acceptable input. Neither failure creates an admission result or
changes a historical envelope's provenance.

`drainMeasurements()` uses the configured production clock when available and
rechecks expiry. For deterministic replay with no supplied clock, use the explicit
time overload; an unstamped getter cannot infer the advance of a fake clock.
