package org.customvision.interop;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import org.customvision.protocol.ClockMapper;
import org.customvision.protocol.Packet;
import org.customvision.protocol.ProtocolDecoder;
import org.customvision.protocol.RawQueue;
import org.customvision.protocol.SourceKey;
import org.customvision.protocol.SourceSession;
import org.customvision.protocol.SyncSnapshot;
import org.customvision.protocol.TimeVersion;
import org.customvision.protocol.VisionClient;
import org.frcworldstate.core.Geometry;
import org.frcworldstate.core.PoseHistory;
import org.frcworldstate.core.World;
import org.frcworldstate.core.WorldEngine;
import org.frcworldstate.vision.CustomVisionAdapter;
import org.frcworldstate.vision.VisionTrackBridge;

/**
 * Released-native localhost -> decoder -> actual session/clock -> consume-once -> core bridge.
 * Every successful capture uses explicitly SYNTHETIC timing evidence. No HAL or hardware.
 */
public final class NativeAdmissionBridgeTests {
  public static final SourceKey SOURCE = new SourceKey(
      "/CustomVision/fixture/front_objects", "front_objects", "object");
  private static final String EPOCH = "synthetic-system-nanotime-loopback-epoch";
  private static final String GOLDEN_SHA = "c0e5c0ea9241423e1d3aa8fe19373931cf3fab080ced9af2b9021d9eaf6fc6d9";
  private static final String EMPTY_OBJECTS_SHA = "19fbeda2124177a3eebf5571698e6cd9ce1b0c7e2fc746fc1223031fd68229fd";
  private static int assertions;

  /** Small profile seam: only these two implementations reference NT natives. */
  public interface Surface extends AutoCloseable {
    RawQueue queue();
    TimeVersion version();
    boolean connected();
    OptionalLong serverMinusLocalRaw();
    long localNtRaw();
    long publish(String text);
    boolean clientStillValid();
    @Override void close();
  }

  private record Calibration(long localToRobotOffsetNs, long uncertaintyNs) {}

