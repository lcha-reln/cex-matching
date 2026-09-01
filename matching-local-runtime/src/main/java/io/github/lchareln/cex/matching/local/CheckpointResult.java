package io.github.lchareln.cex.matching.local;

/**
 * Published snapshot generation and the durable prefix made eligible for whole-segment deletion.
 */
public record CheckpointResult(SnapshotAnchor anchor, long prunedThroughWalSequence) {
  public CheckpointResult {
    if (anchor == null
        || prunedThroughWalSequence < 0
        || prunedThroughWalSequence > anchor.lastWalSequence()) {
      throw new IllegalArgumentException("invalid checkpoint result");
    }
  }
}
