package io.github.lchareln.cex.matching.local;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MatchingCoreCommandApplierTranscriptTest {
  private static final long SHARD = 83;
  private final M08EnvelopeCodec codec = new M08EnvelopeCodec();

  @TempDir Path temporaryDirectory;

  @Test
  void emptyBooksWithDifferentTerminalOrderHistoriesHaveDifferentDigests() {
    MatchingCoreCommandApplier first = new MatchingCoreCommandApplier();
    MatchingCoreCommandApplier second = new MatchingCoreCommandApplier();

    CanonicalResult orderOne = first.apply(ioc(1));
    CanonicalResult orderTwo = second.apply(ioc(2));

    assertTrue(orderOne.events().stream().anyMatch(event -> event.contains("RemainderCanceled")));
    assertTrue(orderTwo.events().stream().anyMatch(event -> event.contains("RemainderCanceled")));
    assertNotEquals(first.semanticStateDigest(), second.semanticStateDigest());
  }

  @Test
  void restartRebuildsTranscriptAndPreservesLaterDuplicateOrderDecision() throws Exception {
    Path uninterruptedDirectory = Files.createDirectories(temporaryDirectory.resolve("direct"));
    Path recoveredDirectory = Files.createDirectories(temporaryDirectory.resolve("recovered"));
    byte[] first = envelope(1, uuid(1), ioc(1));
    byte[] reuse = envelope(2, uuid(2), gtc(1));

    CanonicalResult directReuse;
    String directFinalDigest;
    try (LocalMatchingRuntime direct =
        LocalMatchingRuntime.open(WalConfig.defaults(uninterruptedDirectory, SHARD))) {
      assertInstanceOf(SubmissionResult.NewDurablyApplied.class, direct.submit(first));
      directReuse =
          assertInstanceOf(SubmissionResult.NewDurablyApplied.class, direct.submit(reuse)).result();
      directFinalDigest = direct.semanticStateDigest();
    }

    CanonicalResult firstResult;
    String beforeRestartDigest;
    try (LocalMatchingRuntime initial =
        LocalMatchingRuntime.open(WalConfig.defaults(recoveredDirectory, SHARD))) {
      firstResult =
          assertInstanceOf(SubmissionResult.NewDurablyApplied.class, initial.submit(first))
              .result();
      beforeRestartDigest = initial.semanticStateDigest();
    }

    try (LocalMatchingRuntime recovered =
        LocalMatchingRuntime.open(WalConfig.defaults(recoveredDirectory, SHARD))) {
      assertEquals(beforeRestartDigest, recovered.semanticStateDigest());
      SubmissionResult.DuplicateReplayed replay =
          assertInstanceOf(SubmissionResult.DuplicateReplayed.class, recovered.submit(first));
      assertArrayEquals(firstResult.auditBytes(), replay.originalResult().auditBytes());

      CanonicalResult recoveredReuse =
          assertInstanceOf(SubmissionResult.NewDurablyApplied.class, recovered.submit(reuse))
              .result();
      assertTrue(
          recoveredReuse.events().stream().anyMatch(event -> event.contains("DUPLICATE_ORDER_ID")));
      assertArrayEquals(directReuse.auditBytes(), recoveredReuse.auditBytes());
      assertEquals(directFinalDigest, recovered.semanticStateDigest());
    }
  }

  private byte[] envelope(long sequence, UUID commandId, M08Command command) {
    return codec.encode("transcript", 1, SHARD, sequence, commandId, command);
  }

  private static M08Command.Place ioc(long orderId) {
    return place(orderId, "IOC");
  }

  private static M08Command.Place gtc(long orderId) {
    return place(orderId, "GTC");
  }

  private static M08Command.Place place(long orderId, String policy) {
    return new M08Command.Place(
        "BTC-USDT",
        BigInteger.valueOf(orderId),
        "BUY",
        BigInteger.valueOf(100),
        BigInteger.ONE,
        policy,
        0,
        "NONE",
        Optional.empty());
  }

  private static UUID uuid(long value) {
    return new UUID(0x83, value);
  }
}
