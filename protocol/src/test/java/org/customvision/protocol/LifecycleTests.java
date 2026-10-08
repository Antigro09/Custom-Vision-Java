package org.customvision.protocol;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;

/** CPU-only synthetic lifecycle inputs. Producer golden-byte parsing is tested separately. */
public final class LifecycleTests {
    private static final SourceKey LEFT = new SourceKey("/CustomVision/left/tags", "tags", "apriltag");
    private static final SourceKey RIGHT = new SourceKey("/CustomVision/right/tags", "tags", "apriltag");
    private static final long MS = 1_000_000L;
    private static int checks;

    public static void run() {
        checks = 0;
        goldenReplay();
        retainedAndDuplicate();
        additiveOrderingAndTombstones();
        legacyOrderingAndTombstones();
        bootRetirementAndIsolation();
        reconnectionAndRevision();
        expirationAndRecovery();
        malformedAndOverloadedRecovery();
        malformedActivity();
        clockProvenance();
        boundsAndOwner();
        System.out.println("LifecycleTests: " + checks + " assertions passed");
    }

    private static void goldenReplay() {
        try {
            SourceKey source = new SourceKey("/CustomVision/golden/front_tags", "front_tags", "apriltag");
            ProtocolDecoder decoder = new ProtocolDecoder();
            Packet single = golden(decoder, source, "single_tag", "92cc685066cc44db90928d561945baa1bce8895519c115d20d046f0d7b50eed0", 2882);
            Packet invalid = golden(decoder, source, "same_frame_watchdog", "48868e0a8135c21242e61c8414fdf0e4891a7468a2ed27b7477a03e5b8cb0d97", 1608);
            Packet repeated = golden(decoder, source, "repeated_invalidation", "899b5501e528862ab3ebef632f6463d19418f714c82f6d5eb3fcda8aecaf6721", 1608);
            Packet bootB = golden(decoder, source, "new_boot", "71db7f9c25a91d8bea916d85a0e3ed09ca96592b3262bec351d0dcf59d15f9d6", 2882);
            SourceSession s = new SourceSession(source);
            eq(SourceSession.Kind.PENDING, accept(s, single, 1, 0).kind(), "golden first retained publication pending");
            eq(SourceSession.Kind.ACCEPTED, accept(s, invalid, 2, 0).kind(), "golden watchdog sequence permits adoption of invalid status");
            yes(!s.status().actionable(), "golden sameframe watchdog tombstones frame");
            yes(accept(s, repeated, 3, 0).newlyAcceptedMeasurement().isEmpty(), "golden repeated invalidation emits no measurement");
            eq(SourceSession.Reason.OLDER_PACKET, accept(s, single, 4, 0).reason(), "golden delayed valid seq0 cannot revive");
            eq(SourceSession.Kind.PENDING, accept(s, bootB, 5, 0).kind(), "golden new boot pending advancement");
            eq(SourceSession.Reason.RETIRED_BOOT, accept(s, repeated, 6, 0).reason(), "golden oldboot invalidation cannot affect newboot candidate");
            // Synthetic replay continuation is explicitly a DTO, never changed golden bytes.
            Packet advancingB = new Packet(bootB.source(), bootB.schemaVersion(), bootB.bootId(),
                    bootB.frameId() + 1, OptionalLong.of(1), bootB.revision(), bootB.connected(),
                    bootB.captureServerUs(), bootB.timeSyncValid(), bootB.fields());
            yes(accept(s, advancingB, 7, 0).newlyAcceptedMeasurement().isPresent(), "synthetic advancing B continuation adopts");
            eq(SourceSession.Reason.RETIRED_BOOT, accept(s, invalid, 8, 0).reason(), "golden delayed oldboot invalidation after adoption rejected");
            yes(s.status().actionable(), "golden invalidation cannot clear adopted B");
        } catch (Exception error) { throw new AssertionError("golden lifecycle replay failed", error); }
    }

