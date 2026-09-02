package io.github.lchareln.cex.matching.local;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** One immutable accounting observation from {@link LocalMatchingService}. */
public record ServiceMetricsSnapshot(
    ServiceState state,
    int queueCapacity,
    int queueDepth,
    int maximumQueueDepth,
    long offers,
    long admitted,
    long overloaded,
    long closedOrInvalid,
    long submissionResultCompletions,
    long explicitServiceFailures,
    long pending,
    long durableAcknowledgements,
    Map<AdmissionRejectionCode, Long> rejectionCounts,
    Map<SubmissionResultVariant, Long> submissionResultCounts,
    long checkpointOffers,
    long checkpointAdmitted,
    long checkpointOverloaded,
    long checkpointClosed,
    long checkpointCompletions,
    long checkpointFailures,
    long checkpointPending,
    Map<AdmissionRejectionCode, Long> checkpointRejectionCounts) {
  public ServiceMetricsSnapshot {
    Objects.requireNonNull(state, "state");
    rejectionCounts = completeRejectionCounts(rejectionCounts);
    submissionResultCounts = completeSubmissionCounts(submissionResultCounts);
    checkpointRejectionCounts = completeRejectionCounts(checkpointRejectionCounts);
    if (queueCapacity <= 0
        || queueDepth < 0
        || queueDepth > queueCapacity
        || maximumQueueDepth < queueDepth
        || maximumQueueDepth > queueCapacity) {
      throw new IllegalArgumentException("invalid bounded queue observation");
    }
    if (offers < 0
        || admitted < 0
        || overloaded < 0
        || closedOrInvalid < 0
        || submissionResultCompletions < 0
        || explicitServiceFailures < 0
        || pending < 0
        || durableAcknowledgements < 0
        || checkpointOffers < 0
        || checkpointAdmitted < 0
        || checkpointOverloaded < 0
        || checkpointClosed < 0
        || checkpointCompletions < 0
        || checkpointFailures < 0
        || checkpointPending < 0) {
      throw new IllegalArgumentException("accounting counters must not be negative");
    }
  }

  public boolean offersReconcile() {
    return offers == admitted + overloaded + closedOrInvalid;
  }

  public boolean completionsReconcile() {
    return admitted == submissionResultCompletions + explicitServiceFailures + pending;
  }

  public boolean submissionResultVariantsReconcile() {
    return submissionResultCounts.values().stream().mapToLong(Long::longValue).sum()
        == submissionResultCompletions;
  }

  public boolean durableAcknowledgementsReconcile() {
    return durableAcknowledgements
        == submissionResultCounts.get(SubmissionResultVariant.NEW_DURABLY_APPLIED)
            + submissionResultCounts.get(SubmissionResultVariant.DUPLICATE_REPLAYED);
  }

  public boolean rejectionCountsReconcile() {
    long nonOverload =
        rejectionCounts.entrySet().stream()
            .filter(entry -> entry.getKey() != AdmissionRejectionCode.OVERLOADED_BEFORE_WAL)
            .mapToLong(Map.Entry::getValue)
            .sum();
    return overloaded == rejectionCounts.get(AdmissionRejectionCode.OVERLOADED_BEFORE_WAL)
        && closedOrInvalid == nonOverload;
  }

  /** Queue plus at most the one item owned by the single worker accounts for all pending work. */
  public boolean boundedWorkHandoffReconciles() {
    long totalPending = pending + checkpointPending;
    return totalPending >= queueDepth && totalPending <= (long) queueDepth + 1;
  }

  public boolean fullyReconciled() {
    return offersReconcile()
        && completionsReconcile()
        && submissionResultVariantsReconcile()
        && durableAcknowledgementsReconcile()
        && rejectionCountsReconcile()
        && checkpointOffersReconcile()
        && checkpointCompletionsReconcile()
        && checkpointRejectionCountsReconcile()
        && boundedWorkHandoffReconciles();
  }

  public boolean checkpointOffersReconcile() {
    return checkpointOffers == checkpointAdmitted + checkpointOverloaded + checkpointClosed;
  }

  public boolean checkpointCompletionsReconcile() {
    return checkpointAdmitted == checkpointCompletions + checkpointFailures + checkpointPending;
  }

  public boolean checkpointRejectionCountsReconcile() {
    long nonOverload =
        checkpointRejectionCounts.entrySet().stream()
            .filter(entry -> entry.getKey() != AdmissionRejectionCode.OVERLOADED_BEFORE_WAL)
            .mapToLong(Map.Entry::getValue)
            .sum();
    return checkpointOverloaded
            == checkpointRejectionCounts.get(AdmissionRejectionCode.OVERLOADED_BEFORE_WAL)
        && checkpointClosed == nonOverload;
  }

  private static Map<AdmissionRejectionCode, Long> completeRejectionCounts(
      Map<AdmissionRejectionCode, Long> counts) {
    Objects.requireNonNull(counts, "rejectionCounts");
    EnumMap<AdmissionRejectionCode, Long> complete = new EnumMap<>(AdmissionRejectionCode.class);
    for (AdmissionRejectionCode code : AdmissionRejectionCode.values()) {
      Long count = counts.getOrDefault(code, 0L);
      if (count < 0) {
        throw new IllegalArgumentException("rejection count must not be negative: " + code);
      }
      complete.put(code, count);
    }
    return Map.copyOf(complete);
  }

  private static Map<SubmissionResultVariant, Long> completeSubmissionCounts(
      Map<SubmissionResultVariant, Long> counts) {
    Objects.requireNonNull(counts, "submissionResultCounts");
    EnumMap<SubmissionResultVariant, Long> complete = new EnumMap<>(SubmissionResultVariant.class);
    for (SubmissionResultVariant variant : SubmissionResultVariant.values()) {
      Long count = counts.getOrDefault(variant, 0L);
      if (count < 0) {
        throw new IllegalArgumentException("submission count must not be negative: " + variant);
      }
      complete.put(variant, count);
    }
    return Map.copyOf(complete);
  }
}
