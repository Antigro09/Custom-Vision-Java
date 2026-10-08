package org.customvision.wpilib2026;

import edu.wpi.first.networktables.TimestampedString;
import java.util.ArrayDeque;
import java.util.OptionalLong;
import org.customvision.protocol.Packet;
import org.customvision.protocol.ProtocolDecoder;
import org.customvision.protocol.SourceKey;
import java.nio.file.Files;
import java.nio.file.Path;

/** Synthetic adapter seam tests; no HAL, native NT, clock sync, or robot code is initialized. */
public final class AdapterTests {
  private static int checks;
  public static void main(String[] args) throws Exception {
    var batches = new ArrayDeque<TimestampedString[]>();
    batches.add(new TimestampedString[]{value("a", 101), value("a", 102), value("b", 103)});
    long[] clock = {1_000_000_000L};
    int[] closes = {0};
    var queue = new NtResultQueue(() -> batches.isEmpty() ? new TimestampedString[0] : batches.remove(),
        () -> closes[0]++, () -> OptionalLong.of(55), () -> true, () -> clock[0],
        new NtResultQueue.Settings(.01, 4, 6));
    check(queue.timeVersion() == org.customvision.protocol.TimeVersion.WPILIB_2026_MICROSECONDS, "adapter exposes pinned raw metadata units");
    var first = queue.read(2);
    check(!first.overload() && first.samples().size() == 2, "bounded first drain");
    check(first.samples().get(0).payload().equals("a") && first.samples().get(1).payload().equals("a"), "duplicate bytes retained");
    check(first.samples().get(0).ntTimestamp() == 101 && first.samples().get(1).ntServerTime() == 1102, "raw metadata exact");
    clock[0] += 20_000_000L;
    var next = queue.read(2);
    check(next.samples().size() == 1 && next.samples().get(0).payload().equals("b"), "queue order across loops");
    check(next.samples().get(0).firstObservedRobotNs() == 1_000_000_000L
        && next.samples().get(0).dequeueRobotNs() == clock[0], "observation separate from later dequeue");
    check(queue.read(2).samples().isEmpty(), "getter cannot reinsert retained last value");
    check(queue.rawServerTimeOffset().getAsLong() == 55 && queue.instanceConnectedForDiagnostics(), "raw offset and diagnostic peer connectivity");
    batches.add(new TimestampedString[]{value("x", 200), value("y", 201), value("z", 202), value("lost?", 203)});
    check(queue.read(2).overload() && queue.pendingPackets() == 0, "saturated native queue invalidates whole batch");
    check(queue.overloadCount() == 1 && queue.discardedPacketsAtLeast() == 4, "observable overload lower bound");
    batches.add(new TimestampedString[]{value("fresh", 204)});
    check(queue.read(2).samples().get(0).payload().equals("fresh"), "fresh packet recovery");
    batches.add(new TimestampedString[]{value("old", 205), value("pending", 206)});
    queue.read(1);
    batches.add(new TimestampedString[]{value("queued before reconnect", 207)});
    check(queue.advanceConnectionEpoch() == 1 && queue.pendingPackets() == 0, "local epoch clears old handoff and native queue");
    batches.add(new TimestampedString[]{value("new", 208)});
    check(queue.read(1).samples().get(0).connectionEpoch() == 1, "epoch retained in metadata");
    Throwable[] wrongThread = {null};
    Thread other = new Thread(() -> { try { queue.read(1); } catch (Throwable problem) { wrongThread[0] = problem; } });
    other.start(); other.join();
    check(wrongThread[0] instanceof IllegalStateException, "second loop owner rejected");
    queue.close(); queue.close();
    check(closes[0] == 1, "owned subscriber closed exactly once");
    expect(() -> queue.read(1), IllegalStateException.class);
    expect(() -> new NtResultQueue.Settings(.0, 2, 1), IllegalArgumentException.class);
    expect(() -> new NtResultQueue.Settings(.01, 1025, 1), IllegalArgumentException.class);
    var tinyBatches = new ArrayDeque<TimestampedString[]>();
    tinyBatches.add(new TimestampedString[]{value("a", 1), value("b", 2)});
    var tiny = new NtResultQueue(() -> tinyBatches.remove(), () -> {}, OptionalLong::empty, () -> false,
        () -> 4L, new NtResultQueue.Settings(.01, 4, 1));
    check(tiny.read(1).overload(), "handoff overload does not truncate measurement stream"); tiny.close();
    var pose = new Packet.Pose(new Packet.Vec3(2, 3, 4),
        new Packet.QuaternionWxyz(Math.sqrt(.5), 0, 0, Math.sqrt(.5)), "wpilib_nwu");
    var converted = GeometryConversions.toPose3d(pose);
    check(converted.getX() == 2 && converted.getY() == 3 && converted.getZ() == 4, "meters and fixed origin unchanged");
    check(Math.abs(converted.getRotation().getZ() - Math.PI / 2) < 1e-12, "WXYZ positive NWU yaw unchanged");
    check(GeometryConversions.toPose3d(new Packet.Pose(pose.translation(), new Packet.QuaternionWxyz(1.000002, 0, 0, 0), "wpilib_nwu")).getX() == 2, "producer rounding tolerance matches decoder");
    expect(() -> GeometryConversions.toPose3d(new Packet.Pose(pose.translation(), pose.rotation(), "opencv_optical")), IllegalArgumentException.class);
    expect(() -> GeometryConversions.toPose3d(new Packet.Pose(pose.translation(), new Packet.QuaternionWxyz(2, 0, 0, 0), "wpilib_nwu")), IllegalArgumentException.class);
    expect(() -> GeometryConversions.toPose3d(new Packet.Pose(new Packet.Vec3(Double.NaN, 0, 0), pose.rotation(), "wpilib_nwu")), IllegalArgumentException.class);
    String fixture = Files.readString(Path.of("fixtures/fixtures/single_tag.json"));
    String quaternionKey = "\"rotation_quaternion_wxyz\":[";
    int start = fixture.indexOf(quaternionKey);
    int end = fixture.indexOf(']', start);
    String rounded = fixture.substring(0, start) + quaternionKey + "1.000002,0,0,0]" + fixture.substring(end + 1);
    var decoded = new ProtocolDecoder().decode(new SourceKey("/CustomVision/front_tags", "front_tags", "apriltag"), rounded);
    check(decoded.poseCandidates().stream().map(candidate -> GeometryConversions.toPose3d(candidate.pose())).toList().size() == 4,
        "fixture-derived accepted decoder rounding survives WPILib conversion");
    System.out.println("WPILib 2026 adapter synthetic checks passed: " + checks);
  }
  private static TimestampedString value(String text, long stamp) { return new TimestampedString(stamp, stamp + 1000, text); }
  private static void check(boolean condition, String detail) { checks++; if (!condition) throw new AssertionError(detail); }
  private static void expect(Runnable action, Class<? extends Throwable> type) {
    checks++; try { action.run(); } catch (Throwable error) { if (type.isInstance(error)) return; throw new AssertionError(error); }
    throw new AssertionError("expected " + type.getSimpleName());
  }
}
