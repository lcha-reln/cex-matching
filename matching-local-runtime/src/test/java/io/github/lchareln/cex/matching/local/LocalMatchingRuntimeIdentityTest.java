package io.github.lchareln.cex.matching.local;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lchareln.cex.matching.MarketMode;
import io.github.lchareln.cex.matching.MarketRuleSetArtifact;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalMatchingRuntimeIdentityTest {
  private static final long SHARD = 11;

  @TempDir Path temporaryDirectory;

  private final M08EnvelopeCodec codec = new M08EnvelopeCodec();

  @Test
  void structuralAndIdentityRejectionsNeverEnterWalOrApply() throws Exception {
    TestCommandApplier applier = new TestCommandApplier();
    try (LocalMatchingRuntime runtime =
        LocalMatchingRuntime.openForTesting(config("identity"), applier, FaultInjector.NONE)) {
      byte[] malformed = envelope(1, 1, uuid(1), cancel(1));
      malformed[malformed.length - 1] ^= 1;
      SubmissionResult.StructuralRejected structural =
          assertInstanceOf(SubmissionResult.StructuralRejected.class, runtime.submit(malformed));
      assertEquals(StructuralRejectionCode.PAYLOAD_HASH_MISMATCH, structural.code());
      assertEquals(1, runtime.nextWalSequence());
      assertTrue(applier.applied().isEmpty());

      assertInstanceOf(
          SubmissionResult.NewDurablyApplied.class,
          runtime.submit(envelope(1, 1, uuid(1), cancel(1))));
      assertPreflight(
          PreflightRejectionCode.PRODUCER_SEQUENCE_GAP,
          runtime.submit(envelope(1, 3, uuid(3), cancel(3))));
      assertInstanceOf(
          SubmissionResult.NewDurablyApplied.class,
          runtime.submit(envelope(1, 2, uuid(2), cancel(2))));
      assertPreflight(
          PreflightRejectionCode.SLOT_IDENTITY_CONFLICT,
          runtime.submit(envelope(1, 2, uuid(22), cancel(2))));
      assertPreflight(
          PreflightRejectionCode.COMMAND_ID_SLOT_CONFLICT,
          runtime.submit(envelope(1, 3, uuid(2), cancel(2))));
      assertPreflight(
          PreflightRejectionCode.COMMAND_ID_PAYLOAD_CONFLICT,
          runtime.submit(envelope(1, 1, uuid(1), cancel(999))));
      assertPreflight(
          PreflightRejectionCode.PRODUCER_EPOCH_MUST_START_AT_ONE,
          runtime.submit(envelope(2, 2, uuid(4), cancel(4))));

      assertInstanceOf(
          SubmissionResult.NewDurablyApplied.class,
          runtime.submit(envelope(2, 1, uuid(4), cancel(4))));
      assertInstanceOf(
          SubmissionResult.DuplicateReplayed.class,
          runtime.submit(envelope(1, 1, uuid(1), cancel(1))));
      assertPreflight(
          PreflightRejectionCode.PRODUCER_EPOCH_FENCED,
          runtime.submit(envelope(1, 3, uuid(5), cancel(5))));

      assertEquals(4, runtime.nextWalSequence());
      assertEquals(3, applier.applied().size());
    }
  }

  @Test
  void forceHappensBeforeApplyAndAckBoundaryComesLast() throws Exception {
    List<String> trace = new ArrayList<>();
    TestCommandApplier applier = new TestCommandApplier(trace);
    FaultInjector injector = point -> trace.add(point.name());
    try (LocalMatchingRuntime runtime =
        LocalMatchingRuntime.openForTesting(config("ordering"), applier, injector)) {
      trace.clear();
      assertInstanceOf(
          SubmissionResult.NewDurablyApplied.class,
          runtime.submit(envelope(1, 1, uuid(1), cancel(1))));
      assertEquals(
          List.of(
              "BEFORE_RECORD_LENGTH_WRITE",
              "AFTER_RECORD_LENGTH_WRITE",
              "BEFORE_RECORD_BODY_WRITE",
              "AFTER_RECORD_BODY_WRITE",
              "BEFORE_RECORD_FORCE",
              "AFTER_RECORD_FORCE",
              "BEFORE_LIVE_APPLY",
              "APPLY",
              "AFTER_LIVE_APPLY_BEFORE_ACK"),
          trace);
    }
  }

  @Test
  void journalsBusinessRejectionAndReplaysItsExactResultAfterRestart() throws Exception {
    Path directory = Files.createDirectories(temporaryDirectory.resolve("business-rejection"));
    byte[] invalidSide =
        codec.encode(
            "producer-a",
            1,
            SHARD,
            1,
            uuid(1),
            new M08Command.Place(
                "BTC-USDT",
                BigInteger.ONE,
                "NOT_A_SIDE",
                BigInteger.valueOf(100),
                BigInteger.ONE,
                "GTC",
                0,
                "NONE",
                Optional.empty()));

    CanonicalResult firstResult;
    try (LocalMatchingRuntime first =
        LocalMatchingRuntime.open(WalConfig.defaults(directory, SHARD))) {
      SubmissionResult.NewDurablyApplied applied =
          assertInstanceOf(SubmissionResult.NewDurablyApplied.class, first.submit(invalidSide));
      firstResult = applied.result();
      assertTrue(firstResult.events().stream().anyMatch(event -> event.contains("Rejected")));
      assertEquals(2, first.nextWalSequence());
    }

    try (LocalMatchingRuntime recovered =
        LocalMatchingRuntime.open(WalConfig.defaults(directory, SHARD))) {
      SubmissionResult.DuplicateReplayed duplicate =
          assertInstanceOf(SubmissionResult.DuplicateReplayed.class, recovered.submit(invalidSide));
      assertEquals(firstResult, duplicate.originalResult());
      assertEquals(2, recovered.nextWalSequence());
    }
  }

  @Test
  void callerMutationAfterDecodeCannotChangeTheJournaledEnvelope() throws Exception {
    Path directory = Files.createDirectories(temporaryDirectory.resolve("owned-envelope"));
    byte[] callerBytes = envelope(1, 1, uuid(1), cancel(1));
    byte[] expectedBytes = callerBytes.clone();
    FaultInjector mutateCaller =
        point -> {
          if (point == FaultPoint.BEFORE_RECORD_LENGTH_WRITE) {
            callerBytes[callerBytes.length - 1] ^= 1;
          }
        };

    try (LocalMatchingRuntime runtime =
        LocalMatchingRuntime.open(WalConfig.defaults(directory, SHARD), mutateCaller)) {
      assertInstanceOf(SubmissionResult.NewDurablyApplied.class, runtime.submit(callerBytes));
      assertNotEquals(callerBytes[callerBytes.length - 1], expectedBytes[expectedBytes.length - 1]);
    }
    try (LocalMatchingRuntime recovered =
        LocalMatchingRuntime.open(WalConfig.defaults(directory, SHARD))) {
      assertInstanceOf(SubmissionResult.DuplicateReplayed.class, recovered.submit(expectedBytes));
    }
  }

  @Test
  void reentrantFaultCallbackCannotAckANestedSubmissionOrLosePriorAck() throws Exception {
    Path directory = Files.createDirectories(temporaryDirectory.resolve("reentrant-submit"));
    byte[] prior = envelope(1, 1, uuid(1), cancel(1));
    byte[] outer = envelope(1, 2, uuid(2), cancel(2));
    byte[] nested = envelope(1, 2, uuid(22), cancel(22));
    AtomicReference<LocalMatchingRuntime> runtimeReference = new AtomicReference<>();
    AtomicReference<SubmissionResult> nestedResult = new AtomicReference<>();
    AtomicBoolean armed = new AtomicBoolean();
    FaultInjector reentrant =
        point -> {
          if (point == FaultPoint.BEFORE_RECORD_LENGTH_WRITE && armed.compareAndSet(true, false)) {
            nestedResult.set(runtimeReference.get().submit(nested));
          }
        };

    try (LocalMatchingRuntime runtime =
        LocalMatchingRuntime.open(WalConfig.defaults(directory, SHARD), reentrant)) {
      runtimeReference.set(runtime);
      assertInstanceOf(SubmissionResult.NewDurablyApplied.class, runtime.submit(prior));
      armed.set(true);
      SubmissionResult.DurabilityUnknown unknown =
          assertInstanceOf(SubmissionResult.DurabilityUnknown.class, runtime.submit(outer));
      assertEquals("REENTRANT_SUBMIT", unknown.stage());
      assertInstanceOf(SubmissionResult.FailedClosed.class, nestedResult.get());
      assertEquals(RuntimeState.FAILED_CLOSED, runtime.state());
    }

    try (LocalMatchingRuntime recovered =
        LocalMatchingRuntime.open(WalConfig.defaults(directory, SHARD))) {
      assertInstanceOf(SubmissionResult.DuplicateReplayed.class, recovered.submit(prior));
      assertInstanceOf(SubmissionResult.DuplicateReplayed.class, recovered.submit(outer));
      assertPreflight(PreflightRejectionCode.SLOT_IDENTITY_CONFLICT, recovered.submit(nested));
    }
  }

  @Test
  void realCoreAdapterAppliesEveryM06CommandAndRecoversSameDigest() throws Exception {
    Path directory = Files.createDirectories(temporaryDirectory.resolve("all-commands"));
    MarketRuleSetArtifact bootstrap = MarketRuleSetArtifact.bootstrap();
    MarketRuleSetArtifact unhashed =
        new MarketRuleSetArtifact(
            1,
            1,
            Long.MAX_VALUE,
            "sha256:0000000000000000000000000000000000000000000000000000000000000000");
    MarketRuleSetArtifact v1 =
        new MarketRuleSetArtifact(1, 1, Long.MAX_VALUE, unhashed.computedContentHash());
    List<M08Command> commands =
        List.of(
            new M08Command.Place(
                "BTC-USDT",
                BigInteger.valueOf(1001),
                "SELL",
                BigInteger.valueOf(20_000),
                BigInteger.valueOf(5),
                "GTC",
                0,
                "NONE",
                Optional.empty()),
            new M08Command.Cancel("BTC-USDT", BigInteger.valueOf(1001)),
            new M08Command.PrepareRuleSet(bootstrap.identity(), v1),
            new M08Command.ActivateRuleSet(4, bootstrap.identity(), v1.identity()),
            new M08Command.ChangeMarketMode(5, MarketMode.OPEN, MarketMode.HALTED, "ops-a"),
            new M08Command.MassCancel(6, MarketMode.HALTED, "ops-a"));
    List<byte[]> envelopes = new ArrayList<>();
    for (int index = 0; index < commands.size(); index++) {
      envelopes.add(
          codec.encode("producer-a", 1, SHARD, index + 1L, uuid(index + 1L), commands.get(index)));
    }

    String digest;
    try (LocalMatchingRuntime runtime =
        LocalMatchingRuntime.open(WalConfig.defaults(directory, SHARD))) {
      for (byte[] envelope : envelopes) {
        SubmissionResult.NewDurablyApplied applied =
            assertInstanceOf(SubmissionResult.NewDurablyApplied.class, runtime.submit(envelope));
        assertEquals(64, applied.result().resultDigest().length());
        assertEquals(
            applied.position().applicationSequence(), applied.result().applicationSequence());
      }
      digest = runtime.semanticStateDigest();
      assertEquals(7, runtime.nextWalSequence());
    }

    try (LocalMatchingRuntime recovered =
        LocalMatchingRuntime.open(WalConfig.defaults(directory, SHARD))) {
      assertEquals(digest, recovered.semanticStateDigest());
      assertEquals(7, recovered.nextWalSequence());
      for (byte[] envelope : envelopes) {
        assertInstanceOf(SubmissionResult.DuplicateReplayed.class, recovered.submit(envelope));
      }
    }
  }

  @Test
  void nonzeroGovernedStpAndInvalidRawInstructionAreJournaledAndRecovered() throws Exception {
    Path directory = Files.createDirectories(temporaryDirectory.resolve("m07-stp"));
    MarketRuleSetArtifact bootstrap = MarketRuleSetArtifact.bootstrap();
    List<byte[]> envelopes =
        List.of(
            envelope(
                1,
                1,
                uuid(1),
                new M08Command.Place(
                    "BTC-USDT",
                    BigInteger.ONE,
                    "SELL",
                    BigInteger.valueOf(100),
                    BigInteger.TWO,
                    "GTC",
                    99,
                    "CANCEL_TAKER",
                    Optional.empty())),
            envelope(
                1,
                2,
                uuid(2),
                new M08Command.Place(
                    "BTC-USDT",
                    BigInteger.TWO,
                    "BUY",
                    BigInteger.valueOf(100),
                    BigInteger.valueOf(3),
                    "GTC",
                    99,
                    "CANCEL_MAKER",
                    Optional.of(bootstrap.identity()))),
            envelope(
                1,
                3,
                uuid(3),
                new M08Command.Place(
                    "BTC-USDT",
                    BigInteger.valueOf(3),
                    "BUY",
                    BigInteger.valueOf(99),
                    BigInteger.ONE,
                    "GTC",
                    99,
                    "NONE",
                    Optional.of(bootstrap.identity()))));
    List<CanonicalResult> originalResults = new ArrayList<>();
    String digest;
    try (LocalMatchingRuntime runtime =
        LocalMatchingRuntime.open(WalConfig.defaults(directory, SHARD))) {
      for (byte[] envelope : envelopes) {
        SubmissionResult.NewDurablyApplied applied =
            assertInstanceOf(SubmissionResult.NewDurablyApplied.class, runtime.submit(envelope));
        originalResults.add(applied.result());
      }
      assertTrue(
          originalResults.get(1).events().stream()
              .anyMatch(
                  event -> event.contains("SelfTradePrevented") && event.contains("CANCEL_MAKER")),
          "governed nonzero STP did not reach the M07 prevention path");
      assertTrue(
          originalResults.get(2).events().stream()
              .anyMatch(event -> event.contains("INVALID_STP_INSTRUCTION")),
          "structurally canonical invalid STP did not reach the M07 rejection path");
      digest = runtime.semanticStateDigest();
      assertEquals(4, runtime.nextWalSequence());
      assertTrue(
          new MatchingCoreCommandApplier()
              .supports(codec.decodeCanonical(envelopes.get(1), SHARD).command()));
    }

    try (LocalMatchingRuntime recovered =
        LocalMatchingRuntime.open(WalConfig.defaults(directory, SHARD))) {
      assertEquals(digest, recovered.semanticStateDigest());
      assertEquals(4, recovered.nextWalSequence());
      for (int index = 0; index < envelopes.size(); index++) {
        SubmissionResult.DuplicateReplayed duplicate =
            assertInstanceOf(
                SubmissionResult.DuplicateReplayed.class, recovered.submit(envelopes.get(index)));
        assertArrayEquals(
            originalResults.get(index).auditBytes(), duplicate.originalResult().auditBytes());
      }
    }
  }

  private WalConfig config(String name) throws IOException {
    return WalConfig.defaults(Files.createDirectories(temporaryDirectory.resolve(name)), SHARD);
  }

  private byte[] envelope(long epoch, long sequence, UUID commandId, M08Command command) {
    return codec.encode("producer-a", epoch, SHARD, sequence, commandId, command);
  }

  private static M08Command.Cancel cancel(long orderId) {
    return new M08Command.Cancel("BTC-USDT", BigInteger.valueOf(orderId));
  }

  private static UUID uuid(long value) {
    return new UUID(0x1234, value);
  }

  private static void assertPreflight(PreflightRejectionCode expected, SubmissionResult actual) {
    SubmissionResult.PreflightRejected rejected =
        assertInstanceOf(SubmissionResult.PreflightRejected.class, actual);
    assertEquals(expected, rejected.code());
  }
}
