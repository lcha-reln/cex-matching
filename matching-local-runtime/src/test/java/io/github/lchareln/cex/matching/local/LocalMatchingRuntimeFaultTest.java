package io.github.lchareln.cex.matching.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalMatchingRuntimeFaultTest {
  private static final long SHARD = 19;

  @TempDir Path temporaryDirectory;

  private final M08EnvelopeCodec codec = new M08EnvelopeCodec();

  @Test
  void tornLengthTailReturnsUnknownFailsClosedAndIsTruncatedOnRestart() throws Exception {
    Path directory = Files.createDirectories(temporaryDirectory.resolve("torn-length"));
    byte[] envelope = envelope(1, 1);
    OneShotFault fault = new OneShotFault(FaultPoint.AFTER_RECORD_LENGTH_WRITE);

    try (LocalMatchingRuntime runtime =
        LocalMatchingRuntime.openForTesting(
            WalConfig.defaults(directory, SHARD), new TestCommandApplier(), fault)) {
      SubmissionResult.DurabilityUnknown unknown =
          assertInstanceOf(SubmissionResult.DurabilityUnknown.class, runtime.submit(envelope));
      assertTrue(unknown.attemptedPosition().isPresent());
      assertEquals(RuntimeState.FAILED_CLOSED, runtime.state());
      assertInstanceOf(SubmissionResult.FailedClosed.class, runtime.submit(envelope));
    }

    TestCommandApplier recoveredApplier = new TestCommandApplier();
    try (LocalMatchingRuntime recovered =
        LocalMatchingRuntime.openForTesting(
            WalConfig.defaults(directory, SHARD), recoveredApplier, FaultInjector.NONE)) {
      assertTrue(recoveredApplier.applied().isEmpty());
      assertEquals(1, recovered.nextWalSequence());
      assertInstanceOf(SubmissionResult.NewDurablyApplied.class, recovered.submit(envelope));
    }
  }

  @Test
  void everyCompleteOrForcedCrashWindowRecoversThenReplaysExactDuplicate() throws Exception {
    List<FaultPoint> windows =
        List.of(
            FaultPoint.AFTER_RECORD_BODY_WRITE,
            FaultPoint.AFTER_RECORD_FORCE,
            FaultPoint.BEFORE_LIVE_APPLY,
            FaultPoint.AFTER_LIVE_APPLY_BEFORE_ACK);
    int index = 0;
    for (FaultPoint window : windows) {
      Path directory = Files.createDirectories(temporaryDirectory.resolve("window-" + index));
      byte[] envelope = envelope(1, index + 1L);
      TestCommandApplier firstApplier = new TestCommandApplier();
      try (LocalMatchingRuntime runtime =
          LocalMatchingRuntime.openForTesting(
              WalConfig.defaults(directory, SHARD), firstApplier, new OneShotFault(window))) {
        SubmissionResult.DurabilityUnknown unknown =
            assertInstanceOf(SubmissionResult.DurabilityUnknown.class, runtime.submit(envelope));
        assertEquals(RuntimeState.FAILED_CLOSED, runtime.state());
        assertTrue(unknown.attemptedPosition().isPresent());
        if (window == FaultPoint.AFTER_LIVE_APPLY_BEFORE_ACK) {
          assertEquals(1, firstApplier.applied().size());
        }
      }

      TestCommandApplier recoveredApplier = new TestCommandApplier();
      try (LocalMatchingRuntime recovered =
          LocalMatchingRuntime.openForTesting(
              WalConfig.defaults(directory, SHARD), recoveredApplier, FaultInjector.NONE)) {
        assertEquals(1, recoveredApplier.applied().size());
        SubmissionResult.DuplicateReplayed duplicate =
            assertInstanceOf(SubmissionResult.DuplicateReplayed.class, recovered.submit(envelope));
        assertEquals(1, duplicate.originalPosition().walSequence());
        assertEquals(1, duplicate.originalResult().applicationSequence());
        assertEquals(2, recovered.nextWalSequence());
      }
      index++;
    }
  }

  @Test
  void recoveryFaultNeverOpensADegradedRuntime() throws Exception {
    Path directory = Files.createDirectories(temporaryDirectory.resolve("recovery-fault"));
    byte[] envelope = envelope(1, 1);
    try (LocalMatchingRuntime runtime =
        LocalMatchingRuntime.openForTesting(
            WalConfig.defaults(directory, SHARD), new TestCommandApplier(), FaultInjector.NONE)) {
      assertInstanceOf(SubmissionResult.NewDurablyApplied.class, runtime.submit(envelope));
    }

    assertInstanceOf(
        RecoveryException.class,
        org.junit.jupiter.api.Assertions.assertThrows(
            RecoveryException.class,
            () ->
                LocalMatchingRuntime.openForTesting(
                    WalConfig.defaults(directory, SHARD),
                    new TestCommandApplier(),
                    new OneShotFault(FaultPoint.BEFORE_RECOVERY_APPLY))));

    try (LocalMatchingRuntime recovered =
        LocalMatchingRuntime.openForTesting(
            WalConfig.defaults(directory, SHARD), new TestCommandApplier(), FaultInjector.NONE)) {
      assertInstanceOf(SubmissionResult.DuplicateReplayed.class, recovered.submit(envelope));
      assertFalse(recovered.semanticStateDigest().isBlank());
    }

    Path poisonDirectory = Files.createDirectories(temporaryDirectory.resolve("apply-poison"));
    try (LocalMatchingRuntime poison =
        LocalMatchingRuntime.openForTesting(
            WalConfig.defaults(poisonDirectory, SHARD), new PoisonApplier(), FaultInjector.NONE)) {
      assertInstanceOf(SubmissionResult.DurabilityUnknown.class, poison.submit(envelope));
      assertEquals(RuntimeState.FAILED_CLOSED, poison.state());
    }
    org.junit.jupiter.api.Assertions.assertThrows(
        RecoveryException.class,
        () ->
            LocalMatchingRuntime.openForTesting(
                WalConfig.defaults(poisonDirectory, SHARD),
                new PoisonApplier(),
                FaultInjector.NONE));
    try (LocalMatchingRuntime repaired =
        LocalMatchingRuntime.openForTesting(
            WalConfig.defaults(poisonDirectory, SHARD),
            new TestCommandApplier(),
            FaultInjector.NONE)) {
      assertInstanceOf(SubmissionResult.DuplicateReplayed.class, repaired.submit(envelope));
    }
  }

  private byte[] envelope(long sequence, long orderId) {
    return codec.encode(
        "producer-a",
        1,
        SHARD,
        sequence,
        new UUID(0x99, sequence),
        new M08Command.Cancel("BTC-USDT", BigInteger.valueOf(orderId)));
  }

  private static final class OneShotFault implements FaultInjector {
    private final FaultPoint target;
    private boolean thrown;

    private OneShotFault(FaultPoint target) {
      this.target = target;
    }

    @Override
    public void hit(FaultPoint point) throws IOException {
      if (!thrown && point == target) {
        thrown = true;
        throw new IOException("injected " + point);
      }
    }
  }

  private static final class PoisonApplier implements CommandApplier {
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
      throw new IllegalStateException("poison command");
    }

    @Override
    public String semanticStateDigest() {
      return CanonicalResult.semanticDigest("poison", "not-applied");
    }
  }
}
