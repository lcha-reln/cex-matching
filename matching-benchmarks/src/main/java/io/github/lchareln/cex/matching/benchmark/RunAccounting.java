package io.github.lchareln.cex.matching.benchmark;

import java.util.Map;
import java.util.Objects;

/** Terminal or observation-cut counters copied from raw offer and completion records. */
public record RunAccounting(
    long offers,
    long admitted,
    long overloaded,
    long closedOrInvalid,
    Map<String, Long> submissionResultVariants,
    long explicitServiceFailures,
    long pendingAtObservationCut) {
  public RunAccounting {
    submissionResultVariants = Map.copyOf(submissionResultVariants);
    requireNonNegative(offers, "offers");
    requireNonNegative(admitted, "admitted");
    requireNonNegative(overloaded, "overloaded");
    requireNonNegative(closedOrInvalid, "closedOrInvalid");
    requireNonNegative(explicitServiceFailures, "explicitServiceFailures");
    requireNonNegative(pendingAtObservationCut, "pendingAtObservationCut");
    for (Map.Entry<String, Long> entry : submissionResultVariants.entrySet()) {
      Objects.requireNonNull(entry.getKey(), "submission result variant");
      requireNonNegative(Objects.requireNonNull(entry.getValue(), entry.getKey()), entry.getKey());
    }
  }

  public long submissionResultCompletions() {
    return submissionResultVariants.values().stream().mapToLong(Long::longValue).sum();
  }

  public long terminalCompletions() {
    return submissionResultCompletions() + explicitServiceFailures;
  }

  public long durableAcknowledgements() {
    return submissionResultVariants.getOrDefault("NEW_DURABLY_APPLIED", 0L)
        + submissionResultVariants.getOrDefault("DUPLICATE_REPLAYED", 0L);
  }

  private static void requireNonNegative(long value, String name) {
    if (value < 0) {
      throw new IllegalArgumentException(name + " must be non-negative");
    }
  }
}
