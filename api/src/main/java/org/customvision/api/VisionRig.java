package org.customvision.api;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;
import org.customvision.protocol.ClockMapper;
import org.customvision.protocol.Packet;
import org.customvision.protocol.ProtocolDecoder;
import org.customvision.protocol.SourceKey;
import org.customvision.protocol.SourceSession;
import org.customvision.protocol.VisionClient;

/**
 * Captain-facing transport/lifecycle facade. Call periodic every robot cycle, then drain once.
 * All cameras share one global capture-order window. No estimator, drive port or World-State
 * dependency is owned here; field projection and persistent tracking belong to the consumer.
 */
public final class VisionRig implements AutoCloseable {
  public enum Mode { DISABLED, OBSERVATION_ONLY, FUSION_CANDIDATES }
  @FunctionalInterface
  public interface AdmittedObservationConsumer {
    /** Actual decoder/session/clock admitted envelope; invoked once on the owning robot loop. */
    void accept(VisionClient.Measurement observation, long nowRobotNs);
  }
  public record Bounds(VisionClient.Limits clientLimits, int outputDepth, int diagnosticDepth) {
    public Bounds {
      Objects.requireNonNull(clientLimits);
      if (outputDepth < 1 || outputDepth > 4096 || diagnosticDepth < 1 || diagnosticDepth > 4096)
        throw new IllegalArgumentException("facade queue bounds [1,4096]");
    }
    public static Bounds defaults() { return new Bounds(VisionClient.Limits.defaults(), 64, 64); }
  }
  public record Diagnostic(Optional<CameraConfig> camera, String stage, String reason) {}
  public record Counters(long deliveredObservations, long deliveredPoseEstimates, long facadeOverloads,
      long lostDiagnostics, int pendingObservations, int pendingPoseEstimates, VisionClient.Counters client) {}
  private static final class CameraState {
    final CameraConfig config;
    Mode mode = Mode.OBSERVATION_ONLY;
    PosePolicy policy;
    PoseEstimate latest;
    Optional<Packet.ObservationId> modeFence = Optional.empty();
    Optional<Packet.ObservationId> poseFence = Optional.empty();
    Optional<Packet.ObservationId> observationBindingFence = Optional.empty();
    CameraState(CameraConfig config) { this.config = config; }
  }
  private final Map<String, CameraState> cameras = new LinkedHashMap<>();
  private final Map<SourceKey, CameraState> sources = new LinkedHashMap<>();
  private final VisionClient client;
  private final LongSupplier robotClock;
  private final Bounds bounds;
  private final ArrayDeque<VisionClient.Measurement> observations = new ArrayDeque<>();
  private final ArrayDeque<PoseEstimate> estimates = new ArrayDeque<>();
  private final ArrayDeque<Diagnostic> diagnostics = new ArrayDeque<>();
  private AdmittedObservationConsumer consumer;
  private Thread owner;
  private long lastNow = -1;
  private boolean closed;
  private boolean dispatching;
  private long deliveredObservations, deliveredEstimates, overloads, lostDiagnostics;

  public VisionRig(List<CameraInput> inputs, ClockMapper mapper, LongSupplier robotClock) {
    this(inputs, mapper, robotClock, Bounds.defaults());
  }
  /** Queues transfer ownership to this rig after construction succeeds. */
  public VisionRig(List<CameraInput> inputs, ClockMapper mapper, LongSupplier robotClock, Bounds bounds) {
    Objects.requireNonNull(inputs); Objects.requireNonNull(mapper);
    this.robotClock = Objects.requireNonNull(robotClock, "verified robot monotonic clock required");
    this.bounds = Objects.requireNonNull(bounds);
    List<VisionClient.Input> configured = new ArrayList<>();
    for (CameraInput input : inputs) {
      CameraState state = new CameraState(input.camera());
      if (cameras.putIfAbsent(state.config.name(), state) != null)
        throw new IllegalArgumentException("duplicate camera name: " + state.config.name());
      if (sources.putIfAbsent(state.config.source(), state) != null)
        throw new IllegalArgumentException("duplicate configured camera source");
      configured.add(new VisionClient.Input(state.config.source(), input.queue(), input.synchronization()));
    }
    client = new VisionClient(configured, new ProtocolDecoder(), mapper, bounds.clientLimits(), robotClock);
  }

