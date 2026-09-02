package io.github.lchareln.cex.matching.benchmark;

/** Exact load-then-recovery result bound to one local recovery trace. */
record RecoveryVerification(
    String recoveryTraceId,
    long durableOperations,
    long duplicatesReplayed,
    String liveResultDigest,
    String recoveredResultDigest,
    String directReplayResultDigest,
    String liveSemanticStateDigest,
    String recoveredSemanticStateDigest,
    String directReplaySemanticStateDigest,
    String recoveryTraceSha256,
    long configuredMaxSuffixRecords,
    long configuredMaxSuffixBytes,
    long actualSuffixRecords,
    long actualSuffixBytes,
    long recoveryElapsedNanos) {
  RecoveryVerification {
    if (recoveryTraceId == null
        || recoveryTraceId.isBlank()
        || durableOperations <= 0
        || duplicatesReplayed != durableOperations
        || !liveResultDigest.equals(recoveredResultDigest)
        || !liveResultDigest.equals(directReplayResultDigest)
        || !liveSemanticStateDigest.equals(recoveredSemanticStateDigest)
        || !liveSemanticStateDigest.equals(directReplaySemanticStateDigest)
        || !recoveryTraceSha256.matches("[0-9a-f]{64}")
        || configuredMaxSuffixRecords <= 0
        || configuredMaxSuffixBytes <= 0
        || actualSuffixRecords < 0
        || actualSuffixRecords > configuredMaxSuffixRecords
        || actualSuffixBytes < 0
        || actualSuffixBytes > configuredMaxSuffixBytes
        || recoveryElapsedNanos <= 0) {
      throw new IllegalArgumentException("load-then-recovery evidence is not exact");
    }
  }
}
