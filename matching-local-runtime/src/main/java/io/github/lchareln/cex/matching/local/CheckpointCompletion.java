package io.github.lchareln.cex.matching.local;

import java.util.Objects;

/** Exactly one terminal completion for an admitted checkpoint maintenance operation. */
public sealed interface CheckpointCompletion
    permits CheckpointCompletion.Completed, CheckpointCompletion.ExplicitFailure {
  long workSequence();

  long ownerCompletedNanos();

  /** The exact checkpoint result returned on the owner worker. */
  record Completed(long workSequence, long ownerCompletedNanos, CheckpointResult result)
      implements CheckpointCompletion {
    public Completed {
      if (workSequence <= 0 || ownerCompletedNanos <= 0) {
        throw new IllegalArgumentException("workSequence and ownerCompletedNanos must be positive");
      }
      Objects.requireNonNull(result, "result");
    }
  }

  /** Checkpoint execution failed and the service entered its fail-closed state. */
  record ExplicitFailure(
      long workSequence, long ownerCompletedNanos, ServiceFailureCode code, String detail)
      implements CheckpointCompletion {
    public ExplicitFailure {
      if (workSequence <= 0 || ownerCompletedNanos <= 0) {
        throw new IllegalArgumentException("workSequence and ownerCompletedNanos must be positive");
      }
      Objects.requireNonNull(code, "code");
      Objects.requireNonNull(detail, "detail");
    }
  }
}
