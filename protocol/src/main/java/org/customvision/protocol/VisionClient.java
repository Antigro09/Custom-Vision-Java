package org.customvision.protocol;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.LongSupplier;

/** Bounded coherent packet reader with one robot-loop owner and consume-once measurements. */
public final class VisionClient implements AutoCloseable {
  public record Input(SourceKey source, RawQueue queue, Supplier<SyncSnapshot> sync) {
    public Input { Objects.requireNonNull(source); Objects.requireNonNull(queue); Objects.requireNonNull(sync); }
  }
  public record Limits(int maxSources, int packetsPerSource, int packetsPerCycle,
      int outputDepth, int reorderDepth, long reorderWindowNs) {
    public Limits {
      if (maxSources < 1 || maxSources > 64 || packetsPerSource < 1 || packetsPerSource > 4096
          || packetsPerCycle < 1 || packetsPerCycle > 4096 || outputDepth < 1 || outputDepth > 4096
          || reorderDepth < 1 || reorderDepth > 4096 || reorderWindowNs < 0)
        throw new IllegalArgumentException("invalid client bounds");
    }
    public static Limits defaults() { return new Limits(8, 16, 64, 64, 128, 30_000_000L); }
  }
  /** Validated capture-relative packet; mapping may be rejected, but receipt has no estimator effect. */
  public record Receipt(Packet packet, TransportSample transport, ClockMapper.Result clock) {}
  @FunctionalInterface
  public interface DeliveryGate {
    /** Validates only delivery freshness/provenance; it never admits, projects, fuses or moves. */
    boolean isDeliverable(Measurement observation, long nowRobotNs);
  }
  /**
   * An immutable admitted observation, emitted only by this client's consume-once channel.
   * The actual lifecycle and clock results are retained; consumers never reconstruct acceptance.
   * Admission is historical provenance, not perpetual freshness or authority to move/fuse.
   */
  public static final class Measurement {
    private final Packet packet;
    private final TransportSample transport;
    private final SourceSession.Result admission;
    private final ClockMapper.Result clock;
    private final Object clientIdentity;
    private final long sourceGeneration;

    private Measurement(Packet packet, TransportSample transport,
        SourceSession.Result admission, ClockMapper.Result clock, Object clientIdentity, long sourceGeneration) {
      this.packet = Objects.requireNonNull(packet);
      this.transport = Objects.requireNonNull(transport);
      this.admission = Objects.requireNonNull(admission);
      this.clock = Objects.requireNonNull(clock);
      this.clientIdentity = Objects.requireNonNull(clientIdentity);
      this.sourceGeneration = sourceGeneration;
      if (admission.kind() != SourceSession.Kind.ACCEPTED
          || admission.newlyAcceptedMeasurement().orElse(null) != packet || !clock.accepted())
        throw new IllegalArgumentException("measurement requires this exact admitted packet and accepted clock");
      ClockMapper.MappedCapture capture = clock.capture().orElseThrow();
      if (capture.rawCaptureServerUs() != packet.captureServerUs()
          || capture.rawNtTimestamp() != transport.ntTimestamp()
          || capture.rawNtServerTime() != transport.ntServerTime()
          || capture.firstObservedRobotNs() != transport.firstObservedRobotNs()
          || capture.dequeueRobotNs() != transport.dequeueRobotNs()
          || capture.connectionEpoch() != transport.connectionEpoch())
        throw new IllegalArgumentException("measurement clock/transport provenance differs");
    }
    public Packet packet() { return packet; }
    public TransportSample transport() { return transport; }
    public SourceSession.Result admission() { return admission; }
    public ClockMapper.Result clock() { return clock; }
    public ClockMapper.MappedCapture capture() { return clock.capture().orElseThrow(); }
  }
  public record Rejection(SourceKey source, String stage, String reason, String location) {}
  public record Counters(long decoded, long decodeRejected, long overloads, long timeRejected,
      long lifecycleRejected, long rejectedDiagnosticsLost, int pendingReceipts, int pendingMeasurements,
      CaptureReorderBuffer.Counters reorder) {}
  private static final class State {
    final Input input;
    final SourceSession session;
    long generation;
    boolean generationActive;
    State(Input input) { this.input = input; session = new SourceSession(input.source()); }
  }
  private final ProtocolDecoder decoder;
  private final ClockMapper mapper;
  private final Limits limits;
  private final LongSupplier robotClock;
  private final Object clientIdentity = new Object();
  private final List<State> states;
  private final CaptureReorderBuffer<Measurement> reorder;
  private final ArrayDeque<Receipt> receipts = new ArrayDeque<>();
  private final ArrayDeque<Measurement> measurements = new ArrayDeque<>();
  private final ArrayDeque<Rejection> rejections = new ArrayDeque<>();
  private Thread owner;
  private long lastNow = -1;
  private boolean closed;
  private int nextSource;
  private long decoded, decodeRejected, overloads, timeRejected, lifecycleRejected, rejectionLost;

