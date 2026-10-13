package org.customvision.api;

import java.util.OptionalLong;
import org.customvision.protocol.SyncSnapshot;
import org.customvision.protocol.TimeVersion;

/**
 * Supplies independently verified NT-local-to-robot-epoch evidence. NT's raw offset alone is
 * insufficient. Returned snapshots must retain the supplied profile unit and connection epoch.
 */
@FunctionalInterface
public interface SyncEvidenceProvider {
  SyncSnapshot snapshot(CameraConfig camera, TimeVersion metadataVersion,
      OptionalLong serverMinusLocalRaw, long connectionEpoch, long observedRobotNs);
}
