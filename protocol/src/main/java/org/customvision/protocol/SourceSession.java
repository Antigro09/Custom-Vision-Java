package org.customvision.protocol;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/** Bounded, single robot-loop-owner state for exactly one configured result source.
 *
 * <p>Initial, changed-boot, changed-revision and reconnect adoption require advancing
 * publication identity AND original NT metadata. A first retained value cannot act.
 * For legacy publications, same-frame invalidation is processed before measurement
 * deduplication and leaves a tombstone. Legacy has no packet sequence, so this is a
 * conservative queue-order policy, not a claim of perfect publisher replay ordering.
 * Getter calls never create another measurement. This class never calls an estimator.
 */
public final class SourceSession {
    public enum Kind { ACCEPTED, DUPLICATE, REJECTED, PENDING }
    public enum Reason {
        NONE, SOURCE_MISMATCH, OLD_EPOCH, STALE_RECEIPT, FUTURE_RECEIPT,
        OLDER_PACKET, OLDER_FRAME, RETIRED_BOOT, PROFILE_CHANGED,
        ADOPTION_REQUIRED, METADATA_NOT_ADVANCING, PENDING_LIMIT, RETIREMENT_LIMIT,
        INVALIDATED_FRAME, DISCONNECTED, EXPIRED, PARSE_FAILURE, OVERLOAD
    }

    public record Config(long receiptTimeoutNs, int advancingPublications,
                         int maxPendingPackets, int maxRetiredBoots, int maxTombstones) {
        public Config {
            if (receiptTimeoutNs <= 0 || advancingPublications < 2
                    || maxPendingPackets < advancingPublications || maxRetiredBoots < 1
                    || maxTombstones < 1) throw new IllegalArgumentException("invalid lifecycle bounds");
        }
        public static Config defaults() { return new Config(100_000_000L, 2, 32, 16, 4); }
    }

    public record Result(Kind kind, Optional<Packet> newlyAcceptedMeasurement, Reason reason) {
        public Result { Objects.requireNonNull(kind); Objects.requireNonNull(newlyAcceptedMeasurement); Objects.requireNonNull(reason); }
    }

    /** Ages are monotonic local robot nanoseconds. Capture age exists only after a
     * verified ClockMapper result is explicitly recorded; raw JSON capture remains us. */
    public record Status(SourceKey source, Optional<String> bootId, Optional<String> revision,
                         long connectionEpoch, boolean connected, boolean actionable,
                         boolean fusionEligible, Optional<Packet> currentPacket,
                         Optional<Packet> lastAcceptedPacket, OptionalLong activityAgeNs,
                         OptionalLong acceptedPacketAgeNs, OptionalLong usableObservationAgeNs,
                         OptionalLong captureAgeNs, OptionalLong captureServerUs,
                         Optional<Packet.ObservationId> lastUsableObservationId,
                         int retiredBootCount, int tombstoneCount, boolean adoptionPending,
                         Reason lastReason, long acceptedPackets, long duplicatePackets,
                         long rejectedPackets) {}

    private static final String[] FAMILIES = {"localization", "poi", "objects"};
    private final SourceKey source;
    private final Config config;
    private final Set<String> retiredBoots = new LinkedHashSet<>();
    private final Set<Long> tombstones = new LinkedHashSet<>();
    private Thread owner;
    private long lastNow = -1, epoch = -1;
    private long lastActivity = -1, lastAcceptedAt = -1, lastUsableAt = -1;
    private long mappedCapture = -1, usableCaptureServerUs = -1;
    private long mappedMaxAgeNs;
    private long highFrame = -1, highSequence = -1, recoveryAfter = -1;
    private String boot, revision, profile;
    private Packet lastPacket, actionablePacket;
    private TransportSample lastAcceptedTransport;
    private Packet.ObservationId lastUsableId;
    private Candidate candidate;
    private boolean adoptionRequired = true, retirementLimit;
    private Reason lastReason = Reason.ADOPTION_REQUIRED;
    private long acceptedPackets, duplicatePackets, rejectedPackets;

