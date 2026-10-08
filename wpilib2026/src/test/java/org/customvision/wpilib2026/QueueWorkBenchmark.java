package org.customvision.wpilib2026;

import edu.wpi.first.networktables.TimestampedString;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.OptionalLong;

/** Bounded post-receipt handoff benchmark; native/network/string allocation deliberately excluded. */
public final class QueueWorkBenchmark {
  private QueueWorkBenchmark() {}
  public static void main(String[] args) throws Exception {
    String payload = Files.readString(Path.of("fixtures/fixtures/single_tag.json"));
    TimestampedString[] fixed = new TimestampedString[8];
    for (int i = 0; i < fixed.length; i++) fixed[i] = new TimestampedString(1_000_000L + i, 1_000_000L + i, payload);
    long checksum = 0;
    int warmup = 250, samples = 1000;
    com.sun.management.ThreadMXBean bean = ManagementFactory.getPlatformMXBean(com.sun.management.ThreadMXBean.class);
    boolean supported = bean != null && bean.isThreadAllocatedMemorySupported();
    if (supported && !bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
    long thread = Thread.currentThread().getId();
    long[] times = new long[samples];
    long before, after;
    try (NtResultQueue queue = new NtResultQueue(() -> fixed, () -> {}, () -> OptionalLong.of(0),
        () -> true, System::nanoTime, NtResultQueue.Settings.defaults())) {
      for (int i = 0; i < warmup; i++) checksum += queue.read(8).samples().size();
      before = supported ? bean.getThreadAllocatedBytes(thread) : -1;
      for (int i = 0; i < samples; i++) {
        long start = System.nanoTime(); checksum += queue.read(8).samples().size(); times[i] = System.nanoTime() - start;
      }
      after = supported ? bean.getThreadAllocatedBytes(thread) : -1;
    }
    Arrays.sort(times);
    String result = "{\n  \"environment\":\"Mac OS X " + System.getProperty("os.version") + " " + System.getProperty("os.arch")
        + "\",\n  \"java\":\"" + System.getProperty("java.runtime.version") + "\",\n  \"threads\":1,\n  \"warmup_batches\":" + warmup
        + ",\n  \"sample_batches\":" + samples + ",\n  \"packets_per_batch\":8,\n  \"p50_batch_ns\":" + times[499]
        + ",\n  \"p95_batch_ns\":" + times[949] + ",\n  \"p99_batch_ns\":" + times[989] + ",\n  \"max_batch_ns\":" + times[999]
        + ",\n  \"allocated_bytes_per_batch\":" + (supported ? (after - before) / samples : "null")
        + ",\n  \"checksum\":" + checksum + ",\n  \"limitations\":\"Synthetic 2026 adapter reader seam, reused native-value array and payload strings; measures only bounded handoff and TransportSample creation. Excludes native readQueue, networking, JSON decoding, controller and robot loop.\"\n}\n";
    Files.createDirectories(Path.of("docs/benchmarks")); Files.writeString(Path.of("docs/benchmarks/queue-mac.json"), result);
    System.out.print(result);
  }
}