  /**
   * Controls outputs only; source status still polls and expires while disabled. A mode change
   * fences packets already admitted, including captures still held in the reorder window.
   * This is an admission boundary, not a claim about unseen packets in NT's native queue.
   */
  public void setMode(String cameraName, Mode mode) {
    own(); requireNotDispatching(); CameraState state = named(cameraName);
    if (state.mode != Objects.requireNonNull(mode)) {
      state.modeFence = acceptedBeforeConfiguration(state); state.mode = mode; clearSource(state);
    }
  }
  public Mode mode(String cameraName) { own(); return named(cameraName).mode; }
  /** Changing trust discards prior outputs; historical measurements are not reprocessed. */
  public void setPosePolicy(String cameraName, PosePolicy policy) {
    own(); requireNotDispatching(); CameraState state = named(cameraName);
    state.policy = Objects.requireNonNull(policy); state.poseFence = acceptedBeforeConfiguration(state); clearPoseSource(state);
  }
  public void clearPosePolicy(String cameraName) {
    own(); requireNotDispatching(); CameraState state = named(cameraName);
    state.poseFence = acceptedBeforeConfiguration(state); state.policy = null; clearPoseSource(state);
  }
  /**
   * An installed callback exclusively owns raw observation delivery. drainObservations is then
   * unavailable, preventing accidental duplicate World-State ingestion. Binding changes discard
   * pending observations. Callback failures are diagnosed and are never automatically replayed.
   */
  public void setObservationConsumer(AdmittedObservationConsumer consumer) {
    own(); requireNotDispatching(); this.consumer = Objects.requireNonNull(consumer, "consumer callback required");
    for (CameraState state : cameras.values()) state.observationBindingFence = acceptedBeforeConfiguration(state);
    observations.clear();
  }
  public void clearObservationConsumer() {
    own(); requireNotDispatching();
    for (CameraState state : cameras.values()) state.observationBindingFence = acceptedBeforeConfiguration(state);
    consumer = null; observations.clear();
  }

  /** Call on every robot loop even if no result strings arrive. Never call from an NT listener. */
  public void periodic() {
    own(); requireNotDispatching();
    client.poll();
    long now = now();
    expire(now);
    // Receipt diagnostics are not a second estimator or tracker insertion path.
    client.drainReceipts();
    for (VisionClient.Rejection rejection : client.drainRejections()) {
      CameraState state = sources.get(rejection.source());
      diagnostic(state, rejection.stage(), rejection.reason());
    }
    now = now(); // Expiry checks may sample the production clock inside the client.
    List<VisionClient.Measurement> accepted = client.drainMeasurements(now);
    java.util.Set<SourceKey> overloadedThisCycle = new java.util.HashSet<>();
    for (VisionClient.Measurement measurement : accepted) {
      now = now();
      CameraState state = sources.get(measurement.packet().source());
      if (state.mode == Mode.DISABLED || overloadedThisCycle.contains(state.config.source())) continue;
      if (!afterFence(measurement, state.modeFence)) continue;
      if (!stillCurrent(measurement, now)) continue;
      // Reserve both bounded queues before running callbacks or publishing a partial group.
      boolean deliverObservation = afterFence(measurement, state.observationBindingFence);
      boolean evaluatePose = state.mode == Mode.FUSION_CANDIDATES && afterFence(measurement, state.poseFence);
      if (deliverObservation && consumer == null && observations.size() == bounds.outputDepth()
          || evaluatePose && estimates.size() == bounds.outputDepth()) {
        overloads++; client.reportDeliveryOverload(state.config.source(), now());
        clearSource(state); overloadedThisCycle.add(state.config.source());
        diagnostic(state, "facade", "OUTPUT_OVERLOAD"); continue;
      }
      PoseEstimate estimate = null;
      if (evaluatePose) {
        if (state.policy == null) diagnostic(state, "pose_policy", "MISSING_POSE_POLICY");
        else {
          PosePolicy.Decision decision;
          dispatching = true;
          try { decision = state.policy.evaluate(state.config, measurement); }
          finally { dispatching = false; }
          if (decision.estimate().isPresent()) estimate = decision.estimate().orElseThrow();
          else diagnostic(state, "pose_policy", decision.rejection().orElseThrow().name());
        }
      }
      now = now();
      if (!stillCurrent(measurement, now)) { diagnostic(state, "facade", "EXPIRED_DURING_POLICY"); continue; }
      if (estimate != null) { estimates.addLast(estimate); state.latest = estimate; }
      if (deliverObservation && consumer == null) observations.addLast(measurement);
      else if (deliverObservation) {
        dispatching = true;
        try { consumer.accept(measurement, now); deliveredObservations++; }
        catch (RuntimeException exception) { diagnostic(state, "consumer", "CALLBACK_FAILED"); }
        finally { dispatching = false; }
      }
    }
    expire(now());
  }

