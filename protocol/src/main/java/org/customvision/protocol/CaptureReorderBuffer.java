package org.customvision.protocol;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Predicate;

/**
 * One robot-loop-owned buffer across all cameras, ordered by mapped capture time across cycles.
 * Status/invalidation packets must be applied in publication order before calling this buffer.
 * The caller invalidates source state on OVERLOAD and drops pending source entries on resets.
 */
public final class CaptureReorderBuffer<T> {
  public enum Offer { ACCEPTED, DUPLICATE, LATE, OVERLOAD, FUTURE }

  /** Identity must include configured source, boot and frame (and candidate group if needed). */
  public record Entry<T>(String observationIdentity, SourceKey source, long captureRobotNs, T value) {
    public Entry {
      Objects.requireNonNull(observationIdentity); Objects.requireNonNull(source); Objects.requireNonNull(value);
      if (observationIdentity.isBlank() || observationIdentity.length() > 1_024 || captureRobotNs < 0)
        throw new IllegalArgumentException("invalid measurement identity/time");
    }
  }

  public record Counters(long accepted, long emitted, long duplicate, long late,
      long overload, long future, int pending, long lastEmittedCaptureNs) {}

  private final int capacity;
  private final long reorderWindowNs;
  private final long maxFutureLeadNs;
  private final PriorityQueue<Entry<T>> pending;
  private final Set<String> pendingIdentities = new HashSet<>();
  private long lastEmittedCaptureNs = -1;
  private long closedThroughNs = -1;
  private long lastRobotNowNs = -1;
  private long acceptedCount;
  private long emittedCount;
  private long duplicateCount;
  private long lateCount;
  private long overloadCount;
  private long futureCount;

  public CaptureReorderBuffer(int capacity, long reorderWindowNs, long maxFutureLeadNs) {
    if (capacity < 1 || capacity > 65_536 || reorderWindowNs < 0 || maxFutureLeadNs < 0)
      throw new IllegalArgumentException("invalid reorder bounds");
    this.capacity = capacity;
    this.reorderWindowNs = reorderWindowNs;
    this.maxFutureLeadNs = maxFutureLeadNs;
    pending = new PriorityQueue<>(Comparator.<Entry<T>>comparingLong(Entry::captureRobotNs)
        .thenComparing(Entry::observationIdentity));
  }

  public Offer offer(Entry<T> entry, long nowRobotNs) {
    Objects.requireNonNull(entry); checkNow(nowRobotNs);
    if (pendingIdentities.contains(entry.observationIdentity())) { duplicateCount++; return Offer.DUPLICATE; }
    // Equal capture times after emission are deliberately late: no identity ledger grows forever.
    if (entry.captureRobotNs() <= lastEmittedCaptureNs || entry.captureRobotNs() <= closedThroughNs) {
      lateCount++; return Offer.LATE;
    }
    if (entry.captureRobotNs() > nowRobotNs && entry.captureRobotNs() - nowRobotNs > maxFutureLeadNs) {
      futureCount++; return Offer.FUTURE;
    }
    if (pending.size() == capacity) { overloadCount++; return Offer.OVERLOAD; }
    pending.add(entry); pendingIdentities.add(entry.observationIdentity()); acceptedCount++;
    return Offer.ACCEPTED;
  }

  /** Emit at most maxWork already ordered measurements. Watermark advances even on quiet loops. */
  public List<Entry<T>> drain(long nowRobotNs, int maxWork) {
    checkNow(nowRobotNs);
    if (maxWork < 1 || maxWork > capacity) throw new IllegalArgumentException("invalid per-cycle work bound");
    long watermark = nowRobotNs >= reorderWindowNs ? nowRobotNs - reorderWindowNs : -1;
    closedThroughNs = Math.max(closedThroughNs, watermark);
    List<Entry<T>> emitted = new ArrayList<>(Math.min(maxWork, pending.size()));
    int work = 0;
    while (work < maxWork && !pending.isEmpty() && pending.peek().captureRobotNs() <= watermark) {
      work++;
      Entry<T> entry = pending.remove(); pendingIdentities.remove(entry.observationIdentity());
      if (entry.captureRobotNs() < lastEmittedCaptureNs) { lateCount++; continue; }
      emitted.add(entry); lastEmittedCaptureNs = entry.captureRobotNs(); emittedCount++;
    }
    return List.copyOf(emitted);
  }

  /** Per-source invalidation leaves other cameras' pending candidates and the global watermark intact. */
  public void removeSource(SourceKey source) {
    Objects.requireNonNull(source);
    pending.removeIf(entry -> {
      if (entry.source().equals(source)) { pendingIdentities.remove(entry.observationIdentity()); return true; }
      return false;
    });
  }

  /** Bounded frame/boot-specific invalidation; never reopens already released capture intervals. */
  public void discard(Predicate<? super T> invalidated) {
    Objects.requireNonNull(invalidated);
    pending.removeIf(entry -> {
      if (invalidated.test(entry.value())) { pendingIdentities.remove(entry.observationIdentity()); return true; }
      return false;
    });
  }

  /** Clears pending measurements without allowing chronological regression. */
  public void clearPending() { pending.clear(); pendingIdentities.clear(); }

  public Counters counters() {
    return new Counters(acceptedCount, emittedCount, duplicateCount, lateCount, overloadCount,
        futureCount, pending.size(), lastEmittedCaptureNs);
  }

  private void checkNow(long nowRobotNs) {
    if (nowRobotNs < 0 || nowRobotNs < lastRobotNowNs)
      throw new IllegalArgumentException("robot clock regressed; construct a new buffer after epoch reset");
    lastRobotNowNs = nowRobotNs;
  }
}
