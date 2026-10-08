package org.customvision.protocol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/** CPU-only synthetic contract checks; no HAL, NT native library or robot estimator is loaded. */
public final class TimeTests {
  private static final SourceKey SOURCE_A = new SourceKey("/vision/camera-a", "tags", "apriltag");
  private static final SourceKey SOURCE_B = new SourceKey("/vision/camera-b", "tags", "apriltag");
  private static final long NOW = 1_010_000_000L;
  private TimeTests() {}

  public static void run() {
    unitsAndMapping();
    producerGoldenMapping();
    rejectionChecks();
    historyChecks();
    reorderChecks();
    System.out.println("TimeTests: golden and synthetic mapping, clock rejection, history and cross-camera ordering passed");
  }

  /** Exact publisher bytes supplied by the producer task, not a fabricated wire format. */
  private static void producerGoldenMapping() {
    try {
      Path fixtureBase = Path.of("fixtures");
      Object manifest = Json.parse(Files.readString(fixtureBase.resolve("fixture-manifest.json")),
          ProtocolDecoder.Limits.defaults());
      check(manifest instanceof Map<?, ?>, "fixture manifest object");
      Object entries = ((Map<?, ?>) manifest).get("fixtures");
      check(entries instanceof List<?>, "fixture manifest entries");
      Map<?, ?> measuredManifest = ((List<?>) entries).stream().filter(Map.class::isInstance)
          .map(Map.class::cast).filter(item -> "measured_zero_correction".equals(item.get("name")))
          .findFirst().orElseThrow();
      byte[] bytes = Files.readAllBytes(fixtureBase.resolve((String) measuredManifest.get("path")));
      String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
      check(hash.equals(measuredManifest.get("sha256"))
          && hash.equals("01f47868a8036073d0b24b250afd8348375d8fd6139a6c05a4d023f08affa4f8"),
          "producer measured fixture manifest/hash pin");
      check(bytes.length == ((Long) measuredManifest.get("byte_count")).longValue(), "producer fixture byte count");
      ProtocolDecoder decoder = new ProtocolDecoder();
      SourceKey source = new SourceKey("/CustomVision/fixture/front_tags", "front_tags", "apriltag");
      Packet packet = decoder.decode(source, new String(bytes, StandardCharsets.UTF_8));
      long localCaptureUs = 1_234_567_890_123L;
      long publicationLocalUs = localCaptureUs + 30_000L;
      long publicationServerUs = publicationLocalUs + 2_000_000L;
      long now = Math.multiplyExact(publicationLocalUs, 1_000L) + 5_000_000L;
      long expectedCaptureNs = Math.multiplyExact(localCaptureUs, 1_000L);
      ClockMapper mapper = new ClockMapper("fixture-robot-monotonic");
      for (TimeVersion version : TimeVersion.values()) {
        long factor = version == TimeVersion.WPILIB_2026_MICROSECONDS ? 1L : 1_000L;
        TransportSample sample = new TransportSample(new String(bytes, StandardCharsets.UTF_8),
            Math.multiplyExact(publicationLocalUs, factor), Math.multiplyExact(publicationServerUs, factor),
            now - 3_000_000L, now - 2_000_000L, 4);
        SyncSnapshot snapshot = sync(version, 2_000_000L * factor, "fixture-robot-monotonic",
            true, 0, 100_000L, now, 4);
        ClockMapper.MappedCapture mapped = accepted(mapper.map(packet, sample, snapshot, now));
        check(mapped.rawCaptureServerUs() == 1_234_569_890_123L, "golden raw _us stays micros");
        check(mapped.robotCaptureNs() == expectedCaptureNs, "golden unit conversion matches both profiles");
        check(mapped.captureCorrectionUncertaintyNs() == 250_000L, "golden correction uncertainty preserved");
        Packet unverified = decoder.decode(source, Files.readString(fixtureBase.resolve("fixtures/unverified_nonzero_correction.json")));
        reason(mapper.map(unverified, sample, snapshot, now), ClockMapper.Reason.CAPTURE_CORRECTION_UNVERIFIED);
        Packet unavailable = decoder.decode(source, Files.readString(fixtureBase.resolve("fixtures/unsynchronized_time.json")));
        reason(mapper.map(unavailable, sample, snapshot, now), ClockMapper.Reason.CAPTURE_UNAVAILABLE);
      }
    } catch (IOException | NoSuchAlgorithmException | DecodeException exception) {
      throw new AssertionError("producer golden time test failed", exception);
    }
  }

