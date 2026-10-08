package org.customvision.wpilib2026;

import edu.wpi.first.networktables.NetworkTableInstance;
import edu.wpi.first.networktables.PubSubOption;
import edu.wpi.first.util.WPIUtilJNI;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.LockSupport;
import org.customvision.protocol.TransportSample;

/** Actual pinned-native NT loopback, bound only to localhost; no HAL or robot program. */
public final class NativeLoopbackTests {
  public static void main(String[] args) throws Exception {
    verifyNativeClockUnit();
    int port;
    try (var socket = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) { port = socket.getLocalPort(); }
    String retained = args.length == 0 ? "{\"test\":\"retained\"}" : Files.readString(Path.of(args[0]));
    try (var server = NetworkTableInstance.create(); var client = NetworkTableInstance.create()) {
      server.startServer("", "127.0.0.1", 0, port);
      client.setServer("127.0.0.1", port);
      client.startClient4("cv-java-2026-test");
      var topic = server.getStringTopic("/AdapterTest/camera/tag/result");
      try (var publisher = topic.publish(PubSubOption.sendAll(true), PubSubOption.keepDuplicates(true))) {
        topic.setRetained(true);
        if (!topic.isRetained()) throw new AssertionError("retained topic property");
        long publishedRawTime = WPIUtilJNI.now();
        publisher.set(retained, publishedRawTime); server.flush();
        waitUntil(client::isConnected, "localhost connection");
        try (var queue = new NtResultQueue(client, "/AdapterTest/camera/tag/result", System::nanoTime,
            new NtResultQueue.Settings(.005, 16, 32))) {
          var all = new ArrayList<TransportSample>();
          long deadline = System.nanoTime() + 3_000_000_000L;
          while (all.isEmpty() && System.nanoTime() < deadline) {
            var batch = queue.read(4);
            if (batch.overload()) throw new AssertionError("unexpected saturation");
            all.addAll(batch.samples()); LockSupport.parkNanos(5_000_000L);
          }
          if (all.size() != 1 || !all.get(0).payload().equals(retained)) throw new AssertionError("retained exact bytes");
          if (all.get(0).ntTimestamp() <= 1 || all.get(0).ntServerTime() <= 1) throw new AssertionError("remote raw metadata missing");
          if (Math.abs(all.get(0).ntServerTime() - publishedRawTime) > 0L)
            throw new AssertionError("raw publication timestamp units/precision changed");
          all.clear();
          for (String payload : List.of("{\"test\":1}", "{\"test\":1}", "{\"test\":2}")) { publisher.set(payload); }
          server.flush();
          deadline = System.nanoTime() + 3_000_000_000L;
          while (all.size() < 3 && System.nanoTime() < deadline) {
            var batch = queue.read(1);
            if (batch.overload()) throw new AssertionError("unexpected saturation");
            all.addAll(batch.samples()); LockSupport.parkNanos(5_000_000L);
          }
          if (!all.stream().map(TransportSample::payload).toList().equals(List.of("{\"test\":1}", "{\"test\":1}", "{\"test\":2}")))
            throw new AssertionError("SEND_ALL/KEEP_DUPLICATES publication order: " + all);
          if (!queue.read(4).samples().isEmpty()) throw new AssertionError("no repeated getter reinsertion");
          waitUntil(() -> queue.rawServerTimeOffset().isPresent(), "time synchronization");
          // A retained value survives the producer publisher's lifetime and reaches a late subscriber.
          var cachedTopic = server.getStringTopic("/AdapterTest/cached/tag/result");
          try (var cachedPublisher = cachedTopic.publish(PubSubOption.sendAll(true), PubSubOption.keepDuplicates(true))) {
            cachedTopic.setRetained(true); cachedPublisher.set(retained); server.flush();
          }
          try (var cachedQueue = new NtResultQueue(client, "/AdapterTest/cached/tag/result", System::nanoTime,
                  new NtResultQueue.Settings(.005, 16, 32))) {
            var cached = new ArrayList<TransportSample>();
            long cachedDeadline = System.nanoTime() + 3_000_000_000L;
            while (cached.isEmpty() && System.nanoTime() < cachedDeadline) {
              cached.addAll(cachedQueue.read(4).samples()); LockSupport.parkNanos(5_000_000L);
            }
            if (cached.size() != 1 || !cached.get(0).payload().equals(retained))
              throw new AssertionError("retained cached value after publisher closed");
          }
          // Typical robot topology: remote Jetson-like client publishes, robot-like server subscribes.
          try (var serverQueue = new NtResultQueue(server, "/AdapterTest/remote/tag/result", System::nanoTime,
                  new NtResultQueue.Settings(.005, 16, 32));
              var remotePublisher = client.getStringTopic("/AdapterTest/remote/tag/result").publish(PubSubOption.sendAll(true), PubSubOption.keepDuplicates(true))) {
            remotePublisher.set(retained); client.flush();
            var received = new ArrayList<TransportSample>();
            long remoteDeadline = System.nanoTime() + 3_000_000_000L;
            while (received.isEmpty() && System.nanoTime() < remoteDeadline) {
              var remoteBatch = serverQueue.read(4);
              if (remoteBatch.overload()) throw new AssertionError("inverse flow saturation");
              received.addAll(remoteBatch.samples()); LockSupport.parkNanos(5_000_000L);
            }
            if (received.size() != 1 || !received.get(0).payload().equals(retained))
              throw new AssertionError("client-to-server exact fixture text");
            var remoteValue = received.get(0);
            if (remoteValue.ntTimestamp() <= 1 || remoteValue.ntServerTime() <= 1
                || remoteValue.ntTimestamp() != remoteValue.ntServerTime())
              throw new AssertionError("server remote publication metadata/epoch");
            if (serverQueue.rawServerTimeOffset().isEmpty() || serverQueue.rawServerTimeOffset().getAsLong() != 0)
              throw new AssertionError("server time offset must be present zero");
          }
          queue.close();
          if (!client.isValid() || !client.isConnected()) throw new AssertionError("adapter closed shared instance");
          System.out.println("WPILib 2026 native localhost loopback passed: retained exact bytes, duplicate publication order, raw microsecond metadata, sync availability, inverse robot-server flow, shared-instance ownership");
        }
      }
    }
  }
  private static void verifyNativeClockUnit() {
    WPIUtilJNI.now(); // Initialize the native library before the rate comparison.
    long beforeLocalNs = System.nanoTime();
    long beforeRaw = WPIUtilJNI.now();
    LockSupport.parkNanos(10_000_000L);
    long afterRaw = WPIUtilJNI.now();
    long elapsedLocalNs = System.nanoTime() - beforeLocalNs;
    double ratio = (double) (afterRaw - beforeRaw) * 1000 / elapsedLocalNs;
    if (ratio < .80 || ratio > 1.20) throw new AssertionError("native clock unit mismatch: " + ratio);
  }
  private static void waitUntil(java.util.function.BooleanSupplier condition, String detail) {
    long deadline = System.nanoTime() + 3_000_000_000L;
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) LockSupport.parkNanos(5_000_000L);
    if (!condition.getAsBoolean()) throw new AssertionError(detail);
  }
}
