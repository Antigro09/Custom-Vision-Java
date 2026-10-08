package org.customvision.wpilib2026;

import edu.wpi.first.math.Matrix;
import edu.wpi.first.math.VecBuilder;
import edu.wpi.first.math.geometry.Pose2d;
import edu.wpi.first.math.geometry.Pose3d;
import edu.wpi.first.math.numbers.N1;
import edu.wpi.first.math.numbers.N3;
import java.util.Objects;
import org.customvision.api.PoseEstimate;

/** Representation conversion of one admitted field-robot pose; this class never inserts it. */
public record WpilibPoseEstimate(Pose3d pose, double captureSeconds, PoseEstimate provenance) {
  public WpilibPoseEstimate {
    Objects.requireNonNull(pose); Objects.requireNonNull(provenance);
    if (!pose.equals(GeometryConversions.toPose3d(provenance.fieldToRobot()))
        || Double.compare(captureSeconds, provenance.captureSeconds()) != 0)
      throw new IllegalArgumentException("pose and capture must retain admitted observation provenance");
  }
  public static WpilibPoseEstimate from(PoseEstimate estimate) {
    return new WpilibPoseEstimate(GeometryConversions.toPose3d(estimate.fieldToRobot()),
        estimate.captureSeconds(), estimate);
  }
  /** Uses WPILib's direct Pose3d-to-Pose2d representation conversion; no field/alliance transform. */
  public Pose2d pose2d() { return pose.toPose2d(); }
  /** Returns a fresh m,m,rad vector suitable for robot-owned estimator policy, never covariance. */
  public Matrix<N3, N1> standardDeviations() {
    PoseEstimate.StandardDeviations values = provenance.standardDeviations();
    return VecBuilder.fill(values.xMeters(), values.yMeters(), values.headingRadians());
  }
}