  private static void unitsAndMapping() {
    ClockMapper mapper = new ClockMapper("robot-fpga-test");
    Packet packet = packet(1_010_000L, true, verifiedTiming());
    ClockMapper.MappedCapture a = accepted(mapper.map(packet, sample2026(), sync2026(), NOW));
    check(a.robotCaptureNs() == 1_000_000_000L, "2026 us metadata to robot ns");
    check(a.estimatorSeconds() == 1.0, "ns to estimator seconds");
    check(a.rawCaptureServerUs() == 1_010_000L && a.rawNtTimestamp() == 1_005_000L,
        "raw timestamp provenance retained");
    check(a.firstObservedRobotNs() == 1_008_000_000L && a.dequeueRobotNs() == 1_009_000_000L,
        "observation and dequeue kept separate");
    check(a.producerCorrectionAlreadyApplied(), "producer correction never applied twice");
    check(a.synchronizationUncertaintyNs() == 100_000L && a.captureCorrectionUncertaintyNs() == 1_000_000L
        && a.totalUncertaintyNs() == 1_100_000L, "separate synchronization and correction uncertainty");
    TransportSample sample2027 = new TransportSample("synthetic", 1_005_000_000L, 1_015_000_000L,
        1_008_000_000L, 1_009_000_000L, 4);
    SyncSnapshot sync2027 = sync(TimeVersion.WPILIB_2027_ALPHA7_NANOSECONDS, 10_000_000L,
        "robot-fpga-test", true, 0, 100_000L, NOW, 4);
    ClockMapper.MappedCapture b = accepted(mapper.map(packet, sample2027, sync2027, NOW));
    check(b.robotCaptureNs() == a.robotCaptureNs(), "alpha7 ns metadata preserves JSON us");
    check(b.rawNtTimestamp() == 1_005_000_000L && b.rawServerMinusLocal() == 10_000_000L,
        "alpha7 raw metadata retained");
    reason(mapper.map(packet, sample2026(),
        sync(TimeVersion.WPILIB_2027_ALPHA7_NANOSECONDS, 10_000L,
            "robot-fpga-test", true, 0, 100_000L, NOW, 4), NOW), ClockMapper.Reason.FUTURE_CAPTURE);

    ClockMapper.MappedCapture changed = accepted(mapper.map(packet,
        new TransportSample("synthetic", 1_005_000L, 1_016_000L,
            1_008_000_000L, 1_009_000_000L, 4),
        sync(TimeVersion.WPILIB_2026_MICROSECONDS, 11_000L,
            "robot-fpga-test", true, 0, 100_000L, NOW, 4), NOW));
    check(changed.robotCaptureNs() == 999_000_000L, "offset updates use correct subtraction sign");
    check(a.robotCaptureNs() == 1_000_000_000L, "old mappings remain immutable after offset changes");
    ClockMapper.MappedCapture shiftedEpoch = accepted(mapper.map(packet, sample2026(),
        sync(TimeVersion.WPILIB_2026_MICROSECONDS, 10_000L,
            "robot-fpga-test", true, 2_000_000L, 100_000L, NOW, 4), NOW));
    check(shiftedEpoch.robotCaptureNs() == 1_002_000_000L, "explicit NT-local to robot epoch map");
    check(TimeVersion.jsonMicrosecondsToNanoseconds(123) == 123_000L, "JSON micros always micros");
    Map<String, Object> fractionalUncertainty = Map.of("protocol_profile", "custom-vision-schema2-2026.1", "timing",
        Map.of("clock_domain", "nt_server", "timestamp_unit", "us", "capture_event", "host_frame_read_complete",
            "capture_correction_verified", true, "capture_correction_uncertainty_ms", 0.0000001));
    check(accepted(mapper.map(packet(1_010_000L, true, fractionalUncertainty), sample2026(), sync2026(), NOW))
        .captureCorrectionUncertaintyNs() == 1L, "fractional ns uncertainty rounds up");
  }

