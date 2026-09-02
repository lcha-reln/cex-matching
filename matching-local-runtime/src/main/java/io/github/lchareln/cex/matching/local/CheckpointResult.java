package io.github.lchareln.cex.matching.local;

/**
 * Published snapshot generation and the durable prefix made eligible for whole-segment deletion.
 */
public record CheckpointResult(
    SnapshotAnchor anchor,
    long prunedThroughWalSequence,
    long suffixRecordsBeforeCheckpoint,
    long suffixBytesBeforeCheckpoint) {
  public CheckpointResult(SnapshotAnchor anchor, long prunedThroughWalSequence) {
    this(anchor, prunedThroughWalSequence, 0, 0);
  }

  public CheckpointResult {
    if (anchor == null
        || prunedThroughWalSequence < 0
        || prunedThroughWalSequence > anchor.lastWalSequence()
        || suffixRecordsBeforeCheckpoint < 0
        || suffixBytesBeforeCheckpoint < 0) {
      throw new IllegalArgumentException("invalid checkpoint result");
    }
  }
}
