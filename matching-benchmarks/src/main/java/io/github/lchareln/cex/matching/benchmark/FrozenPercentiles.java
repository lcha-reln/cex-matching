package io.github.lchareln.cex.matching.benchmark;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Repository-owned nearest-rank percentiles over persisted raw integer samples. */
public final class FrozenPercentiles {
  public static final List<Double> QUANTILES = List.of(0.5, 0.95, 0.99, 0.999);
  public static final String RANK_RULE = "NEAREST_RANK_CEIL_Q_TIMES_N";

  private FrozenPercentiles() {}

  public static long nearestRank(List<Long> rawSamples, double quantile) {
    if (rawSamples.isEmpty()) {
      throw new IllegalArgumentException("at least one raw sample is required");
    }
    if (!(quantile > 0.0 && quantile <= 1.0) || !Double.isFinite(quantile)) {
      throw new IllegalArgumentException("quantile must be finite and in (0, 1]");
    }
    List<Long> sorted = new ArrayList<>(rawSamples.size());
    for (Long sample : rawSamples) {
      if (sample == null || sample < 0) {
        throw new IllegalArgumentException("samples must be non-null and non-negative");
      }
      sorted.add(sample);
    }
    sorted.sort(Long::compareTo);
    int numerator = frozenPermilleNumerator(quantile);
    long rank = Math.ceilDiv(Math.multiplyExact((long) sorted.size(), numerator), 1_000L);
    int index = Math.toIntExact(Math.max(0, Math.min(sorted.size() - 1L, rank - 1L)));
    return sorted.get(index);
  }

  private static int frozenPermilleNumerator(double quantile) {
    if (Double.compare(quantile, 0.5) == 0) {
      return 500;
    }
    if (Double.compare(quantile, 0.95) == 0) {
      return 950;
    }
    if (Double.compare(quantile, 0.99) == 0) {
      return 990;
    }
    if (Double.compare(quantile, 0.999) == 0) {
      return 999;
    }
    throw new IllegalArgumentException("quantile is not one of the frozen M10 quantiles");
  }

  public static Map<String, Long> frozen(List<Long> rawSamples) {
    Map<String, Long> values = new LinkedHashMap<>();
    values.put("p50", nearestRank(rawSamples, 0.5));
    values.put("p95", nearestRank(rawSamples, 0.95));
    values.put("p99", nearestRank(rawSamples, 0.99));
    values.put("p99_9", nearestRank(rawSamples, 0.999));
    return Map.copyOf(values);
  }

  static long nearestRank(long[] rawSamples, int quantilePermilleNumerator) {
    if (rawSamples.length == 0) {
      throw new IllegalArgumentException("at least one raw sample is required");
    }
    if (quantilePermilleNumerator != 500
        && quantilePermilleNumerator != 950
        && quantilePermilleNumerator != 990
        && quantilePermilleNumerator != 999) {
      throw new IllegalArgumentException("quantile is not frozen by M10");
    }
    long[] sorted = rawSamples.clone();
    for (long sample : sorted) {
      if (sample < 0) {
        throw new IllegalArgumentException("samples must be non-negative");
      }
    }
    java.util.Arrays.sort(sorted);
    long rank =
        Math.ceilDiv(Math.multiplyExact((long) sorted.length, quantilePermilleNumerator), 1_000L);
    return sorted[Math.toIntExact(rank - 1)];
  }

  static Map<String, Long> frozen(long[] rawSamples) {
    Map<String, Long> values = new LinkedHashMap<>();
    values.put("p50", nearestRank(rawSamples, 500));
    values.put("p95", nearestRank(rawSamples, 950));
    values.put("p99", nearestRank(rawSamples, 990));
    values.put("p99_9", nearestRank(rawSamples, 999));
    return Map.copyOf(values);
  }
}