  private static void rejectionChecks() {
    ClockMapper mapper = new ClockMapper("robot-fpga-test");
    Packet valid = packet(1_010_000L, true, verifiedTiming());
    reason(mapper.map(packet(0, true, verifiedTiming()), sample2026(), sync2026(), NOW),
        ClockMapper.Reason.CAPTURE_UNAVAILABLE);
    reason(mapper.map(packet(1_010_000L, false, verifiedTiming()), sample2026(), sync2026(), NOW),
        ClockMapper.Reason.UNSYNCHRONIZED);
    SyncSnapshot missing = new SyncSnapshot(TimeVersion.WPILIB_2026_MICROSECONDS, OptionalLong.empty(),
        "robot-fpga-test", true, 0, 100_000L, NOW, 4, "synthetic known mapping");
    reason(mapper.map(valid, sample2026(), missing, NOW), ClockMapper.Reason.UNSYNCHRONIZED);
    reason(mapper.map(valid, sample2026(),
        sync(TimeVersion.WPILIB_2026_MICROSECONDS, 10_000L, "desktop-unix", true, 0, 0, NOW, 4), NOW),
        ClockMapper.Reason.EPOCH_MISMATCH);
    reason(mapper.map(valid, sample2026(),
        sync(TimeVersion.WPILIB_2026_MICROSECONDS, 10_000L, "robot-fpga-test", false, 0, 0, NOW, 4), NOW),
        ClockMapper.Reason.EPOCH_MISMATCH);
    reason(mapper.map(valid, sample2026(),
        sync(TimeVersion.WPILIB_2026_MICROSECONDS, 10_000L, "robot-fpga-test", true, 0, 0, NOW, 3), NOW),
        ClockMapper.Reason.CONNECTION_EPOCH_MISMATCH);
    reason(mapper.map(valid, sample2026(),
        sync(TimeVersion.WPILIB_2026_MICROSECONDS, 10_000L, "robot-fpga-test", true, 0, 0, 0, 4), NOW),
        ClockMapper.Reason.SYNC_STALE);
    reason(mapper.map(valid, sample2026(),
        sync(TimeVersion.WPILIB_2026_MICROSECONDS, 10_000L, "robot-fpga-test", true, 0, 0, NOW + 1, 4), NOW),
        ClockMapper.Reason.SYNC_FUTURE);
    reason(mapper.map(packet(1_010_000L, true, Map.of()), sample2026(), sync2026(), NOW),
        ClockMapper.Reason.CAPTURE_CORRECTION_UNVERIFIED);
    ClockMapper legacy = new ClockMapper(new ClockMapper.Config("robot-fpga-test", 250_000_000L,
        2_000_000L, 1_000_000_000L, 5_000_000L,
        Optional.of(new ClockMapper.CorrectionVerification(1_000_000L, "synthetic measured legacy correction"))));
    check(accepted(legacy.map(packet(1_010_000L, true, Map.of()), sample2026(), sync2026(), NOW))
        .captureCorrectionEvidence().contains("legacy"), "legacy needs explicit correction evidence");
    reason(mapper.map(packet(Long.MAX_VALUE, true, verifiedTiming()), sample2026(), sync2026(), NOW),
        ClockMapper.Reason.OVERFLOW);
    reason(mapper.map(valid, sample2026(),
        sync(TimeVersion.WPILIB_2026_MICROSECONDS, Long.MIN_VALUE,
            "robot-fpga-test", true, 0, 0, NOW, 4), NOW), ClockMapper.Reason.OVERFLOW);
    reason(mapper.map(packet(1_025_000L, true, verifiedTiming()), sample2026(), sync2026(), NOW),
        ClockMapper.Reason.FUTURE_CAPTURE);
    reason(mapper.map(packet(1_020_001L, true, verifiedTiming()), sample2026(), sync2026(), NOW),
        ClockMapper.Reason.FUTURE_CAPTURE);
    reason(mapper.map(packet(700_000L, true, verifiedTiming()), sample2026(), sync2026(), NOW),
        ClockMapper.Reason.STALE_CAPTURE);
    reason(mapper.map(valid, new TransportSample("synthetic", 0, 1,
        1_008_000_000L, 1_009_000_000L, 4), sync2026(), NOW), ClockMapper.Reason.INVALID_METADATA);
    reason(mapper.map(valid, new TransportSample("synthetic", 1_005_000L, 1_050_000L,
        1_008_000_000L, 1_009_000_000L, 4), sync2026(), NOW), ClockMapper.Reason.METADATA_EPOCH_MISMATCH);
    reason(mapper.map(valid, sample2026(),
        sync(TimeVersion.WPILIB_2026_MICROSECONDS, 10_000L,
            "robot-fpga-test", true, 0, 5_000_000L, NOW, 4), NOW), ClockMapper.Reason.UNCERTAINTY_EXCEEDED);
    Map<String, Object> wrongDomain = Map.of("timing", Map.of("clock_domain", "unix", "timestamp_unit", "us",
        "capture_event", "host_frame_read_complete", "capture_correction_verified", true,
        "capture_correction_uncertainty_ms", 1.0));
    reason(mapper.map(packet(1_010_000L, true, wrongDomain), sample2026(), sync2026(), NOW),
        ClockMapper.Reason.INVALID_PROVENANCE);
    Map<String, Object> unverified = Map.of("timing", Map.of("clock_domain", "nt_server", "timestamp_unit", "us",
        "capture_event", "host_frame_read_complete", "capture_correction_verified", false,
        "capture_correction_uncertainty_ms", 1.0));
    reason(mapper.map(packet(1_010_000L, true, unverified), sample2026(), sync2026(), NOW),
        ClockMapper.Reason.CAPTURE_CORRECTION_UNVERIFIED);
    Map<String, Object> negativeUncertainty = Map.of("timing", Map.of("clock_domain", "nt_server", "timestamp_unit", "us",
        "capture_event", "host_frame_read_complete", "capture_correction_verified", true,
        "capture_correction_uncertainty_ms", -0.0000001));
    reason(mapper.map(packet(1_010_000L, true, negativeUncertainty), sample2026(), sync2026(), NOW),
        ClockMapper.Reason.INVALID_PROVENANCE);
  }

