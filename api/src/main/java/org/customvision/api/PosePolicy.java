package org.customvision.api;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import org.customvision.protocol.Packet;
import org.customvision.protocol.VisionClient;

/** Explicit quality gates plus caller-calibrated noise; no implicit trust or covariance claims. */
public record PosePolicy(Thresholds thresholds, StandardDeviationProvider standardDeviationProvider) {
  @FunctionalInterface
  public interface StandardDeviationProvider {
    /** Empty means calibration does not cover this observation; never fall back to guessed noise. */
    Optional<PoseEstimate.StandardDeviations> calibratedFor(Context context);
  }
  public record Context(CameraConfig camera, VisionClient.Measurement observation,
      Packet.Localization localization, PoseEstimate.Method method) {}
  public record Thresholds(Set<PoseEstimate.Method> allowedMethods, int minimumTags,
      double maximumReprojectionErrorPx, OptionalDouble maximumAmbiguity,
      OptionalDouble maximumDistanceMeters, OptionalDouble minimumDecisionMargin) {
    public Thresholds {
      allowedMethods = Set.copyOf(allowedMethods);
      Objects.requireNonNull(maximumAmbiguity); Objects.requireNonNull(maximumDistanceMeters);
      Objects.requireNonNull(minimumDecisionMargin);
      if (allowedMethods.isEmpty() || minimumTags < 1 || minimumTags > 512
          || !Double.isFinite(maximumReprojectionErrorPx) || maximumReprojectionErrorPx < 0)
        throw new IllegalArgumentException("explicit methods, tag count and finite pixel bound required");
      for (OptionalDouble bound : List.of(maximumAmbiguity, maximumDistanceMeters, minimumDecisionMargin))
        if (bound.isPresent() && (!Double.isFinite(bound.getAsDouble()) || bound.getAsDouble() < 0))
          throw new IllegalArgumentException("configured quality bounds must be finite and nonnegative");
      if (maximumDistanceMeters.isPresent() && maximumDistanceMeters.getAsDouble() == 0)
        throw new IllegalArgumentException("distance limit must be positive");
    }
  }
  public enum Rejection {
    NO_VALID_LOCALIZATION, FIELD_ROBOT_POSE_MISSING, UNSUPPORTED_METHOD, METHOD_DISABLED,
    INVALID_METHOD_TAG_COUNT, TAG_COUNT, REPROJECTION_MISSING_OR_INVALID, REPROJECTION_EXCEEDED,
    AMBIGUITY_MISSING_OR_INVALID, AMBIGUITY_EXCEEDED, DISTANCE_MISSING_OR_INVALID, DISTANCE_EXCEEDED,
    DECISION_MARGIN_MISSING_OR_INVALID, DECISION_MARGIN_TOO_LOW, CALIBRATION_UNAVAILABLE,
    CALIBRATION_CALLBACK_FAILED
  }
  public record Decision(Optional<PoseEstimate> estimate, Optional<Rejection> rejection) {
    public Decision {
      Objects.requireNonNull(estimate); Objects.requireNonNull(rejection);
      if (estimate.isPresent() == rejection.isPresent()) throw new IllegalArgumentException("one pose-policy outcome required");
    }
  }
  public PosePolicy { Objects.requireNonNull(thresholds); Objects.requireNonNull(standardDeviationProvider); }

