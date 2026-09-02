package io.github.lchareln.cex.matching.local;

/** Stable accounting dimension for every variant of {@link SubmissionResult}. */
public enum SubmissionResultVariant {
  NEW_DURABLY_APPLIED,
  DUPLICATE_REPLAYED,
  STRUCTURAL_REJECTED,
  PREFLIGHT_REJECTED,
  CHECKPOINT_REQUIRED,
  DURABILITY_UNKNOWN,
  FAILED_CLOSED;

  public static SubmissionResultVariant from(SubmissionResult result) {
    return switch (result) {
      case SubmissionResult.NewDurablyApplied ignored -> NEW_DURABLY_APPLIED;
      case SubmissionResult.DuplicateReplayed ignored -> DUPLICATE_REPLAYED;
      case SubmissionResult.StructuralRejected ignored -> STRUCTURAL_REJECTED;
      case SubmissionResult.PreflightRejected ignored -> PREFLIGHT_REJECTED;
      case SubmissionResult.CheckpointRequired ignored -> CHECKPOINT_REQUIRED;
      case SubmissionResult.DurabilityUnknown ignored -> DURABILITY_UNKNOWN;
      case SubmissionResult.FailedClosed ignored -> FAILED_CLOSED;
    };
  }

  public boolean durableAcknowledgement() {
    return this == NEW_DURABLY_APPLIED || this == DUPLICATE_REPLAYED;
  }
}
