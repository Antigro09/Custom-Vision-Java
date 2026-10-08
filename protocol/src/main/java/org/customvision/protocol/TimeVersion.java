package org.customvision.protocol;

/** Pinned NT metadata units. JSON fields ending in _us remain integer microseconds in both. */
public enum TimeVersion {
  WPILIB_2026_MICROSECONDS(1_000L),
  WPILIB_2027_ALPHA7_NANOSECONDS(1L);

  private final long nanosecondsPerUnit;

  TimeVersion(long nanosecondsPerUnit) { this.nanosecondsPerUnit = nanosecondsPerUnit; }

  public long metadataToNanoseconds(long raw) {
    return Math.multiplyExact(raw, nanosecondsPerUnit);
  }

  public static long jsonMicrosecondsToNanoseconds(long raw) {
    return Math.multiplyExact(raw, 1_000L);
  }
}
