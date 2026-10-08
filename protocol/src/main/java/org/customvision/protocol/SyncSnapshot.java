package org.customvision.protocol;

import java.util.Objects;
import java.util.OptionalLong;

/**
 * Explicit mapping evidence, captured in the selected adapter's NT time unit. Adding
 * serverMinusLocalRaw to local NT time gives server time. localToRobotOffsetNs must be
 * independently verified against the configured robot monotonic epoch; an offset merely
 * returned by NTCore does not prove that epoch relationship. This is not Unix time.
 */
public record SyncSnapshot(TimeVersion version, OptionalLong serverMinusLocalRaw,
    String robotEpoch, boolean robotEpochVerified, long localToRobotOffsetNs,
    long uncertaintyNs, long observedRobotNs, long connectionEpoch, String verification) {
  public SyncSnapshot {
    Objects.requireNonNull(version, "version");
    Objects.requireNonNull(serverMinusLocalRaw, "serverMinusLocalRaw");
    Objects.requireNonNull(robotEpoch, "robotEpoch");
    Objects.requireNonNull(verification, "verification");
    if (robotEpoch.isBlank() || robotEpoch.length() > 128 || verification.length() > 512
        || uncertaintyNs < 0 || observedRobotNs < 0 || connectionEpoch < 0
        || (robotEpochVerified && verification.isBlank())) {
      throw new IllegalArgumentException("invalid clock mapping evidence");
    }
  }
}
