package io.github.lchareln.cex.matching.local;

import java.util.Objects;
import java.util.Optional;

/** Exhaustive local submission result; only durable outcomes are acknowledgements. */
public sealed interface SubmissionResult
    permits SubmissionResult.NewDurablyApplied,
        SubmissionResult.DuplicateReplayed,
        SubmissionResult.StructuralRejected,
        SubmissionResult.PreflightRejected,
        SubmissionResult.DurabilityUnknown,
        SubmissionResult.FailedClosed {

  record NewDurablyApplied(WalPosition position, CanonicalResult result)
      implements SubmissionResult {
    public NewDurablyApplied {
      Objects.requireNonNull(position, "position");
      Objects.requireNonNull(result, "result");
    }
  }

  record DuplicateReplayed(WalPosition originalPosition, CanonicalResult originalResult)
      implements SubmissionResult {
    public DuplicateReplayed {
      Objects.requireNonNull(originalPosition, "originalPosition");
      Objects.requireNonNull(originalResult, "originalResult");
    }
  }

  record StructuralRejected(StructuralRejectionCode code, String detail)
      implements SubmissionResult {
    public StructuralRejected {
      Objects.requireNonNull(code, "code");
      Objects.requireNonNull(detail, "detail");
    }
  }

  record PreflightRejected(PreflightRejectionCode code) implements SubmissionResult {
    public PreflightRejected {
      Objects.requireNonNull(code, "code");
    }
  }

  record DurabilityUnknown(Optional<WalPosition> attemptedPosition, String stage, String detail)
      implements SubmissionResult {
    public DurabilityUnknown {
      attemptedPosition = Objects.requireNonNull(attemptedPosition, "attemptedPosition");
      Objects.requireNonNull(stage, "stage");
      Objects.requireNonNull(detail, "detail");
    }
  }

  record FailedClosed(String detail) implements SubmissionResult {
    public FailedClosed {
      Objects.requireNonNull(detail, "detail");
    }
  }
}