  /** Explicit cycle-time replay mode. Production NT readers must use the clock-supplied constructor. */
  public VisionClient(List<Input> inputs, ProtocolDecoder decoder, ClockMapper mapper, Limits limits) {
    this(inputs, decoder, mapper, limits, null);
  }
  /** The clock must share the verified robot monotonic epoch supplied to queues and SyncSnapshot. */
  public VisionClient(List<Input> inputs, ProtocolDecoder decoder, ClockMapper mapper, Limits limits,
      LongSupplier robotClock) {
    this.robotClock = robotClock;
    this.decoder = Objects.requireNonNull(decoder); this.mapper = Objects.requireNonNull(mapper);
    this.limits = Objects.requireNonNull(limits); Objects.requireNonNull(inputs);
    if (inputs.isEmpty() || inputs.size() > limits.maxSources()) throw new IllegalArgumentException("source limit");
    Map<SourceKey, State> unique = new LinkedHashMap<>();
    for (Input input : inputs) if (unique.putIfAbsent(input.source(), new State(input)) != null)
      throw new IllegalArgumentException("duplicate configured source");
    states = List.copyOf(unique.values());
    reorder = new CaptureReorderBuffer<>(limits.reorderDepth(), limits.reorderWindowNs(), 2_000_000L);
  }

  /** Called every robot loop, including quiet loops. Parsing and status ordering never run on an NT thread. */
  public void poll(long nowRobotNs) {
    own(nowRobotNs);
    for (State state : states) {
      expireState(state, nowRobotNs);
    }
    int budget = limits.packetsPerCycle();
    int visited = 0;
    for (int checked = 0; checked < states.size() && budget > 0; checked++) {
      State state = states.get((nextSource + checked) % states.size());
      int allowance = Math.min(limits.packetsPerSource(), budget);
      visited++;
      RawQueue.Batch batch = state.input.queue().read(allowance);
      nowRobotNs = refreshNow(nowRobotNs);
      budget -= allowance; // Reserve work even when a native queue reports saturation.
      if (batch.overload() || batch.samples().size() > allowance) {
        overloaded(state, nowRobotNs, "input_queue"); continue;
      }
      for (TransportSample sample : batch.samples()) process(state, sample, nowRobotNs);
    }
    nextSource = (nextSource + visited) % states.size();
    nowRobotNs = refreshNow(nowRobotNs);
    for (State state : states) {
      expireState(state, nowRobotNs);
    }
    expireMeasurements(nowRobotNs);
    for (CaptureReorderBuffer.Entry<Measurement> entry : reorder.drain(nowRobotNs,
        Math.min(limits.packetsPerCycle(), limits.reorderDepth()))) {
      if (!find(entry.source()).session.status().actionable()) continue;
      if (stale(entry.value(), nowRobotNs)) { staleRejected(entry.value()); continue; }
      if (measurements.size() == limits.outputDepth()) {
        State state = find(entry.source()); overloaded(state, nowRobotNs, "measurement_output");
      } else measurements.addLast(entry.value());
    }
  }

