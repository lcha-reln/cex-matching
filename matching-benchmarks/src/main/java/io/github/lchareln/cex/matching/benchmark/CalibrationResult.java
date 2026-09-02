package io.github.lchareln.cex.matching.benchmark;

/** Unpaced calibration selects a reference rate but contributes no release latency samples. */
public record CalibrationResult(
    long elapsedNanos,
    long logicalOperations,
    long durableCompletions,
    long checkpointCount,
    long referenceRateOperationsPerSecond) {
  public CalibrationResult {
    if (elapsedNanos <= 0
        || logicalOperations <= 0
        || durableCompletions <= 0
        || checkpointCount < 0
        || referenceRateOperationsPerSecond <= 0
        || durableCompletions > logicalOperations) {
      throw new IllegalArgumentException("invalid unpaced calibration result");
    }
  }
}
