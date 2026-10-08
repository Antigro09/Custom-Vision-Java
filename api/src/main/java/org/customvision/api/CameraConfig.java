package org.customvision.api;

import java.util.Objects;
import org.customvision.protocol.SourceKey;

/** A human-friendly name plus the complete configured coherent-result source identity. */
public record CameraConfig(String name, SourceKey source) {
  public CameraConfig {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(source, "source");
    if (name.isBlank() || name.length() > 128 || name.chars().anyMatch(c -> c < 0x20))
      throw new IllegalArgumentException("camera name must be nonblank and bounded");
  }
  /** Namespace is the full topic prefix before /result; pipeline is the expected payload name. */
  public static CameraConfig tags(String name, String namespace, String pipeline) {
    return new CameraConfig(name, new SourceKey(namespace, pipeline, "apriltag"));
  }
  public static CameraConfig objects(String name, String namespace, String pipeline) {
    return new CameraConfig(name, new SourceKey(namespace, pipeline, "object"));
  }
}
