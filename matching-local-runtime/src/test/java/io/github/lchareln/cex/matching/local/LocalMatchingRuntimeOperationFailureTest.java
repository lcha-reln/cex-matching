package io.github.lchareln.cex.matching.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalMatchingRuntimeOperationFailureTest {
  private static final long SHARD = 81;
  private final M08EnvelopeCodec codec = new M08EnvelopeCodec();

  @TempDir Path temporaryDirectory;

  @Test
  void writeBodyAndRecordForceCanFailBeforeTheOperationWithoutAck() throws Exception {
    OperationOutcome body = submitFailure(FaultPoint.BEFORE_RECORD_BODY_WRITE, "body");
    assertEquals(0, body.recoveredApplyCount());
    assertEquals(1, body.recoveredNextWalSequence());

    OperationOutcome force = submitFailure(FaultPoint.BEFORE_RECORD_FORCE, "force");
    assertEquals(1, force.recoveredApplyCount());
    assertEquals(2, force.recoveredNextWalSequence());
  }

  @Test
  void headerForceAtomicMoveAndDirectoryForceFailBeforeOperationAndOpenFails() throws Exception {
    List<FaultPoint> points =
        List.of(
            FaultPoint.BEFORE_SEGMENT_HEADER_WRITE,
            FaultPoint.BEFORE_SEGMENT_HEADER_FORCE,
            FaultPoint.BEFORE_SEGMENT_ATOMIC_RENAME,
            FaultPoint.BEFORE_DIRECTORY_FORCE);
    for (FaultPoint point : points) {
      Path directory = Files.createDirectories(temporaryDirectory.resolve(point.name()));
      WalConfig config = WalConfig.defaults(directory, SHARD);
      assertThrows(
          IOException.class, () -> LocalMatchingRuntime.open(config, new OneShotFault(point)));
      try (LocalMatchingRuntime recovered = LocalMatchingRuntime.open(config)) {
        assertEquals(1, recovered.nextWalSequence());
        assertInstanceOf(SubmissionResult.NewDurablyApplied.class, recovered.submit(envelope(1)));
      }
      try (var paths = Files.list(directory)) {
        assertTrue(paths.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")));
      }
    }
  }

  @Test
  void lockAcquisitionCanFailBeforeTryLockAndLeavesNoOwner() throws Exception {
    Path directory = Files.createDirectories(temporaryDirectory.resolve("lock"));
    WalConfig config = WalConfig.defaults(directory, SHARD);
    assertThrows(
        IOException.class,
        () ->
            LocalMatchingRuntime.open(config, new OneShotFault(FaultPoint.BEFORE_DIRECTORY_LOCK)));
    try (LocalMatchingRuntime recovered = LocalMatchingRuntime.open(config)) {
      assertEquals(1, recovered.nextWalSequence());
    }
  }

  @Test
  void finalTailTruncateForceCanFailBeforeForceAndRecoveryDoesNotOpen() throws Exception {
    Path directory = Files.createDirectories(temporaryDirectory.resolve("tail-force"));
    WalConfig config = WalConfig.defaults(directory, SHARD);
    try (LocalMatchingRuntime runtime =
        LocalMatchingRuntime.open(config, new OneShotFault(FaultPoint.AFTER_RECORD_LENGTH_WRITE))) {
      assertInstanceOf(SubmissionResult.DurabilityUnknown.class, runtime.submit(envelope(1)));
    }

    assertThrows(
        IOException.class,
        () ->
            LocalMatchingRuntime.open(
                config, new OneShotFault(FaultPoint.BEFORE_TAIL_TRUNCATE_FORCE)));
    assertThrows(
        IOException.class,
        () ->
            LocalMatchingRuntime.open(
                config, new OneShotFault(FaultPoint.BEFORE_RECOVERY_ACTIVE_FORCE)));
    assertThrows(
        IOException.class,
        () ->
            LocalMatchingRuntime.open(
                config, new OneShotFault(FaultPoint.BEFORE_RECOVERY_DIRECTORY_FORCE)));
    try (LocalMatchingRuntime recovered = LocalMatchingRuntime.open(config)) {
      assertEquals(1, recovered.nextWalSequence());
      assertInstanceOf(SubmissionResult.NewDurablyApplied.class, recovered.submit(envelope(1)));
    }
  }

  private OperationOutcome submitFailure(FaultPoint point, String name) throws Exception {
    Path directory = Files.createDirectories(temporaryDirectory.resolve(name));
    WalConfig config = WalConfig.defaults(directory, SHARD);
    try (LocalMatchingRuntime runtime =
        LocalMatchingRuntime.open(config, new OneShotFault(point))) {
      SubmissionResult.DurabilityUnknown unknown =
          assertInstanceOf(SubmissionResult.DurabilityUnknown.class, runtime.submit(envelope(1)));
      assertEquals("APPEND_OR_FORCE", unknown.stage());
      assertEquals(RuntimeState.FAILED_CLOSED, runtime.state());
      assertInstanceOf(SubmissionResult.FailedClosed.class, runtime.submit(envelope(1)));
    }
    TestCommandApplier recoveredApplier = new TestCommandApplier();
    try (LocalMatchingRuntime recovered =
        LocalMatchingRuntime.openForTesting(config, recoveredApplier, FaultInjector.NONE)) {
      return new OperationOutcome(recoveredApplier.applied().size(), recovered.nextWalSequence());
    }
  }

  @Test
  void missingWalDirectoryFailsOpenAndIsNotCreatedByRuntime() {
    Path missing = temporaryDirectory.resolve("missing");
    assertThrows(
        IOException.class, () -> LocalMatchingRuntime.open(WalConfig.defaults(missing, SHARD)));
    assertTrue(Files.notExists(missing));
  }

  private byte[] envelope(long sequence) {
    return codec.encode(
        "operation-failure",
        1,
        SHARD,
        sequence,
        new UUID(0x81, sequence),
        new M08Command.Cancel("BTC-USDT", BigInteger.valueOf(sequence)));
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
        throw new IOException("injected before operation " + point);
      }
    }
  }

  private record OperationOutcome(int recoveredApplyCount, long recoveredNextWalSequence) {}
}
