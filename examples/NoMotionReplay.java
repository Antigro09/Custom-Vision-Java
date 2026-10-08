import java.nio.file.Files;
import java.nio.file.Path;
import org.customvision.protocol.Packet;
import org.customvision.protocol.ProtocolDecoder;
import org.customvision.protocol.SourceKey;

/** Offline decoding example only. No HAL, networking, estimator, robot subsystem or motion. */
public final class NoMotionReplay {
  private NoMotionReplay() {}
  public static void main(String[] args) throws Exception {
    String fixture = args.length == 0 ? "fixtures/fixtures/single_tag.json" : args[0];
    SourceKey source = new SourceKey("/CustomVision/jetson-tags/front_tags", "front_tags", "apriltag");
    Packet packet = new ProtocolDecoder().decode(source, Files.readString(Path.of(fixture)));
    System.out.println("Validated " + packet.observationId() + " profile=" + packet.profile());
    System.out.println("Pose candidates=" + packet.poseCandidates().size()
        + "; capture_server_us=" + packet.captureServerUs());
    System.out.println("Physical timing verification=" + packet.timestamps().captureCorrectionVerified());
    System.out.println("Robot-owned clock, liveness and fusion policy must approve any insertion.");
  }
}
