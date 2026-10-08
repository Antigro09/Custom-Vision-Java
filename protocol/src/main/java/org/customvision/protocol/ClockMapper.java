package org.customvision.protocol;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** Pure, versioned capture-clock mapping. No camera-latency compensation is performed here. */
public final class ClockMapper {
  public enum Reason {
    CAPTURE_UNAVAILABLE, UNSYNCHRONIZED, EPOCH_MISMATCH, CONNECTION_EPOCH_MISMATCH,
    SYNC_STALE, SYNC_FUTURE, CAPTURE_CORRECTION_UNVERIFIED, INVALID_PROVENANCE,
    INVALID_METADATA, METADATA_EPOCH_MISMATCH, UNCERTAINTY_EXCEEDED,
    OVERFLOW, FUTURE_CAPTURE, STALE_CAPTURE
  }

  public record Rejection(Reason reason, String detail) {
    public Rejection { Objects.requireNonNull(reason); Objects.requireNonNull(detail); }
  }

  /** Evidence is for an already applied producer correction, never a correction to apply again. */
  public record CorrectionVerification(long uncertaintyNs, String evidence) {
    public CorrectionVerification {
      Objects.requireNonNull(evidence);
      if (uncertaintyNs < 0 || evidence.isBlank() || evidence.length() > 512)
        throw new IllegalArgumentException("invalid correction evidence");
    }
  }

  public record Config(String robotEpoch, long maxCaptureAgeNs, long maxFutureLeadNs,
      long maxSyncAgeNs, long maxUncertaintyNs,
      Optional<CorrectionVerification> legacyCaptureCorrectionEvidence) {
    public Config {
      Objects.requireNonNull(robotEpoch);
      Objects.requireNonNull(legacyCaptureCorrectionEvidence);
      if (robotEpoch.isBlank() || robotEpoch.length() > 128 || maxCaptureAgeNs < 0
          || maxFutureLeadNs < 0 || maxSyncAgeNs < 0 || maxUncertaintyNs < 0)
        throw new IllegalArgumentException("invalid clock bounds");
    }

    public static Config conservative(String robotEpoch) {
      return new Config(robotEpoch, 250_000_000L, 0L,
          1_000_000_000L, 5_000_000L, Optional.empty());
    }
  }

  /** Every raw value and unit survives mapping; immutable mappings are never reinterpreted later. */
  public record MappedCapture(long rawCaptureServerUs, long rawNtTimestamp,
      long rawNtServerTime, TimeVersion metadataVersion, long rawServerMinusLocal,
      long ntTimestampNs, long ntServerTimeNs, long serverMinusLocalNs,
      long localToRobotOffsetNs, long firstObservedRobotNs, long dequeueRobotNs,
      long connectionEpoch, long syncObservedRobotNs, String robotEpoch,
      String epochVerification, boolean producerCorrectionAlreadyApplied,
      String captureCorrectionEvidence, long synchronizationUncertaintyNs,
      long captureCorrectionUncertaintyNs, long totalUncertaintyNs, long robotCaptureNs) {
    public double estimatorSeconds() { return robotCaptureNs / 1_000_000_000.0; }
    public long ageNs(long nowRobotNs) { return Math.subtractExact(nowRobotNs, robotCaptureNs); }
  }

  public record Result(Optional<MappedCapture> capture, Optional<Rejection> rejection) {
    public Result {
      Objects.requireNonNull(capture); Objects.requireNonNull(rejection);
      if (capture.isPresent() == rejection.isPresent())
        throw new IllegalArgumentException("exactly one mapping outcome required");
    }
    public boolean accepted() { return capture.isPresent(); }
  }

  private final Config config;
  public ClockMapper(Config config) { this.config = Objects.requireNonNull(config); }
  public ClockMapper(String robotEpoch) { this(Config.conservative(robotEpoch)); }
  public Config config() { return config; }