  private void process(State state, TransportSample sample, long now) {
    now = refreshNow(now);
    state.session.observeActivity(sample, now);
    Packet packet;
    try { packet = decoder.decode(state.input.source(), sample.payload()); decoded++; }
    catch (DecodeException exception) {
      now = refreshNow(now);
      decodeRejected++; state.session.parseFailure(now); purge(state.input.source());
      reject(new Rejection(state.input.source(), "decode", exception.reason().name(), exception.path())); return;
    }
    now = refreshNow(now);
    SourceSession.Status before = state.session.status();
    SourceSession.Result result = state.session.accept(packet, sample, now);
    SourceSession.Status after = state.session.status();
    if (!before.bootId().equals(after.bootId()) || !before.revision().equals(after.revision())
        || before.connectionEpoch() != after.connectionEpoch() || !after.actionable()) purge(packet.source());
    if (lostFamily(before, after)) purgeMeasurements(packet.source());
    // A clearing publication is applied before observation dedup, including same-frame tombstones.
    if (result.kind() == SourceSession.Kind.ACCEPTED && !packet.usable()) purge(packet.source());
    if (result.kind() == SourceSession.Kind.REJECTED) {
      lifecycleRejected++; reject(new Rejection(packet.source(), "lifecycle", result.reason().name(), "$"));
    }
    if (result.newlyAcceptedMeasurement().isEmpty()) return;
    ClockMapper.Result clock;
    try {
      SyncSnapshot snapshot = Objects.requireNonNull(state.input.sync().get(), "synchronization provider returned null");
      now = refreshNow(now); // Sync retrieval can also observe a later robot time.
      clock = mapper.map(packet, sample, snapshot, now);
    } catch (RuntimeException exception) {
      state.session.rejectCaptureMapping(); purgeMeasurements(packet.source()); timeRejected++;
      reject(new Rejection(packet.source(), "clock_binding", "CLOCK_BINDING_FAILED", "$.capture_server_us"));
      throw new IllegalStateException("synchronization/clock binding failed for " + packet.source(), exception);
    }
    if (receipts.size() == limits.outputDepth()) { overloaded(state, now, "receipt_output"); return; }
    receipts.addLast(new Receipt(packet, sample, clock));
    if (!clock.accepted()) {
      state.session.rejectCaptureMapping(); purgeMeasurements(packet.source()); timeRejected++;
      reject(new Rejection(packet.source(), "clock", clock.rejection().orElseThrow().reason().name(), "$.capture_server_us"));
      return;
    }
    ClockMapper.MappedCapture capture = clock.capture().orElseThrow();
    if (!state.session.recordCaptureMapping(packet, capture.robotCaptureNs(), now, mapper.config().maxCaptureAgeNs())) {
      purgeMeasurements(packet.source()); timeRejected++;
      reject(new Rejection(packet.source(), "capture_state", "SOURCE_CAPTURE_STATE_REJECTED", "$.capture_server_us"));
      return;
    }
    state.generationActive = true;
    Measurement measurement = new Measurement(packet, sample, result, clock, clientIdentity, state.generation);
    String identity = observationIdentity(packet);
    CaptureReorderBuffer.Offer offered = reorder.offer(new CaptureReorderBuffer.Entry<>(
        identity, packet.source(), capture.robotCaptureNs(), measurement), now);
    if (offered == CaptureReorderBuffer.Offer.OVERLOAD) overloaded(state, now, "capture_reorder");
    else if (offered != CaptureReorderBuffer.Offer.ACCEPTED)
      reject(new Rejection(packet.source(), "capture_order", offered.name(), "$.capture_server_us"));
  }

