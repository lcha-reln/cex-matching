package io.github.lchareln.cex.matching.cluster;

import io.aeron.cluster.ClusterControl;

/** Durable completion witness sampled after Aeron appended and forced its RecordingLog entries. */
public record M11SnapshotCompletion(
    long completionCountBefore,
    long completionCountAfter,
    ClusterControl.ToggleState controlToggleState,
    long leadershipTermId,
    long logPosition,
    long serviceRecordingId,
    long consensusRecordingId) {
  public M11SnapshotCompletion {
    if (completionCountAfter <= completionCountBefore) {
      throw new IllegalArgumentException("snapshot completion counter did not advance");
    }
    if (controlToggleState != ClusterControl.ToggleState.NEUTRAL) {
      throw new IllegalArgumentException("snapshot control toggle did not reset to NEUTRAL");
    }
    if (leadershipTermId < 0
        || logPosition < 0
        || serviceRecordingId < 0
        || consensusRecordingId < 0) {
      throw new IllegalArgumentException("snapshot RecordingLog witness is incomplete");
    }
  }
}
