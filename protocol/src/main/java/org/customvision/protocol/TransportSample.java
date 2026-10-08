package org.customvision.protocol;

/** NT metadata uses the unit of the selected adapter; local times always use robot nanoseconds.
 * firstObservedRobotNs is readQueue observation time, not a claim of true network ingress. */
public record TransportSample(String payload, long ntTimestamp, long ntServerTime,
    long firstObservedRobotNs, long dequeueRobotNs, long connectionEpoch) {
  public TransportSample {
    if (payload == null || firstObservedRobotNs < 0 || dequeueRobotNs < firstObservedRobotNs
        || connectionEpoch < 0) throw new IllegalArgumentException("invalid transport sample");
  }
}