  public static void run(Surface surface, Path fixturePath) throws Exception {
    byte[] bytes = Files.readAllBytes(fixturePath);
    String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    check(GOLDEN_SHA.equals(sha), "authoritative unchanged golden byte SHA256");
    String golden = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    byte[] emptyBytes = Files.readAllBytes(fixturePath.resolveSibling("empty_objects.json"));
    check(EMPTY_OBJECTS_SHA.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(emptyBytes))),
        "authoritative empty-objects golden byte SHA256");
    // Use the real producer's cleared family shape for a watchdog, not a partially invalidated packet.
    String emptyObjects = new String(emptyBytes, java.nio.charset.StandardCharsets.UTF_8)
        .replace("\"packet_seq\":7", "\"packet_seq\":0");
    Packet decoded = new ProtocolDecoder().decode(SOURCE, golden);
    check(!Boolean.TRUE.equals(decoded.family("timing").orElseThrow().get("capture_correction_verified")),
        "original fixture explicitly has unverified timing");

    // Publish before the subscriber's connection is ready: the topic is genuinely retained.
    surface.publish(golden);
    waitUntil(() -> surface.connected() && surface.serverMinusLocalRaw().isPresent(),
        "native localhost connection and time synchronization");
    Calibration calibration = calibrate(surface);
    AtomicBoolean syncAvailable = new AtomicBoolean(true);
    var mapper = new ClockMapper(new ClockMapper.Config(EPOCH, 250_000_000L, 1_000_000L,
        1_000_000_000L, 5_000_000L, Optional.empty()));
    try (var client = new VisionClient(List.of(new VisionClient.Input(SOURCE, surface.queue(), () -> {
      long observed = System.nanoTime();
      return new SyncSnapshot(surface.version(), syncAvailable.get()
          ? surface.serverMinusLocalRaw() : OptionalLong.empty(), EPOCH, true,
          calibration.localToRobotOffsetNs(), calibration.uncertaintyNs(), observed, 0,
          "SYNTHETIC localhost WPIUtil clock bracket against System.nanoTime; no hardware evidence");
    })), new ProtocolDecoder(), mapper, new VisionClient.Limits(1, 8, 8, 16, 16, 50_000_000L), System::nanoTime)) {

      // Connection/sync is insufficient for topic readiness. Actual decode is our receipt barrier.
      pollUntil(client, () -> client.counters().decoded() == 1, "retained original packet receipt");
      check(client.statuses(System.nanoTime()).get(SOURCE).adoptionPending(), "one retained publication stays pending");
      check(client.drainMeasurements().isEmpty(), "retained first publication cannot fabricate acceptance");
      check(client.drainReceipts().isEmpty(), "pending publication has no admitted clock receipt");

      publishAndPoll(surface, client, golden.replace("\"packet_seq\":0", "\"packet_seq\":1"));
      List<VisionClient.Receipt> original = client.drainReceipts();
      check(original.size() == 1, "fresh advancing publication performs actual boot adoption");
      check(original.get(0).clock().rejection().orElseThrow().reason()
          == ClockMapper.Reason.CAPTURE_CORRECTION_UNVERIFIED, "original timing remains rejected");
      check(client.drainMeasurements().isEmpty(), "clock rejection cannot reach World-State");

      String acceptedText = synthetic(golden, 43, 2, captureUs(surface), true);
      long publishedRaw = publishAndPoll(surface, client, acceptedText);
      awaitMeasurement(client);
      List<VisionClient.Measurement> admitted = client.drainMeasurements(System.nanoTime());
      check(admitted.size() == 1, "actual native/decoder/session/clock emits one admitted envelope");
      VisionClient.Measurement measurement = admitted.get(0);
      check(measurement.admission().kind() == SourceSession.Kind.ACCEPTED
          && measurement.admission().newlyAcceptedMeasurement().orElseThrow() == measurement.packet(),
          "exact original lifecycle result and packet identity retained");
      VisionClient.Receipt receipt = client.drainReceipts().get(0);
      check(measurement.clock() == receipt.clock() && measurement.transport() == receipt.transport(),
          "exact original full clock and transport objects retained");
      check(measurement.transport().payload().equals(acceptedText), "native bytes match distinctly synthetic packet");
      long wireQuantumRaw = surface.version() == TimeVersion.WPILIB_2026_MICROSECONDS ? 1 : 1000;
      long expectedWireRaw = publishedRaw / wireQuantumRaw * wireQuantumRaw;
      check(measurement.transport().ntServerTime() == expectedWireRaw,
          "native received server metadata equals NT4's microsecond wire quantization; published=" + publishedRaw
              + ", received=" + measurement.transport().ntServerTime() + ", rawQuantum=" + wireQuantumRaw);
      check(measurement.capture().rawNtServerTime() == measurement.transport().ntServerTime()
          && measurement.capture().rawNtTimestamp() == measurement.transport().ntTimestamp(),
          "exact received metadata, including wire precision, preserved into capture provenance");
      System.out.println("CVJ_WIRE_PRECISION\t" + surface.version() + "\tpublishedRaw=" + publishedRaw
          + "\treceivedServerRaw=" + measurement.transport().ntServerTime()
          + "\tdiscardedSubMicrosecondRaw=" + (publishedRaw - expectedWireRaw));
      check(measurement.capture().metadataVersion() == surface.version(), "profile metadata version preserved");
      check(measurement.capture().ntServerTimeNs() == surface.version().metadataToNanoseconds(expectedWireRaw),
          "2026 microsecond or alpha-7 nanosecond metadata converted exactly");
      check(measurement.capture().rawCaptureServerUs() == measurement.packet().captureServerUs(),
          "JSON integer capture stays microseconds on both profiles");
      long independentlyMapped = Math.addExact(Math.subtractExact(
          TimeVersion.jsonMicrosecondsToNanoseconds(measurement.packet().captureServerUs()),
          surface.version().metadataToNanoseconds(measurement.capture().rawServerMinusLocal())),
          calibration.localToRobotOffsetNs());
      check(independentlyMapped == measurement.capture().robotCaptureNs(),
          "capture is mapped from NT capture/offset and separately verified epoch, never dequeue/Unix");
      check(measurement.capture().robotCaptureNs() < measurement.transport().firstObservedRobotNs(),
          "10 ms synthetic capture delay remains capture-relative");
      check(client.drainMeasurements().isEmpty(), "consume-once channel cannot re-emit observation");

      WorldEngine engine = normalizeAndTrack(client, measurement);
      int retainedRaw = engine.rawHistory().size();
      publishAndPoll(surface, client, acceptedText); // Identical text, later native publication metadata.
      check(client.drainMeasurements().isEmpty(), "KEEP_DUPLICATES delivery cannot re-admit same publication");
      check(engine.rawHistory().size() == retainedRaw, "duplicate cannot reinsert core observations");
      check(client.statuses(System.nanoTime()).get(SOURCE).duplicatePackets() >= 1,
          "duplicate delivery is observable independently of measurement channel");

      // Leave a successful frame queued; an ordered same-frame invalidation must purge it.
      String frame44 = synthetic(golden, 44, 3, captureUs(surface), true);
      publishAndPoll(surface, client, frame44);
      awaitMeasurement(client);
      check(client.counters().pendingMeasurements() == 1, "fresh accepted observation remains queued");
      String invalid = synthetic(emptyObjects, 44, 4, new ProtocolDecoder().decode(SOURCE, frame44).captureServerUs(), true)
          .replace("\"connected\":true", "\"connected\":false");
      publishAndPoll(surface, client, invalid);
      check(client.drainMeasurements().isEmpty(), "same-frame invalidation purges queued measurement before handoff");
      check(!client.statuses(System.nanoTime()).get(SOURCE).actionable(), "invalidation clears actionable source");
      publishAndPoll(surface, client, frame44.replace("\"packet_seq\":3", "\"packet_seq\":5"));
      check(client.drainMeasurements().isEmpty(), "delayed valid same-frame packet cannot revive tombstone");
      check(client.statuses(System.nanoTime()).get(SOURCE).tombstoneCount() >= 1,
          "same-frame invalidation tombstone retained");

      // A caller may retain an envelope after draining. Its first bridge call must still be gated.
      String heldText = synthetic(golden, 45, 6, captureUs(surface), true);
      publishAndPoll(surface, client, heldText);
      awaitMeasurement(client);
      VisionClient.Measurement held = client.drainMeasurements().get(0);
      client.drainReceipts();
      BoundWorld heldWorld = boundWorld(client, held);
      check(heldWorld.engine().rawHistory().isEmpty(), "held envelope has never been forwarded to tracker");
      publishAndPoll(surface, client, synthetic(emptyObjects, 45, 7, held.packet().captureServerUs(), true)
          .replace("\"connected\":true", "\"connected\":false"));
      assertBlockedFirstDelivery(heldWorld, held, "first delivery after invalidation");
      publishAndPoll(surface, client, synthetic(golden, 46, 8, captureUs(surface), true));
      awaitMeasurement(client);
      check(client.drainMeasurements().size() == 1, "fresh frame restores live delivery");
      client.drainReceipts();
      assertBlockedFirstDelivery(heldWorld, held, "first delivery after later source recovery");

      String priorBootText = synthetic(golden, 47, 9, captureUs(surface), true);
      publishAndPoll(surface, client, priorBootText);
      awaitMeasurement(client);
      VisionClient.Measurement priorBoot = client.drainMeasurements().get(0);
      client.drainReceipts();
      BoundWorld priorBootWorld = boundWorld(client, priorBoot);
      String bootB = golden.replace("fixture-boot-a", "fixture-boot-b");
      publishAndPoll(surface, client, synthetic(bootB, 0, 0, captureUs(surface), true));
      check(client.drainMeasurements().isEmpty(), "new boot's retained first publication stays pending");
      publishAndPoll(surface, client, synthetic(bootB, 1, 1, captureUs(surface), true));
      awaitMeasurement(client);
      VisionClient.Measurement currentBoot = client.drainMeasurements().get(0);
      client.drainReceipts();
      assertBlockedFirstDelivery(priorBootWorld, priorBoot, "first delivery after different boot adoption");
      publishAndPoll(surface, client, priorBootText.replace("\"packet_seq\":9", "\"packet_seq\":10"));
      check(client.statuses(System.nanoTime()).get(SOURCE).bootId().orElseThrow().equals("fixture-boot-b"),
          "delayed retired boot cannot alter active boot");
      check(client.isDeliverable(currentBoot, System.nanoTime()), "delayed retired boot cannot revoke current boot envelope");

      syncAvailable.set(false);
      publishAndPoll(surface, client, synthetic(bootB, 2, 2, captureUs(surface), true));
      List<VisionClient.Receipt> unavailable = client.drainReceipts();
      check(unavailable.size() == 1 && unavailable.get(0).clock().rejection().orElseThrow().reason()
          == ClockMapper.Reason.UNSYNCHRONIZED, "missing consumer sync rejects actual admitted native input");
      check(client.drainMeasurements().isEmpty(), "unsynchronized capture cannot cross bridge");

      syncAvailable.set(true);
      publishAndPoll(surface, client, synthetic(bootB, 3, 3, captureUs(surface), true));
      awaitMeasurement(client);
      check(client.counters().pendingMeasurements() == 1, "fresh synchronized packet can recover");
      // No poll call after this delay: production no-arg drain itself must read the supplied clock.
      LockSupport.parkNanos(120_000_000L);
      check(client.drainMeasurements().isEmpty(), "production no-arg drain expires source during a quiet cycle");
      check(!client.statuses(System.nanoTime()).get(SOURCE).actionable(), "quiet expiry clears current source only");
      check(engine.rawHistory().size() == retainedRaw, "source invalidation/expiry cannot delete persistent world history");
      check(surface.clientStillValid(), "owned subscriber lifecycle leaves shared instance valid");
    }
    check(surface.clientStillValid(), "closing VisionClient never closes shared NetworkTableInstance");
    System.out.println("CVJ_ADMISSION_BRIDGE\tPASS\t" + surface.version() + "\t" + assertions
        + " assertions\tSYNTHETIC timing only\tgolden=" + sha);
  }

  private record BoundWorld(VisionTrackBridge bridge, WorldEngine engine) {}

  private static BoundWorld boundWorld(VisionClient client, VisionClient.Measurement measurement) {
    Packet packet = measurement.packet();
    var binding = new CustomVisionAdapter.Binding(SOURCE, packet.profile(), packet.bootId(), EPOCH,
        0, measurement.transport().connectionEpoch(), measurement.capture().metadataVersion(),
        (String) packet.fields().get("calibration_revision"), (String) packet.fields().get("mount_revision"),
        Optional.empty(), "synthetic-single-exposure", "synthetic-target-plane-v1");
    var policy = new CustomVisionAdapter.Policy(0, .000002, .5, 10, .04, .25, .000001,
        5_000_000, 250_000, 250_000, 8);
    var adapter = new CustomVisionAdapter(binding, policy);
    long nowUs = System.nanoTime() / 1000;
    var history = new PoseHistory(8, 1_000_000);
    var engine = new WorldEngine(new WorldEngine.Config(8, 32, 8, 250_000, 500_000, 10_000, 10_000,
        1, .5, 1, .01, 5, 1, .02, 10, .02, .1, .2, .1, false), history,
        new Geometry.FieldIdentity("OFFSEASON_2026", "synthetic-no-robot", "map-v1"));
    engine.addEgo(ego(measurement.capture().robotCaptureNs() / 1000, 1, 2, Math.PI / 2));
    engine.addEgo(ego(nowUs, 1, 2, Math.PI / 2));
    return new BoundWorld(new VisionTrackBridge(adapter, engine, client), engine);
  }

  private static void assertBlockedFirstDelivery(BoundWorld world, VisionClient.Measurement held, String description) {
    var decision = world.bridge().acceptRobotNanoseconds(held, System.nanoTime());
    check(decision.normalization().reason() == CustomVisionAdapter.Reason.NO_NEW_MEASUREMENT,
        description + " is rejected by originating live client");
    check(decision.tracking().isEmpty() && decision.insertedObservations() == 0
        && world.engine().rawHistory().isEmpty(), description + " never becomes a first tracker insertion");
    check(decision.normalization().provenance().lifecycleAdmission() == held.admission()
        && decision.normalization().provenance().clockMapping() == held.clock(),
        "blocked first delivery retains historical admission and clock, without fabricated acceptance");
  }

  private static WorldEngine normalizeAndTrack(VisionClient client, VisionClient.Measurement measurement) {
    BoundWorld world = boundWorld(client, measurement);
    WorldEngine engine = world.engine();
    // This is the actual Measurement envelope. No successful SourceSession.Result is assembled.
    long nowNs = System.nanoTime();
    var decision = world.bridge().acceptRobotNanoseconds(measurement, nowNs);
    check(decision.measurement() == measurement, "bridge retains actual consumed envelope");
    var normalized = decision.normalization();
    check(normalized.status() == CustomVisionAdapter.Status.ACCEPTED, "actual admitted envelope normalizes");
    check(normalized.provenance().rawPacket() == measurement.packet()
        && normalized.provenance().lifecycleAdmission() == measurement.admission()
        && normalized.provenance().clockMapping() == measurement.clock(), "World-State preserves exact provenance objects");
    World.ObservationFrame frame = normalized.frame().orElseThrow();
    WorldEngine.FrameDecision tracked = decision.tracking().orElseThrow();
    check(tracked.status() == WorldEngine.FrameStatus.ACCEPTED && tracked.acceptedMeasurements() == 2,
        "canonical two target observations enter engine exactly once");
    check(decision.insertedObservations() == 2, "bridge reports actual tracking decision separately from normalization");
    var tracks = engine.publish(ego(nowNs / 1000, 1, 2, Math.PI / 2)).tracks();
    check(tracks.size() == 2, "persistent tracking uses canonical targets, not detection reference count");
    check(tracks.stream().anyMatch(t -> Math.abs(t.positionM().x() - 1) < 1e-9
        && Math.abs(t.positionM().y() - 3) < 1e-9), "capture-time NWU projection uses historical canonical pose");
    check(tracks.stream().allMatch(t -> t.lastMeasurementUs() == frame.stamp().captureUs()),
        "world track measurement time remains mapped capture, not arrival");
    return engine;
  }

  private static World.EgoState ego(long timeUs, double x, double y, double heading) {
    return new World.EgoState(timeUs, new Geometry.Pose2(new Geometry.Vec2(x, y), heading),
        Geometry.Velocity2.zero(), new Geometry.Uncertainty(.01, 0, .01), true);
  }

  private static Calibration calibrate(Surface surface) {
    surface.localNtRaw(); // Native library startup stays outside the clock bracket.
    Calibration best = null;
    for (int i = 0; i < 32; i++) {
      long before = System.nanoTime();
      long ntNs = surface.version().metadataToNanoseconds(surface.localNtRaw());
      long after = System.nanoTime();
      long width = after - before;
      long offset = Math.subtractExact(before + width / 2, ntNs);
      long bound = width / 2 + 2_000; // Native call bracket plus 2026's 1 us quantization.
      if (best == null || bound < best.uncertaintyNs()) best = new Calibration(offset, bound);
    }
    check(best != null && best.uncertaintyNs() < 1_000_000, "separately bracketed local NT to robot epoch");
    // A bounded 2 ms transport/NT sync budget covers localhost offset quantization, not camera latency.
    return new Calibration(best.localToRobotOffsetNs(), best.uncertaintyNs() + 2_000_000L);
  }

  private static long captureUs(Surface surface) {
    return surface.version().metadataToNanoseconds(surface.localNtRaw()) / 1000 - 10_000;
  }
  private static String synthetic(String golden, long frame, long seq, long captureUs, boolean verified) {
    String text = golden.replace("\"frame_id\":42", "\"frame_id\":" + frame)
        .replace("\"packet_seq\":0", "\"packet_seq\":" + seq)
        .replace("\"capture_server_us\":1234569890123", "\"capture_server_us\":" + captureUs);
    if (verified) text = text.replace("\"capture_correction_verified\":false", "\"capture_correction_verified\":true")
        .replace("\"capture_correction_uncertainty_ms\":null", "\"capture_correction_uncertainty_ms\":0.001");
    return text;
  }
  private static long publishAndPoll(Surface surface, VisionClient client, String payload) {
    long before = client.counters().decoded();
    long publishedRaw = surface.publish(payload);
    pollUntil(client, () -> client.counters().decoded() > before, "fresh native publication receipt");
    return publishedRaw;
  }
  private static void awaitMeasurement(VisionClient client) {
    // Receipt and capture-order release are distinct. A zero reorder window would correctly
    // close quiet-loop capture intervals before this deliberately 10 ms old packet arrives.
    pollUntil(client, () -> client.counters().pendingMeasurements() == 1,
        "admitted native capture reaches bounded 50 ms capture-order release");
  }
  private static void pollUntil(VisionClient client, BooleanSupplier ready, String description) {
    long deadline = System.nanoTime() + 3_000_000_000L;
    do { client.poll(); if (ready.getAsBoolean()) return; LockSupport.parkNanos(1_000_000L); }
    while (System.nanoTime() < deadline);
    throw new AssertionError(description + "; counters=" + client.counters() + "; rejections=" + client.drainRejections());
  }
  private static void waitUntil(BooleanSupplier ready, String description) {
    long deadline = System.nanoTime() + 3_000_000_000L;
    while (!ready.getAsBoolean() && System.nanoTime() < deadline) LockSupport.parkNanos(1_000_000L);
    check(ready.getAsBoolean(), description);
  }
  private static void check(boolean condition, String description) {
    assertions++;
    if (!condition) throw new AssertionError(description);
  }
}
