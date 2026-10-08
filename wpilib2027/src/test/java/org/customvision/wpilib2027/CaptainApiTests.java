package org.customvision.wpilib2027;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.Set;
import org.customvision.api.CameraConfig;
import org.customvision.api.CameraInput;
import org.customvision.api.PoseEstimate;
import org.customvision.api.PosePolicy;
import org.customvision.api.VisionRig;
import org.customvision.protocol.ClockMapper;
import org.customvision.protocol.RawQueue;
import org.customvision.protocol.SourceSession;
import org.customvision.protocol.SyncSnapshot;
import org.customvision.protocol.TimeVersion;
import org.customvision.protocol.TransportSample;

/** Pinned WPILib representation tests through actual decoder/admission. No HAL/NT natives. */
public final class CaptainApiTests {
  private CaptainApiTests() {}
  private static int checks;
  private static final long BASE = 1_234_569_890_123_000L;
  private static final TimeVersion VERSION = TimeVersion.WPILIB_2027_ALPHA7_NANOSECONDS;
  private static final class Queue implements RawQueue {
    final ArrayDeque<TransportSample> values = new ArrayDeque<>();
    boolean closed;
    @Override public Batch read(int limit) {
      List<TransportSample> result = new ArrayList<>();
      while (result.size() < limit && !values.isEmpty()) result.add(values.removeFirst());
      return new Batch(result, false);
    }
    @Override public void close() { closed = true; }
  }
  public static void main(String[] args) throws Exception {
    String template = Files.readString(Path.of("fixtures/fixtures/measured_zero_correction.json"));
    Queue queue = new Queue(); long[] now = {BASE + 1_000_000L};
    CameraConfig camera = CameraConfig.tags("front", "/captain/front_tags", "front_tags");
    try (VisionRig rig = new VisionRig(List.of(new CameraInput(camera, queue, () -> new SyncSnapshot(
        VERSION, OptionalLong.of(0), "profile-synthetic", true, 0, 0, now[0], 0,
        "synthetic matched epoch; not physical timing evidence"))), new ClockMapper("profile-synthetic"), () -> now[0],
        new VisionRig.Bounds(new org.customvision.protocol.VisionClient.Limits(1, 8, 8, 16, 16, 0), 16, 16))) {
      rig.setMode("front", VisionRig.Mode.FUSION_CANDIDATES);
      rig.setPosePolicy("front", new PosePolicy(new PosePolicy.Thresholds(Set.of(PoseEstimate.Method.SINGLE_TAG_PNP),
          1, 1, OptionalDouble.of(.2), OptionalDouble.empty(), OptionalDouble.empty()), context -> Optional.of(
              new PoseEstimate.StandardDeviations(.1, .2, Math.toRadians(3), "synthetic-cal", "synthetic-tune"))));
      publish(queue, template, 42, 0, BASE, now[0]); rig.periodic();
      check(rig.drainPoseEstimates().isEmpty(), "retained startup value not converted into pose");
      now[0] = BASE + 3_000_000L; publish(queue, template, 43, 1, BASE + 2_000_000L, now[0]); rig.periodic();
      List<PoseEstimate> estimates = rig.drainPoseEstimates(); check(estimates.size() == 1, "real admission produces one facade pose");
      WpilibPoseEstimate converted = WpilibPoseEstimate.from(estimates.get(0));
      check(converted.provenance().observation().admission().kind() == SourceSession.Kind.ACCEPTED, "exact lifecycle provenance survives WPILib facade");
      check(converted.provenance().capture().metadataVersion() == VERSION, "pinned metadata units survive facade");
      check(converted.captureSeconds() == (BASE + 2_000_000L) / 1_000_000_000.0, "seconds are mapped capture, not dequeue");
      check(converted.pose().getX() == 1.613282 && converted.pose().getY() == 3.171826,
          "fixed-origin field robot translation unchanged");
      check(converted.pose2d().getX() == converted.pose().getX()
          && converted.pose2d().getY() == converted.pose().getY(), "direct 3D to2D conversion only");
      check(Math.abs(converted.pose2d().getRotation().getRadians() - converted.pose().getRotation().getZ()) < 1e-12,
          "NWU WXYZ yaw sign unchanged");
      var noise = converted.standardDeviations();
      check(noise.get(0, 0) == .1 && noise.get(1, 0) == .2
          && noise.get(2, 0) == Math.toRadians(3), "calibrated standard deviation units are m,m,rad");
      noise.set(0, 0, 99);
      check(converted.standardDeviations().get(0, 0) == .1, "mutable WPILib noise vector cannot mutate stored calibration");
      check(rig.drainPoseEstimates().isEmpty(), "WPILib conversion does not create new insertion request");
      expect(() -> new WpilibPoseEstimate(converted.pose(), converted.captureSeconds() + 1, converted.provenance()), IllegalArgumentException.class);
      expect(() -> CustomVisionRig.create(null, List.of(camera), new ClockMapper("profile-synthetic"), () -> now[0],
          (config, unit, offset, epoch, observed) -> null), NullPointerException.class);
    }
    check(queue.closed, "owned queue closes without HAL/shared NT close");
    System.out.println("WPILib 2027 CaptainApiTests PASS: " + checks + " assertions");
  }
  private static void publish(Queue queue, String template, long frame, long sequence, long capture, long publication) {
    String bytes = template.replace("\"frame_id\":42", "\"frame_id\":" + frame)
        .replace("\"packet_seq\":16", "\"packet_seq\":" + sequence)
        .replace("\"capture_server_us\":1234569890123", "\"capture_server_us\":" + capture / 1000);
    long raw = VERSION == TimeVersion.WPILIB_2026_MICROSECONDS ? publication / 1000L : publication;
    queue.values.addLast(new TransportSample(bytes, raw, raw, publication, publication, 0));
  }
  private static void check(boolean condition, String detail) { checks++; if (!condition) throw new AssertionError(detail); }
  private static void expect(Runnable action, Class<? extends Throwable> expected) {
    checks++;
    try { action.run(); }
    catch (Throwable problem) { if (expected.isInstance(problem)) return; throw new AssertionError(problem); }
    throw new AssertionError("expected " + expected.getSimpleName());
  }
}
