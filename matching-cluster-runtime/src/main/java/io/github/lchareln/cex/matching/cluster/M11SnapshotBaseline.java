package io.github.lchareln.cex.matching.cluster;

/** Snapshot completion counter and prior RecordingLog identities sampled before a request. */
public record M11SnapshotBaseline(
    long completionCount, long serviceRecordingId, long consensusRecordingId) {}
