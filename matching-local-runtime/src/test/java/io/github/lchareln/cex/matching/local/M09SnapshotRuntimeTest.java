package io.github.lchareln.cex.matching.local;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lchareln.cex.matching.MarketMode;
import io.github.lchareln.cex.matching.MarketRuleSetArtifact;
import io.github.lchareln.cex.matching.MatchingStateImage;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class M09SnapshotRuntimeTest {
  private static final long SHARD = 41;

  @TempDir Path temporaryDirectory;

  private final M08EnvelopeCodec envelopeCodec = new M08EnvelopeCodec();

  @Test
  void codecRoundTripRetainsTerminalOrdersPreparedRuleSetModeAndOriginalResults() throws Exception {
    Path directory = directory("round-trip");
    List<byte[]> commands = statefulCommands();
    List<CanonicalResult> originalResults = new ArrayList<>();
    String checkpointDigest;
    SnapshotAnchor anchor;
    try (LocalMatchingRuntime runtime = LocalMatchingRuntime.open(config(directory))) {
      for (byte[] command : commands) {
        SubmissionResult.NewDurablyApplied applied =
            assertInstanceOf(SubmissionResult.NewDurablyApplied.class, runtime.submit(command));
        originalResults.add(applied.result());
      }
      checkpointDigest = runtime.semanticStateDigest();
      anchor = runtime.checkpoint().anchor();
    }

    Path snapshot = snapshotFiles(directory).getLast();
    M09SnapshotCodec.DecodedSnapshot decoded =
        new M09SnapshotCodec().decodeCanonical(Files.readAllBytes(snapshot));
    assertEquals(anchor, decoded.anchor());
    MatchingStateImage matching = decoded.state().applierState().matchingState();
    assertTrue(matching.control().preparedRuleSet().isPresent());
    assertEquals(MarketMode.CANCEL_ONLY, matching.control().marketMode());
    assertEquals(
        List.of(
            MatchingStateImage.Lifecycle.FILLED,
            MatchingStateImage.Lifecycle.FILLED,
            MatchingStateImage.Lifecycle.CANCELED),
        matching.orders().stream().map(MatchingStateImage.OrderImage::lifecycle).toList());

    try (LocalMatchingRuntime recovered = LocalMatchingRuntime.open(config(directory))) {
      assertEquals(checkpointDigest, recovered.semanticStateDigest());
      assertEquals(commands.size() + 1L, recovered.nextWalSequence());
      for (int index = 0; index < commands.size(); index++) {
        SubmissionResult.DuplicateReplayed duplicate =
            assertInstanceOf(
                SubmissionResult.DuplicateReplayed.class, recovered.submit(commands.get(index)));
        assertArrayEquals(
            originalResults.get(index).auditBytes(), duplicate.originalResult().auditBytes());
      }
    }
  }

  @Test
  void snapshotPlusContinuousSuffixMatchesGenesisReplay() throws Exception {
    List<byte[]> prefix = statefulCommands();
    byte[] suffix =
        envelope(
            prefix.size() + 1L,
            new M08Command.ChangeMarketMode(
                prefix.size() + 1L,
                MarketMode.CANCEL_ONLY,
                MarketMode.HALTED,
                "ops-after-snapshot"));
    Path checkpointed = directory("checkpointed");
    Path genesis = directory("genesis");

    try (LocalMatchingRuntime runtime = LocalMatchingRuntime.open(config(checkpointed))) {
      prefix.forEach(command -> assertDurable(runtime.submit(command)));
      runtime.checkpoint();
      assertDurable(runtime.submit(suffix));
    }
    try (LocalMatchingRuntime runtime = LocalMatchingRuntime.open(config(genesis))) {
      prefix.forEach(command -> assertDurable(runtime.submit(command)));
      assertDurable(runtime.submit(suffix));
    }

    try (LocalMatchingRuntime fromSnapshot = LocalMatchingRuntime.open(config(checkpointed));
        LocalMatchingRuntime fromGenesis = LocalMatchingRuntime.open(config(genesis))) {
      assertEquals(fromGenesis.nextWalSequence(), fromSnapshot.nextWalSequence());
      assertEquals(fromGenesis.semanticStateDigest(), fromSnapshot.semanticStateDigest());
      assertInstanceOf(SubmissionResult.DuplicateReplayed.class, fromSnapshot.submit(suffix));
    }
  }

  @Test
  void hardRecordAndByteBudgetReturnsCheckpointRequiredBeforeWal() throws Exception {
    Path directory = directory("budget");
    WalConfig budgeted =
        new WalConfig(
            directory,
            SHARD,
            WalConfig.DEFAULT_MAX_SEGMENT_BYTES,
            WalConfig.DEFAULT_MAX_RECORD_BYTES,
            new RecoveryBudget(1, 1_048_576));
    byte[] first = envelope(1, cancel(1));
    byte[] second = envelope(2, cancel(2));
    try (LocalMatchingRuntime runtime = LocalMatchingRuntime.open(budgeted)) {
      assertDurable(runtime.submit(first));
      assertInstanceOf(SubmissionResult.DuplicateReplayed.class, runtime.submit(first));
      SubmissionResult.CheckpointRequired required =
          assertInstanceOf(SubmissionResult.CheckpointRequired.class, runtime.submit(second));
      assertEquals(1, required.suffixRecords());
      assertEquals(2, runtime.nextWalSequence());
      runtime.checkpoint();
      assertDurable(runtime.submit(second));
      assertEquals(3, runtime.nextWalSequence());
    }
  }

  @Test
  void finiteRecoveryRejectsLegacySuffixBeyondRecordBudgetBeforeAnyApply() throws Exception {
    Path directory = directory("recovery-record-budget");
    List<byte[]> commands = new ArrayList<>();
    for (long sequence = 1;
        sequence <= RecoveryBudget.M09_DEFAULT.maxSuffixRecords() + 1;
        sequence++) {
      commands.add(envelope(sequence, cancel(sequence)));
    }
    writeLegacySuffix(directory, commands);

    assertFiniteRecoveryRejectedBeforeApply(directory, commands.size());
  }

  @Test
  void finiteRecoveryRejectsLegacySuffixBeyondByteBudgetBeforeAnyApply() throws Exception {
    Path directory = directory("recovery-byte-budget");
    String wide = "x".repeat(16 * 1024);
    List<byte[]> commands = new ArrayList<>();
    for (long sequence = 1; sequence <= 17; sequence++) {
      commands.add(
          envelope(
              sequence,
              new M08Command.Place(
                  wide,
                  BigInteger.valueOf(sequence),
                  wide,
                  BigInteger.ONE,
                  BigInteger.ONE,
                  wide,
                  0,
                  wide,
                  Optional.empty())));
    }
    long encodedBytes =
        commands.stream().mapToLong(command -> M08WalFormat.RECORD_OVERHEAD + command.length).sum();
    assertTrue(commands.size() < RecoveryBudget.M09_DEFAULT.maxSuffixRecords());
    assertTrue(encodedBytes > RecoveryBudget.M09_DEFAULT.maxSuffixBytes());
    writeLegacySuffix(directory, commands);

    assertFiniteRecoveryRejectedBeforeApply(directory, commands.size());
  }

  @Test
  void retainsTwoGenerationsPrunesOnlyCoveredClosedSegmentsAndRejectsCorruptLatest()
      throws Exception {
    Path directory = directory("retention");
    WalConfig config = config(directory);
    try (LocalMatchingRuntime runtime = LocalMatchingRuntime.open(config)) {
      assertDurable(runtime.submit(envelope(1, cancel(1))));
      runtime.checkpoint();
      assertDurable(runtime.submit(envelope(2, cancel(2))));
      runtime.checkpoint();
      assertDurable(runtime.submit(envelope(3, cancel(3))));
      runtime.checkpoint();
    }

    assertEquals(2, snapshotFiles(directory).size());
    assertEquals(
        List.of("segment-00000000000000000003.m08w1", "segment-00000000000000000004.m08w1"),
        segmentFiles(directory).stream().map(path -> path.getFileName().toString()).toList());
    try (LocalMatchingRuntime recovered = LocalMatchingRuntime.open(config)) {
      assertEquals(4, recovered.nextWalSequence());
      assertInstanceOf(
          SubmissionResult.DuplicateReplayed.class, recovered.submit(envelope(1, cancel(1))));
    }

    Path latest = snapshotFiles(directory).getLast();
    byte[] corrupt = Files.readAllBytes(latest);
    corrupt[corrupt.length / 2] ^= 1;
    Files.write(latest, corrupt, StandardOpenOption.TRUNCATE_EXISTING);
    assertThrows(SnapshotCorruptionException.class, () -> LocalMatchingRuntime.open(config));
  }

  @Test
  void namedSnapshotRetentionAndSuffixReadSeamsFailClosedWithoutLosingDurableHistory()
      throws Exception {
    for (FaultPoint point :
        List.of(
            FaultPoint.BEFORE_SNAPSHOT_TEMP_WRITE,
            FaultPoint.AFTER_PARTIAL_SNAPSHOT_TEMP_WRITE,
            FaultPoint.BEFORE_SNAPSHOT_FILE_FORCE,
            FaultPoint.BEFORE_SNAPSHOT_READ,
            FaultPoint.BEFORE_SNAPSHOT_ATOMIC_RENAME,
            FaultPoint.BEFORE_SNAPSHOT_DIRECTORY_FORCE,
            FaultPoint.AFTER_SNAPSHOT_DIRECTORY_FORCE_BEFORE_RETENTION)) {
      Path directory = directory("snapshot-seam-" + point);
      byte[] command = envelope(1, cancel(1));
      try (LocalMatchingRuntime runtime =
          LocalMatchingRuntime.open(config(directory), new OneShotFault(point))) {
        assertDurable(runtime.submit(command));
        assertThrows(IOException.class, runtime::checkpoint);
        assertEquals(RuntimeState.FAILED_CLOSED, runtime.state());
        if (point == FaultPoint.AFTER_PARTIAL_SNAPSHOT_TEMP_WRITE) {
          List<Path> partialFiles = temporarySnapshotFiles(directory);
          assertEquals(1, partialFiles.size());
          Path partial = partialFiles.getFirst();
          assertTrue(Files.size(partial) > 0);
          assertThrows(
              SnapshotCorruptionException.class,
              () -> new M09SnapshotCodec().decodeCanonical(Files.readAllBytes(partial)));
        }
        if (point == FaultPoint.AFTER_SNAPSHOT_DIRECTORY_FORCE_BEFORE_RETENTION) {
          assertEquals(1, snapshotFiles(directory).size());
          assertEquals(
              List.of("segment-00000000000000000001.m08w1"),
              segmentFiles(directory).stream().map(path -> path.getFileName().toString()).toList());
        }
      }
      try (LocalMatchingRuntime recovered = LocalMatchingRuntime.open(config(directory))) {
        assertInstanceOf(SubmissionResult.DuplicateReplayed.class, recovered.submit(command));
      }
    }

    for (FaultPoint point :
        List.of(
            FaultPoint.BEFORE_RETENTION_DELETE,
            FaultPoint.AFTER_FIRST_RETENTION_SEGMENT_DELETE,
            FaultPoint.BEFORE_RETENTION_DIRECTORY_FORCE,
            FaultPoint.AFTER_RETENTION_DIRECTORY_FORCE_BEFORE_RETURN)) {
      Path directory = directory("retention-seam-" + point);
      byte[] first = envelope(1, cancel(1));
      byte[] second = envelope(2, cancel(2));
      try (LocalMatchingRuntime runtime =
          LocalMatchingRuntime.open(config(directory), new OneShotFault(point))) {
        assertDurable(runtime.submit(first));
        runtime.checkpoint();
        assertDurable(runtime.submit(second));
        assertThrows(IOException.class, runtime::checkpoint);
        assertEquals(RuntimeState.FAILED_CLOSED, runtime.state());
      }
      try (LocalMatchingRuntime recovered = LocalMatchingRuntime.open(config(directory))) {
        assertInstanceOf(SubmissionResult.DuplicateReplayed.class, recovered.submit(first));
        assertInstanceOf(SubmissionResult.DuplicateReplayed.class, recovered.submit(second));
      }
    }

    Path suffixDirectory = directory("suffix-read-seam");
    byte[] prefix = envelope(1, cancel(1));
    byte[] suffix = envelope(2, cancel(2));
    try (LocalMatchingRuntime runtime = LocalMatchingRuntime.open(config(suffixDirectory))) {
      assertDurable(runtime.submit(prefix));
      runtime.checkpoint();
      assertDurable(runtime.submit(suffix));
    }
    assertThrows(
        IOException.class,
        () ->
            LocalMatchingRuntime.open(
                config(suffixDirectory), new OneShotFault(FaultPoint.BEFORE_SNAPSHOT_SUFFIX_READ)));
    try (LocalMatchingRuntime recovered = LocalMatchingRuntime.open(config(suffixDirectory))) {
      assertInstanceOf(SubmissionResult.DuplicateReplayed.class, recovered.submit(suffix));
    }
  }

  @Test
  void retentionScanRejectsCanonicalEnvelopeCorruptionEvenWhenRecordCrcIsValid() throws Exception {
    Path directory = directory("retention-canonical-scan");
    byte[] first = envelope(1, cancel(1));
    try (LocalMatchingRuntime runtime = LocalMatchingRuntime.open(config(directory))) {
      assertDurable(runtime.submit(first));
      runtime.checkpoint();

      Path closed = directory.resolve("segment-00000000000000000001.m08w1");
      byte[] segment = Files.readAllBytes(closed);
      int recordLength =
          ByteBuffer.wrap(segment, M08WalFormat.HEADER_BYTES, Integer.BYTES).getInt();
      int envelopeLength = recordLength - M08WalFormat.RECORD_OVERHEAD;
      byte[] corruptRecord = M08WalFormat.record(1, 1, new byte[envelopeLength]);
      System.arraycopy(corruptRecord, 0, segment, M08WalFormat.HEADER_BYTES, corruptRecord.length);
      Files.write(closed, segment, StandardOpenOption.TRUNCATE_EXISTING);

      assertDurable(runtime.submit(envelope(2, cancel(2))));
      assertThrows(IOException.class, runtime::checkpoint);
      assertEquals(RuntimeState.FAILED_CLOSED, runtime.state());
    }
  }

  /**
   * NON_CLAIM: without a separate durable tail anchor, an externally deleted terminal segment is
   * indistinguishable from a valid halt before rollover and therefore cannot be detected reliably.
   */
  @Test
  void nonClaimExternalTerminalSegmentDeletionIsObservationallyIndistinguishable()
      throws Exception {
    Path haltedBeforeRollover = directory("non-claim-halt-before-rollover");
    Path externallyDeletedTail = directory("non-claim-external-tail-delete");
    byte[] prefix = envelope(1, cancel(1));
    byte[] suffix = envelope(2, cancel(2));

    try (LocalMatchingRuntime runtime =
        LocalMatchingRuntime.open(
            config(haltedBeforeRollover),
            new OneShotFault(FaultPoint.AFTER_SNAPSHOT_DIRECTORY_FORCE_BEFORE_RETENTION))) {
      assertDurable(runtime.submit(prefix));
      assertThrows(IOException.class, runtime::checkpoint);
    }

    CanonicalResult acknowledgedSuffix;
    try (LocalMatchingRuntime runtime = LocalMatchingRuntime.open(config(externallyDeletedTail))) {
      assertDurable(runtime.submit(prefix));
      runtime.checkpoint();
      acknowledgedSuffix =
          assertInstanceOf(SubmissionResult.NewDurablyApplied.class, runtime.submit(suffix))
              .result();
    }
    Path deleted = externallyDeletedTail.resolve("segment-00000000000000000002.m08w1");
    assertTrue(Files.deleteIfExists(deleted));
    forceDirectory(externallyDeletedTail);

    List<Path> haltedFiles = observableDurableFiles(haltedBeforeRollover);
    List<Path> deletedTailFiles = observableDurableFiles(externallyDeletedTail);
    assertEquals(
        haltedFiles.stream().map(path -> path.getFileName().toString()).toList(),
        deletedTailFiles.stream().map(path -> path.getFileName().toString()).toList());
    for (int index = 0; index < haltedFiles.size(); index++) {
      assertArrayEquals(
          Files.readAllBytes(haltedFiles.get(index)),
          Files.readAllBytes(deletedTailFiles.get(index)));
    }

    try (LocalMatchingRuntime reopened = LocalMatchingRuntime.open(config(externallyDeletedTail))) {
      assertEquals(2, reopened.nextWalSequence());
      SubmissionResult.NewDurablyApplied replayedAsNew =
          assertInstanceOf(SubmissionResult.NewDurablyApplied.class, reopened.submit(suffix));
      assertArrayEquals(acknowledgedSuffix.auditBytes(), replayedAsNew.result().auditBytes());
    }
  }

  private List<byte[]> statefulCommands() {
    MarketRuleSetArtifact bootstrap = MarketRuleSetArtifact.bootstrap();
    MarketRuleSetArtifact unhashed =
        new MarketRuleSetArtifact(
            1,
            1,
            Long.MAX_VALUE,
            "sha256:0000000000000000000000000000000000000000000000000000000000000000");
    MarketRuleSetArtifact prepared =
        new MarketRuleSetArtifact(1, 1, Long.MAX_VALUE, unhashed.computedContentHash());
    List<M08Command> commands =
        List.of(
            place(1, "SELL", 101, 2),
            place(2, "BUY", 101, 2),
            place(3, "SELL", 102, 4),
            cancel(3),
            new M08Command.PrepareRuleSet(bootstrap.identity(), prepared),
            new M08Command.ChangeMarketMode(6, MarketMode.OPEN, MarketMode.CANCEL_ONLY, "ops-a"));
    List<byte[]> encoded = new ArrayList<>();
    for (int index = 0; index < commands.size(); index++) {
      encoded.add(envelope(index + 1L, commands.get(index)));
    }
    return List.copyOf(encoded);
  }

  private byte[] envelope(long sequence, M08Command command) {
    return envelopeCodec.encode(
        "producer-a", 1, SHARD, sequence, new UUID(0x909, sequence), command);
  }

  private static M08Command.Place place(long orderId, String side, long price, long quantity) {
    return new M08Command.Place(
        "BTC-USDT",
        BigInteger.valueOf(orderId),
        side,
        BigInteger.valueOf(price),
        BigInteger.valueOf(quantity),
        "GTC",
        0,
        "NONE",
        Optional.empty());
  }

  private static M08Command.Cancel cancel(long orderId) {
    return new M08Command.Cancel("BTC-USDT", BigInteger.valueOf(orderId));
  }

  private WalConfig config(Path directory) {
    return WalConfig.snapshotDefaults(directory, SHARD);
  }

  private void writeLegacySuffix(Path directory, List<byte[]> commands) throws Exception {
    try (LocalMatchingRuntime runtime =
        LocalMatchingRuntime.openForTesting(
            WalConfig.defaults(directory, SHARD), new TestCommandApplier(), FaultInjector.NONE)) {
      commands.forEach(command -> assertDurable(runtime.submit(command)));
    }
  }

  private void assertFiniteRecoveryRejectedBeforeApply(Path directory, int expectedLegacyApplies)
      throws Exception {
    TestCommandApplier bounded = new TestCommandApplier();
    assertThrows(
        RecoveryException.class,
        () -> LocalMatchingRuntime.openForTesting(config(directory), bounded, FaultInjector.NONE));
    assertEquals(0, bounded.applied().size());

    TestCommandApplier legacy = new TestCommandApplier();
    try (LocalMatchingRuntime recovered =
        LocalMatchingRuntime.openForTesting(
            WalConfig.defaults(directory, SHARD), legacy, FaultInjector.NONE)) {
      assertEquals(expectedLegacyApplies, legacy.applied().size());
      assertEquals(expectedLegacyApplies + 1L, recovered.nextWalSequence());
    }
  }

  private Path directory(String name) throws Exception {
    return Files.createDirectories(temporaryDirectory.resolve(name));
  }

  private static void assertDurable(SubmissionResult result) {
    assertInstanceOf(SubmissionResult.NewDurablyApplied.class, result);
  }

  private static List<Path> snapshotFiles(Path directory) throws Exception {
    try (var paths = Files.list(directory)) {
      return paths
          .filter(path -> path.getFileName().toString().endsWith(".m09s1"))
          .sorted(Comparator.comparing(path -> path.getFileName().toString()))
          .toList();
    }
  }

  private static List<Path> segmentFiles(Path directory) throws Exception {
    try (var paths = Files.list(directory)) {
      return paths
          .filter(path -> path.getFileName().toString().endsWith(".m08w1"))
          .sorted(Comparator.comparing(path -> path.getFileName().toString()))
          .toList();
    }
  }

  private static List<Path> temporarySnapshotFiles(Path directory) throws Exception {
    try (var paths = Files.list(directory)) {
      return paths
          .filter(path -> path.getFileName().toString().endsWith(".m09s1.tmp"))
          .sorted(Comparator.comparing(path -> path.getFileName().toString()))
          .toList();
    }
  }

  private static List<Path> observableDurableFiles(Path directory) throws IOException {
    try (var paths = Files.list(directory)) {
      return paths
          .filter(path -> !path.getFileName().toString().equals(".m08w1.lock"))
          .sorted(Comparator.comparing(path -> path.getFileName().toString()))
          .toList();
    }
  }

  private static void forceDirectory(Path directory) throws IOException {
    try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
      channel.force(true);
    }
  }

  private static final class OneShotFault implements FaultInjector {
    private final FaultPoint target;
    private boolean armed = true;

    private OneShotFault(FaultPoint target) {
      this.target = target;
    }

    @Override
    public void hit(FaultPoint point) throws IOException {
      if (armed && point == target) {
        armed = false;
        throw new IOException("injected " + target);
      }
    }
  }
}