    private static Packet golden(ProtocolDecoder decoder, SourceKey source, String name, String hash, int size) throws Exception {
        Path root = Path.of(System.getProperty("customvision.fixtures", "fixtures"));
        if (!Files.isDirectory(root.resolve("fixtures"))) root = Path.of("..", "fixtures");
        byte[] bytes = Files.readAllBytes(root.resolve("fixtures").resolve(name + ".json"));
        eq(size, bytes.length, "golden exact byte count " + name);
        eq(hash, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), "golden pinned SHA256 " + name);
        return decoder.decode(source, new String(bytes, StandardCharsets.UTF_8));
    }

    private static void retainedAndDuplicate() {
        SourceSession s = new SourceSession(LEFT);
        Packet p1 = packet(LEFT, "A", 1, 1, true);
        eq(SourceSession.Kind.PENDING, accept(s, p1, 1, 0).kind(), "retained first is pending");
        yes(!s.status().actionable(), "retained first cannot act");
        eq(SourceSession.Kind.DUPLICATE, accept(s, p1, 2, 0).kind(), "repeated retained first cannot adopt");
        SourceSession.Result fresh = accept(s, packet(LEFT, "A", 2, 2, true), 3, 0);
        yes(fresh.newlyAcceptedMeasurement().isPresent(), "advancing publication adopts");
        long acceptedAgeBefore = s.status().acceptedPacketAgeNs().orElseThrow();
        eq(SourceSession.Kind.DUPLICATE, accept(s, packet(LEFT, "A", 2, 2, true), 50, 0).kind(), "same seq duplicate");
        eq(47 * MS + acceptedAgeBefore, s.status().acceptedPacketAgeNs().orElseThrow(), "duplicate does not refresh accepted publication");
        eq(47 * MS, s.status().usableObservationAgeNs().orElseThrow(), "duplicate does not refresh observation");
        yes(s.status().currentPacket().isPresent(), "status observes existing packet");
        yes(s.status().currentPacket().isPresent(), "repeated getter remains status only");
        SourceSession m = new SourceSession(LEFT);
        accept(m, packet(LEFT, "A", 1, 1, true), 1, 0);
        TransportSample sameMetadata = new TransportSample("synthetic", 1, 1, 2 * MS, 2 * MS, 0);
        eq(SourceSession.Reason.METADATA_NOT_ADVANCING,
                m.accept(packet(LEFT, "A", 2, 2, true), sameMetadata, 2 * MS).reason(), "adoption requires increasing original metadata");
        yes(!m.status().actionable(), "metadata-reused publication cannot act");
    }

    private static void additiveOrderingAndTombstones() {
        SourceSession s = adopted(LEFT, true);
        SourceSession.Result invalid = accept(s, packet(LEFT, "A", 2, 3, false), 3, 0);
        eq(SourceSession.Kind.ACCEPTED, invalid.kind(), "same-frame seq invalidation accepted");
        yes(!s.status().actionable(), "same-frame invalidation clears actions");
        eq(1, s.status().tombstoneCount(), "same-frame invalidation tombstone retained");
        SourceSession.Result delayed = accept(s, packet(LEFT, "A", 2, 4, true), 4, 0);
        yes(delayed.newlyAcceptedMeasurement().isEmpty() && !s.status().actionable(), "even newer status seq cannot revive invalidated frame");
        yes(accept(s, packet(LEFT, "A", 3, 5, true), 5, 0).newlyAcceptedMeasurement().isPresent(), "fresh newer frame restores");
        eq(SourceSession.Reason.OLDER_PACKET, accept(s, packet(LEFT, "A", 3, 4, false), 6, 0).reason(), "older sequence cannot invalidate");
        eq(SourceSession.Reason.OLDER_FRAME, accept(s, packet(LEFT, "A", 2, 6, false), 7, 0).reason(), "older frame cannot invalidate despite newer seq");
        yes(s.status().actionable(), "old invalidations leave current frame intact");
        SourceSession.Result heartbeat = accept(s, packet(LEFT, "A", 4, 6, false), 8, 0);
        yes(heartbeat.newlyAcceptedMeasurement().isEmpty(), "invalid heartbeat emits no measurement");
        eq(3 * MS, s.status().usableObservationAgeNs().orElseThrow(), "heartbeat does not refresh observation age");
        Packet disconnected = packet(LEFT, "A", 5, 7, true);
        disconnected = new Packet(disconnected.source(), 2, disconnected.bootId(), disconnected.frameId(),
                disconnected.packetSeq(), disconnected.revision(), false, disconnected.captureServerUs(),
                disconnected.timeSyncValid(), disconnected.fields());
        accept(s, disconnected, 9, 0);
        yes(!s.status().connected() && !s.status().actionable(), "connected=false clears families even if payload retains detections");

        SourceSession families = adopted(LEFT, true);
        Packet withPoi = packetWithFamilies(LEFT, "A", 3, 3, true, Map.of("poi", Map.of("valid", true)));
        accept(families, withPoi, 3, 0);
        yes(families.status().currentPacket().orElseThrow().familyValid("poi"), "valid family appears");
        accept(families, packet(LEFT, "A", 4, 4, true), 4, 0);
        yes(!families.status().currentPacket().orElseThrow().familyValid("poi"), "missing family clears preceding value");
        Packet validLocalization = packetWithFamilies(LEFT, "A", 5, 5, true, Map.of("localization", Map.of("valid", true)));
        accept(families, validLocalization, 5, 0);
        accept(families, packet(LEFT, "A", 5, 6, true), 6, 0);
        yes(!families.status().actionable(), "same-frame family removal tombstones all actionable outputs");
        accept(families, packetWithFamilies(LEFT, "A", 5, 7, true, Map.of("localization", Map.of("valid", true))), 7, 0);
        yes(!families.status().actionable(), "delayed same-frame valid family cannot revive");
    }

    private static void legacyOrderingAndTombstones() {
        SourceSession s = adopted(LEFT, false);
        eq(SourceSession.Kind.DUPLICATE, accept(s, packet(LEFT, "A", 2, -1, true), 3, 0).kind(), "legacy frame dedup");
        eq(SourceSession.Kind.ACCEPTED, accept(s, packet(LEFT, "A", 2, -1, false), 4, 0).kind(), "legacy invalidation before dedup");
        yes(!s.status().actionable(), "legacy invalidation clears");
        yes(accept(s, packet(LEFT, "A", 2, -1, true), 5, 0).newlyAcceptedMeasurement().isEmpty(), "legacy delayed valid blocked");
        yes(!s.status().actionable(), "legacy tombstone survives valid duplicate");
        Packet invalidDuplicate = packet(LEFT, "A", 2, -1, false);
        TransportSample repeatedMetadata = new TransportSample("synthetic", 4, 4, 5 * MS, 5 * MS, 0);
        eq(SourceSession.Kind.DUPLICATE, s.accept(invalidDuplicate, repeatedMetadata, 5 * MS).kind(), "legacy invalid redelivery metadata dedup");
        eq(1 * MS, s.status().acceptedPacketAgeNs().orElseThrow(), "invalid duplicate does not refresh accepted publication");
        yes(accept(s, packet(LEFT, "A", 3, -1, true), 6, 0).newlyAcceptedMeasurement().isPresent(), "legacy next frame restores");
        eq(SourceSession.Reason.OLDER_FRAME, accept(s, packet(LEFT, "A", 2, -1, false), 7, 0).reason(), "legacy older invalidation rejected");
        yes(s.status().actionable(), "legacy current unaffected by old invalidation");

        SourceSession pending = new SourceSession(LEFT);
        accept(pending, packet(LEFT, "A", 1, 1, false), 1, 0);
        SourceSession.Result next = accept(pending, packet(LEFT, "A", 1, 2, true), 2, 0);
        yes(next.newlyAcceptedMeasurement().isEmpty() && !pending.status().actionable(), "pending same-frame invalidation survives adoption");
    }

    private static void bootRetirementAndIsolation() {
        SourceSession left = adopted(LEFT, true);
        SourceSession right = adopted(RIGHT, true);
        eq(SourceSession.Kind.PENDING, accept(left, packet(LEFT, "B", 1, 1, true), 3, 0).kind(), "boot B first pending");
        yes(!left.status().actionable(), "boot change clears source");
        yes(right.status().actionable(), "another source remains actionable");
        eq(SourceSession.Reason.RETIRED_BOOT, accept(left, packet(LEFT, "A", 2, 3, false), 4, 0).reason(), "retired A invalidation rejected during B adoption");
        yes(accept(left, packet(LEFT, "B", 2, 2, true), 5, 0).newlyAcceptedMeasurement().isPresent(), "B adopted");
        eq(SourceSession.Reason.RETIRED_BOOT, accept(left, packet(LEFT, "A", 100, 100, false), 6, 0).reason(), "delayed A cannot clear B");
        eq("B", left.status().currentPacket().orElseThrow().bootId(), "B remains current");
        eq(SourceSession.Reason.SOURCE_MISMATCH, accept(left, packet(RIGHT, "B", 3, 3, false), 7, 0).reason(), "full namespace isolates same pipeline");
        yes(left.status().actionable() && right.status().actionable(), "source mismatch does not affect either source");
    }

    private static void reconnectionAndRevision() {
        SourceSession s = adopted(LEFT, true);
        s.disconnect(1, 3 * MS);
        yes(!s.status().actionable() && !s.status().connected(), "peer disconnect clears only source");
        eq(SourceSession.Kind.DUPLICATE, accept(s, packet(LEFT, "A", 2, 2, true), 4, 1).kind(), "cached reconnect duplicate can't adopt");
        eq(SourceSession.Kind.PENDING, accept(s, packet(LEFT, "A", 3, 3, true), 5, 1).kind(), "first advancing reconnect pending");
        yes(accept(s, packet(LEFT, "A", 4, 4, true), 6, 1).newlyAcceptedMeasurement().isPresent(), "second advancing reconnect adopted");
        eq(SourceSession.Reason.OLD_EPOCH, accept(s, packet(LEFT, "A", 5, 5, false), 7, 0).reason(), "old local epoch ignored");
        yes(s.status().actionable(), "old epoch does not clear current");
        Packet r1 = revision(packet(LEFT, "A", 5, 5, true), "new-calibration");
        eq(SourceSession.Kind.PENDING, accept(s, r1, 8, 1).kind(), "revision change requires advancing adoption");
        yes(!s.status().actionable(), "revision change clears cached family values");
        eq(SourceSession.Reason.OLDER_FRAME, accept(s, packet(LEFT, "A", 4, 4, true), 9, 1).reason(), "pending revision can't regress");
        yes(accept(s, revision(packet(LEFT, "A", 6, 6, true), "new-calibration"), 10, 1).newlyAcceptedMeasurement().isPresent(), "revision adopted");
    }

    private static void expirationAndRecovery() {
        SourceSession s = adopted(LEFT, true);
        accept(s, packet(LEFT, "A", 2, 2, true), 90, 0);
        s.expire(102 * MS);
        yes(!s.status().actionable(), "expire every cycle without arrival");
        eq(100 * MS, s.status().usableObservationAgeNs().orElseThrow(), "observation age independent duplicate activity");
        eq(12 * MS, s.status().activityAgeNs().orElseThrow(), "activity reflects delivery separately");
        accept(s, packet(LEFT, "A", 2, 3, true), 103, 0);
        yes(!s.status().actionable(), "sameframe fresh status cannot revive expired observation");
        yes(accept(s, packet(LEFT, "A", 3, 4, true), 104, 0).newlyAcceptedMeasurement().isPresent(), "fresh advancing observation recovers timeout");
        TransportSample delayed = new TransportSample("synthetic", 200, 200, 1 * MS, 105 * MS, 0);
        eq(SourceSession.Reason.STALE_RECEIPT, s.accept(packet(LEFT, "A", 4, 5, false), delayed, 105 * MS).reason(), "queue-delayed stale invalidation rejected");
        yes(s.status().actionable(), "stale queued invalidation cannot clear fresh frame");
        SourceSession right = adopted(RIGHT, true);
        // Server may remain connected due to right peer; source timeout is independent.
        right.expire(50 * MS);
        s.expire(205 * MS);
        yes(!s.status().actionable() && right.status().actionable(), "one missing source expires while another peer remains live");
        accept(s, packet(LEFT, "A", 1, 1, true), 206, 0);
        yes(!s.status().connected(), "rejection after timeout cannot change source liveness");
    }

    private static void malformedAndOverloadedRecovery() {
        SourceSession s = adopted(LEFT, true);
        s.parseFailure(3 * MS);
        eq(SourceSession.Reason.PARSE_FAILURE, s.status().lastReason(), "parse failure observable");
        yes(!s.status().actionable(), "parse failure clears current source");
        accept(s, packet(LEFT, "A", 2, 3, true), 4, 0);
        yes(!s.status().actionable(), "parse failure cannot revive sameframe");
        yes(accept(s, packet(LEFT, "A", 3, 4, true), 5, 0).newlyAcceptedMeasurement().isPresent(), "new frame recovers parse failure");
        s.overload(6 * MS);
        yes(!s.status().actionable(), "overload invalidates action");
        TransportSample beforeLoss = new TransportSample("synthetic", 7, 7, 5 * MS, 7 * MS, 0);
        eq(SourceSession.Reason.STALE_RECEIPT, s.accept(packet(LEFT, "A", 4, 5, true), beforeLoss, 7 * MS).reason(), "pre-overload handoff can't restore");
        yes(accept(s, packet(LEFT, "A", 4, 5, true), 8, 0).newlyAcceptedMeasurement().isPresent(), "fresh acceptable frame recovers overload");
    }

    private static void clockProvenance() {
        SourceSession s = adopted(LEFT, true);
        Packet packet = s.status().currentPacket().orElseThrow();
        yes(!s.status().fusionEligible() && s.status().captureAgeNs().isEmpty(), "unmapped capture never claims fusion eligibility");
        yes(s.recordCaptureMapping(packet, 1 * MS, 2 * MS), "verified capture map records");
        eq(1 * MS, s.status().captureAgeNs().orElseThrow(), "capture age distinct from receipt age");
        yes(s.status().fusionEligible(), "mapped current frame eligible");
        accept(s, packet(LEFT, "A", 3, 3, true), 3, 0);
        yes(s.status().captureAgeNs().isEmpty(), "new packet cannot retain old capture mapping");
        yes(!s.recordCaptureMapping(packet, 1 * MS, 3 * MS), "old observation mapping rejected");
        yes(!s.recordCaptureMapping(s.status().currentPacket().orElseThrow(), 4 * MS, 3 * MS), "future capture mapping rejected");
        s.rejectCaptureMapping();
        yes(!s.status().fusionEligible(), "mapping rejection leaves capture-relative status only");
    }

    private static void malformedActivity() {
        SourceSession s = adopted(LEFT, true);
        TransportSample malformed = new TransportSample("{bad JSON", 30, 30, 30 * MS, 50 * MS, 0);
        yes(s.observeActivity(malformed, 50 * MS), "malformed arrival still reports diagnostic activity");
        s.parseFailure(50 * MS);
        eq(20 * MS, s.status().activityAgeNs().orElseThrow(), "bad JSON preserves original observation time, not parse/dequeue time");
        eq(48 * MS, s.status().acceptedPacketAgeNs().orElseThrow(), "bad JSON does not refresh accepted packet age");
        eq(48 * MS, s.status().usableObservationAgeNs().orElseThrow(), "bad JSON does not refresh usable observation age");
        yes(!s.status().actionable(), "malformed activity does not imply actionable state");
        yes(!s.observeActivity(new TransportSample("future", 51, 51, 51 * MS, 51 * MS, 0), 50 * MS), "future raw activity rejected");
        eq(20 * MS, s.status().activityAgeNs().orElseThrow(), "future activity cannot alter source activity");
        s.disconnect(1, 51 * MS);
        yes(!s.observeActivity(new TransportSample("old epoch", 52, 52, 52 * MS, 52 * MS, 0), 52 * MS), "old epoch raw activity rejected");
        yes(!s.observeActivity(new TransportSample("stale", 130, 130, 30 * MS, 130 * MS, 1), 130 * MS), "stale original receipt cannot refresh activity");
        eq(100 * MS, s.status().activityAgeNs().orElseThrow(), "stale activity stays tied to last fresh arrival");
    }

    private static void boundsAndOwner() {
        SourceSession s = new SourceSession(LEFT, new SourceSession.Config(100 * MS, 2, 3, 1, 1));
        accept(s, packet(LEFT, "A", 1, 1, true), 1, 0);
        accept(s, packet(LEFT, "A", 2, 2, true), 2, 0);
        accept(s, packet(LEFT, "B", 1, 1, true), 3, 0);
        accept(s, packet(LEFT, "B", 2, 2, true), 4, 0);
        eq(SourceSession.Reason.RETIREMENT_LIMIT, accept(s, packet(LEFT, "C", 1, 1, true), 5, 0).reason(), "retired boot bound fails closed");
        yes(!s.status().actionable() && s.status().retiredBootCount() == 1, "retirement history never evicts to permit replay");
        SourceSession many = adopted(LEFT, true);
        for (int frame = 3; frame < 100; frame++) {
            accept(many, packet(LEFT, "A", frame, frame, false), frame, 0);
            yes(many.status().tombstoneCount() <= 4, "tombstones bounded by monotonic frame rejection");
        }
        SourceSession pending = new SourceSession(LEFT, new SourceSession.Config(100 * MS, 2, 2, 2, 1));
        Packet p = packet(LEFT, "A", 1, 1, true);
        accept(pending, p, 1, 0);
        accept(pending, p, 2, 0);
        eq(SourceSession.Reason.PENDING_LIMIT, accept(pending, p, 3, 0).reason(), "pending packet work bounded");
        SourceSession owner = adopted(LEFT, true);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try { owner.expire(3 * MS); } catch (Throwable error) { failure.set(error); }
        });
        thread.start();
        try { thread.join(); } catch (InterruptedException error) { throw new AssertionError(error); }
        yes(failure.get() instanceof IllegalStateException, "NT thread cannot mutate lifecycle");
        boolean backwards = false;
        try { owner.expire(1 * MS); } catch (IllegalArgumentException expected) { backwards = true; }
        yes(backwards, "robot monotonic clock cannot move backwards");
    }

    private static SourceSession adopted(SourceKey source, boolean sequenced) {
        SourceSession session = new SourceSession(source);
        accept(session, packet(source, "A", 1, sequenced ? 1 : -1, true), 1, 0);
        yes(accept(session, packet(source, "A", 2, sequenced ? 2 : -1, true), 2, 0)
                .newlyAcceptedMeasurement().isPresent(), "synthetic adoption");
        return session;
    }
    private static SourceSession.Result accept(SourceSession source, Packet packet, long timeMs, long epoch) {
        return source.accept(packet, new TransportSample("synthetic", timeMs, timeMs, timeMs * MS, timeMs * MS, epoch), timeMs * MS);
    }
    private static Packet packet(SourceKey source, String boot, long frame, long seq, boolean usable) {
        return packetWithFamilies(source, boot, frame, seq, usable, Map.of());
    }
    private static Packet packetWithFamilies(SourceKey source, String boot, long frame, long seq,
                                              boolean usable, Map<String, Object> families) {
        Map<String, Object> fields = new LinkedHashMap<>();
        if (seq >= 0) fields.put("protocol_profile", "custom-vision-schema2-2026.1");
        fields.put("detections", usable ? List.of(Map.of("id", 1L)) : List.of());
        fields.putAll(families);
        return new Packet(source, 2, boot, frame, seq < 0 ? OptionalLong.empty() : OptionalLong.of(seq),
                "legacy", true, timeCaptureUs(frame), true, fields);
    }
    private static long timeCaptureUs(long frame) { return frame * 1_000L; }
    private static Packet revision(Packet p, String revision) {
        return new Packet(p.source(), p.schemaVersion(), p.bootId(), p.frameId(), p.packetSeq(), revision,
                p.connected(), p.captureServerUs(), p.timeSyncValid(), p.fields());
    }
    private static void yes(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static void eq(Object expected, Object actual, String message) { checks++; if (!expected.equals(actual)) throw new AssertionError(message + ": expected " + expected + ", got " + actual); }
}
