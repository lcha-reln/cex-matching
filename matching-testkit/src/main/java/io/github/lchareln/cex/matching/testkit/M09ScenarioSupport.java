package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.MarketMode;
import io.github.lchareln.cex.matching.MarketRuleSetArtifact;
import io.github.lchareln.cex.matching.local.CanonicalResult;
import io.github.lchareln.cex.matching.local.LocalMatchingRuntime;
import io.github.lchareln.cex.matching.local.M08Command;
import io.github.lchareln.cex.matching.local.M08EnvelopeCodec;
import io.github.lchareln.cex.matching.local.RecoveryBudget;
import io.github.lchareln.cex.matching.local.SubmissionResult;
import io.github.lchareln.cex.matching.local.WalConfig;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.CRC32C;

/** Shared real-runtime commands and independent file mutations for M09 judge suites. */
final class M09ScenarioSupport {
  static final long SHARD = 9_109;
  static final int MIN_MAX_RECORD_BYTES = 1_048_608;
  static final long SMALL_SEGMENT_BYTES = 1_100_000;
  static final String LARGE_FIELD = "m".repeat(12_000);
  private static final int SNAPSHOT_SHA_BYTES = 32;
  private final M08EnvelopeCodec codec = new M08EnvelopeCodec();

  WalConfig config(Path directory) {
    return WalConfig.snapshotDefaults(directory, SHARD);
  }

  WalConfig unbounded(Path directory) {
    return WalConfig.defaults(directory, SHARD);
  }

  WalConfig small(Path directory) {
    return new WalConfig(
        directory,
        SHARD,
        SMALL_SEGMENT_BYTES,
        MIN_MAX_RECORD_BYTES,
        new RecoveryBudget(64, 1_048_576));
  }

  WalConfig smallWithBudget(Path directory, long records, long bytes) {
    return new WalConfig(
        directory,
        SHARD,
        SMALL_SEGMENT_BYTES,
        MIN_MAX_RECORD_BYTES,
        new RecoveryBudget(records, bytes));
  }

  WalConfig budget(Path directory, long records, long bytes) {
    return new WalConfig(
        directory,
        SHARD,
        WalConfig.DEFAULT_MAX_SEGMENT_BYTES,
        WalConfig.DEFAULT_MAX_RECORD_BYTES,
        new RecoveryBudget(records, bytes));
  }

  CommandStream stream(String producer) {
    return new CommandStream(producer);
  }

  byte[] envelope(String producer, long epoch, long sequence, UUID commandId, M08Command command) {
    return codec.encode(producer, epoch, SHARD, sequence, commandId, command);
  }

  List<M08Command> fullStateCommands() {
    MarketRuleSetArtifact bootstrap = MarketRuleSetArtifact.bootstrap();
    MarketRuleSetArtifact first = ruleSet(2, 1, Long.MAX_VALUE);
    MarketRuleSetArtifact second = ruleSet(3, 2, Long.MAX_VALUE - 1);
    return List.of(
        place(1, "SELL", 101, 5, 11, "CANCEL_TAKER"),
        place(6, "SELL", 101, 4, 16, "CANCEL_TAKER"),
        place(2, "BUY", 101, 2, 12, "CANCEL_TAKER"),
        place(3, "SELL", 102, 4, 13, "CANCEL_TAKER"),
        cancel(3),
        place(4, "SELL", 99, 3, 77, "CANCEL_TAKER"),
        place(5, "BUY", 99, 1, 77, "CANCEL_TAKER"),
        new M08Command.PrepareRuleSet(bootstrap.identity(), first),
        new M08Command.ActivateRuleSet(9, bootstrap.identity(), first.identity()),
        new M08Command.PrepareRuleSet(first.identity(), second),
        new M08Command.ChangeMarketMode(11, MarketMode.OPEN, MarketMode.CANCEL_ONLY, "m09-ops"));
  }

