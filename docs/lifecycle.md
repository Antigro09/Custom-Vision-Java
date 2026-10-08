# Source lifecycle

`SourceSession` owns state for one full configured `SourceKey`: namespace,
pipeline, and type. Two cameras using the same pipeline name remain separate
sources when their namespaces differ. Observation identity is source + boot +
frame; object track identity is source + boot + local track ID. Neither identity
is a persistent field track.

One robot-loop thread owns mutations. Call `expire(nowRobotNs)` on every cycle,
including cycles with no queued values. `accept` returns a newly accepted
measurement at most once for an observation. `status()` reads state and never
returns a new insertion request. Status validity, capture-relative DTOs, and
clock-approved fusion eligibility are separate. This module never calls a pose
estimator or commands motion.

## Initial, boot, revision, and reconnect adoption

The default policy requires two advancing publications before a source can
become actionable at startup, after a boot/revision change, or after a local
connection epoch changes. The first cached/retained value is pending. Each
advancement must increase `packet_seq` for the named additive profile, or
`frame_id` for legacy schema 2. Original NT metadata must also advance: use
`ntServerTime` when both samples have positive server times, otherwise positive
`ntTimestamp`. These comparisons preserve the selected adapter's raw units;
they do not convert microseconds into nanoseconds.

Repeated delivery with new local dequeue times cannot adopt a retained value.
Adoption does not prove capture freshness or a robot clock epoch; `ClockMapper`
still gates fusion. The local `firstObservedRobotNs` is when `readQueue` observed
the value, not true network ingress. A queued packet's dequeue time does not
replace that original receipt provenance.

Changing boot or revision clears capture-relative actionable state and mapped
capture age. On a boot change, the old boot is retired as soon as the new
candidate is observed. A delayed old-boot invalidation cannot clear the current
or pending new boot. The source's publication/frame high-water marks survive a
reconnect or revision change; they reset only when a new boot is adopted.

Default bounds are a 100 ms receipt timeout, two advancing publications, 32
candidate attempts, 16 retired boots, and four tombstones. Pending state stores
only the latest candidate. Exceeding the candidate-attempt bound rejects the
candidate; a different, unadopted boot is retired. Retired boots are never evicted
to permit old-session replay. Exhausting the retired-boot bound **fails closed**:
actionable state stays invalid and subsequent packets are rejected with
`RETIREMENT_LIMIT`.

An intentional application reset recreates `VisionClient`/`SourceSession` after
closing its owned subscribers. That starts fresh bounded adoption and discards
the prior session's replay history. The application must choose this reset
deliberately; timeout/disconnect does not automatically erase retired boots.
Recreation cannot prove that arbitrary old cached values are current, so the
advancement and clock checks remain mandatory.

## Publication order and invalidation

For the additive profile, an eligible accepted `packet_seq` must strictly
increase. Equal sequences are duplicates; lower sequences and genuinely older
frames are rejected. New status is applied before measurement deduplication.
An invalid/empty publication tombstones its frame, even when its frame ID matches
the previous valid measurement. A later valid packet for that same frame cannot
revive it, even if that packet has a newer sequence. A fresh newer frame can
restore actionable output.

A packet replaces each family's current value; an absent family clears the
preceding per-source family value. As a conservative policy, losing a previously
valid family or all detections **within the same frame** tombstones the whole
observation. Other families in that frame therefore also lose actionable state.
This avoids keeping a partial observation alive across an invalidation. It can
discard otherwise valid same-frame data; it does not manufacture a new pose or
retain the previous family's geometry.

Legacy schema 2 has no `packet_seq`. Process queued same-frame invalidations
before frame deduplication and keep the same tombstones. A duplicate legacy
valid frame emits no new measurement. Redelivery of a legacy invalidation with
identical original NT timestamp/server-time metadata can still clear status,
but cannot refresh accepted-publication age. Older frames and retired boots are
rejected. Once a newer frame advances the high-water mark, older tombstones can
be removed safely because those frames remain rejected.

Legacy ordering remains ambiguous. Frame identity and NT metadata cannot always
distinguish a delayed same-frame publication, a cached value, and a fresh
heartbeat, especially if publisher timestamps are unavailable or repeated.
Queue order plus conservative permanent same-frame invalidation is the policy;
the implementation does not claim perfect replay ordering for this profile.

## Activity, expiry, and recovery

Call `observeActivity(sample, nowRobotNs)` before decoding each raw string from
the configured source. Malformed JSON can report diagnostic source activity
without becoming an accepted publication. The method records the original
`firstObservedRobotNs`, rejecting future receipts, old local connection epochs,
and receipts already at least the configured timeout old. It changes no
publication/measurement freshness, adoption state, actionable outputs, or capture
mapping. `accept` also observes activity for valid decoded packets.

Status keeps these ages separate:

- Activity age: last eligible raw arrival, including malformed payloads and
  duplicate delivery.
- Accepted-packet age: last accepted status publication's original receipt.
- Usable-observation age: last newly accepted usable frame's original receipt.
- Capture age: available only after an explicit verified robot-clock mapping.

Duplicate delivery and invalid heartbeats never refresh usable-observation age.
Raw historical capture microseconds and `lastUsableObservationId` remain
diagnostic provenance after invalidation; they do not imply actionable state.
Applying a new status clears the prior mapped capture age. Fusion eligibility
requires a current actionable observation and its verified capture mapping.

Timeout independently invalidates this source even when NT server-wide
`isConnected()` remains true because another peer is connected. An explicit
peer-level disconnect clears this source and requires reconnect adoption.
Parse failure or queue overload clears actionable state, tombstones the current
frame, and rejects handoff data observed before the failure/loss barrier. A fresh
acceptable newer observation can recover; a duplicate of the old frame cannot.
These operations do not delete another source's caches or World-State tracks.

The caller must also reject unsynchronized, stale/future, or epoch-mismatched
capture times before fusion/field projection. Receipt activity and the producer's
`connected` boolean alone are insufficient for those decisions.

`recordCaptureMapping(packet,captureNs,nowNs,maxCaptureAgeNs)` stores the mapper's
explicit capture lifetime. Every `expire`/`status(now)` invalidates that mapping
when it ages past the bound independently of receipt freshness. The three-argument
standalone overload uses the receipt timeout as a conservative capture bound.
VisionClient passes its exact ClockMapper bound; custom standalone consumers must
pass their own chosen bound and still apply robot-owned fusion quality policy.
