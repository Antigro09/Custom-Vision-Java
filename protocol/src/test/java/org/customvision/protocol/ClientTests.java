package org.customvision.protocol;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.OptionalLong;

/** Synthetic loop/replay integration using pinned producer bytes as the mutation base. */
public final class ClientTests {
  private ClientTests() {}
  private static int assertions;
  private static final long CAPTURE = 1_234_569_890_123_000L;
  private static final SourceKey A = new SourceKey("/test/a/front_tags", "front_tags", "apriltag");
  private static final SourceKey B = new SourceKey("/test/b/front_tags", "front_tags", "apriltag");
  private static String template;
  private static final class FakeQueue implements RawQueue {
    final ArrayDeque<TransportSample> pending = new ArrayDeque<>();
    boolean overloaded, closed;
    java.util.function.LongSupplier observingClock;
    int calls;
    void add(long frame, long seq, long capture, long publication) {
      String json = template.replace("\"frame_id\":42", "\"frame_id\":" + frame)
          .replace("\"packet_seq\":16", "\"packet_seq\":" + seq)
          .replace("\"capture_server_us\":1234569890123", "\"capture_server_us\":" + capture / 1000);
      addJson(json, publication);
    }
    void addJson(String json, long now) { pending.add(new TransportSample(json, now / 1000, now / 1000, now, now, 0)); }
    @Override public Batch read(int max) {
      calls++;
      if (overloaded) { overloaded = false; pending.clear(); return new Batch(List.of(), true); }
      java.util.ArrayList<TransportSample> output = new java.util.ArrayList<>();
      while (output.size() < max && !pending.isEmpty()) output.add(pending.remove());
      if (observingClock != null) {
        long observed = observingClock.getAsLong();
        for (int i = 0; i < output.size(); i++) {
          TransportSample value = output.get(i);
          output.set(i, new TransportSample(value.payload(), value.ntTimestamp(), value.ntServerTime(), observed, observed, value.connectionEpoch()));
        }
      }
      return new Batch(output, false);
    }
    @Override public void close() { closed = true; }
  }
  private static SyncSnapshot sync(long now) {
    return new SyncSnapshot(TimeVersion.WPILIB_2026_MICROSECONDS, OptionalLong.of(0),
        "synthetic-robot", true, 0, 0, now, 0, "synthetic matched NT and robot epochs");
  }
  private static VisionClient client(FakeQueue a, FakeQueue b, int output) {
    List<VisionClient.Input> inputs = b == null ? List.of(new VisionClient.Input(A, a, () -> sync(CAPTURE)))
        : List.of(new VisionClient.Input(A, a, () -> sync(CAPTURE)), new VisionClient.Input(B, b, () -> sync(CAPTURE)));
    return new VisionClient(inputs, new ProtocolDecoder(), new ClockMapper("synthetic-robot"),
        new VisionClient.Limits(8, 16, 64, output, 128, 30_000_000L));
  }
  public static void run() throws Exception {
    template = Files.readString(Path.of("fixtures/fixtures/measured_zero_correction.json"));
    FakeQueue a = new FakeQueue(), b = new FakeQueue();
    try (VisionClient client = client(a, b, 64)) {
      a.add(42, 0, CAPTURE, CAPTURE + 1_000_000L);
      b.add(42, 0, CAPTURE, CAPTURE + 1_000_000L);
      client.poll(CAPTURE + 1_000_000L);
      check(client.drainMeasurements().isEmpty(), "retained first values cannot act");
      a.add(43, 1, CAPTURE + 2_000_000L, CAPTURE + 3_000_000L);
      b.add(43, 1, CAPTURE + 1_000_000L, CAPTURE + 3_000_000L);
      client.poll(CAPTURE + 3_000_000L);
      check(client.drainReceipts().size() == 2, "two newly accepted raw receipts");
      check(client.drainMeasurements().isEmpty(), "bounded reorder holds across loops");
      client.poll(CAPTURE + 35_000_000L);
      List<VisionClient.Measurement> ready = client.drainMeasurements();
      check(ready.size() == 2 && ready.get(0).packet().source().equals(B), "global cross-camera capture order");
      check(client.drainMeasurements().isEmpty(), "drain once prevents repeated insertion");
      a.add(43, 1, CAPTURE + 2_000_000L, CAPTURE + 40_000_000L);
      b.add(44, 2, CAPTURE + 39_000_000L, CAPTURE + 40_000_000L);
      client.poll(CAPTURE + 40_000_000L);
      check(client.statuses(CAPTURE + 40_000_000L).get(A).usableObservationAgeNs().orElseThrow() == 37_000_000L,
          "duplicates do not refresh usable age");
      client.poll(CAPTURE + 105_000_000L);
      check(!client.statuses(CAPTURE + 105_000_000L).get(A).actionable(), "source A expires with B connected");
      check(client.statuses(CAPTURE + 105_000_000L).get(B).actionable(), "source B isolated live");
      a.addJson("{malformed", CAPTURE + 106_000_000L);
      client.poll(CAPTURE + 106_000_000L);
      check(client.counters().decodeRejected() == 1, "parse failure observable");
      check(client.statuses(CAPTURE + 106_000_000L).get(B).actionable(), "failure does not clear another source");
      b.overloaded = true; client.poll(CAPTURE + 107_000_000L);
      check(client.counters().overloads() == 1 && !client.statuses(CAPTURE + 107_000_000L).get(B).actionable(), "overload fail closed");
    }
    check(a.closed && b.closed, "only owned queues closed");

    FakeQueue overflow = new FakeQueue();
    try (VisionClient client = client(overflow, null, 1)) {
      overflow.add(42, 0, CAPTURE, CAPTURE + 1_000_000L); client.poll(CAPTURE + 1_000_000L);
      overflow.add(43, 1, CAPTURE + 2_000_000L, CAPTURE + 3_000_000L); client.poll(CAPTURE + 3_000_000L);
      client.drainReceipts();
      overflow.add(44, 2, CAPTURE + 4_000_000L, CAPTURE + 5_000_000L); client.poll(CAPTURE + 5_000_000L);
      client.drainReceipts();
      overflow.add(45, 3, CAPTURE + 6_000_000L, CAPTURE + 7_000_000L); client.poll(CAPTURE + 7_000_000L);
      client.drainReceipts();
      client.poll(CAPTURE + 40_000_000L);
      check(client.counters().overloads() == 1, "output saturation visible");
      check(client.drainMeasurements().isEmpty(), "overload cannot re-add later already-extracted source entries");
      check(!client.statuses(CAPTURE + 40_000_000L).get(A).actionable(), "output saturation invalidates source");
    }
    FakeQueue invalid = new FakeQueue();
    try (VisionClient client = client(invalid, null, 64)) {
      invalid.add(42, 0, CAPTURE, CAPTURE + 1_000_000L); client.poll(CAPTURE + 1_000_000L);
      invalid.add(43, 1, CAPTURE + 2_000_000L, CAPTURE + 3_000_000L); client.poll(CAPTURE + 3_000_000L);
      String clear = Files.readString(Path.of("fixtures/fixtures/same_frame_watchdog.json"))
          .replace("\"frame_id\":42", "\"frame_id\":43").replace("\"packet_seq\":1", "\"packet_seq\":2");
      invalid.addJson(clear, CAPTURE + 4_000_000L); client.poll(CAPTURE + 4_000_000L);
      invalid.add(43, 3, CAPTURE + 2_000_000L, CAPTURE + 5_000_000L); client.poll(CAPTURE + 5_000_000L);
      client.poll(CAPTURE + 40_000_000L);
      check(client.drainMeasurements().isEmpty(), "same-frame invalidation purges pending and delayed valid cannot revive");
    }
    // Real queues stamp observation AFTER poll entry; synchronization retrieval also advances time.
    long[] advancing = {CAPTURE + 1_000_000L};
    java.util.function.LongSupplier clock = () -> advancing[0] += 1_000L;
    FakeQueue live = new FakeQueue(); live.observingClock = clock;
    try (VisionClient client = new VisionClient(List.of(new VisionClient.Input(A, live, () -> sync(clock.getAsLong()))),
        new ProtocolDecoder(), new ClockMapper("synthetic-robot"), VisionClient.Limits.defaults(), clock)) {
      live.add(42, 0, CAPTURE, CAPTURE + 1_000_000L); client.poll();
      live.add(43, 1, CAPTURE + 2_000_000L, CAPTURE + 3_000_000L);
      advancing[0] = CAPTURE + 3_000_000L;
      client.poll();
      check(client.drainReceipts().size() == 1, "post-read and post-sync current clock accepts live transport");
      check(client.statuses(clock.getAsLong()).get(A).fusionEligible(), "post-sync timestamp is not falsely future");
      live.add(44, 2, CAPTURE + 4_000_000L, CAPTURE + 5_000_000L);
      live.addJson("{malformed", CAPTURE + 5_000_000L);
      advancing[0] = CAPTURE + 5_000_000L;
      client.poll();
      check(client.counters().decodeRejected() == 1, "valid then malformed advancing-clock batch does not regress");
      check(!client.statuses(clock.getAsLong()).get(A).actionable(), "malformed packet clears live source");
      check(client.statuses(clock.getAsLong()).get(A).activityAgeNs().orElseThrow() < 100_000L,
          "malformed raw arrivals update activity only");
    }
    FakeQueue aging = new FakeQueue();
    ClockMapper shortLife = new ClockMapper(new ClockMapper.Config("synthetic-robot", 10_000_000L, 0,
        1_000_000_000L, 5_000_000L, java.util.Optional.empty()));
    try (VisionClient client = new VisionClient(List.of(new VisionClient.Input(A, aging, () -> sync(CAPTURE))),
        new ProtocolDecoder(), shortLife, VisionClient.Limits.defaults())) {
      aging.add(42, 0, CAPTURE, CAPTURE + 1_000_000L); client.poll(CAPTURE + 1_000_000L);
      aging.add(43, 1, CAPTURE + 2_000_000L, CAPTURE + 3_000_000L); client.poll(CAPTURE + 3_000_000L);
      client.drainReceipts();
      client.poll(CAPTURE + 35_000_000L);
      check(client.drainMeasurements().isEmpty(), "reorder release rechecks stricter capture lifetime");
      check(!client.statuses(CAPTURE + 35_000_000L).get(A).fusionEligible(), "capture lifetime expires fusion eligibility");
      check(client.counters().timeRejected() == 1, "stale reordered measurement observable");
    }
    FakeQueue retained = new FakeQueue();
    try (VisionClient client = new VisionClient(List.of(new VisionClient.Input(A, retained, () -> sync(CAPTURE))),
        new ProtocolDecoder(), shortLife, new VisionClient.Limits(8, 16, 64, 64, 128, 0))) {
      retained.add(42, 0, CAPTURE, CAPTURE + 1_000_000L); client.poll(CAPTURE + 1_000_000L);
      retained.add(43, 1, CAPTURE + 2_000_000L, CAPTURE + 3_000_000L); client.poll(CAPTURE + 3_000_000L);
      client.drainReceipts();
      retained.add(44, 2, CAPTURE + 19_000_000L, CAPTURE + 20_000_000L); client.poll(CAPTURE + 20_000_000L);
      List<VisionClient.Measurement> current = client.drainMeasurements(CAPTURE + 21_000_000L);
      check(current.size() == 1 && current.get(0).packet().frameId() == 44, "live source cannot retain old fusion output");
    }
    System.out.println("ClientTests PASS: " + assertions + " assertions");
  }
  private static void check(boolean condition, String text) { assertions++; if (!condition) throw new AssertionError(text); }
}
