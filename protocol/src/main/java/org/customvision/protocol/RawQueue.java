package org.customvision.protocol;

import java.util.List;

/** One robot-loop owner reads bounded coherent snapshots; implementations own subscriptions only. */
public interface RawQueue extends AutoCloseable {
  record Batch(List<TransportSample> samples, boolean overload) {
    public Batch { samples = List.copyOf(samples); }
  }
  Batch read(int maxPackets);
  @Override void close();
}
