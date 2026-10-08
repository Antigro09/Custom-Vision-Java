package org.customvision.wpilib2026;

import edu.wpi.first.networktables.NetworkTableInstance;
import edu.wpi.first.networktables.PubSubOption;
import edu.wpi.first.networktables.StringPublisher;
import edu.wpi.first.util.WPIUtilJNI;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.OptionalLong;
import org.customvision.protocol.RawQueue;
import org.customvision.protocol.TimeVersion;

/** Real 2026.2.1 NT natives; owns only ephemeral test instances, no HAL. */
public final class NativeAdmissionBridgeTests {
  private static final class Surface implements org.customvision.interop.NativeAdmissionBridgeTests.Surface {
    private final NetworkTableInstance server = NetworkTableInstance.create();
    private final NetworkTableInstance client = NetworkTableInstance.create();
    private final StringPublisher publisher;
    private final NtResultQueue queue;
    private boolean started;
    Surface() throws Exception {
      int port;
      try (var socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) { port = socket.getLocalPort(); }
      server.startServer("", "127.0.0.1", 0, port);
      client.setServer("127.0.0.1", port);
      String topicName = org.customvision.interop.NativeAdmissionBridgeTests.SOURCE.resultTopic();
      var topic = server.getStringTopic(topicName);
      publisher = topic.publish(PubSubOption.sendAll(true), PubSubOption.keepDuplicates(true));
      topic.setRetained(true);
      queue = new NtResultQueue(client, topicName, System::nanoTime, new NtResultQueue.Settings(.005, 32, 64));
    }
    @Override public RawQueue queue() { return queue; }
    @Override public TimeVersion version() { return queue.timeVersion(); }
    @Override public boolean connected() { return client.isConnected(); }
    @Override public OptionalLong serverMinusLocalRaw() { return queue.rawServerTimeOffset(); }
    @Override public long localNtRaw() { return WPIUtilJNI.now(); }
    @Override public long publish(String text) {
      long raw = WPIUtilJNI.now(); publisher.set(text, raw); server.flush();
      if (!started) { client.startClient4("cvj-admission-2026"); started = true; }
      return raw;
    }
    @Override public boolean clientStillValid() { return client.isValid(); }
    @Override public void close() { queue.close(); publisher.close(); client.close(); server.close(); }
  }
  public static void main(String[] args) throws Exception {
    if (args.length != 1) throw new IllegalArgumentException("pass the checked golden fixture path");
    try (var surface = new Surface()) { org.customvision.interop.NativeAdmissionBridgeTests.run(surface, Path.of(args[0])); }
  }
}
