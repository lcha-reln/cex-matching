package io.github.lchareln.cex.matching.local;

import io.github.lchareln.cex.matching.testkit.M08SemanticFailure;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Testkit-only bridge for exercising deterministic apply failure without widening production API.
 */
public final class M08RuntimeJudgeProbe {
  private M08RuntimeJudgeProbe() {}

  public static Result exercisePoisonRecovery(Path directory, long shardId, byte[] envelope) {
    PoisonException livePoison = new PoisonException();
    SubmissionResult first;
    try (LocalMatchingRuntime runtime =
        LocalMatchingRuntime.openForTesting(
            WalConfig.defaults(directory, shardId),
            new PoisonApplier(livePoison),
            FaultInjector.NONE)) {
      try {
        first = runtime.submit(envelope);
      } catch (PoisonException escaped) {
        if (escaped != livePoison) {
          throw escaped;
        }
        throw new M08SemanticFailure("poison apply escaped live submit");
      }
      require(first instanceof SubmissionResult.DurabilityUnknown, "poison apply returned an ACK");
      require(runtime.state() == RuntimeState.FAILED_CLOSED, "poison apply did not fail closed");
    } catch (IOException failure) {
      throw new IllegalStateException("cannot close poison runtime", failure);
    }

    boolean poisonBlockedRecovery = false;
    PoisonException recoveryPoison = new PoisonException();
    try (LocalMatchingRuntime ignored =
        LocalMatchingRuntime.openForTesting(
            WalConfig.defaults(directory, shardId),
            new PoisonApplier(recoveryPoison),
            FaultInjector.NONE)) {
      require(ignored.state() == RuntimeState.OPEN, "unexpected poison recovery state");
      throw new M08SemanticFailure("poison command did not block recovery");
    } catch (RecoveryException expected) {
      requireExpectedRecoveryCause(expected, recoveryPoison);
      poisonBlockedRecovery = true;
    } catch (PoisonException escaped) {
      if (escaped != recoveryPoison) {
        throw escaped;
      }
      throw new M08SemanticFailure("poison apply escaped recovery");
    } catch (IOException failure) {
      throw new IllegalStateException("poison recovery I/O failed", failure);
    }

    CountingApplier repaired = new CountingApplier();
    SubmissionResult retry;
    try (LocalMatchingRuntime runtime =
        LocalMatchingRuntime.openForTesting(
            WalConfig.defaults(directory, shardId), repaired, FaultInjector.NONE)) {
      retry = runtime.submit(envelope);
      require(
          retry instanceof SubmissionResult.DuplicateReplayed, "repaired recovery lost identity");
    } catch (IOException failure) {
      throw new IllegalStateException("repaired recovery failed", failure);
    }
    return new Result(
        first.getClass().getSimpleName(),
        poisonBlockedRecovery,
        repaired.applied.size(),
        retry.getClass().getSimpleName());
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new M08SemanticFailure(message);
    }
  }

  private static void requireExpectedRecoveryCause(
      RecoveryException failure, Throwable expectedCause) {
    if (failure.getCause() != expectedCause) {
      throw new IllegalStateException("unexpected recovery failure instead of poison", failure);
    }
  }

  private static final class PoisonApplier implements CommandApplier {
    private final PoisonException poison;

    private PoisonApplier(PoisonException poison) {
      this.poison = poison;
    }

    @Override
    public boolean supports(M08Command command) {
      return true;
    }

    @Override
    public long nextApplicationSequence() {
      return 1;
    }

    @Override
    public CanonicalResult apply(M08Command command) {
      throw poison;
    }

    @Override
    public String semanticStateDigest() {
      return CanonicalResult.semanticDigest("poison", "not-applied");
    }
  }

  private static final class PoisonException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private PoisonException() {
      super("deterministic poison command");
    }
  }

  private static final class CountingApplier implements CommandApplier {
    private final List<M08Command> applied = new ArrayList<>();

    @Override
    public boolean supports(M08Command command) {
      return true;
    }

    @Override
    public long nextApplicationSequence() {
      return applied.size() + 1L;
    }

    @Override
    public CanonicalResult apply(M08Command command) {
      applied.add(command);
      long sequence = applied.size();
      return CanonicalResult.create(
          "JUDGE",
          sequence,
          List.of("APPLIED:" + command.getClass().getSimpleName()),
          "testkit-only",
          semanticStateDigest());
    }

    @Override
    public String semanticStateDigest() {
      return CanonicalResult.semanticDigest("judge", applied.toString());
    }
  }

  public record Result(
      String firstResult,
      boolean poisonBlockedRecovery,
      int repairedApplyCount,
      String exactRetryResult) {}
}
