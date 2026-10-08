package org.customvision.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.customvision.protocol.ClockMapper;
import org.customvision.protocol.Packet;
import org.customvision.protocol.VisionClient;

/**
 * One consume-once robot-owned fusion-policy candidate. This is the producer's fixed-origin
 * field-to-robot pose, never a camera pose or a field projection performed by this library.
 */
public record PoseEstimate(CameraConfig camera, Packet.Pose fieldToRobot, Method method,
    List<Long> usedTagIds, Map<String, Object> rawQuality, StandardDeviations standardDeviations,
    VisionClient.Measurement observation) {
  /** Names describe producer algorithms; no Limelight estimator names or heading solver aliases. */
  public enum Method { SINGLE_TAG_PNP, MULTITAG_PNP }

  /**
   * Caller-calibrated estimator measurement noise, in meters/meters/radians. IDs describe the
   * empirical calibration and active tuning. Pixel residuals do not become these values.
   */
  public record StandardDeviations(double xMeters, double yMeters, double headingRadians,
      String calibrationId, String tuningId) {
    public StandardDeviations {
      for (double value : new double[]{xMeters, yMeters, headingRadians})
        if (!Double.isFinite(value) || value <= 0 || !Double.isFinite(value * value) || value * value <= 0)
          throw new IllegalArgumentException("standard deviations in m,m,rad require representable positive finite squared variance");
      validId(calibrationId, "calibrationId"); validId(tuningId, "tuningId");
    }
    private static void validId(String id, String name) {
      Objects.requireNonNull(id, name);
      if (id.isBlank() || id.length() > 512 || id.chars().anyMatch(c -> c < 0x20))
        throw new IllegalArgumentException(name + " must describe calibration/tuning provenance");
    }
  }

  public PoseEstimate {
    Objects.requireNonNull(camera); Objects.requireNonNull(fieldToRobot); Objects.requireNonNull(method);
    Objects.requireNonNull(standardDeviations); Objects.requireNonNull(observation);
    if (!camera.source().equals(observation.packet().source())
        || !"wpilib_nwu".equals(fieldToRobot.frame()))
      throw new IllegalArgumentException("matching source and named field NWU pose required");
    Packet.Localization localization = observation.packet().localization().orElseThrow();
    String expectedMethod = method == Method.SINGLE_TAG_PNP ? "single_tag_pnp" : "multitag_pnp";
    if (!localization.valid() || !localization.fieldRobot().equals(java.util.Optional.of(fieldToRobot))
        || !localization.usedTagIds().equals(usedTagIds) || !localization.quality().equals(rawQuality)
        || !expectedMethod.equals(localization.quality().get("method")))
      throw new IllegalArgumentException("pose, method, tags and quality must match the admitted producer localization");
    usedTagIds = localization.usedTagIds();
    // Reuse decoder-produced recursively immutable quality; never retain a caller's mutable map.
    rawQuality = localization.quality();
  }
  public ClockMapper.MappedCapture capture() { return observation.capture(); }
  public double captureSeconds() { return capture().estimatorSeconds(); }
}