  List<byte[]> encode(CommandStream stream, List<M08Command> commands) {
    List<byte[]> result = new ArrayList<>();
    commands.forEach(command -> result.add(stream.next(command)));
    return List.copyOf(result);
  }

  static M08Command.Place place(
      long orderId,
      String side,
      long price,
      long quantity,
      long participantGroup,
      String stpPolicy) {
    return new M08Command.Place(
        "BTC-USDT",
        BigInteger.valueOf(orderId),
        side,
        BigInteger.valueOf(price),
        BigInteger.valueOf(quantity),
        "GTC",
        participantGroup,
        stpPolicy,
        Optional.empty());
  }

  static M08Command.Cancel cancel(long orderId) {
    return new M08Command.Cancel("BTC-USDT", BigInteger.valueOf(orderId));
  }

  static M08Command.Place largeBusinessRejection(long orderId) {
    return new M08Command.Place(
        LARGE_FIELD,
        BigInteger.valueOf(orderId),
        LARGE_FIELD,
        BigInteger.ONE,
        BigInteger.ONE,
        LARGE_FIELD,
        0,
        "NONE",
        Optional.empty());
  }

  static MarketRuleSetArtifact ruleSet(long version, long lower, long upper) {
    MarketRuleSetArtifact unhashed =
        new MarketRuleSetArtifact(
            version,
            lower,
            upper,
            "sha256:0000000000000000000000000000000000000000000000000000000000000000");
    return new MarketRuleSetArtifact(version, lower, upper, unhashed.computedContentHash());
  }

  static SubmissionResult.NewDurablyApplied requireNew(SubmissionResult result, String message) {
    if (!(result instanceof SubmissionResult.NewDurablyApplied applied)) {
      throw new M09SemanticFailure(message + ": " + result);
    }
    return applied;
  }

  static SubmissionResult.DuplicateReplayed requireDuplicate(
      SubmissionResult result, String message) {
    if (!(result instanceof SubmissionResult.DuplicateReplayed duplicate)) {
      throw new M09SemanticFailure(message + ": " + result);
    }
    return duplicate;
  }

  static void require(boolean condition, String message) {
    if (!condition) {
      throw new M09SemanticFailure(message);
    }
  }

  static void requireExactInjectedIOException(
      IOException observed, IOException injected, String boundary) {
    if (injected == null || observed != injected) {
      throw new IllegalStateException(boundary + " surfaced an unrelated IOException", observed);
    }
  }

  static void requireSystemBoundary(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }

  static String signature(SubmissionResult result) {
    if (result instanceof SubmissionResult.NewDurablyApplied applied) {
      return canonical("NEW", applied.position().walSequence(), applied.result());
    }
    if (result instanceof SubmissionResult.DuplicateReplayed duplicate) {
      return canonical(
          "DUPLICATE", duplicate.originalPosition().walSequence(), duplicate.originalResult());
    }
    if (result instanceof SubmissionResult.PreflightRejected rejected) {
      return "PREFLIGHT|" + rejected.code();
    }
    if (result instanceof SubmissionResult.StructuralRejected rejected) {
      return "STRUCTURAL|" + rejected.code();
    }
    if (result instanceof SubmissionResult.CheckpointRequired required) {
      return "CHECKPOINT_REQUIRED|" + required.suffixRecords() + '|' + required.suffixBytes();
    }
    if (result instanceof SubmissionResult.DurabilityUnknown unknown) {
      return "UNKNOWN|" + unknown.stage();
    }
    return "FAILED_CLOSED";
  }

  private static String canonical(String kind, long wal, CanonicalResult result) {
    return kind
        + '|'
        + wal
        + '|'
        + result.applicationSequence()
        + '|'
        + result.resultDigest()
        + '|'
        + result.semanticStateDigest();
  }

