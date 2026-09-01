package io.github.lchareln.cex.matching.local;

/** Immutable M09S1 generation and the last command included by its state image. */
public record SnapshotAnchor(
    long generation, long shardId, long lastWalSequence, long lastApplicationSequence) {
  public SnapshotAnchor {
    if (generation <= 0 || shardId <= 0 || lastWalSequence < 0 || lastApplicationSequence < 0) {
      throw new IllegalArgumentException("invalid snapshot anchor");
    }
  }
}
