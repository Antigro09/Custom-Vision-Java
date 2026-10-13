package org.customvision.wpilib2027;

import org.wpilib.networktables.NetworkTableInstance;
import org.wpilib.networktables.PubSubOption;
import org.wpilib.networktables.StringPublisher;
import org.wpilib.util.WPIUtilJNI;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.OptionalLong;
import org.customvision.protocol.RawQueue;
import org.customvision.protocol.TimeVersion;

/** Real alpha-7 NT natives; owns only ephemeral test instances, no HAL. */
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
      server.startServer("", "127.0.0.1", "", port);
      client.setServer("127.0.0.1", port);
      String topicName = org.customvision.interop.NativeAdmissionBridgeTests.SOURCE.resultTopic();
      var topic = server.getStringTopic(topicName);
      publisher = topic.publish(PubSubOption.SEND_ALL, PubSubOption.KEEP_DUPLICATES);
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
      if (!started) { client.startClient("cvj-admission-2027"); started = true; }
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