  public Result map(Packet packet, TransportSample sample, SyncSnapshot sync, long nowRobotNs) {
    Objects.requireNonNull(packet); Objects.requireNonNull(sample); Objects.requireNonNull(sync);
    if (nowRobotNs < 0) return reject(Reason.INVALID_METADATA, "negative robot monotonic time");
    if (packet.captureServerUs() <= 0)
      return reject(Reason.CAPTURE_UNAVAILABLE, "capture_server_us unavailable");
    if (!packet.timeSyncValid() || sync.serverMinusLocalRaw().isEmpty())
      return reject(Reason.UNSYNCHRONIZED, "producer or consumer NT sync unavailable");
    if (!sync.robotEpochVerified() || !config.robotEpoch().equals(sync.robotEpoch()))
      return reject(Reason.EPOCH_MISMATCH, "configured robot epoch not verified");
    if (sample.connectionEpoch() != sync.connectionEpoch())
      return reject(Reason.CONNECTION_EPOCH_MISMATCH, "sync belongs to another connection epoch");
    if (sync.observedRobotNs() > nowRobotNs)
      return reject(Reason.SYNC_FUTURE, "sync observation after current robot time");
    if (nowRobotNs - sync.observedRobotNs() > config.maxSyncAgeNs())
      return reject(Reason.SYNC_STALE, "sync evidence expired");
    if (sample.firstObservedRobotNs() > nowRobotNs || sample.dequeueRobotNs() > nowRobotNs)
      return reject(Reason.INVALID_METADATA, "transport observation after current robot time");

    Optional<CorrectionVerification> correction;
    Optional<Map<String, Object>> timing = packet.family("timing");
    if (timing.isPresent()) {
      Map<String, Object> fields = timing.orElseThrow();
      if (!"nt_server".equals(fields.get("clock_domain"))
          || !"us".equals(fields.get("timestamp_unit"))
          || !"host_frame_read_complete".equals(fields.get("capture_event")))
        return reject(Reason.INVALID_PROVENANCE, "capture provenance must name NT server microseconds");
      if (!Boolean.TRUE.equals(fields.get("capture_correction_verified")))
        return reject(Reason.CAPTURE_CORRECTION_UNVERIFIED, "producer capture correction unverified");
      try {
        Object uncertainty = fields.get("capture_correction_uncertainty_ms");
        if (!(uncertainty instanceof Long || uncertainty instanceof Double))
          return reject(Reason.CAPTURE_CORRECTION_UNVERIFIED, "correction uncertainty unavailable");
        BigDecimal ms = uncertainty instanceof Long value
            ? BigDecimal.valueOf(value) : BigDecimal.valueOf((Double) uncertainty);
        if (ms.signum() < 0)
          return reject(Reason.INVALID_PROVENANCE, "negative correction uncertainty");
        long ns = ms.multiply(BigDecimal.valueOf(1_000_000L))
            .setScale(0, RoundingMode.CEILING).longValueExact();
        correction = Optional.of(new CorrectionVerification(ns, "producer timing block"));
      } catch (ArithmeticException | IllegalArgumentException exception) {
        return reject(Reason.INVALID_PROVENANCE, "invalid correction uncertainty");
      }
    } else if ("legacy-schema2".equals(packet.profile())) {
      correction = config.legacyCaptureCorrectionEvidence();
    } else {
      correction = Optional.empty();
    }
    if (correction.isEmpty())
      return reject(Reason.CAPTURE_CORRECTION_UNVERIFIED, "no measured capture correction evidence");

    try {
      long captureServerNs = TimeVersion.jsonMicrosecondsToNanoseconds(packet.captureServerUs());
      long timestampNs = sync.version().metadataToNanoseconds(sample.ntTimestamp());
      long serverNs = sync.version().metadataToNanoseconds(sample.ntServerTime());
      long offsetRaw = sync.serverMinusLocalRaw().orElseThrow();
      long offsetNs = sync.version().metadataToNanoseconds(offsetRaw);
      long totalUncertainty = Math.addExact(sync.uncertaintyNs(), correction.orElseThrow().uncertaintyNs());
      if (totalUncertainty > config.maxUncertaintyNs())
        return reject(Reason.UNCERTAINTY_EXCEEDED, "capture and clock uncertainty exceed configured bound");
      // 0/1 server timestamps represent local values, not remote producer publication evidence.
      if (sample.ntTimestamp() <= 1 || sample.ntServerTime() <= 1)
        return reject(Reason.INVALID_METADATA, "remote publication timestamps unavailable");
      long predictedServer = Math.addExact(timestampNs, offsetNs);
      long metadataError = absoluteDifference(predictedServer, serverNs);
      if (metadataError > sync.uncertaintyNs())
        return reject(Reason.METADATA_EPOCH_MISMATCH, "NT timestamp and serverTime disagree with pinned offset");
      long localCaptureNs = Math.subtractExact(captureServerNs, offsetNs);
      long robotCaptureNs = Math.addExact(localCaptureNs, sync.localToRobotOffsetNs());
      long publicationRobotNs = Math.addExact(timestampNs, sync.localToRobotOffsetNs());
      if (robotCaptureNs < 0 || publicationRobotNs < 0)
        return reject(Reason.EPOCH_MISMATCH, "mapped capture/publication before robot epoch");
      if (publicationRobotNs > nowRobotNs && publicationRobotNs - nowRobotNs > config.maxFutureLeadNs())
        return reject(Reason.METADATA_EPOCH_MISMATCH, "publication timestamp in future robot epoch");
      if (robotCaptureNs > nowRobotNs && robotCaptureNs - nowRobotNs > config.maxFutureLeadNs())
        return reject(Reason.FUTURE_CAPTURE, "capture after current robot time");
      if (robotCaptureNs <= nowRobotNs && nowRobotNs - robotCaptureNs > config.maxCaptureAgeNs())
        return reject(Reason.STALE_CAPTURE, "capture age exceeds configured bound");
      if (robotCaptureNs > publicationRobotNs
          && robotCaptureNs - publicationRobotNs > config.maxFutureLeadNs())
        return reject(Reason.FUTURE_CAPTURE, "capture after its publication");
      return new Result(Optional.of(new MappedCapture(packet.captureServerUs(), sample.ntTimestamp(),
          sample.ntServerTime(), sync.version(), offsetRaw, timestampNs, serverNs, offsetNs,
          sync.localToRobotOffsetNs(), sample.firstObservedRobotNs(), sample.dequeueRobotNs(),
          sample.connectionEpoch(), sync.observedRobotNs(), sync.robotEpoch(), sync.verification(),
          true, correction.orElseThrow().evidence(), sync.uncertaintyNs(),
          correction.orElseThrow().uncertaintyNs(), totalUncertainty, robotCaptureNs)), Optional.empty());
    } catch (ArithmeticException exception) {
      return reject(Reason.OVERFLOW, "checked time conversion/arithmetic overflow");
    }
  }

  private static long absoluteDifference(long left, long right) {
    return left >= right ? Math.subtractExact(left, right) : Math.subtractExact(right, left);
  }

  private static Result reject(Reason reason, String detail) {
    return new Result(Optional.empty(), Optional.of(new Rejection(reason, detail)));
  }
}
