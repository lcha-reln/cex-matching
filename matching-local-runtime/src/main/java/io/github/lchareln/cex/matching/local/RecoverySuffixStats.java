package io.github.lchareln.cex.matching.local;

/** Exact WAL records and encoded bytes that must be replayed after the latest snapshot anchor. */
public record RecoverySuffixStats(long records, long bytes) {
  public RecoverySuffixStats {
    if (records < 0 || bytes < 0) {
      throw new IllegalArgumentException("recovery suffix usage must not be negative");
    }
  }
}
