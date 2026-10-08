package org.customvision.protocol;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Robot-owned, bounded history. The consumer chooses its immutable pose type; this module does
 * no field projection, transforms, covariance propagation, or estimator mutation. All calls are
 * made by the robot-loop owner. Direct sampleAt implementations may clamp; use checkedSample.
 */
public interface HistoricalPoseProvider<P> {
  /** Inclusive coverage span. Separate spans represent holes that must never be interpolated. */
  record Coverage(long oldestRobotNs, long newestRobotNs) {
    public Coverage {
      if (oldestRobotNs < 0 || newestRobotNs < oldestRobotNs)
        throw new IllegalArgumentException("invalid history coverage");
    }
    public boolean contains(long timeNs) { return timeNs >= oldestRobotNs && timeNs <= newestRobotNs; }
  }

  /** Immutable atomic view; a reset changes resetIdentity even if timestamps overlap. */
  record Window(String robotEpoch, String resetIdentity, long resetAtRobotNs, List<Coverage> coverage) {
    public Window {
      Objects.requireNonNull(robotEpoch); Objects.requireNonNull(resetIdentity);
      coverage = List.copyOf(coverage);
      if (robotEpoch.isBlank() || robotEpoch.length() > 128 || resetIdentity.isBlank()
          || resetIdentity.length() > 128 || resetAtRobotNs < 0 || coverage.size() > 128)
        throw new IllegalArgumentException("invalid history identity/bounds");
      long previousEnd = -1;
      for (Coverage span : coverage) {
        if (span.oldestRobotNs() < resetAtRobotNs || span.oldestRobotNs() <= previousEnd)
          throw new IllegalArgumentException("history spans must be ordered, disjoint and post-reset");
        previousEnd = span.newestRobotNs();
      }
    }
    public Optional<Long> oldestRobotNs() {
      return coverage.isEmpty() ? Optional.empty() : Optional.of(coverage.get(0).oldestRobotNs());
    }
    public Optional<Long> newestRobotNs() {
      return coverage.isEmpty() ? Optional.empty()
          : Optional.of(coverage.get(coverage.size() - 1).newestRobotNs());
    }
    public boolean covers(long timeNs) {
      for (Coverage span : coverage) if (span.contains(timeNs)) return true;
      return false;
    }
  }

  enum Reason { EPOCH_MISMATCH, RESET_MISMATCH, PRE_RESET, OUT_OF_COVERAGE, HOLE, NO_SAMPLE, RESET_DURING_SAMPLE }

  record Sample<P>(Optional<P> pose, Optional<Reason> rejection, String resetIdentity, long robotCaptureNs) {
    public Sample {
      Objects.requireNonNull(pose); Objects.requireNonNull(rejection); Objects.requireNonNull(resetIdentity);
      if (pose.isPresent() == rejection.isPresent())
        throw new IllegalArgumentException("exactly one history outcome required");
    }
    public boolean accepted() { return pose.isPresent(); }
  }

  Window window();
  Optional<P> sampleAt(long robotCaptureNs);

  /** Validate before sampleAt can clamp; then reject a reset that raced sampling. */
  default Sample<P> checkedSample(long robotCaptureNs, String expectedRobotEpoch, String expectedResetIdentity) {
    Objects.requireNonNull(expectedRobotEpoch); Objects.requireNonNull(expectedResetIdentity);
    Window before = Objects.requireNonNull(window());
    if (!before.robotEpoch().equals(expectedRobotEpoch))
      return rejected(Reason.EPOCH_MISMATCH, before, robotCaptureNs);
    if (!before.resetIdentity().equals(expectedResetIdentity))
      return rejected(Reason.RESET_MISMATCH, before, robotCaptureNs);
    if (robotCaptureNs < before.resetAtRobotNs()) return rejected(Reason.PRE_RESET, before, robotCaptureNs);
    if (before.coverage().isEmpty() || robotCaptureNs < before.oldestRobotNs().orElseThrow()
        || robotCaptureNs > before.newestRobotNs().orElseThrow())
      return rejected(Reason.OUT_OF_COVERAGE, before, robotCaptureNs);
    if (!before.covers(robotCaptureNs)) return rejected(Reason.HOLE, before, robotCaptureNs);
    Optional<P> pose = Objects.requireNonNull(sampleAt(robotCaptureNs));
    Window after = Objects.requireNonNull(window());
    if (!before.resetIdentity().equals(after.resetIdentity())
        || !before.robotEpoch().equals(after.robotEpoch())
        || before.resetAtRobotNs() != after.resetAtRobotNs())
      return rejected(Reason.RESET_DURING_SAMPLE, after, robotCaptureNs);
    if (pose.isEmpty()) return rejected(Reason.NO_SAMPLE, before, robotCaptureNs);
    return new Sample<>(pose, Optional.empty(), before.resetIdentity(), robotCaptureNs);
  }

  private static <P> Sample<P> rejected(Reason reason, Window window, long captureNs) {
    return new Sample<>(Optional.empty(), Optional.of(reason), window.resetIdentity(), captureNs);
  }
}
