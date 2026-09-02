package io.github.lchareln.cex.matching.local;

import java.util.Objects;

/** Result of the bounded admission decision; enqueue is deliberately not a business ACK. */
public sealed interface AdmissionResult permits AdmissionResult.Enqueued, AdmissionResult.Rejected {

  /**
   * Ownership was transferred to the bounded service.
   *
   * <p>The completion is the only place where a submission result or explicit service failure can
   * appear. This admission result is not durable acknowledgement.
   */
  record Enqueued(
      long workSequence,
      long admissionSequence,
      int decisionQueueDepth,
      long decisionNanos,
      CompletionHandle<ServiceCompletion> completion)
      implements AdmissionResult {
    public Enqueued {
      if (workSequence <= 0 || admissionSequence <= 0 || decisionNanos <= 0) {
        throw new IllegalArgumentException("admission identity and decision time must be positive");
      }
      if (decisionQueueDepth < 0) {
        throw new IllegalArgumentException("decisionQueueDepth must not be negative");
      }
      Objects.requireNonNull(completion, "completion");
    }
  }

  /** No ownership was transferred and no completion will follow. */
  record Rejected(
      AdmissionRejectionCode code, String detail, int decisionQueueDepth, long decisionNanos)
      implements AdmissionResult {
    public Rejected {
      Objects.requireNonNull(code, "code");
      Objects.requireNonNull(detail, "detail");
      if (decisionQueueDepth < 0 || decisionNanos <= 0) {
        throw new IllegalArgumentException(
            "decisionQueueDepth must not be negative and decisionNanos must be positive");
      }
    }
  }
}
