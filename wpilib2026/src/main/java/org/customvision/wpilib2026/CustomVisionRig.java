package org.customvision.wpilib2026;

import edu.wpi.first.networktables.NetworkTableInstance;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;
import org.customvision.api.CameraConfig;
import org.customvision.api.CameraInput;
import org.customvision.api.PosePolicy;
import org.customvision.api.SyncEvidenceProvider;
import org.customvision.api.VisionRig;
import org.customvision.protocol.ClockMapper;
import org.customvision.protocol.SyncSnapshot;

/**
 * Pinned WPILib 2026 facade construction. Caller retains its shared NT instance, verified
 * robot clock, synchronization evidence, estimator, drivetrain and scheduler ownership.
 */
public final class CustomVisionRig implements AutoCloseable {
  private final VisionRig rig;
  private final Map<String, NtResultQueue> queues;
  private CustomVisionRig(VisionRig rig, Map<String, NtResultQueue> queues) {
    this.rig = rig; this.queues = Map.copyOf(queues);
  }
  public static CustomVisionRig create(NetworkTableInstance sharedInstance, List<CameraConfig> cameras,
      ClockMapper mapper, LongSupplier verifiedRobotNanoClock, SyncEvidenceProvider clockEvidence) {
    return create(sharedInstance, cameras, mapper, verifiedRobotNanoClock, clockEvidence,
        NtResultQueue.Settings.defaults(), VisionRig.Bounds.defaults());
  }
  public static CustomVisionRig create(NetworkTableInstance sharedInstance, List<CameraConfig> cameras,
      ClockMapper mapper, LongSupplier verifiedRobotNanoClock, SyncEvidenceProvider clockEvidence,
      NtResultQueue.Settings subscriptionSettings, VisionRig.Bounds bounds) {
    Objects.requireNonNull(sharedInstance, "caller-owned NT instance required");
    Objects.requireNonNull(cameras); Objects.requireNonNull(mapper);
    Objects.requireNonNull(verifiedRobotNanoClock, "verified robot monotonic clock binding required");
    Objects.requireNonNull(clockEvidence, "verified synchronization evidence callback required");
    Objects.requireNonNull(subscriptionSettings); Objects.requireNonNull(bounds);
    if (cameras.isEmpty() || cameras.size() > bounds.clientLimits().maxSources())
      throw new IllegalArgumentException("nonempty bounded camera configuration required");
    var names = new java.util.HashSet<String>();
    var sources = new java.util.HashSet<org.customvision.protocol.SourceKey>();
    for (CameraConfig camera : cameras) {
      if (!names.add(camera.name()) || !sources.add(camera.source()))
        throw new IllegalArgumentException("duplicate camera name or result source");
    }
    Map<String, NtResultQueue> queues = new LinkedHashMap<>();
    List<CameraInput> inputs = new ArrayList<>();
    try {
      for (CameraConfig camera : cameras) {
        NtResultQueue queue = new NtResultQueue(sharedInstance, camera.source().resultTopic(),
            verifiedRobotNanoClock, subscriptionSettings);
        queues.put(camera.name(), queue);
        inputs.add(new CameraInput(camera, queue, () -> {
          var offset = queue.rawServerTimeOffset();
          long epoch = queue.connectionEpoch();
          SyncSnapshot snapshot = Objects.requireNonNull(clockEvidence.snapshot(camera, queue.timeVersion(),
              offset, epoch, verifiedRobotNanoClock.getAsLong()), "synchronization callback returned null");
          if (snapshot.version() != queue.timeVersion() || snapshot.connectionEpoch() != epoch
              || !snapshot.serverMinusLocalRaw().equals(offset))
            throw new IllegalStateException("synchronization callback changed pinned NT unit, epoch or raw offset");
          return snapshot;
        }));
      }
      return new CustomVisionRig(new VisionRig(inputs, mapper, verifiedRobotNanoClock, bounds), queues);
    } catch (RuntimeException exception) {
      for (NtResultQueue queue : queues.values()) {
        try { queue.close(); } catch (RuntimeException close) { exception.addSuppressed(close); }
      }
      throw exception;
    }
  }
  public VisionRig rig() { return rig; }
  public org.customvision.protocol.VisionClient.DeliveryGate observationDeliveryGate() { return rig.observationDeliveryGate(); }
  public void periodic() { rig.periodic(); }
  public void setMode(String camera, VisionRig.Mode mode) { rig.setMode(camera, mode); }
  public void setPosePolicy(String camera, PosePolicy policy) { rig.setPosePolicy(camera, policy); }
  /** Consume once; calling repeatedly produces no duplicate estimator insertion requests. */
  public List<WpilibPoseEstimate> drainPoseEstimates() {
    return rig.drainPoseEstimates().stream().map(WpilibPoseEstimate::from).toList();
  }
  /** Caller supplies a source-specific disconnect/reconnect boundary, never aggregate isConnected. */
  public void resetConnection(String cameraName) {
    NtResultQueue queue = queues.get(Objects.requireNonNull(cameraName));
    if (queue == null) throw new IllegalArgumentException("camera is not configured: " + cameraName);
    rig.disconnect(cameraName, queue.advanceConnectionEpoch());
  }
  @Override public void close() { rig.close(); }
}