    private static final class Candidate {
        Packet packet;
        TransportSample transport;
        int advancing = 1, attempts = 1;
        boolean tombstoned;
        Candidate(Packet packet, TransportSample transport) {
            this.packet = packet; this.transport = transport; tombstoned = !packet.usable();
        }
    }

    public SourceSession(SourceKey source) { this(source, Config.defaults()); }
    public SourceSession(SourceKey source, Config config) {
        this.source = Objects.requireNonNull(source); this.config = Objects.requireNonNull(config);
    }

    public Result accept(Packet packet, TransportSample sample, long nowNs) {
        own(nowNs);
        Objects.requireNonNull(packet); Objects.requireNonNull(sample);
        expireInternal(nowNs);
        if (!source.equals(packet.source())) return rejected(Reason.SOURCE_MISMATCH);
        if (sample.firstObservedRobotNs() > nowNs || sample.dequeueRobotNs() > nowNs)
            return rejected(Reason.FUTURE_RECEIPT);
        observeActivity(sample, nowNs);
        if (sample.connectionEpoch() < epoch) return rejected(Reason.OLD_EPOCH);
        if (sample.connectionEpoch() > epoch) {
            epoch = sample.connectionEpoch();
            candidate = null;
            adoptionRequired = true;
            invalidate(Reason.ADOPTION_REQUIRED);
        }
        if (nowNs - sample.firstObservedRobotNs() >= config.receiptTimeoutNs()
                || sample.firstObservedRobotNs() < recoveryAfter) return rejected(Reason.STALE_RECEIPT);
        if (retirementLimit) return rejected(Reason.RETIREMENT_LIMIT);
        if (retiredBoots.contains(packet.bootId())) return rejected(Reason.RETIRED_BOOT);
        if (candidate != null && candidate.packet.bootId().equals(packet.bootId())) {
            if (packet.frameId() < candidate.packet.frameId()) return rejected(Reason.OLDER_FRAME);
            if (packet.packetSeq().isPresent() && candidate.packet.packetSeq().isPresent()
                    && packet.packetSeq().getAsLong() < candidate.packet.packetSeq().getAsLong())
                return rejected(Reason.OLDER_PACKET);
        }

        boolean sameBoot = packet.bootId().equals(boot);
        if (sameBoot) {
            if (!packet.profile().equals(profile)) return rejected(Reason.PROFILE_CHANGED);
            if (packet.frameId() < highFrame) return rejected(Reason.OLDER_FRAME);
            if (packet.packetSeq().isPresent() && packet.packetSeq().getAsLong() <= highSequence) {
                if (packet.packetSeq().getAsLong() == highSequence) return duplicate(Reason.NONE);
                return rejected(Reason.OLDER_PACKET);
            }
        }

        if (!sameBoot || adoptionRequired || !packet.revision().equals(revision)) {
            if (!sameBoot && boot != null && !retiredBoots.contains(boot) && !retire(boot))
                return rejected(Reason.RETIREMENT_LIMIT);
            if (candidate != null && (!candidate.packet.bootId().equals(packet.bootId())
                    || !candidate.packet.revision().equals(packet.revision()))) {
                if (!candidate.packet.bootId().equals(packet.bootId())
                        && !candidate.packet.bootId().equals(boot) && !retire(candidate.packet.bootId()))
                    return rejected(Reason.RETIREMENT_LIMIT);
                candidate = null;
            }
            invalidate(Reason.ADOPTION_REQUIRED);
            adoptionRequired = true;
            return adopt(packet, sample);
        }
        return apply(packet, sample, false);
    }

    /** Observe a configured source's raw string before parsing, including malformed
     * payloads. The original readQueue observation time is retained. This updates
     * diagnostic activity only, never publication/measurement freshness, adoption,
     * connection epoch, actionable output or capture mapping. */
    public boolean observeActivity(TransportSample sample, long nowNs) {
        own(nowNs);
        Objects.requireNonNull(sample);
        if (sample.connectionEpoch() < epoch || sample.firstObservedRobotNs() > nowNs
                || sample.dequeueRobotNs() > nowNs
                || nowNs - sample.firstObservedRobotNs() >= config.receiptTimeoutNs()) return false;
        lastActivity = Math.max(lastActivity, sample.firstObservedRobotNs());
        return true;
    }

