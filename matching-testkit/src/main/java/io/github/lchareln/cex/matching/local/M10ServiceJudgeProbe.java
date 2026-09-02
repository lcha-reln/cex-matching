package io.github.lchareln.cex.matching.local;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.TimeoutException;

/** Same-package judge bridge for identity-level result pass-through at the owner-worker seam. */
public final class M10ServiceJudgeProbe {
  private M10ServiceJudgeProbe() {}

  public static Result verifyExactSubmissionResultPassThrough() {
    byte[] offered = {1, 2, 3, 4};
    byte[] expectedBytes = offered.clone();
    SubmissionResult returned =
        new SubmissionResult.StructuralRejected(
            StructuralRejectionCode.UNSUPPORTED_COMMAND, "m10 exact-instance probe");
    RecordingPort port = new RecordingPort(returned);
    try (LocalMatchingService service =
        LocalMatchingService.openForTesting(new ServiceConfig(2), port)) {
      AdmissionResult admission = service.trySubmit(offered);
      if (!(admission instanceof AdmissionResult.Enqueued enqueued)) {
        throw new IllegalStateException("judge probe was not enqueued: " + admission);
      }
      Arrays.fill(offered, (byte) 0x7f);
      ServiceCompletion terminal = enqueued.completion().await(Duration.ofSeconds(5));
      if (!(terminal instanceof ServiceCompletion.SubmissionCompleted completed)) {
        throw new IllegalStateException("judge probe did not receive a submission result");
      }
      return new Result(
          completed.result() == returned,
          Arrays.equals(expectedBytes, port.observedBytes),
          port.submissions,
          service.metrics().submissionResultVariantsReconcile());
    } catch (IOException failure) {
      throw new IllegalStateException("judge probe close failed", failure);
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("judge probe interrupted", failure);
    } catch (TimeoutException failure) {
      throw new IllegalStateException("judge probe completion failed", failure);
    }
  }

  public record Result(
      boolean sameSubmissionResultInstance,
      boolean callerBytesOwned,
      int submissions,
      boolean variantAccountingReconciles) {}

  private static final class RecordingPort implements LocalMatchingService.RuntimePort {
    private final SubmissionResult returned;
    private volatile byte[] observedBytes = new byte[0];
    private volatile int submissions;

    RecordingPort(SubmissionResult returned) {
      this.returned = returned;
    }

    @Override
    public SubmissionResult submit(byte[] canonicalEnvelope) {
      observedBytes = canonicalEnvelope.clone();
      submissions++;
      return returned;
    }

    @Override
    public CheckpointResult checkpoint() {
      throw new UnsupportedOperationException("checkpoint is outside this probe");
    }

    @Override
    public RuntimeState state() {
      return RuntimeState.OPEN;
    }

    @Override
    public void close() {}
  }
}
