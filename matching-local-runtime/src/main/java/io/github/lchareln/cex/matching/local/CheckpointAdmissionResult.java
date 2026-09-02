package io.github.lchareln.cex.matching.local;

import java.util.Objects;

/** Bounded admission result for one owner-worker checkpoint maintenance operation. */
public sealed interface CheckpointAdmissionResult
    permits CheckpointAdmissionResult.Enqueued, CheckpointAdmissionResult.Rejected {

  /** The maintenance operation joined the same finite FIFO as business submissions. */
  record Enqueued(
      long workSequence, long decisionNanos, CompletionHandle<CheckpointCompletion> completion)
      implements CheckpointAdmissionResult {
    public Enqueued {
      if (workSequence <= 0 || decisionNanos <= 0) {
        throw new IllegalArgumentException("workSequence and decisionNanos must be positive");
      }
      Objects.requireNonNull(completion, "completion");
    }
  }

  /** The maintenance operation did not enter the owner-worker FIFO. */
  record Rejected(AdmissionRejectionCode code, String detail, long decisionNanos)
      implements CheckpointAdmissionResult {
    public Rejected {
      Objects.requireNonNull(code, "code");
      Objects.requireNonNull(detail, "detail");
      if (decisionNanos <= 0) {
        throw new IllegalArgumentException("decisionNanos must be positive");
      }
    }
  }
}
