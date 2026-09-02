package io.github.lchareln.cex.matching.local;

import java.util.Objects;

/** Exactly one terminal completion for an admitted item. */
public sealed interface ServiceCompletion
    permits ServiceCompletion.SubmissionCompleted, ServiceCompletion.ExplicitFailure {
  long workSequence();

  long admissionSequence();

  long ownerCompletedNanos();

  /** The exact {@link SubmissionResult} object returned by {@link LocalMatchingRuntime}. */
  record SubmissionCompleted(
      long workSequence, long admissionSequence, long ownerCompletedNanos, SubmissionResult result)
      implements ServiceCompletion {
    public SubmissionCompleted {
      if (workSequence <= 0 || admissionSequence <= 0 || ownerCompletedNanos <= 0) {
        throw new IllegalArgumentException("completion identity and owner time must be positive");
      }
      Objects.requireNonNull(result, "result");
    }
  }

  /** The service could not invoke or finish the unchanged local submission boundary. */
  record ExplicitFailure(
      long workSequence,
      long admissionSequence,
      long ownerCompletedNanos,
      ServiceFailureCode code,
      String detail)
      implements ServiceCompletion {
    public ExplicitFailure {
      if (workSequence <= 0 || admissionSequence <= 0 || ownerCompletedNanos <= 0) {
        throw new IllegalArgumentException("completion identity and owner time must be positive");
      }
      Objects.requireNonNull(code, "code");
      Objects.requireNonNull(detail, "detail");
    }
  }
}