  private static String observationIdentity(Packet packet) {
    // Hash length-prefixed UTF-8 components to bound keys and avoid separator collisions.
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (String value : List.of(packet.source().namespace(), packet.source().pipeline(),
          packet.source().type(), packet.bootId(), Long.toString(packet.frameId()))) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("required SHA-256 unavailable", exception); }
  }

  private void expireState(State state, long now) {
    state.session.expire(now);
    SourceSession.Status status = state.session.status();
    if (status.captureAgeNs().isPresent() && status.captureAgeNs().getAsLong() > mapper.config().maxCaptureAgeNs())
      state.session.rejectCaptureMapping();
    if (!state.session.status().actionable()) purge(state.input.source());
  }
  private boolean stale(Measurement value, long now) {
    long capture = value.capture().robotCaptureNs();
    return capture <= now && now - capture > mapper.config().maxCaptureAgeNs();
  }
  private void staleRejected(Measurement value) {
    timeRejected++;
    reject(new Rejection(value.packet().source(), "measurement_lifetime", "STALE_CAPTURE", "$.capture_server_us"));
  }
  private void expireMeasurements(long now) {
    measurements.removeIf(value -> { if (stale(value, now)) { staleRejected(value); return true; } return false; });
  }
  private void overloaded(State state, long now, String stage) {
    overloads++; state.session.overload(now); purge(state.input.source());
    reject(new Rejection(state.input.source(), stage, "OVERLOAD", "$"));
  }
  private void purge(SourceKey source) {
    purgeMeasurements(source);
    receipts.removeIf(value -> value.packet().source().equals(source));
  }
  private void purgeMeasurements(SourceKey source) {
    State state = find(source);
    // Fence copies already drained into a facade/consumer queue. Repeated quiet-cycle expiry
    // does not keep advancing the generation once this interval has been invalidated.
    if (state.generationActive) {
      state.generation = Math.incrementExact(state.generation);
      state.generationActive = false;
    }
    reorder.removeSource(source);
    measurements.removeIf(value -> value.packet().source().equals(source));
  }
  private static boolean lostFamily(SourceSession.Status before, SourceSession.Status after) {
    if (before.currentPacket().isEmpty() || after.currentPacket().isEmpty()) return false;
    Packet previous = before.currentPacket().orElseThrow(), current = after.currentPacket().orElseThrow();
    for (String family : List.of("localization", "poi", "objects"))
      if (previous.familyValid(family) && !current.familyValid(family)) return true;
    return !previous.detections().isEmpty() && current.detections().isEmpty();
  }
  private void reject(Rejection rejection) {
    if (rejections.size() == limits.outputDepth()) { rejections.removeFirst(); rejectionLost++; }
    rejections.addLast(rejection);
  }
  private State find(SourceKey source) {
    return states.stream().filter(state -> state.input.source().equals(source)).findFirst().orElseThrow();
  }
  /** Source-specific connection notification; global server isConnected is not a Jetson liveness test. */
  public void disconnect(SourceKey source, long connectionEpoch, long nowRobotNs) {
    own(nowRobotNs); find(source).session.disconnect(connectionEpoch, nowRobotNs); purge(source);
  }
  /** Report lost downstream handoff/output. Clear this source until a fresh advancing packet arrives. */
  public void reportDeliveryOverload(SourceKey source, long nowRobotNs) {
    nowRobotNs = refreshNow(nowRobotNs);
    overloaded(find(Objects.requireNonNull(source)), nowRobotNs, "downstream_output");
  }
  /** Raw accepted receipts for diagnostics. These are not insertion requests. */
  public List<Receipt> drainReceipts() { own(lastNow); return drain(receipts); }
  public List<Measurement> drainMeasurements() { return drainMeasurements(Math.max(0, lastNow)); }
  public List<Measurement> drainMeasurements(long nowRobotNs) {
    nowRobotNs = refreshNow(nowRobotNs);
    for (State state : states) expireState(state, nowRobotNs);
    expireMeasurements(nowRobotNs);
    return drain(measurements);
  }
  /**
   * Revalidate a retained envelope before a facade/consumer delivers it. A status can invalidate
   * an output and then recover in one poll; final source status alone cannot prove its validity.
   * This does not emit another measurement or refresh any age, and does not authorize motion/fusion.
   */
  public boolean isDeliverable(Measurement observation, long nowRobotNs) {
    Objects.requireNonNull(observation);
    nowRobotNs = refreshNow(nowRobotNs);
    if (observation.clientIdentity != clientIdentity) return false;
    State state = find(observation.packet().source());
    expireState(state, nowRobotNs);
    SourceSession.Status status = state.session.status();
    return state.generationActive && observation.sourceGeneration == state.generation
        && status.actionable() && status.fusionEligible()
        && status.bootId().orElse("").equals(observation.packet().bootId())
        && status.revision().orElse("").equals(observation.packet().revision())
        && status.connectionEpoch() == observation.transport().connectionEpoch()
        && nowRobotNs >= observation.capture().robotCaptureNs() && !stale(observation, nowRobotNs);
  }
  public List<Rejection> drainRejections() { own(lastNow); return drain(rejections); }
  private static <T> List<T> drain(ArrayDeque<T> queue) {
    List<T> output = new ArrayList<>(queue); queue.clear(); return List.copyOf(output);
  }
  public Map<SourceKey, SourceSession.Status> statuses(long nowRobotNs) {
    own(nowRobotNs); Map<SourceKey, SourceSession.Status> output = new LinkedHashMap<>();
    for (State state : states) {
      expireState(state, nowRobotNs);
      SourceSession.Status status = state.session.status();
      if (!status.actionable()) purge(state.input.source());
      output.put(state.input.source(), status);
    }
    return Map.copyOf(output);
  }
  public Counters counters() {
    own(lastNow); return new Counters(decoded, decodeRejected, overloads, timeRejected,
        lifecycleRejected, rejectionLost, receipts.size(), measurements.size(), reorder.counters());
  }
  public void poll() {
    if (robotClock == null) throw new IllegalStateException("poll() requires the production robot clock constructor");
    poll(robotClock.getAsLong());
  }
  private long refreshNow(long fallback) {
    long now = robotClock == null ? fallback : robotClock.getAsLong();
    own(now); return now;
  }

  private void own(long now) {
    if (closed) throw new IllegalStateException("client closed");
    Thread current = Thread.currentThread();
    if (owner == null) owner = current;
    if (owner != current) throw new IllegalStateException("one robot-loop owner required");
    if (now < 0 || now < lastNow) throw new IllegalArgumentException("robot clock regressed");
    lastNow = now;
  }
  /** Closes owned queue/subscriber resources only. Shared NetworkTableInstance ownership stays with robot. */
  @Override public void close() {
    if (closed) return;
    own(Math.max(0, lastNow)); RuntimeException failure = null;
    for (State state : states) try { state.input.queue().close(); }
    catch (RuntimeException exception) { if (failure == null) failure = exception; else failure.addSuppressed(exception); }
    receipts.clear(); measurements.clear(); rejections.clear(); reorder.clearPending(); closed = true;
    if (failure != null) throw failure;
  }
}