  public Decision evaluate(CameraConfig camera, VisionClient.Measurement observation) {
    Objects.requireNonNull(camera); Objects.requireNonNull(observation);
    if (!camera.source().equals(observation.packet().source())) throw new IllegalArgumentException("camera source mismatch");
    Packet packet = observation.packet();
    Optional<Packet.Localization> family = packet.localization();
    if (family.isEmpty() || !family.orElseThrow().valid()) return rejected(Rejection.NO_VALID_LOCALIZATION);
    Packet.Localization localization = family.orElseThrow();
    if (localization.fieldRobot().isEmpty()) return rejected(Rejection.FIELD_ROBOT_POSE_MISSING);
    Object rawMethod = localization.quality().get("method");
    PoseEstimate.Method method;
    if ("single_tag_pnp".equals(rawMethod)) method = PoseEstimate.Method.SINGLE_TAG_PNP;
    else if ("multitag_pnp".equals(rawMethod)) method = PoseEstimate.Method.MULTITAG_PNP;
    else return rejected(Rejection.UNSUPPORTED_METHOD);
    if (!thresholds.allowedMethods().contains(method)) return rejected(Rejection.METHOD_DISABLED);
    Set<Long> tags = new HashSet<>(localization.usedTagIds());
    if (tags.size() != localization.usedTagIds().size()
        || method == PoseEstimate.Method.SINGLE_TAG_PNP && tags.size() != 1
        || method == PoseEstimate.Method.MULTITAG_PNP && tags.size() < 2)
      return rejected(Rejection.INVALID_METHOD_TAG_COUNT);
    if (tags.size() < thresholds.minimumTags()) return rejected(Rejection.TAG_COUNT);
    OptionalDouble reprojection = nonnegative(localization.quality(), "reprojection_error_px");
    if (reprojection.isEmpty()) return rejected(Rejection.REPROJECTION_MISSING_OR_INVALID);
    if (reprojection.getAsDouble() > thresholds.maximumReprojectionErrorPx()) return rejected(Rejection.REPROJECTION_EXCEEDED);
    if (thresholds.maximumAmbiguity().isPresent()) {
      OptionalDouble value = nonnegative(localization.quality(), "ambiguity");
      if (value.isEmpty()) return rejected(Rejection.AMBIGUITY_MISSING_OR_INVALID);
      if (value.getAsDouble() > thresholds.maximumAmbiguity().getAsDouble()) return rejected(Rejection.AMBIGUITY_EXCEEDED);
    }
    // Per-tag distance/margin may be required explicitly. Missing detection coverage rejects,
    // rather than inventing aggregate localization metrics or trusting unobserved tags.
    if (thresholds.maximumDistanceMeters().isPresent() || thresholds.minimumDecisionMargin().isPresent()) {
      Map<Long, Map<String, Object>> detections = new java.util.LinkedHashMap<>();
      for (Map<String, Object> detection : packet.detections()) {
        Object id = detection.get("id");
        if (id instanceof Long tag && tags.contains(tag)) {
          if (detections.putIfAbsent(tag, detection) != null) return rejected(Rejection.INVALID_METHOD_TAG_COUNT);
        }
      }
      for (long tag : tags) {
        Map<String, Object> detection = detections.getOrDefault(tag, Map.of());
        if (thresholds.maximumDistanceMeters().isPresent()) {
          OptionalDouble value = nonnegative(detection, "distance_m");
          if (value.isEmpty()) return rejected(Rejection.DISTANCE_MISSING_OR_INVALID);
          if (value.getAsDouble() > thresholds.maximumDistanceMeters().getAsDouble()) return rejected(Rejection.DISTANCE_EXCEEDED);
        }
        if (thresholds.minimumDecisionMargin().isPresent()) {
          OptionalDouble value = nonnegative(detection, "decision_margin");
          if (value.isEmpty()) return rejected(Rejection.DECISION_MARGIN_MISSING_OR_INVALID);
          if (value.getAsDouble() < thresholds.minimumDecisionMargin().getAsDouble()) return rejected(Rejection.DECISION_MARGIN_TOO_LOW);
        }
      }
    }
    Optional<PoseEstimate.StandardDeviations> noise;
    try { noise = Objects.requireNonNull(standardDeviationProvider.calibratedFor(new Context(camera, observation, localization, method))); }
    catch (RuntimeException exception) { return rejected(Rejection.CALIBRATION_CALLBACK_FAILED); }
    if (noise.isEmpty()) return rejected(Rejection.CALIBRATION_UNAVAILABLE);
    return new Decision(Optional.of(new PoseEstimate(camera, localization.fieldRobot().orElseThrow(), method,
        localization.usedTagIds(), localization.quality(), noise.orElseThrow(), observation)), Optional.empty());
  }
  private static OptionalDouble nonnegative(Map<String, Object> fields, String key) {
    Object raw = fields.get(key);
    if (!(raw instanceof Long || raw instanceof Double)) return OptionalDouble.empty();
    double number = ((Number) raw).doubleValue();
    return Double.isFinite(number) && number >= 0 ? OptionalDouble.of(number) : OptionalDouble.empty();
  }
  private static Decision rejected(Rejection reason) { return new Decision(Optional.empty(), Optional.of(reason)); }
}