  /** Consume-once raw admitted observations, suitable for the World-State bridge. */
  public List<VisionClient.Measurement> drainObservations() {
    own(); requireNotDispatching();
    if (consumer != null) throw new IllegalStateException("observation callback owns delivery; drainObservations is unavailable");
    expire(now()); List<VisionClient.Measurement> result = drain(observations);
    deliveredObservations += result.size(); return result;
  }
  /** Consume-once field-robot candidates; repeated drains cannot reinsert a pose. */
  public List<PoseEstimate> drainPoseEstimates() {
    own(); requireNotDispatching(); expire(now()); List<PoseEstimate> result = drain(estimates);
    deliveredEstimates += result.size(); return result;
  }
  /** UI/debug snapshot only. Never feed this repeatable getter to addVisionMeasurement. */
  public Optional<PoseEstimate> latestPoseForDiagnostics(String cameraName) {
    own(); expire(now()); return Optional.ofNullable(named(cameraName).latest);
  }
  public Map<CameraConfig, SourceSession.Status> sourceStatuses() {
    own(); long now = now(); expire(now);
    Map<SourceKey, SourceSession.Status> raw = client.statuses(now());
    Map<CameraConfig, SourceSession.Status> result = new LinkedHashMap<>();
    for (CameraState state : cameras.values()) result.put(state.config, raw.get(state.config.source()));
    return Map.copyOf(result);
  }
  public List<Diagnostic> drainDiagnostics() { own(); return drain(diagnostics); }
  public Counters counters() {
    own(); expire(now()); return new Counters(deliveredObservations, deliveredEstimates, overloads, lostDiagnostics,
        observations.size(), estimates.size(), client.counters());
  }
  public List<CameraConfig> cameras() { return cameras.values().stream().map(state -> state.config).toList(); }
  /**
   * Read-only origin delivery gate for the World-State bridge. It is safe to use inside the
   * synchronous observation callback and preserves private client ownership. Admission is
   * historical provenance; this gate rechecks mode/binding boundaries and current lifecycle.
   */
  public VisionClient.DeliveryGate observationDeliveryGate() { own(); return this::isObservationDeliverable; }
  private boolean isObservationDeliverable(VisionClient.Measurement observation, long nowRobotNs) {
    own(); Objects.requireNonNull(observation);
    CameraState state = sources.get(observation.packet().source());
    return state != null && state.mode != Mode.DISABLED && afterFence(observation, state.modeFence)
        && afterFence(observation, state.observationBindingFence) && client.isDeliverable(observation, nowRobotNs);
  }
  /** Caller must notify the specific source; aggregate server connection does not prove liveness. */
  public void disconnect(String cameraName, long connectionEpoch) {
    own(); requireNotDispatching(); CameraState state = named(cameraName);
    client.disconnect(state.config.source(), connectionEpoch, now()); clearSource(state);
  }
  private void expire(long now) {
    Map<SourceKey, SourceSession.Status> statuses = client.statuses(now);
    for (CameraState state : cameras.values()) {
      SourceSession.Status status = statuses.get(state.config.source());
      if (!status.actionable() || !status.fusionEligible()) clearSource(state);
      else {
        boolean fieldRobotPresent = status.currentPacket().flatMap(value -> value.localization())
            .filter(value -> value.valid() && value.fieldRobot().isPresent()).isPresent();
        if (!fieldRobotPresent) clearPoseSource(state);
        else if (state.latest != null && !client.isDeliverable(state.latest.observation(), now)) state.latest = null;
      }
    }
    observations.removeIf(value -> !client.isDeliverable(value, now));
    estimates.removeIf(value -> !client.isDeliverable(value.observation(), now));
  }
  private boolean stillCurrent(VisionClient.Measurement value, long now) {
    return client.isDeliverable(value, now);
  }
  private CameraState named(String name) {
    CameraState state = cameras.get(Objects.requireNonNull(name));
    if (state == null) throw new IllegalArgumentException("camera is not configured: " + name);
    return state;
  }
  private Optional<Packet.ObservationId> acceptedBeforeConfiguration(CameraState state) {
    return client.statuses(now()).get(state.config.source()).lastAcceptedPacket().map(Packet::observationId);
  }
  private static boolean afterFence(VisionClient.Measurement value, Optional<Packet.ObservationId> fence) {
    return fence.isEmpty() || !fence.orElseThrow().bootId().equals(value.packet().bootId())
        || value.packet().frameId() > fence.orElseThrow().frameId();
  }
  private void clearSource(CameraState state) {
    observations.removeIf(value -> value.packet().source().equals(state.config.source())); clearPoseSource(state);
  }
  private void clearPoseSource(CameraState state) {
    estimates.removeIf(value -> value.camera().equals(state.config)); state.latest = null;
  }
  private void diagnostic(CameraState state, String stage, String reason) {
    if (diagnostics.size() == bounds.diagnosticDepth()) { diagnostics.removeFirst(); lostDiagnostics++; }
    diagnostics.addLast(new Diagnostic(Optional.ofNullable(state).map(value -> value.config), stage, reason));
  }
  private static <T> List<T> drain(ArrayDeque<T> queue) {
    List<T> result = List.copyOf(queue); queue.clear(); return result;
  }
  private void requireNotDispatching() {
    if (dispatching) throw new IllegalStateException("mutating or draining rig during its observation callback is forbidden");
  }
  private long now() {
    long now = robotClock.getAsLong();
    if (now < 0 || now < lastNow) throw new IllegalStateException("verified robot monotonic clock regressed");
    lastNow = now; return now;
  }
  private void own() {
    if (closed) throw new IllegalStateException("vision rig closed");
    if (owner == null) owner = Thread.currentThread();
    else if (owner != Thread.currentThread()) throw new IllegalStateException("one robot-loop owner required");
  }
  /** Only owned subscribers are closed; caller's shared NT instance and estimator remain owned by caller. */
  @Override public void close() {
    if (closed) return;
    own(); requireNotDispatching();
    try { client.close(); }
    finally { observations.clear(); estimates.clear(); diagnostics.clear(); cameras.values().forEach(state -> state.latest = null); closed = true; }
  }
}