    private Result adopt(Packet packet, TransportSample sample) {
        if (candidate == null) {
            candidate = new Candidate(packet, sample);
            lastReason = Reason.ADOPTION_REQUIRED;
            return result(Kind.PENDING, Reason.ADOPTION_REQUIRED);
        }
        Candidate pending = candidate;
        if (++pending.attempts > config.maxPendingPackets()) {
            candidate = null;
            if (!packet.bootId().equals(boot)) retire(packet.bootId());
            return rejected(Reason.PENDING_LIMIT);
        }
        if (!packet.profile().equals(pending.packet.profile())) return rejected(Reason.PROFILE_CHANGED);
        if (packet.frameId() < pending.packet.frameId()) return rejected(Reason.OLDER_FRAME);
        if (!advances(packet, pending.packet)) return duplicate(Reason.ADOPTION_REQUIRED);
        if (!metadataAdvances(sample, pending.transport)) return rejected(Reason.METADATA_NOT_ADVANCING);
        pending.tombstoned = packet.frameId() == pending.packet.frameId()
                ? pending.tombstoned || !packet.usable() || lostFamily(pending.packet, packet)
                : !packet.usable();
        pending.packet = packet;
        pending.transport = sample;
        pending.advancing++;
        if (pending.advancing < config.advancingPublications())
            return result(Kind.PENDING, Reason.ADOPTION_REQUIRED);

        boolean changedBoot = !packet.bootId().equals(boot);
        if (changedBoot) {
            highFrame = -1; highSequence = -1; tombstones.clear();
        }
        boot = packet.bootId(); revision = packet.revision(); profile = packet.profile();
        candidate = null; adoptionRequired = false;
        if (pending.tombstoned) tombstone(packet.frameId());
        return apply(packet, sample, pending.tombstoned);
    }

    private Result apply(Packet packet, TransportSample sample, boolean pendingTombstone) {
        boolean repeatedFrame = packet.frameId() == highFrame;
        boolean repeatedLegacyDelivery = repeatedFrame && packet.packetSeq().isEmpty()
                && lastAcceptedTransport != null
                && sample.ntTimestamp() == lastAcceptedTransport.ntTimestamp()
                && sample.ntServerTime() == lastAcceptedTransport.ntServerTime();
        boolean invalidateFrame = !packet.usable() || (repeatedFrame && lastPacket != null
                && lostFamily(lastPacket, packet));
        if (repeatedFrame && packet.packetSeq().isEmpty() && !invalidateFrame) {
            return duplicate(tombstones.contains(packet.frameId()) ? Reason.INVALIDATED_FRAME : Reason.NONE);
        }
        if (packet.frameId() > highFrame) {
            // Older frames are permanently rejected by highFrame, so their tombstones
            // can be removed without enabling revival or retaining unbounded history.
            tombstones.removeIf(frame -> frame < packet.frameId());
        }
        if (invalidateFrame || pendingTombstone) tombstone(packet.frameId());
        boolean deadFrame = tombstones.contains(packet.frameId());

        // Apply coherent status before deduplication. This is critical for legacy
        // same-frame invalidation, which intentionally has no packet_seq.
        lastPacket = packet;
        mappedCapture = -1;
        if (deadFrame) {
            actionablePacket = null;
        } else if (!repeatedFrame) {
            actionablePacket = packet;
        } else if (actionablePacket != null) {
            actionablePacket = packet; // status-only: no second measurement emitted
        }

        // Even an exactly duplicated legacy invalidation has already cleared the
        // frame above, but redelivery with identical original metadata cannot
        // refresh accepted-publication age.
        if (repeatedLegacyDelivery) return duplicate(deadFrame ? Reason.INVALIDATED_FRAME : Reason.NONE);

        highFrame = Math.max(highFrame, packet.frameId());
        if (packet.packetSeq().isPresent()) highSequence = packet.packetSeq().getAsLong();
        lastAcceptedAt = sample.firstObservedRobotNs();
        lastAcceptedTransport = sample;
        acceptedPackets++;
        if (!repeatedFrame && !deadFrame && packet.usable()) {
            lastUsableAt = sample.firstObservedRobotNs();
            usableCaptureServerUs = packet.captureServerUs();
            lastUsableId = packet.observationId();
            lastReason = Reason.NONE;
            return new Result(Kind.ACCEPTED, Optional.of(packet), Reason.NONE);
        }
        lastReason = deadFrame ? Reason.INVALIDATED_FRAME : Reason.NONE;
        return result(Kind.ACCEPTED, lastReason);
    }

