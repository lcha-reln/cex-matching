package io.github.lchareln.cex.matching.local;

import io.github.lchareln.cex.matching.MarketControlSnapshot;
import io.github.lchareln.cex.matching.MarketMode;
import io.github.lchareln.cex.matching.MatchingStateImage;
import io.github.lchareln.cex.matching.SingleInstrumentMatchingEngine;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/** Narrow testkit-only bridge for real M09 snapshot/WAL mutations and fixtures. */
public final class M09RuntimeJudgeProbe {
  private M09RuntimeJudgeProbe() {}

  /** Decodes a snapshot with the production M09S1 codec for hook-time namespace observations. */
  public static void requireCanonicalSnapshot(Path snapshot) throws IOException {
    new M09SnapshotCodec().decodeCanonical(Files.readAllBytes(snapshot));
  }

  /** Opens the real runtime with a testkit-only delegate that records only after real JDK I/O. */
  public static LocalMatchingRuntime openWithStorageTrace(
      WalConfig config, FaultInjector faultInjector, Consumer<StorageOperationObservation> observer)
      throws IOException {
    StorageOperations delegate = JdkStorageOperations.INSTANCE;
    StorageOperations recording =
        new StorageOperations() {
          @Override
          public void forceFile(Path path, FileChannel channel) throws IOException {
            delegate.forceFile(path, channel);
            observer.accept(observation("FORCE_FILE", path, null));
          }

          @Override
          public void atomicMove(Path source, Path target) throws IOException {
            delegate.atomicMove(source, target);
            observer.accept(observation("ATOMIC_MOVE", source, target));
          }

          @Override
          public void forceDirectory(Path directory) throws IOException {
            delegate.forceDirectory(directory);
            observer.accept(observation("FORCE_DIRECTORY", directory, null));
          }

          @Override
          public void delete(Path path) throws IOException {
            delegate.delete(path);
            observer.accept(observation("DELETE", path, null));
          }
        };
    return LocalMatchingRuntime.openForStorageTesting(config, faultInjector, recording);
  }

  /** Reads the anchor selected by the production SnapshotStore discovery path. */
  public static SnapshotSelection selectedSnapshot(WalConfig config) throws IOException {
    try (SegmentedWal wal =
        SegmentedWal.open(config, FaultInjector.NONE, JdkStorageOperations.INSTANCE)) {
      SnapshotAnchor anchor =
          wal.recoveredSnapshot()
              .orElseThrow(() -> new IllegalStateException("M09 selection has no snapshot"))
              .anchor();
      return new SnapshotSelection(
          anchor.generation(),
          anchor.shardId(),
          anchor.lastWalSequence(),
          anchor.lastApplicationSequence());
    }
  }

  private static StorageOperationObservation observation(String kind, Path path, Path target) {
    return new StorageOperationObservation(
        kind,
        path.toAbsolutePath().normalize().toString(),
        target == null ? "" : target.toAbsolutePath().normalize().toString());
  }

  /**
   * Moves the only suffix record into the prior segment while retaining a valid cut+1 active
   * header. The resulting first segment crosses the snapshot cut and must not be retired.
   */
  public static CrossingFixture createCrossingSegmentFixture(Path directory, long shardId) {
    try {
      List<Path> segments;
      try (var paths = Files.list(directory)) {
        segments =
            paths
                .filter(path -> path.getFileName().toString().endsWith(".m08w1"))
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .toList();
      }
      require(segments.size() == 2, "crossing fixture requires two WAL segments");
      Path previous = segments.get(0);
      Path active = segments.get(1);
      byte[] previousBytes = Files.readAllBytes(previous);
      byte[] activeBytes = Files.readAllBytes(active);
      require(
          activeBytes.length > M08WalFormat.HEADER_BYTES,
          "crossing fixture requires one suffix record");
      int recordLength =
          ByteBuffer.wrap(activeBytes, M08WalFormat.HEADER_BYTES, Integer.BYTES).getInt();
      require(
          activeBytes.length == M08WalFormat.HEADER_BYTES + recordLength,
          "crossing fixture requires exactly one suffix record");
      byte[] record =
          java.util.Arrays.copyOfRange(activeBytes, M08WalFormat.HEADER_BYTES, activeBytes.length);
      M08WalFormat.DecodedRecord decoded = M08WalFormat.decodeRecord(record);
      byte[] crossing =
          java.util.Arrays.copyOf(previousBytes, previousBytes.length + record.length);
      System.arraycopy(record, 0, crossing, previousBytes.length, record.length);
      byte[] nextHeader =
          M08WalFormat.header(shardId, 2, Math.incrementExact(decoded.walSequence()));
      forceWrite(previous, crossing);
      forceWrite(active, nextHeader);
      forceDirectory(directory);
      return new CrossingFixture(
          previous.getFileName().toString(),
          active.getFileName().toString(),
          decoded.walSequence(),
          2);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot create M09 crossing-segment fixture", failure);
    }
  }

