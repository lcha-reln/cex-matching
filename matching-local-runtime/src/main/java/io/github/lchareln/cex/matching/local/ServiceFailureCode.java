package io.github.lchareln.cex.matching.local;

/** Failure that explicitly completes an admitted item without fabricating a submission result. */
public enum ServiceFailureCode {
  /** The runtime returned a fail-closed outcome, so later accepted items were not submitted. */
  RUNTIME_FAILED_CLOSED,

  /** The worker or runtime threw outside the existing {@link SubmissionResult} grammar. */
  UNEXPECTED_WORKER_FAILURE,

  /** The owner-worker checkpoint failed; the runtime cannot safely resume. */
  CHECKPOINT_FAILED,

  /** The worker was interrupted while the service was still accepting. */
  UNEXPECTED_WORKER_INTERRUPTION
}
