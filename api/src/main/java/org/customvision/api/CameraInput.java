package org.customvision.api;

import java.util.Objects;
import java.util.function.Supplier;
import org.customvision.protocol.RawQueue;
import org.customvision.protocol.SyncSnapshot;

/** Owned result subscriber and caller-supplied clock evidence for one configured source. */
public record CameraInput(CameraConfig camera, RawQueue queue, Supplier<SyncSnapshot> synchronization) {
  public CameraInput {
    Objects.requireNonNull(camera, "camera");
    Objects.requireNonNull(queue, "queue");
    Objects.requireNonNull(synchronization, "synchronization evidence callback is required");
  }
}
