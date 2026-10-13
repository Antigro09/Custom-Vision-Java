package org.customvision.api;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.customvision.protocol.ClockMapper;
import org.customvision.protocol.RawQueue;
import org.customvision.protocol.SourceSession;
import org.customvision.protocol.SyncSnapshot;
import org.customvision.protocol.TimeVersion;
import org.customvision.protocol.TransportSample;
import org.customvision.protocol.VisionClient;

/** Synthetic facade integration through the real decoder, admission, clock and consume-once path. */
public final class ApiTests {
  private ApiTests() {}
  private static int checks;
  private static final long BASE = 1_234_569_890_123_000L;
  private static final CameraConfig FRONT = CameraConfig.tags("front", "/api/front/front_tags", "front_tags");
  private static final CameraConfig REAR = CameraConfig.tags("rear", "/api/rear/front_tags", "front_tags");
  private static String fixture;
  private static String watchdog;
  private static final PoseEstimate.StandardDeviations CALIBRATION = new PoseEstimate.StandardDeviations(
      .12, .18, Math.toRadians(4), "synthetic-lab-calibration-v1", "synthetic-tuning-v1");
  private static final class FakeQueue implements RawQueue {
    final ArrayDeque<TransportSample> pending = new ArrayDeque<>();
    boolean closed;
    @Override public Batch read(int maxPackets) {
      List<TransportSample> output = new ArrayList<>();
      while (output.size() < maxPackets && !pending.isEmpty()) output.add(pending.removeFirst());
      return new Batch(output, false);
    }
    @Override public void close() { closed = true; }
    void publish(String bytes, long publication, long receipt) {
      pending.addLast(new TransportSample(bytes, publication / 1000L, publication / 1000L, receipt, receipt, 0));
    }
  }
  private static final class Harness implements AutoCloseable {
    final long[] clock = {BASE + 1_000_000L};
    final FakeQueue front = new FakeQueue();
    final FakeQueue rear = new FakeQueue();
    final VisionRig rig;
    Harness(boolean second, int outputDepth) { this(second, outputDepth, 0); }
    Harness(boolean second, int outputDepth, long reorderWindowNs) {
      List<CameraInput> inputs = new ArrayList<>();
      inputs.add(new CameraInput(FRONT, front, () -> synchronization(clock[0])));
      if (second) inputs.add(new CameraInput(REAR, rear, () -> synchronization(clock[0])));
      rig = new VisionRig(inputs, new ClockMapper("api-synthetic-robot"), () -> clock[0],
          new VisionRig.Bounds(new VisionClient.Limits(8, 16, 64, 64, 128, reorderWindowNs), outputDepth, 64));
    }
    void publish(FakeQueue queue, long frame, long sequence, long captureMs, long publicationMs,
        UnaryOperator<String> mutation) {
      long publication = BASE + publicationMs * 1_000_000L;
      clock[0] = publication;
      queue.publish(mutation.apply(bytes(frame, sequence, BASE + captureMs * 1_000_000L)), publication, publication);
    }
    void next(long frame, long sequence, long captureMs, long publicationMs) {
      publish(front, frame, sequence, captureMs, publicationMs, UnaryOperator.identity()); rig.periodic();
    }
    void prime() { next(42, 0, 0, 1); }
    void accepted() { prime(); next(43, 1, 2, 3); }
    @Override public void close() { rig.close(); }
  }
  public static void main(String[] args) throws Exception {
    fixture = Files.readString(Path.of("fixtures/fixtures/measured_zero_correction.json"));
    watchdog = Files.readString(Path.of("fixtures/fixtures/same_frame_watchdog.json"));
    constructors(); observationOnly(); calibratedPoses(); methodsAndQuality(); modesAndBindings();
    lifecycleAndQueueBounds(); captureOrder(); slowCallbacks(); advancingClock(); deliveryGate();
    System.out.println("Captain facade ApiTests PASS: " + checks + " assertions");
  }
  private static void constructors() {
    check(FRONT.source().resultTopic().equals("/api/front/front_tags/result"), "full coherent namespace is explicit");
    check(CameraConfig.objects("objects", "/CV/objects", "objects").source().type().equals("object"), "object source factory");
    expect(() -> CameraConfig.tags("", "/CV/front", "front"), IllegalArgumentException.class);
    expect(() -> CameraConfig.tags("front", "CV/front", "front"), IllegalArgumentException.class);
    expect(() -> new PoseEstimate.StandardDeviations(0, .1, .1, "cal", "tune"), IllegalArgumentException.class);
    expect(() -> new PoseEstimate.StandardDeviations(.1, .1, Double.NaN, "cal", "tune"), IllegalArgumentException.class);
    expect(() -> new PoseEstimate.StandardDeviations(Double.MAX_VALUE, .1, .1, "cal", "tune"), IllegalArgumentException.class);
    expect(() -> new PoseEstimate.StandardDeviations(Double.MIN_VALUE, .1, .1, "cal", "tune"), IllegalArgumentException.class);
    expect(() -> new PoseEstimate.StandardDeviations(.1, .1, .1, "", "tune"), IllegalArgumentException.class);
    expect(() -> new PosePolicy.Thresholds(Set.of(), 1, 2, OptionalDouble.empty(), OptionalDouble.empty(), OptionalDouble.empty()), IllegalArgumentException.class);
    expect(() -> new PosePolicy.Thresholds(Set.of(PoseEstimate.Method.MULTITAG_PNP), 1, -1,
        OptionalDouble.empty(), OptionalDouble.empty(), OptionalDouble.empty()), IllegalArgumentException.class);
    FakeQueue queue = new FakeQueue();
    expect(() -> new VisionRig(List.of(new CameraInput(FRONT, queue, () -> synchronization(BASE)),
        new CameraInput(FRONT, queue, () -> synchronization(BASE))), new ClockMapper("api-synthetic-robot"), () -> BASE), IllegalArgumentException.class);
    check(!queue.closed, "failed pure construction does not close caller's untransferred queue");
  }
  private static void observationOnly() {
    Harness h = new Harness(false, 64);
    try (h) {
      check(h.rig.mode("front") == VisionRig.Mode.OBSERVATION_ONLY, "safe explicit observation-only default");
      h.prime(); check(h.rig.drainObservations().isEmpty(), "first retained packet cannot become an observation");
      h.next(43, 1, 2, 3);
      List<VisionClient.Measurement> observations = h.rig.drainObservations();
      check(observations.size() == 1, "actual advancing decoder/session path releases one observation");
      VisionClient.Measurement measurement = observations.get(0);
      check(measurement.admission().kind() == SourceSession.Kind.ACCEPTED, "preserved exact accepted admission");
      check(measurement.admission().newlyAcceptedMeasurement().orElseThrow() == measurement.packet(), "same packet object in actual admission");
      check(measurement.clock().capture().orElseThrow() == measurement.capture(), "capture accessor retains full accepted clock outcome");
      check(measurement.transport().payload().equals(bytes(43, 1, BASE + 2_000_000L)), "exact transport payload retained");
      check(measurement.transport().ntTimestamp() == (BASE + 3_000_000L) / 1000, "original raw us metadata retained");
      check(h.rig.drainObservations().isEmpty(), "consume once observation drain");
      check(h.rig.drainPoseEstimates().isEmpty(), "no guessed standard deviations or implicit pose fusion");
      h.next(43, 1, 2, 4);
      check(h.rig.drainObservations().isEmpty(), "duplicate packet delivery never repeats observations");
      check(h.rig.sourceStatuses().get(FRONT).usableObservationAgeNs().orElseThrow() == 1_000_000L,
          "duplicate does not refresh measurement freshness");
    }
    check(h.front.closed && !h.rear.closed, "rig closes only transferred owned queues");
  }
  private static void calibratedPoses() {
    try (Harness h = new Harness(false, 64)) {
      h.rig.setMode("front", VisionRig.Mode.FUSION_CANDIDATES); h.rig.setPosePolicy("front", policy());
      h.accepted();
      List<PoseEstimate> poses = h.rig.drainPoseEstimates();
      check(poses.size() == 1, "one eligible calibrated field-robot estimate");
      PoseEstimate estimate = poses.get(0);
      check(estimate.method() == PoseEstimate.Method.SINGLE_TAG_PNP, "distinct single-tag method");
      check(estimate.fieldToRobot().equals(estimate.observation().packet().localization().orElseThrow().fieldRobot().orElseThrow()), "wire field robot pose unchanged");
      check(estimate.standardDeviations() == CALIBRATION, "calibrated m,m,rad preserved independently pixel residuals");
      check(estimate.captureSeconds() == (BASE + 2_000_000L) / 1_000_000_000.0, "mapped robot capture seconds");
      check(estimate.usedTagIds().equals(List.of(1L)), "tag correlation provenance retained");
      check(estimate.rawQuality().get("reprojection_error_px").equals(0.0), "pixel residuals retain named diagnostic units");
      check(h.rig.drainPoseEstimates().isEmpty(), "consume once pose drain");
      check(h.rig.latestPoseForDiagnostics("front").orElseThrow() == estimate, "repeatable diagnostic getter explicitly separate");
      check(h.rig.latestPoseForDiagnostics("front").orElseThrow() == estimate, "diagnostic snapshot does not enqueue pose");
      check(h.rig.drainPoseEstimates().isEmpty(), "diagnostic reads cannot create fusion requests");
      expect(() -> estimate.usedTagIds().add(9L), UnsupportedOperationException.class);
      expect(() -> estimate.rawQuality().put("ambiguity", 1.0), UnsupportedOperationException.class);
      expect(() -> new PoseEstimate(FRONT, estimate.fieldToRobot(), PoseEstimate.Method.MULTITAG_PNP,
          estimate.usedTagIds(), estimate.rawQuality(), CALIBRATION, estimate.observation()), IllegalArgumentException.class);
      h.clock[0] = BASE + 104_000_000L;
      check(h.rig.latestPoseForDiagnostics("front").isEmpty(), "getter expires missing-source status without periodic packets");
      check(h.rig.drainObservations().isEmpty(), "quiet-cycle drain expires old queued observation");
    }
  }
  private static void methodsAndQuality() throws Exception {
    checkReject("unsupported heading method", json -> json.replace("\"method\":\"single_tag_pnp\"", "\"method\":\"heading_assisted\""), policy(), "UNSUPPORTED_METHOD");
    checkReject("MT2 alias is unsupported", json -> json.replace("\"method\":\"single_tag_pnp\"", "\"method\":\"MegaTag2\""), policy(), "UNSUPPORTED_METHOD");
    checkReject("quality threshold", json -> json.replace("\"inlier_tag_count\":1,\"reprojection_error_px\":0.0", "\"inlier_tag_count\":1,\"reprojection_error_px\":3.0"), policy(), "REPROJECTION_EXCEEDED");
    checkReject("ambiguity threshold", json -> json.replace("\"ambiguity\":0.0", "\"ambiguity\":0.5"), policy(), "AMBIGUITY_EXCEEDED");
    checkReject("tag threshold", UnaryOperator.identity(), new PosePolicy(new PosePolicy.Thresholds(Set.of(PoseEstimate.Method.SINGLE_TAG_PNP),
        2, 2, OptionalDouble.empty(), OptionalDouble.empty(), OptionalDouble.empty()), context -> Optional.of(CALIBRATION)), "TAG_COUNT");
    checkReject("calibration coverage", UnaryOperator.identity(), new PosePolicy(policy().thresholds(), context -> Optional.empty()), "CALIBRATION_UNAVAILABLE");
    checkReject("calibration callback", UnaryOperator.identity(), new PosePolicy(policy().thresholds(), context -> { throw new IllegalArgumentException("synthetic failure"); }), "CALIBRATION_CALLBACK_FAILED");
    checkReject("required distance missing", json -> json.replace("\"distance_m\":3.074085,", ""), distancePolicy(), "DISTANCE_MISSING_OR_INVALID");
    checkReject("required margin missing", json -> json.replace("\"decision_margin\":100.0,", ""), distancePolicy(), "DECISION_MARGIN_MISSING_OR_INVALID");
    checkReject("camera-only never synthesized into robot pose", json -> json.replace("\"field_to_robot\":{" +
        "\"translation_m\":[1.613282,3.171826,0.314351],\"rotation_quaternion_wxyz\":[0.991965,-0.066462,0.07709,-0.075139]," +
        "\"rotation_rpy_deg\":[-8.333162,8.218767,-9.263258],\"frame\":\"wpilib_nwu\"}", "\"field_to_robot\":null"), policy(), "FIELD_ROBOT_POSE_MISSING");
    String multi = Files.readString(Path.of("fixtures/fixtures/multitag.json"));
    try (Harness h = new Harness(false, 64)) {
      h.rig.setMode("front", VisionRig.Mode.FUSION_CANDIDATES); h.rig.setPosePolicy("front", policy()); h.prime();
      h.publish(h.front, 43, 1, 2, 3, json -> multi.replace("\"frame_id\":42", "\"frame_id\":43")
          .replace("\"packet_seq\":3", "\"packet_seq\":1").replace("\"capture_server_us\":1234569890123", "\"capture_server_us\":" + (BASE + 2_000_000L) / 1000)
          .replace("\"capture_correction_verified\":false", "\"capture_correction_verified\":true")
          .replace("\"capture_correction_uncertainty_ms\":null", "\"capture_correction_uncertainty_ms\":0.0"));
      h.rig.periodic(); List<PoseEstimate> estimates = h.rig.drainPoseEstimates();
      check(estimates.size() == 1 && estimates.get(0).method() == PoseEstimate.Method.MULTITAG_PNP, "actual multitag producer method distinctly retained");
      check(estimates.get(0).usedTagIds().size() == 3, "multitag joint observation remains one candidate");
    }
  }
  private static void modesAndBindings() {
    try (Harness h = new Harness(false, 64)) {
      h.rig.setMode("front", VisionRig.Mode.FUSION_CANDIDATES); h.accepted();
      check(h.rig.drainPoseEstimates().isEmpty(), "fusion mode without trust binding cannot output pose");
      check(hasDiagnostic(h.rig, "MISSING_POSE_POLICY"), "missing trust binding diagnosed");
      h.rig.setMode("front", VisionRig.Mode.DISABLED); h.next(44, 2, 4, 5);
      check(h.rig.drainObservations().isEmpty() && h.rig.drainPoseEstimates().isEmpty(), "disabled mode drains without actionable output");
      check(h.rig.sourceStatuses().get(FRONT).actionable(), "disabled facade still polls source diagnostics");
      h.rig.setMode("front", VisionRig.Mode.OBSERVATION_ONLY); h.rig.periodic();
      check(h.rig.drainObservations().isEmpty(), "enable does not reprocess retained packet");
      h.next(45, 3, 6, 7); check(h.rig.drainObservations().size() == 1, "fresh advancing packet after re-enable");
      expect(() -> h.rig.setMode("missing", VisionRig.Mode.DISABLED), IllegalArgumentException.class);
      expect(() -> h.rig.setObservationConsumer(null), NullPointerException.class);
    }
    try (Harness h = new Harness(false, 64)) {
      List<VisionClient.Measurement> delivered = new ArrayList<>();
      h.rig.setObservationConsumer((measurement, now) -> { check(now >= measurement.capture().robotCaptureNs(), "callback current clock accompanies exact observation"); delivered.add(measurement); });
      h.accepted(); h.rig.periodic();
      check(delivered.size() == 1 && delivered.get(0).admission().newlyAcceptedMeasurement().orElseThrow() == delivered.get(0).packet(), "callback has exact actual envelope once");
      expect(h.rig::drainObservations, IllegalStateException.class);
      h.rig.setObservationConsumer((measurement, now) -> { throw new IllegalStateException("synthetic consumer failure"); });
      h.next(44, 2, 4, 5); check(hasDiagnostic(h.rig, "CALLBACK_FAILED"), "consumer failure is observable");
      h.rig.periodic(); check(!hasDiagnostic(h.rig, "CALLBACK_FAILED"), "failed callback is not replayed");
      h.rig.setObservationConsumer((measurement, now) -> h.rig.setMode("front", VisionRig.Mode.DISABLED));
      h.next(45, 3, 6, 7); check(hasDiagnostic(h.rig, "CALLBACK_FAILED"), "mutating rig within callback rejected");
      check(h.rig.mode("front") == VisionRig.Mode.OBSERVATION_ONLY, "callback cannot silently change mode mid-dispatch");
    }
    try (Harness h = new Harness(false, 64, 30_000_000L)) {
      h.accepted(); check(h.rig.drainObservations().isEmpty(), "actual reorder window holds admitted observation");
      h.rig.setMode("front", VisionRig.Mode.DISABLED); h.rig.setMode("front", VisionRig.Mode.OBSERVATION_ONLY);
      h.clock[0] = BASE + 35_000_000L; h.rig.periodic();
      check(h.rig.drainObservations().isEmpty(), "disable/re-enable fences previously admitted pending reorder observation");
      h.next(44, 2, 36, 37); h.clock[0] = BASE + 70_000_000L; h.rig.periodic();
      check(h.rig.drainObservations().size() == 1, "freshly advancing admission releases after mode fence");
    }
    try (Harness h = new Harness(false, 64, 30_000_000L)) {
      h.rig.setMode("front", VisionRig.Mode.FUSION_CANDIDATES); h.rig.setPosePolicy("front", policy()); h.accepted();
      h.rig.setPosePolicy("front", policy()); h.clock[0] = BASE + 35_000_000L; h.rig.periodic();
      check(h.rig.drainPoseEstimates().isEmpty(), "new trust policy does not process previously admitted held capture");
      check(h.rig.drainObservations().size() == 1, "pose trust fence leaves World-State raw observation delivery intact");
      h.next(44, 2, 36, 37); h.clock[0] = BASE + 70_000_000L; h.rig.periodic();
      check(h.rig.drainPoseEstimates().size() == 1, "fresh admission evaluates current trust policy");
    }
  }
  private static void lifecycleAndQueueBounds() {
    try (Harness h = new Harness(false, 64)) {
      h.rig.setMode("front", VisionRig.Mode.FUSION_CANDIDATES); h.rig.setPosePolicy("front", policy()); h.accepted();
      String clear = watchdog.replace("\"frame_id\":42", "\"frame_id\":43").replace("\"packet_seq\":1", "\"packet_seq\":2");
      h.clock[0] = BASE + 4_000_000L; h.front.publish(clear, h.clock[0], h.clock[0]); h.rig.periodic();
      check(h.rig.drainObservations().isEmpty() && h.rig.drainPoseEstimates().isEmpty(), "same-frame invalidation clears retained facade outputs");
      check(h.rig.latestPoseForDiagnostics("front").isEmpty(), "same-frame invalidation clears diagnostic latest");
      h.next(43, 3, 2, 5); check(h.rig.drainPoseEstimates().isEmpty(), "delayed same-frame cannot revive facade");
    }
    try (Harness h = new Harness(false, 64)) {
      h.rig.setMode("front", VisionRig.Mode.FUSION_CANDIDATES); h.rig.setPosePolicy("front", policy()); h.accepted();
      h.publish(h.front, 44, 2, 4, 5, json -> json.replace("fixture-boot-a", "fixture-boot-b"));
      h.publish(h.front, 45, 3, 6, 7, json -> json.replace("fixture-boot-a", "fixture-boot-b"));
      h.rig.periodic();
      List<PoseEstimate> estimates = h.rig.drainPoseEstimates();
      check(estimates.stream().noneMatch(value -> value.observation().packet().bootId().equals("fixture-boot-a")), "retained prior boot output fenced after same-loop new boot adoption");
      check(estimates.size() == 1 && estimates.get(0).observation().packet().bootId().equals("fixture-boot-b"), "fresh adopted boot output only");
      check(h.rig.drainObservations().stream().allMatch(value -> value.packet().bootId().equals("fixture-boot-b")), "raw queued observations honor boot fence");
    }
    try (Harness h = new Harness(false, 64)) {
      h.rig.setMode("front", VisionRig.Mode.FUSION_CANDIDATES); h.rig.setPosePolicy("front", policy()); h.accepted();
      UnaryOperator<String> newCalibration = json -> json.replace("sha256:73e4c40fe2a27fc9df165550e294e977c20277e4ff13dbb6d5cf38da7a51bcb1",
          "sha256:" + "0".repeat(64));
      h.publish(h.front, 44, 2, 4, 5, newCalibration);
      h.publish(h.front, 45, 3, 6, 7, newCalibration); h.rig.periodic();
      List<PoseEstimate> estimates = h.rig.drainPoseEstimates();
      check(estimates.size() == 1 && estimates.get(0).observation().packet().frameId() == 45,
          "retained prior revision output fenced after same-loop new revision adoption");
      check(h.rig.drainObservations().stream().noneMatch(value -> value.packet().frameId() == 43), "raw observations honor revision fence");
    }
    try (Harness h = new Harness(false, 64)) {
      h.rig.setMode("front", VisionRig.Mode.FUSION_CANDIDATES); h.rig.setPosePolicy("front", policy()); h.accepted();
      h.clock[0] = BASE + 6_000_000L;
      String clear = watchdog.replace("\"frame_id\":42", "\"frame_id\":43").replace("\"packet_seq\":1", "\"packet_seq\":2");
      h.front.publish(clear, BASE + 4_000_000L, h.clock[0]);
      h.front.publish(bytes(44, 3, BASE + 5_000_000L), h.clock[0], h.clock[0]); h.rig.periodic();
      List<PoseEstimate> estimates = h.rig.drainPoseEstimates();
      check(estimates.size() == 1 && estimates.get(0).observation().packet().frameId() == 44, "intermediate invalidation fences retained previous pose despite final actionable packet");
      check(h.rig.drainObservations().stream().noneMatch(value -> value.packet().frameId() == 43), "intermediate invalidation fences old raw observations");
    }
    try (Harness h = new Harness(false, 1)) {
      h.rig.setMode("front", VisionRig.Mode.FUSION_CANDIDATES); h.rig.setPosePolicy("front", policy()); h.prime();
      h.publish(h.front, 43, 1, 2, 3, UnaryOperator.identity());
      h.publish(h.front, 44, 2, 4, 5, UnaryOperator.identity());
      h.publish(h.front, 45, 3, 6, 7, UnaryOperator.identity()); h.rig.periodic();
      check(h.rig.drainObservations().isEmpty() && h.rig.drainPoseEstimates().isEmpty(), "facade overload clears whole source and later extracted entries cannot re-add");
      check(h.rig.counters().facadeOverloads() == 1 && hasDiagnostic(h.rig, "OUTPUT_OVERLOAD"), "facade overload visible and bounded");
      h.next(46, 4, 8, 9); check(h.rig.drainPoseEstimates().size() == 1, "fresh publication recovers facade output overload");
    }
  }
  private static void captureOrder() {
    try (Harness h = new Harness(true, 64)) {
      h.rig.setMode("front", VisionRig.Mode.FUSION_CANDIDATES); h.rig.setMode("rear", VisionRig.Mode.FUSION_CANDIDATES);
      h.rig.setPosePolicy("front", policy()); h.rig.setPosePolicy("rear", policy());
      h.publish(h.front, 42, 0, 0, 1, UnaryOperator.identity());
      h.publish(h.rear, 42, 0, 0, 1, UnaryOperator.identity()); h.rig.periodic();
      h.publish(h.front, 43, 1, 3, 4, UnaryOperator.identity());
      h.publish(h.rear, 43, 1, 2, 4, UnaryOperator.identity()); h.rig.periodic();
      List<PoseEstimate> estimates = h.rig.drainPoseEstimates();
      check(estimates.size() == 2 && estimates.get(0).camera().equals(REAR), "rig uses one cross-camera global capture order");
      check(estimates.get(0).captureSeconds() < estimates.get(1).captureSeconds(), "capture monotonic across cameras");
      h.publish(h.rear, 44, 2, 1, 5, UnaryOperator.identity()); h.rig.periodic();
      check(h.rig.drainPoseEstimates().isEmpty() && hasDiagnostic(h.rig, "LATE"), "cross-loop late camera cannot regress estimator capture order");
    }
  }
  private static void slowCallbacks() {
    try (Harness h = new Harness(false, 64)) {
      h.rig.setMode("front", VisionRig.Mode.FUSION_CANDIDATES);
      h.rig.setPosePolicy("front", new PosePolicy(policy().thresholds(), context -> {
        h.clock[0] += 300_000_000L; return Optional.of(CALIBRATION);
      }));
      h.accepted(); check(h.rig.drainPoseEstimates().isEmpty() && h.rig.drainObservations().isEmpty(), "slow calibration cannot release expired observation");
      check(hasDiagnostic(h.rig, "EXPIRED_DURING_POLICY"), "slow callback expiry diagnosed");
    }
    try (Harness h = new Harness(false, 64)) {
      h.rig.setMode("front", VisionRig.Mode.FUSION_CANDIDATES);
      h.rig.setPosePolicy("front", new PosePolicy(policy().thresholds(), context -> {
        h.rig.setMode("front", VisionRig.Mode.DISABLED); return Optional.of(CALIBRATION);
      }));
      h.accepted(); check(h.rig.drainPoseEstimates().isEmpty(), "calibration cannot mutate mode mid-evaluation");
      check(h.rig.mode("front") == VisionRig.Mode.FUSION_CANDIDATES, "blocked calibration mutation retains configured mode");
      check(hasDiagnostic(h.rig, "CALIBRATION_CALLBACK_FAILED"), "calibration mutation failure diagnosed");
      h.rig.setPosePolicy("front", new PosePolicy(policy().thresholds(), context -> {
        h.rig.clearPosePolicy("front"); return Optional.of(CALIBRATION);
      }));
      h.next(44, 2, 4, 5); check(h.rig.drainPoseEstimates().isEmpty(), "calibration cannot remove trust during evaluation");
      check(hasDiagnostic(h.rig, "CALIBRATION_CALLBACK_FAILED"), "trust mutation failure diagnosed");
    }
  }
  private static void advancingClock() {
    long[] now = {BASE + 1_000_000L};
    java.util.function.LongSupplier advancing = () -> now[0] += 1_000L;
    FakeQueue queue = new FakeQueue();
    try (VisionRig rig = new VisionRig(List.of(new CameraInput(FRONT, queue, () -> synchronization(advancing.getAsLong()))),
        new ClockMapper("api-synthetic-robot"), advancing,
        new VisionRig.Bounds(new VisionClient.Limits(1, 8, 8, 16, 16, 0), 16, 16))) {
      rig.setMode("front", VisionRig.Mode.FUSION_CANDIDATES); rig.setPosePolicy("front", policy());
      queue.publish(bytes(42, 0, BASE), now[0], now[0]); rig.periodic();
      now[0] = BASE + 3_000_000L; queue.publish(bytes(43, 1, BASE + 2_000_000L), now[0], now[0]); rig.periodic();
      check(rig.sourceStatuses().get(FRONT).fusionEligible(), "fence expiry checks resample advancing production clock before status reads");
      check(rig.latestPoseForDiagnostics("front").isPresent(), "advancing clock diagnostic read retains live estimate");
      check(rig.drainPoseEstimates().size() == 1, "advancing clock drain preserves one valid estimate");
      check(rig.drainObservations().size() == 1, "advancing clock raw drain shares current eligibility fence");
      check(rig.counters().pendingObservations() == 0, "advancing clock counters do not regress client time");
      rig.periodic(); check(rig.drainPoseEstimates().isEmpty(), "advancing clock quiet loop never repeats pose");
    }
  }
  private static void deliveryGate() {
    try (Harness h = new Harness(false, 64)) {
      VisionClient.DeliveryGate gate = h.rig.observationDeliveryGate();
      int[] deliveries = {0};
      h.rig.setObservationConsumer((observation, now) -> {
        check(gate.isDeliverable(observation, now), "origin gate is safe and live inside exact observation callback");
        check(h.rig.observationDeliveryGate().isDeliverable(observation, now), "callback may obtain read-only gate without reentrancy mutation");
        deliveries[0]++;
      });
      h.accepted(); check(deliveries[0] == 1, "callback origin gate permits one newly delivered observation");
    }
    try (Harness h = new Harness(false, 1)) {
      h.rig.setMode("front", VisionRig.Mode.FUSION_CANDIDATES); h.rig.setPosePolicy("front", policy()); h.accepted();
      VisionClient.Measurement old = h.rig.drainObservations().get(0);
      VisionClient.DeliveryGate gate = h.rig.observationDeliveryGate();
      check(gate.isDeliverable(old, h.clock[0]), "live retained envelope remains origin-eligible before output loss");
      h.next(44, 2, 4, 5);
      check(!gate.isDeliverable(old, h.clock[0]), "facade output overload invalidates actual client generation");
      check(h.rig.drainPoseEstimates().isEmpty(), "overload removes previous pending pose");
      h.next(45, 3, 6, 7);
      VisionClient.Measurement fresh = h.rig.drainObservations().get(0);
      check(gate.isDeliverable(fresh, h.clock[0]) && !gate.isDeliverable(old, h.clock[0]),
          "fresh recovery does not revive old envelope after facade output loss");
      h.rig.setMode("front", VisionRig.Mode.DISABLED);
      check(!gate.isDeliverable(fresh, h.clock[0]), "source disabled mode gates retained bridge envelope");
      h.rig.setMode("front", VisionRig.Mode.OBSERVATION_ONLY);
      check(!gate.isDeliverable(fresh, h.clock[0]), "mode re-enable boundary cannot revive admitted bridge envelope");
    }
    try (Harness h = new Harness(false, 64); Harness other = new Harness(false, 64)) {
      h.accepted(); other.accepted();
      VisionClient.Measurement foreign = other.rig.drainObservations().get(0);
      check(!h.rig.observationDeliveryGate().isDeliverable(foreign, h.clock[0]), "configured identity alone cannot fabricate originating-client delivery");
    }
  }
  private static void checkReject(String description, UnaryOperator<String> mutation, PosePolicy policy, String reason) {
    try (Harness h = new Harness(false, 64)) {
      h.rig.setMode("front", VisionRig.Mode.FUSION_CANDIDATES); h.rig.setPosePolicy("front", policy); h.prime();
      h.publish(h.front, 43, 1, 2, 3, mutation); h.rig.periodic();
      check(h.rig.drainPoseEstimates().isEmpty(), description + " rejects fusion output");
      check(hasDiagnostic(h.rig, reason), description + " rejection diagnosed as " + reason);
    }
  }
  private static boolean hasDiagnostic(VisionRig rig, String reason) { return rig.drainDiagnostics().stream().anyMatch(value -> value.reason().equals(reason)); }
  private static PosePolicy policy() {
    return new PosePolicy(new PosePolicy.Thresholds(Set.of(PoseEstimate.Method.SINGLE_TAG_PNP, PoseEstimate.Method.MULTITAG_PNP),
        1, 2, OptionalDouble.of(.2), OptionalDouble.empty(), OptionalDouble.empty()), context -> Optional.of(CALIBRATION));
  }
  private static PosePolicy distancePolicy() {
    return new PosePolicy(new PosePolicy.Thresholds(Set.of(PoseEstimate.Method.SINGLE_TAG_PNP),
        1, 2, OptionalDouble.empty(), OptionalDouble.of(5), OptionalDouble.of(10)), context -> Optional.of(CALIBRATION));
  }
  private static String bytes(long frame, long sequence, long capture) {
    return fixture.replace("\"frame_id\":42", "\"frame_id\":" + frame)
        .replace("\"packet_seq\":16", "\"packet_seq\":" + sequence)
        .replace("\"capture_server_us\":1234569890123", "\"capture_server_us\":" + capture / 1000);
  }
  private static SyncSnapshot synchronization(long now) {
    return new SyncSnapshot(TimeVersion.WPILIB_2026_MICROSECONDS, OptionalLong.of(0),
        "api-synthetic-robot", true, 0, 0, now, 0, "synthetic matched epochs, not physical verification");
  }
  private static void check(boolean condition, String detail) { checks++; if (!condition) throw new AssertionError(detail); }
  private static void expect(Runnable action, Class<? extends Throwable> expected) {
    checks++;
    try { action.run(); }
    catch (Throwable problem) { if (expected.isInstance(problem)) return; throw new AssertionError("wrong failure", problem); }
    throw new AssertionError("expected " + expected.getSimpleName());
  }
}
