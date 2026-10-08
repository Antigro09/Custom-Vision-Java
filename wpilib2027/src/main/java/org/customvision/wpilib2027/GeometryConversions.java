package org.customvision.wpilib2027;

import org.wpilib.math.geometry.Pose3d;
import org.wpilib.math.geometry.Quaternion;
import org.wpilib.math.geometry.Rotation3d;
import org.customvision.protocol.Packet;
import org.customvision.protocol.ProtocolDecoder;

/** Unit/frame validation and representation conversion only: no field projection or alliance flip. */
public final class GeometryConversions {
  private GeometryConversions() {}
  public static Pose3d toPose3d(Packet.Pose pose) {
    if (pose == null || !"wpilib_nwu".equals(pose.frame()) || pose.translation() == null
        || pose.rotation() == null) throw new IllegalArgumentException("named wpilib_nwu pose required");
    var t = pose.translation();
    var q = pose.rotation();
    for (double value : new double[]{t.x(), t.y(), t.z(), q.w(), q.x(), q.y(), q.z()})
      if (!Double.isFinite(value)) throw new IllegalArgumentException("finite meters/WXYZ required");
    double norm = Math.hypot(Math.hypot(q.w(), q.x()), Math.hypot(q.y(), q.z()));
    if (Math.abs(norm - 1.0) > ProtocolDecoder.QUATERNION_NORM_TOLERANCE) throw new IllegalArgumentException("unit WXYZ quaternion required");
    return new Pose3d(t.x(), t.y(), t.z(), new Rotation3d(new Quaternion(q.w(), q.x(), q.y(), q.z())));
  }
}
