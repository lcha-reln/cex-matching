package io.github.lchareln.cex.matching.benchmark;

/** Persisted raw counters needed to derive one M10 measured point. */
public record RateMeasurement(
    long offeredRate,
    int queueCapacity,
    long admitted,
    long completed,
    long overloaded,
    long startingBacklog,
    long endingBacklog,
    long p99QueueDepth,
    long postCutOverloaded) {
  /** Compatibility constructor for model-only callers with no producer-closure overload. */
  public RateMeasurement(
      long offeredRate,
      int queueCapacity,
      long admitted,
      long completed,
      long overloaded,
      long startingBacklog,
      long endingBacklog,
      long p99QueueDepth) {
    this(
        offeredRate,
        queueCapacity,
        admitted,
        completed,
        overloaded,
        startingBacklog,
        endingBacklog,
        p99QueueDepth,
        0);
  }

  public RateMeasurement {
    if (offeredRate <= 0 || queueCapacity <= 0) {
      throw new IllegalArgumentException("offered rate and queue capacity must be positive");
    }
    if (admitted < 0
        || completed < 0
        || overloaded < 0
        || startingBacklog < 0
        || endingBacklog < 0
        || p99QueueDepth < 0
        || postCutOverloaded < 0
        || p99QueueDepth > queueCapacity) {
      throw new IllegalArgumentException("measurement counters must be non-negative");
    }
    if (completed > admitted) {
      throw new IllegalArgumentException("completed cannot exceed admitted");
    }
  }

  public long backlogGrowth() {
    return Math.max(0, endingBacklog - startingBacklog);
  }
}