  static void requireEquivalent(LocalMatchingRuntime candidate, LocalMatchingRuntime genesis) {
    require(
        candidate.nextWalSequence() == genesis.nextWalSequence(),
        "snapshot runtime and genesis next WAL sequence differ");
    require(
        candidate.semanticStateDigest().equals(genesis.semanticStateDigest()),
        "snapshot runtime and genesis semantic digest differ");
  }

  static List<Path> snapshotFiles(Path directory) {
    return files(directory, ".m09s1");
  }

  static List<Path> segmentFiles(Path directory) {
    return files(directory, ".m08w1");
  }

  static List<Path> tempSnapshots(Path directory) {
    return files(directory, ".m09s1.tmp");
  }

  private static List<Path> files(Path directory, String suffix) {
    try (var paths = Files.list(directory)) {
      return paths
          .filter(path -> path.getFileName().toString().endsWith(suffix))
          .sorted(Comparator.comparing(path -> path.getFileName().toString()))
          .toList();
    } catch (IOException failure) {
      throw new IllegalStateException("cannot list M09 files", failure);
    }
  }

  static void corruptBody(Path snapshot) {
    try {
      byte[] bytes = Files.readAllBytes(snapshot);
      bytes[Math.max(48, bytes.length / 2)] ^= 1;
      Files.write(snapshot, bytes, StandardOpenOption.TRUNCATE_EXISTING);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot corrupt M09 snapshot", failure);
    }
  }

  static void rewriteVersionWithValidIntegrity(Path snapshot, int version) {
    rewriteHeader(snapshot, 4, ByteBuffer.allocate(4).putInt(version).array());
  }

  static void rewriteShardWithValidIntegrity(Path snapshot, long shard) {
    rewriteHeader(snapshot, 16, ByteBuffer.allocate(8).putLong(shard).array());
  }

  static void rewriteGenerationWithValidIntegrity(Path snapshot, long generation) {
    rewriteHeader(snapshot, 8, ByteBuffer.allocate(8).putLong(generation).array());
  }

  static void rewriteWalCutWithValidIntegrity(Path snapshot, long lastWalSequence) {
    rewriteHeader(snapshot, 24, ByteBuffer.allocate(8).putLong(lastWalSequence).array());
  }

  private static void rewriteHeader(Path snapshot, int offset, byte[] replacement) {
    try {
      byte[] bytes = Files.readAllBytes(snapshot);
      System.arraycopy(replacement, 0, bytes, offset, replacement.length);
      int digestOffset = bytes.length - SNAPSHOT_SHA_BYTES;
      int crcOffset = digestOffset - Integer.BYTES;
      CRC32C crc = new CRC32C();
      crc.update(bytes, 0, crcOffset);
      ByteBuffer.wrap(bytes, crcOffset, Integer.BYTES).putInt((int) crc.getValue());
      byte[] digest = sha256(bytes, digestOffset);
      System.arraycopy(digest, 0, bytes, digestOffset, digest.length);
      Files.write(snapshot, bytes, StandardOpenOption.TRUNCATE_EXISTING);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot rewrite M09 snapshot header", failure);
    }
  }

  private static byte[] sha256(byte[] bytes, int length) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(bytes, 0, length);
      return digest.digest();
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA-256 is unavailable", failure);
    }
  }

  static void deleteTree(Path path) {
    if (!Files.exists(path)) {
      return;
    }
    try (var paths = Files.walk(path)) {
      for (Path current : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(current);
      }
    } catch (IOException failure) {
      throw new IllegalStateException("cannot clear M09 working path", failure);
    }
  }

  final class CommandStream {
    private final String producer;
    private long sequence = 1;

    private CommandStream(String producer) {
      this.producer = producer;
    }

    byte[] next(M08Command command) {
      long current = sequence++;
      return codec.encode(
          producer,
          1,
          SHARD,
          current,
          new UUID(0x0909000000000000L ^ producer.hashCode(), current),
          command);
    }

    long nextSequence() {
      return sequence;
    }
  }
}
