package org.customvision.wpilib2026;

import edu.wpi.first.networktables.PubSubOption;
import edu.wpi.first.networktables.NetworkTableInstance;
import edu.wpi.first.networktables.StringSubscriber;
import edu.wpi.first.networktables.TimestampedString;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.customvision.protocol.RawQueue;
import org.customvision.protocol.TransportSample;
import org.customvision.protocol.TimeVersion;

/** Coherent result-topic polling. All reads and epoch changes belong to one robot-loop thread.
 * NT metadata is microseconds; the supplied clock must be the verified robot monotonic epoch in ns.
 * firstObservedRobotNs observes readQueue(), not native ingress. No estimator is touched here. */
public final class NtResultQueue implements RawQueue {
  public record Settings(double periodicSeconds, int pollStorage, int handoffDepth) {
    public Settings {
      if (!Double.isFinite(periodicSeconds) || periodicSeconds < .005 || periodicSeconds > 1
          || pollStorage < 2 || pollStorage > 1024 || handoffDepth < 1 || handoffDepth > 1024)
        throw new IllegalArgumentException("period [0.005,1] s; poll [2,1024]; handoff [1,1024]");
    }
    public static Settings defaults() { return new Settings(.01, 32, 64); }
  }
  private record Observed(TimestampedString value, long firstObservedNs) {}
  private final Supplier<TimestampedString[]> reader;
  private final Runnable closeSubscriber;
  private final Supplier<OptionalLong> offset;
  private final BooleanSupplier instanceConnected;
  private final LongSupplier robotNanoClock;
  private final Settings settings;
  private final ArrayDeque<Observed> handoff = new ArrayDeque<>();
  private Thread owner;
  private long connectionEpoch;
  private long overloadCount;
  private long discardedAtLeast;
  private boolean closed;

  public NtResultQueue(NetworkTableInstance sharedInstance, String resultTopic,
      LongSupplier robotNanoClock, Settings settings) {
    this(subscribe(sharedInstance, resultTopic, settings, robotNanoClock), sharedInstance::getServerTimeOffset,
        sharedInstance::isConnected, robotNanoClock, settings);
  }
  private NtResultQueue(StringSubscriber subscriber, Supplier<OptionalLong> offset,
      BooleanSupplier connected, LongSupplier robotNanoClock, Settings settings) {
    this(subscriber::readQueue, subscriber::close, offset, connected, robotNanoClock, settings);
  }
  // An injectable queue seam keeps overload/order/ownership tests independent of native NT.
  NtResultQueue(Supplier<TimestampedString[]> reader, Runnable closeSubscriber,
      Supplier<OptionalLong> offset, BooleanSupplier instanceConnected,
      LongSupplier robotNanoClock, Settings settings) {
    this.reader = Objects.requireNonNull(reader);
    this.closeSubscriber = Objects.requireNonNull(closeSubscriber);
    this.offset = Objects.requireNonNull(offset);
    this.instanceConnected = Objects.requireNonNull(instanceConnected);
    this.robotNanoClock = Objects.requireNonNull(robotNanoClock);
    this.settings = Objects.requireNonNull(settings);
  }
  private static StringSubscriber subscribe(NetworkTableInstance instance, String topic,
      Settings settings, LongSupplier robotNanoClock) {
    Objects.requireNonNull(instance);
    Objects.requireNonNull(robotNanoClock);
    Objects.requireNonNull(settings);
    if (topic == null || topic.length() > 512 || !topic.startsWith("/")
        || !topic.endsWith("/result") || topic.contains("//") || topic.indexOf('\0') >= 0)
      throw new IllegalArgumentException("an absolute configured /result topic is required");
    return instance.getStringTopic(topic).subscribe("", PubSubOption.periodic(settings.periodicSeconds()),
        PubSubOption.sendAll(true), PubSubOption.keepDuplicates(true), PubSubOption.pollStorage(settings.pollStorage()));
  }
  @Override public Batch read(int maxPackets) {
    own();
    if (maxPackets < 1 || maxPackets > 1024) throw new IllegalArgumentException("maxPackets [1,1024]");
    TimestampedString[] values = Objects.requireNonNull(reader.get());
    // readQueue has no dropped-update counter. A full polling queue might already have lost values.
    // Conservative saturation drops the WHOLE batch and prior handoff; fresh input must recover.
    if (values.length >= settings.pollStorage()
        || values.length > settings.handoffDepth() - handoff.size()) {
      discardedAtLeast = Math.addExact(discardedAtLeast, (long) values.length + handoff.size());
      handoff.clear();
      overloadCount = Math.incrementExact(overloadCount);
      return new Batch(List.of(), true);
    }
    long observedNs = now();
    for (TimestampedString value : values) handoff.addLast(new Observed(Objects.requireNonNull(value), observedNs));
    int count = Math.min(maxPackets, handoff.size());
    List<TransportSample> output = new ArrayList<>(count);
    long dequeueNs = now();
    for (int i = 0; i < count; i++) {
      Observed observed = handoff.removeFirst();
      TimestampedString value = observed.value();
      output.add(new TransportSample(value.value, value.timestamp, value.serverTime,
          observed.firstObservedNs(), dequeueNs, connectionEpoch));
    }
    return new Batch(output, false);
  }
  /** Explicit local epoch boundary; caller also clears source state. This is not source liveness.
   * A retained value arriving later still needs advancing-publication eligibility in the protocol. */
  public long advanceConnectionEpoch() {
    own();
    connectionEpoch = Math.incrementExact(connectionEpoch);
    handoff.clear();
    reader.get(); // Discard currently queued values from the prior local connection epoch.
    return connectionEpoch;
  }
  /** Raw server-minus-local offset, microseconds. Add to local time to obtain server time. */
  public OptionalLong rawServerTimeOffset() { own(); return offset.get(); }
  /** Aggregate connectivity is diagnostic only: another peer can keep this true. */
  public boolean instanceConnectedForDiagnostics() { return instanceConnected.getAsBoolean(); }
  public TimeVersion timeVersion() { return TimeVersion.WPILIB_2026_MICROSECONDS; }
  public long connectionEpoch() { return connectionEpoch; }
  public long overloadCount() { return overloadCount; }
  public long discardedPacketsAtLeast() { return discardedAtLeast; }
  public int pendingPackets() { return handoff.size(); }
  private long now() {
    long result = robotNanoClock.getAsLong();
    if (result < 0) throw new IllegalStateException("robot monotonic clock must be nonnegative");
    return result;
  }
  private synchronized void own() {
    if (closed) throw new IllegalStateException("subscriber closed");
    Thread current = Thread.currentThread();
    if (owner == null) owner = current;
    else if (owner != current) throw new IllegalStateException("one robot-loop owner required");
  }
  @Override public void close() {
    if (closed) return;
    own();
    closed = true;
    handoff.clear();
    closeSubscriber.run(); // Never close the supplied shared NetworkTableInstance.
  }
}