  private static void historyChecks() {
    FakeHistory history = new FakeHistory();
    for (long endpoint : List.of(100L, 150L, 200L, 250L)) {
      check(history.checkedSample(endpoint, "robot-fpga-test", "reset-1").accepted(), "inclusive history endpoint");
    }
    int calls = history.calls;
    check(history.checkedSample(99, "robot-fpga-test", "reset-1").rejection().orElseThrow()
        == HistoricalPoseProvider.Reason.PRE_RESET, "pre-reset rejects before clamping");
    check(history.checkedSample(251, "robot-fpga-test", "reset-1").rejection().orElseThrow()
        == HistoricalPoseProvider.Reason.OUT_OF_COVERAGE, "future history boundary rejects before clamping");
    check(history.checkedSample(175, "robot-fpga-test", "reset-1").rejection().orElseThrow()
        == HistoricalPoseProvider.Reason.HOLE, "history holes reject before clamping");
    check(history.checkedSample(125, "robot-fpga-test", "retired-reset").rejection().orElseThrow()
        == HistoricalPoseProvider.Reason.RESET_MISMATCH, "wrong reset identity");
    check(history.checkedSample(125, "desktop", "reset-1").rejection().orElseThrow()
        == HistoricalPoseProvider.Reason.EPOCH_MISMATCH, "wrong history epoch");
    check(calls == history.calls, "bad history requests never invoke potentially clamping sampler");
    history.resetOnSample = true;
    check(history.checkedSample(125, "robot-fpga-test", "reset-1").rejection().orElseThrow()
        == HistoricalPoseProvider.Reason.RESET_DURING_SAMPLE, "reset racing sample rejects");
    history.resetOnSample = false; history.noSample = true;
    check(history.checkedSample(125, "robot-fpga-test", "reset-2").rejection().orElseThrow()
        == HistoricalPoseProvider.Reason.NO_SAMPLE, "coverage with no actual sample rejects");
  }