  public static int mutateLatestSnapshot(Path directory, StateMutation mutation) {
    try {
      Path snapshot = latestSnapshot(directory);
      M09SnapshotCodec codec = new M09SnapshotCodec();
      M09SnapshotCodec.DecodedSnapshot decoded =
          codec.decodeCanonical(Files.readAllBytes(snapshot));
      LocalRuntimeStateImage state = decoded.state();
      CommandApplierState applier = state.applierState();
      MatchingStateImage matching = applier.matchingState();
      List<IdentityBindingImage> bindings = state.identityBindings();
      if (mutation == StateMutation.DROP_RESTING_ORDER) {
        List<MatchingStateImage.OrderImage> orders =
            matching.orders().stream()
                .filter(order -> order.lifecycle() != MatchingStateImage.Lifecycle.RESTING)
                .toList();
        require(orders.size() < matching.orders().size(), "snapshot has no resting order to drop");
        matching = new MatchingStateImage(matching.control(), orders);
      } else if (mutation == StateMutation.RESET_MARKET_MODE) {
        MarketControlSnapshot control = matching.control();
        MarketControlSnapshot reset =
            new MarketControlSnapshot(
                control.activeRuleSet(),
                control.preparedRuleSet(),
                control.controlRevision(),
                control.lastActivationFence(),
                control.nextApplicationSequence(),
                control.nextAcceptanceSequence(),
                MarketMode.OPEN,
                0,
                Optional.empty(),
                Optional.empty());
        matching = new MatchingStateImage(reset, matching.orders());
      } else if (mutation == StateMutation.DROP_PREPARED_RULE_SET) {
        MarketControlSnapshot control = matching.control();
        require(control.preparedRuleSet().isPresent(), "snapshot has no prepared rule set to drop");
        MarketControlSnapshot changed =
            new MarketControlSnapshot(
                control.activeRuleSet(),
                Optional.empty(),
                control.controlRevision(),
                control.lastActivationFence(),
                control.nextApplicationSequence(),
                control.nextAcceptanceSequence(),
                control.marketMode(),
                control.modeRevision(),
                control.lastModeTransitionFence(),
                control.lastMassCancelFence());
        matching = new MatchingStateImage(changed, matching.orders());
      } else if (mutation == StateMutation.DROP_DURABLE_IDENTITY_RESULT) {
        require(bindings.size() > 1, "snapshot has too few identity bindings to drop one");
        bindings = List.copyOf(bindings.subList(1, bindings.size()));
      }
      CommandApplierState changedApplier = applier;
      if (matching != applier.matchingState()) {
        changedApplier =
            new CommandApplierState(
                matching,
                applier.transcriptDigest(),
                semanticDigest(matching, applier.transcriptDigest()));
      }
      LocalRuntimeStateImage changed =
          new LocalRuntimeStateImage(
              changedApplier, bindings, state.lastWalSequence(), state.lastApplicationSequence());
      forceWrite(snapshot, codec.encode(decoded.anchor(), changed));
      forceDirectory(directory);
      return 1;
    } catch (IOException failure) {
      throw new IllegalStateException("cannot mutate M09 snapshot state", failure);
    }
  }

