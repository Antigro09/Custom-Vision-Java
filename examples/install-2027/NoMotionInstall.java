import org.wpilib.networktables.NetworkTableInstance;
import org.wpilib.math.linalg.Matrix;
import org.wpilib.math.geometry.Pose2d;
import org.wpilib.math.numbers.N1;
import org.wpilib.math.numbers.N3;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;
import org.customvision.api.CameraConfig;
import org.customvision.api.SyncEvidenceProvider;
import org.customvision.api.PoseEstimate;
import org.customvision.api.PosePolicy;
import org.customvision.api.VisionRig;
import org.customvision.protocol.ClockMapper;
import org.customvision.wpilib2027.CustomVisionRig;

/** Compile-only installation example; no robot, HAL, estimator or motor is created. */
public final class NoMotionInstall {
  private NoMotionInstall() {}

  /** Caller owns the shared instance and must supply verified robot clock evidence. */
  public static CustomVisionRig construct(NetworkTableInstance sharedInstance,
      LongSupplier verifiedRobotNanoClock, SyncEvidenceProvider verifiedSyncEvidence,
      String robotEpoch) {
    var frontTags = CameraConfig.tags("front-tags", "/CustomVision/front/tags", "tags");
    var rearObjects = CameraConfig.objects("rear-objects", "/CustomVision/rear/objects", "objects");
    return CustomVisionRig.create(sharedInstance, List.of(frontTags, rearObjects),
        new ClockMapper(robotEpoch), verifiedRobotNanoClock, verifiedSyncEvidence);
  }

  /** Call once from the caller's robot loop. Consumes observations; does not fuse or drive. */
  public static int inspectNewObservations(CustomVisionRig vision) {
    vision.periodic();
    return vision.rig().drainObservations().size();
  }

  /** The caller supplies calibrated noise and explicit quality limits; no guessed defaults. */
  public static void configurePoseCandidates(CustomVisionRig vision, PosePolicy calibratedPolicy) {
    vision.setPosePolicy("front-tags", Objects.requireNonNull(calibratedPolicy));
    vision.setMode("front-tags", VisionRig.Mode.FUSION_CANDIDATES);
  }

  @FunctionalInterface
  public interface PoseInspection {
    void accept(Pose2d pose, double captureSeconds, Matrix<N3, N1> stdDevsMetersMetersRadians,
        PoseEstimate.Method method, PoseEstimate fullProvenance);
  }

  /** Inspect consume-once candidates after periodic; no estimator is called by this example. */
  public static void inspectPoseCandidates(CustomVisionRig vision, PoseInspection inspection) {
    Objects.requireNonNull(inspection, "explicit inspection callback required");
    for (var estimate : vision.drainPoseEstimates()) {
      inspection.accept(estimate.pose2d(), estimate.captureSeconds(), estimate.standardDeviations(),
          estimate.provenance().method(), estimate.provenance());
    }
  }
}