    /** Only record an already-verified mapping for the currently actionable frame.
     * Mapping is always cleared when a new status packet is applied. */
    public boolean recordCaptureMapping(Packet packet, long captureRobotNs, long nowNs) {
        // Standalone callers get a conservative capture-age bound; pass the mapper bound explicitly
        // when using a differently configured ClockMapper.
        return recordCaptureMapping(packet, captureRobotNs, nowNs, config.receiptTimeoutNs());
    }

    public boolean recordCaptureMapping(Packet packet, long captureRobotNs, long nowNs, long maxCaptureAgeNs) {
        own(nowNs);
        if (maxCaptureAgeNs < 0) throw new IllegalArgumentException("negative capture age bound");
        expireInternal(nowNs);
        if (captureRobotNs < 0 || captureRobotNs > nowNs || actionablePacket == null
                || !actionablePacket.observationId().equals(packet.observationId())
                || !packet.timeSyncValid() || nowNs - captureRobotNs > maxCaptureAgeNs) return false;
        mappedCapture = captureRobotNs;
        mappedMaxAgeNs = maxCaptureAgeNs;
        return true;
    }

    public void rejectCaptureMapping() { own(lastNow < 0 ? 0 : lastNow); mappedCapture = -1; }

    /** An explicit peer-level disconnect, not server-wide isConnected(). */
    public void disconnect(long newConnectionEpoch, long nowNs) {
        own(nowNs);
        if (newConnectionEpoch < 0 || newConnectionEpoch < epoch)
            throw new IllegalArgumentException("connection epoch must not decrease");
        epoch = newConnectionEpoch;
        candidate = null; adoptionRequired = true; recoveryAfter = nowNs;
        invalidate(Reason.DISCONNECTED);
    }

    /** Conservative parse failure policy: clear this source and require a fresh,
     * advancing observation. A duplicate of the prior frame cannot restore it. */
    public void parseFailure(long nowNs) {
        own(nowNs); candidate = null; recoveryAfter = nowNs; invalidate(Reason.PARSE_FAILURE);
        if (highFrame >= 0) tombstone(highFrame);
    }

    /** Queue loss makes intervening status unknown. The next fresh advancing frame
     * can restore capture-relative outputs; clock checks remain separate. */
    public void overload(long nowNs) {
        own(nowNs); candidate = null; recoveryAfter = nowNs; invalidate(Reason.OVERLOAD);
        if (highFrame >= 0) tombstone(highFrame);
    }

    /** Call every robot cycle, including cycles where readQueue returns no values. */
    public void expire(long nowNs) { own(nowNs); expireInternal(nowNs); }
    public Status status(long nowNs) { expire(nowNs); return status(); }

