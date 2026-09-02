package io.github.lchareln.cex.matching.benchmark;

/** One raw resource point; every dimension is cumulative or an explicitly named gauge. */
public record ResourceObservation(
    long observedNanos,
    long totalThreadAllocatedBytes,
    long garbageCollectionCount,
    long garbageCollectionMillis,
    long processCpuNanos,
    long heapUsedBytes,
    long committedVirtualMemoryBytes,
    long systemMemoryUsedBytes,
    int queueDepth) {
  public ResourceObservation {
    if (observedNanos < 0
        || totalThreadAllocatedBytes < 0
        || garbageCollectionCount < 0
        || garbageCollectionMillis < 0
        || processCpuNanos < 0
        || heapUsedBytes < 0
        || committedVirtualMemoryBytes < 0
        || systemMemoryUsedBytes < 0
        || queueDepth < 0) {
      throw new IllegalArgumentException("resource dimensions must be present and non-negative");
    }
  }
}