  private static void reorderChecks() {
    CaptureReorderBuffer<String> buffer = new CaptureReorderBuffer<>(4, 100, 5);
    check(buffer.offer(entry("A/boot/10", SOURCE_A, 1_000, "a10"), 1_020)
        == CaptureReorderBuffer.Offer.ACCEPTED, "first camera packet accepted");
    check(buffer.drain(1_050, 4).isEmpty(), "measurement waits across robot loops");
    check(buffer.offer(entry("B/boot/8", SOURCE_B, 980, "b8"), 1_060)
        == CaptureReorderBuffer.Offer.ACCEPTED, "other camera arrives later with earlier capture");
    List<CaptureReorderBuffer.Entry<String>> both = buffer.drain(1_100, 4);
    check(both.size() == 2 && both.get(0).value().equals("b8") && both.get(1).value().equals("a10"),
        "global capture ordering across cameras and cycles");
    check(buffer.offer(entry("B/boot/7", SOURCE_B, 990, "b7"), 1_110)
        == CaptureReorderBuffer.Offer.LATE, "late camera packet cannot regress insertion time");
    check(buffer.offer(entry("A/boot/10", SOURCE_A, 1_000, "a10"), 1_110)
        == CaptureReorderBuffer.Offer.LATE, "already emitted identity never reemits");
    check(buffer.drain(1_110, 4).isEmpty(), "repeated drain never repeats measurements");
    buffer.offer(entry("A/boot/11", SOURCE_A, 1_100, "a11"), 1_120);
    check(buffer.offer(entry("A/boot/11", SOURCE_A, 1_100, "a11"), 1_120)
        == CaptureReorderBuffer.Offer.DUPLICATE, "pending duplicate delivered once");
    buffer.offer(entry("B/boot/9", SOURCE_B, 1_110, "b9"), 1_120);
    buffer.discard(value -> value.equals("a11"));
    check(buffer.drain(1_220, 1).get(0).value().equals("b9"), "invalidation purges only matching frame");
    buffer.drain(1_500, 4);
    check(buffer.offer(entry("A/boot/12", SOURCE_A, 1_300, "quiet-old"), 1_500)
        == CaptureReorderBuffer.Offer.LATE, "quiet loops still close capture intervals");
    check(buffer.offer(entry("A/boot/13", SOURCE_A, 1_510, "future"), 1_500)
        == CaptureReorderBuffer.Offer.FUTURE, "future bound enforced");
    CaptureReorderBuffer<String> bounded = new CaptureReorderBuffer<>(2, 100, 5);
    bounded.offer(entry("A/1", SOURCE_A, 100, "one"), 100);
    bounded.offer(entry("B/2", SOURCE_B, 101, "two"), 101);
    check(bounded.offer(entry("A/3", SOURCE_A, 102, "three"), 102)
        == CaptureReorderBuffer.Offer.OVERLOAD, "capacity overload observable without silent truncation");
    check(bounded.drain(250, 1).size() == 1 && bounded.counters().pending() == 1,
        "per-cycle output bounded");
    bounded.removeSource(SOURCE_B);
    check(bounded.counters().pending() == 0 && bounded.counters().overload() == 1,
        "source reset removes only source's pending measurements and preserves counters");
    boolean regressed = false;
    try { bounded.drain(249, 1); } catch (IllegalArgumentException expected) { regressed = true; }
    check(regressed, "clock regression requires explicit epoch-reset buffer construction");
  }

  private static final class FakeHistory implements HistoricalPoseProvider<String> {
    int calls;
    boolean resetOnSample;
    boolean noSample;
    String reset = "reset-1";
    @Override public Window window() {
      return new Window("robot-fpga-test", reset, 100, List.of(new Coverage(100, 150), new Coverage(200, 250)));
    }
    @Override public Optional<String> sampleAt(long captureNs) {
      calls++;
      if (resetOnSample) reset = "reset-2";
      return noSample ? Optional.empty() : Optional.of("robot-pose-at-" + captureNs);
    }
  }

  private static CaptureReorderBuffer.Entry<String> entry(String id, SourceKey source, long capture, String value) {
    return new CaptureReorderBuffer.Entry<>(id, source, capture, value);
  }
  private static Packet packet(long captureUs, boolean synchronizedClock, Map<String, Object> fields) {
    return new Packet(SOURCE_A, 2, "synthetic-boot", 7, OptionalLong.empty(), "synthetic-revision", true,
        captureUs, synchronizedClock, fields);
  }
  private static Map<String, Object> verifiedTiming() {
    return Map.of("protocol_profile", "custom-vision-schema2-2026.1", "timing",
        Map.of("clock_domain", "nt_server", "timestamp_unit", "us", "capture_event", "host_frame_read_complete",
            "capture_correction_verified", true, "capture_correction_uncertainty_ms", 1.0));
  }
  private static TransportSample sample2026() {
    return new TransportSample("synthetic", 1_005_000L, 1_015_000L, 1_008_000_000L, 1_009_000_000L, 4);
  }
  private static SyncSnapshot sync2026() {
    return sync(TimeVersion.WPILIB_2026_MICROSECONDS, 10_000L, "robot-fpga-test", true, 0, 100_000L, NOW, 4);
  }
  private static SyncSnapshot sync(TimeVersion version, long offset, String epoch, boolean verified,
      long localToRobot, long uncertainty, long observed, long connection) {
    return new SyncSnapshot(version, OptionalLong.of(offset), epoch, verified, localToRobot,
        uncertainty, observed, connection, verified ? "synthetic known mapping" : "");
  }
  private static ClockMapper.MappedCapture accepted(ClockMapper.Result result) {
    check(result.accepted(), "mapping accepted: " + result.rejection());
    return result.capture().orElseThrow();
  }
  private static void reason(ClockMapper.Result result, ClockMapper.Reason expected) {
    check(!result.accepted() && result.rejection().orElseThrow().reason() == expected,
        "expected " + expected + " got " + result.rejection());
  }
  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
