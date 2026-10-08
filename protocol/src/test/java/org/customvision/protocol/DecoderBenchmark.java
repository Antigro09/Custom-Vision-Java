package org.customvision.protocol;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Small single-thread CPU benchmark on an identified desktop; no controller performance claims. */
public final class DecoderBenchmark {
  private DecoderBenchmark() {}
  public static void main(String[] args) throws Exception {
    String json = Files.readString(Path.of("fixtures/fixtures/single_tag.json"));
    SourceKey key = new SourceKey("/bench/front_tags", "front_tags", "apriltag");
    ProtocolDecoder decoder = new ProtocolDecoder();
    int warmup = 250, samples = 1000;
    long checksum = 0;
    for (int i = 0; i < warmup; i++) checksum += decoder.decode(key, json).frameId();
    com.sun.management.ThreadMXBean bean = ManagementFactory.getPlatformMXBean(com.sun.management.ThreadMXBean.class);
    boolean allocationSupported = bean != null && bean.isThreadAllocatedMemorySupported();
    if (allocationSupported && !bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
    long thread = Thread.currentThread().getId();
    long allocatedBefore = allocationSupported ? bean.getThreadAllocatedBytes(thread) : -1;
    long[] latency = new long[samples];
    for (int i = 0; i < samples; i++) {
      long start = System.nanoTime(); checksum += decoder.decode(key, json).frameId();
      latency[i] = System.nanoTime() - start;
    }
    long allocatedAfter = allocationSupported ? bean.getThreadAllocatedBytes(thread) : -1;
    Arrays.sort(latency);
    String result = "{\n  \"environment\":\"" + System.getProperty("os.name") + " " + System.getProperty("os.version")
        + " " + System.getProperty("os.arch") + "\",\n  \"java\":\"" + System.getProperty("java.runtime.version")
        + "\",\n  \"threads\":1,\n  \"warmup_packets\":" + warmup + ",\n  \"sample_packets\":" + samples
        + ",\n  \"payload_utf8_bytes\":" + json.getBytes(StandardCharsets.UTF_8).length
        + ",\n  \"p50_ns\":" + latency[499] + ",\n  \"p95_ns\":" + latency[949]
        + ",\n  \"p99_ns\":" + latency[989] + ",\n  \"max_ns\":" + latency[999]
        + ",\n  \"allocated_bytes_per_packet\":" + (allocationSupported ? (allocatedAfter - allocatedBefore) / samples : "null")
        + ",\n  \"checksum\":" + checksum + ",\n  \"limitations\":\"Single small synthetic producer fixture on this Mac with 256MiB heap; includes parse/validation/immutable DTO copy. Excludes NT allocation/native transport, estimator, controller, camera, loaded robot loop.\"\n}\n";
    Files.createDirectories(Path.of("docs/benchmarks"));
    Files.writeString(Path.of("docs/benchmarks/decoder-mac.json"), result);
    System.out.print(result);
  }
}
