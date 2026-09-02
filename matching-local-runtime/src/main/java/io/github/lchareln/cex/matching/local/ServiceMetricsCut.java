package io.github.lchareln.cex.matching.local;

import java.util.Objects;

/** One accounting snapshot and its monotonic observation time captured under the service gate. */
public record ServiceMetricsCut(long cutToken, long observedNanos, ServiceMetricsSnapshot metrics) {
  public ServiceMetricsCut {
    if (cutToken <= 0 || observedNanos <= 0) {
      throw new IllegalArgumentException("metrics cut identity and time must be positive");
    }
    Objects.requireNonNull(metrics, "metrics");
  }
}