  public static int replayCutRecordAsFirstSuffix(Path directory, long shardId) {
    try {
      List<Path> segments = walSegments(directory);
      require(segments.size() == 2, "cut replay fixture requires two segments");
      byte[] covered = Files.readAllBytes(segments.getFirst());
      int length = ByteBuffer.wrap(covered, M08WalFormat.HEADER_BYTES, Integer.BYTES).getInt();
      byte[] encoded =
          java.util.Arrays.copyOfRange(
              covered, M08WalFormat.HEADER_BYTES, M08WalFormat.HEADER_BYTES + length);
      M08WalFormat.DecodedRecord decoded = M08WalFormat.decodeRecord(encoded);
      byte[] replay = M08WalFormat.record(2, 2, decoded.envelopeBytes());
      byte[] target = M08WalFormat.header(shardId, 2, 2);
      byte[] complete = java.util.Arrays.copyOf(target, target.length + replay.length);
      System.arraycopy(replay, 0, complete, target.length, replay.length);
      forceWrite(segments.getLast(), complete);
      forceDirectory(directory);
      return 1;
    } catch (IOException failure) {
      throw new IllegalStateException("cannot create M09 cut replay mutant", failure);
    }
  }

  public static int dropFirstSuffixRecord(Path directory) {
    try {
      List<Path> segments = walSegments(directory);
      require(segments.size() >= 2, "suffix skip fixture requires a cut+1 segment");
      Path active = segments.getLast();
      byte[] bytes = Files.readAllBytes(active);
      require(bytes.length > M08WalFormat.HEADER_BYTES, "suffix skip fixture has no record");
      forceWrite(active, java.util.Arrays.copyOf(bytes, M08WalFormat.HEADER_BYTES));
      forceDirectory(directory);
      return 1;
    } catch (IOException failure) {
      throw new IllegalStateException("cannot drop M09 suffix record", failure);
    }
  }

  private static String semanticDigest(MatchingStateImage matching, String transcript) {
    SingleInstrumentMatchingEngine engine = SingleInstrumentMatchingEngine.restore(matching);
    String publicCore =
        CanonicalResult.semanticDigest(
            engine.marketControlSnapshot().toString(), engine.snapshot().toString());
    return CanonicalResult.semanticDigest(publicCore, transcript);
  }

  private static Path latestSnapshot(Path directory) throws IOException {
    try (var paths = Files.list(directory)) {
      return paths
          .filter(path -> path.getFileName().toString().endsWith(".m09s1"))
          .max(Comparator.comparing(path -> path.getFileName().toString()))
          .orElseThrow(() -> new IllegalStateException("M09 mutation has no snapshot"));
    }
  }

  private static List<Path> walSegments(Path directory) throws IOException {
    try (var paths = Files.list(directory)) {
      return paths
          .filter(path -> path.getFileName().toString().endsWith(".m08w1"))
          .sorted(Comparator.comparing(path -> path.getFileName().toString()))
          .toList();
    }
  }

  private static void forceWrite(Path path, byte[] bytes) throws IOException {
    Files.write(path, bytes, StandardOpenOption.TRUNCATE_EXISTING);
    try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
      channel.force(true);
    }
  }

  private static void forceDirectory(Path directory) throws IOException {
    try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
      channel.force(true);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }

  public record CrossingFixture(
      String crossingSegment, String activeSegment, long suffixWalSequence, int actions) {}

  public record StorageOperationObservation(String kind, String path, String target) {}

  public record SnapshotSelection(
      long generation, long shardId, long lastWalSequence, long lastApplicationSequence) {}

  public enum StateMutation {
    DROP_RESTING_ORDER,
    RESET_MARKET_MODE,
    DROP_PREPARED_RULE_SET,
    DROP_DURABLE_IDENTITY_RESULT
  }
}
