package io.github.lchareln.cex.matching.cluster;

/** Durable completion witness sampled after Aeron appended and forced its RecordingLog entries. */
public record M11SnapshotCompletion(
    long completionCountBefore,
    long completionCountAfter,
    M11SnapshotControlToggleState controlToggleState,
    long previousServiceRecordingId,
    long previousConsensusRecordingId,
    long serviceLeadershipTermId,
    long consensusLeadershipTermId,
    long serviceLogPosition,
    long consensusLogPosition,
    long serviceRecordingId,
    long consensusRecordingId) {
  public M11SnapshotCompletion {
    if (completionCountAfter <= completionCountBefore) {
      throw new IllegalArgumentException("snapshot completion counter did not advance");
    }
    if (controlToggleState != M11SnapshotControlToggleState.NEUTRAL) {
      throw new IllegalArgumentException("snapshot control toggle did not reset to NEUTRAL");
    }
    if (serviceLeadershipTermId < 0
        || consensusLeadershipTermId < 0
        || serviceLogPosition < 0
        || consensusLogPosition < 0
        || serviceRecordingId < 0
        || consensusRecordingId < 0) {
      throw new IllegalArgumentException("snapshot RecordingLog witness is incomplete");
    }
    if (serviceRecordingId == previousServiceRecordingId
        || consensusRecordingId == previousConsensusRecordingId) {
      throw new IllegalArgumentException("snapshot RecordingLog entries did not advance");
    }
    if (serviceLeadershipTermId != consensusLeadershipTermId
        || serviceLogPosition != consensusLogPosition) {
      throw new IllegalArgumentException(
          "consensus and service snapshot entries do not share a term and log position");
    }
  }

  public boolean recordingIdsChanged() {
    return serviceRecordingId != previousServiceRecordingId
        && consensusRecordingId != previousConsensusRecordingId;
  }

  public boolean sameTermAndLogPosition() {
    return serviceLeadershipTermId == consensusLeadershipTermId
        && serviceLogPosition == consensusLogPosition;
  }

  /** Shared term retained as a compatibility convenience for existing M11 report code. */
  public long leadershipTermId() {
    return serviceLeadershipTermId;
  }

  /** Shared log position retained as a compatibility convenience for existing M11 report code. */
  public long logPosition() {
    return serviceLogPosition;
  }
}
