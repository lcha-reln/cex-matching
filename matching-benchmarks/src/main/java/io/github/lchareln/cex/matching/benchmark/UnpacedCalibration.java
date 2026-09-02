package io.github.lchareln.cex.matching.benchmark;

import io.github.lchareln.cex.matching.local.AdmissionResult;
import io.github.lchareln.cex.matching.local.LocalMatchingService;
import io.github.lchareln.cex.matching.local.M08Command;
import io.github.lchareln.cex.matching.local.M08EnvelopeCodec;
import io.github.lchareln.cex.matching.local.ServiceCompletion;
import io.github.lchareln.cex.matching.local.SubmissionResult;
import java.math.BigInteger;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

/** No-pacer calibration loop; its output selects a rate and never enters latency evidence. */
final class UnpacedCalibration {
  private static final Duration COMPLETION_TIMEOUT = Duration.ofSeconds(30);
  private final M08EnvelopeCodec codec = new M08EnvelopeCodec();

  CalibrationResult run(LocalMatchingService service, Duration duration) {
    long start = System.nanoTime();
    long deadline = Math.addExact(start, duration.toNanos());
    long logical = 0;
    long durable = 0;
    while (System.nanoTime() < deadline) {
      logical = Math.incrementExact(logical);
      byte[] envelope = envelope(logical);
      while (true) {
        AdmissionResult admission = service.trySubmit(envelope);
        if (!(admission instanceof AdmissionResult.Enqueued enqueued)) {
          throw new IllegalStateException("serial unpaced calibration unexpectedly rejected");
        }
        ServiceCompletion completion = await(enqueued);
        if (!(completion instanceof ServiceCompletion.SubmissionCompleted submitted)) {
          throw new IllegalStateException("calibration submission failed explicitly");
        }
        if (submitted.result() instanceof SubmissionResult.CheckpointRequired) {
          throw new IllegalStateException(
              "qualification calibration encountered CheckpointRequired; the finite WAL budget "
                  + "does not cover the configured calibration duration");
        }
        if (!(submitted.result() instanceof SubmissionResult.NewDurablyApplied)) {
          throw new IllegalStateException(
              "calibration produced non-durable terminal result: "
                  + submitted.result().getClass().getSimpleName());
        }
        durable = Math.incrementExact(durable);
        break;
      }
    }
    long elapsed = System.nanoTime() - start;
    long reference = Math.floorDiv(Math.multiplyExact(durable, 1_000_000_000L), elapsed);
    return new CalibrationResult(elapsed, logical, durable, 0, Math.max(1, reference));
  }

  private static ServiceCompletion await(AdmissionResult.Enqueued enqueued) {
    try {
      return enqueued.completion().await(COMPLETION_TIMEOUT);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("calibration completion wait interrupted", interrupted);
    } catch (TimeoutException timeout) {
      throw new IllegalStateException("calibration completion wait timed out", timeout);
    }
  }

  private byte[] envelope(long sequence) {
    M08Command command =
        new M08Command.Place(
            "BTC-USDT",
            BigInteger.valueOf(sequence),
            "BUY",
            BigInteger.valueOf(100),
            BigInteger.ONE,
            "IOC",
            0,
            "NONE",
            Optional.empty());
    return codec.encode(
        "m10-calibration", 1, 1, sequence, new UUID(0x4d313043414c4942L, sequence), command);
  }
}