    /** Read-only status; never drains or re-emits observations. */
    public Status status() {
        long now = Math.max(0, lastNow);
        return new Status(source, Optional.ofNullable(boot), Optional.ofNullable(revision), epoch,
                lastPacket != null && lastPacket.connected() && lastAcceptedAt >= 0
                        && now - lastAcceptedAt < config.receiptTimeoutNs() && !adoptionRequired,
                actionablePacket != null, actionablePacket != null && mappedCapture >= 0,
                Optional.ofNullable(actionablePacket), Optional.ofNullable(lastPacket),
                age(lastActivity, now), age(lastAcceptedAt, now), age(lastUsableAt, now),
                age(mappedCapture, now), usableCaptureServerUs < 0 ? OptionalLong.empty()
                        : OptionalLong.of(usableCaptureServerUs),
                Optional.ofNullable(lastUsableId),
                retiredBoots.size(), tombstones.size(), adoptionRequired || candidate != null,
                lastReason, acceptedPackets, duplicatePackets, rejectedPackets);
    }

    private void expireInternal(long nowNs) {
        if (mappedCapture >= 0 && nowNs - mappedCapture > mappedMaxAgeNs) mappedCapture = -1;
        if (candidate != null && nowNs - candidate.transport.firstObservedRobotNs() >= config.receiptTimeoutNs()) {
            candidate = null;
            invalidate(Reason.EXPIRED);
        }
        if (lastAcceptedAt >= 0 && nowNs - lastAcceptedAt >= config.receiptTimeoutNs()) {
            if (highFrame >= 0) tombstone(highFrame);
            invalidate(Reason.EXPIRED);
        }
        if (lastUsableAt >= 0 && nowNs - lastUsableAt >= config.receiptTimeoutNs()) {
            if (highFrame >= 0) tombstone(highFrame);
            actionablePacket = null; mappedCapture = -1;
        }
    }

    private void invalidate(Reason reason) { actionablePacket = null; mappedCapture = -1; lastReason = reason; }
    private boolean retire(String bootId) {
        if (retiredBoots.contains(bootId)) return true;
        if (retiredBoots.size() == config.maxRetiredBoots()) {
            retirementLimit = true; invalidate(Reason.RETIREMENT_LIMIT); return false;
        }
        retiredBoots.add(bootId); return true;
    }
    private void tombstone(long frame) {
        tombstones.add(frame);
        while (tombstones.size() > config.maxTombstones()) {
            long oldest = tombstones.iterator().next();
            // Only frames below the current high-water mark are safely removable.
            if (oldest >= highFrame) break;
            tombstones.remove(oldest);
        }
    }
    private Result rejected(Reason reason) { rejectedPackets++; lastReason = reason; return result(Kind.REJECTED, reason); }
    private Result duplicate(Reason reason) { duplicatePackets++; return result(Kind.DUPLICATE, reason); }
    private static Result result(Kind kind, Reason reason) { return new Result(kind, Optional.empty(), reason); }
    private static OptionalLong age(long then, long now) { return then < 0 ? OptionalLong.empty() : OptionalLong.of(now - then); }
    private static boolean advances(Packet next, Packet previous) {
        if (next.packetSeq().isPresent() != previous.packetSeq().isPresent()) return false;
        return next.packetSeq().isPresent()
                ? next.packetSeq().getAsLong() > previous.packetSeq().getAsLong()
                : next.frameId() > previous.frameId();
    }
    private static boolean metadataAdvances(TransportSample next, TransportSample previous) {
        if (next.ntServerTime() > 0 && previous.ntServerTime() > 0)
            return next.ntServerTime() > previous.ntServerTime();
        return next.ntTimestamp() > 0 && previous.ntTimestamp() > 0
                && next.ntTimestamp() > previous.ntTimestamp();
    }
    private static boolean lostFamily(Packet previous, Packet next) {
        for (String family : FAMILIES)
            if (previous.familyValid(family) && !next.familyValid(family)) return true;
        return !previous.detections().isEmpty() && next.detections().isEmpty();
    }
    private void own(long nowNs) {
        if (nowNs < 0 || nowNs < lastNow) throw new IllegalArgumentException("robot monotonic clock moved backwards");
        Thread current = Thread.currentThread();
        if (owner == null) owner = current;
        else if (owner != current) throw new IllegalStateException("SourceSession has one robot-loop owner");
        lastNow = nowNs;
    }
}
