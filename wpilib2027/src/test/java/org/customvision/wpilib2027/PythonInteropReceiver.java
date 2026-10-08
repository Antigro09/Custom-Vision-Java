package org.customvision.wpilib2027;

import org.wpilib.networktables.NetworkTableInstance;
import org.wpilib.util.WPIUtilJNI;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import org.customvision.protocol.ProtocolDecoder;
import org.customvision.protocol.SourceKey;

/** Test-only server receiver for actual Python NT4 publications. Uses this library's public API. */
public final class PythonInteropReceiver {
  private record Input(SourceKey source, NtResultQueue queue) {}
  private static void emit(String line) { System.out.println("CVJ_INTEROP\t" + line); System.out.flush(); }
  private static String b64(String value) { return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
  public static void main(String[] args) throws Exception {
    if (args.length < 4 || (args.length - 1) % 3 != 0) throw new IllegalArgumentException("port and source triples");
    int port = Integer.parseInt(args[0]);
    if (port < 1024 || port > 65535) throw new IllegalArgumentException("ephemeral localhost port");
    var decoder = new ProtocolDecoder();
    List<Input> inputs = new ArrayList<>();
    try (var instance = NetworkTableInstance.create()) {
      instance.startServer("", "127.0.0.1", "", port);
      try {
        for (int i = 1; i < args.length; i += 3) {
          var source = new SourceKey(args[i], args[i + 1], args[i + 2]);
          var queue = new NtResultQueue(instance, source.namespace() + "/result", System::nanoTime,
              new NtResultQueue.Settings(.01, 64, 128));
          inputs.add(new Input(source, queue));
        }
        emit("READY\t2027.0.0-alpha-7\t" + inputs.get(0).queue().timeVersion());
        try (var commands = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
          String command;
          while ((command = commands.readLine()) != null) {
            if (command.equals("CLOSE")) { emit("CLOSED"); break; }
            if (command.equals("STATUS")) {
              var offset = instance.getServerTimeOffset();
              emit("STATUS\t" + WPIUtilJNI.now() + "\t" + (offset.isPresent() ? offset.getAsLong() : "unavailable")
                  + "\t" + instance.isConnected());
              continue;
            }
            if (!command.equals("DRAIN")) throw new IllegalArgumentException("unknown test command");
            int count = 0;
            int overload = 0;
            for (var input : inputs) {
              var batch = input.queue().read(32);
              if (batch.overload()) { overload++; continue; }
              for (var sample : batch.samples()) {
                String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(sample.payload().getBytes(StandardCharsets.UTF_8)));
                try {
                  var packet = decoder.decode(input.source(), sample.payload());
                  emit("SAMPLE\t" + sha + "\t" + b64(packet.source().namespace()) + "\t" + b64(packet.source().pipeline())
                      + "\t" + b64(packet.source().type()) + "\t" + b64(packet.profile()) + "\t" + packet.frameId()
                      + "\t" + b64(packet.bootId()) + "\t" + packet.packetSeq().orElse(-1)
                      + "\t" + sample.ntTimestamp() + "\t" + sample.ntServerTime() + "\t" + WPIUtilJNI.now()
                      + "\t" + packet.captureServerUs());
                  count++;
                } catch (org.customvision.protocol.DecodeException rejected) {
                  emit("REJECT\t" + sha + "\t" + b64(rejected.toString()));
                }
              }
            }
            emit("DRAIN_END\t" + count + "\t" + overload);
          }
        }
      } finally {
        for (var input : inputs) input.queue().close();
      }
    }
  }
}
