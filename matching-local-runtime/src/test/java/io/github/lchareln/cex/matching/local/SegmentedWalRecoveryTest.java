package io.github.lchareln.cex.matching.local;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SegmentedWalRecoveryTest {
  private static final long SHARD = 23;
  private static final int MAX_RECORD =
      M08EnvelopeCodec.MAX_ENVELOPE_BYTES + M08WalFormat.RECORD_OVERHEAD;
  private static final long MAX_SEGMENT = M08WalFormat.HEADER_BYTES + (long) MAX_RECORD;

  @TempDir Path temporaryDirectory;

  @Test
  void genesisAndRolloverPublishForcedHeadersWithContiguousCoordinates() throws Exception {
    WalConfig config =
        new WalConfig(temporaryDirectory.resolve("rollover"), SHARD, MAX_SEGMENT, MAX_RECORD);
    byte[] fullEnvelope = new byte[M08EnvelopeCodec.MAX_ENVELOPE_BYTES];
    fullEnvelope[0] = 1;
    fullEnvelope[fullEnvelope.length - 1] = 2;

    try (SegmentedWal wal = SegmentedWal.open(config, FaultInjector.NONE)) {
      assertTrue(wal.recoveredRecords().isEmpty());
      WalPosition first = wal.append(fullEnvelope, 1);
      WalPosition second = wal.append(fullEnvelope, 2);
      assertEquals(1, first.segmentId());
      assertEquals(2, second.segmentId());
      assertEquals(1, first.walSequence());
      assertEquals(2, second.walSequence());
    }

    Path orphan = config.directory().resolve("segment-00000000000000000003.m08w1.tmp");
    Files.write(orphan, new byte[] {9, 9, 9});
    try (SegmentedWal recovered = SegmentedWal.open(config, FaultInjector.NONE)) {
      assertFalse(Files.exists(orphan));
      assertEquals(2, recovered.recoveredRecords().size());
      assertArrayEquals(fullEnvelope, recovered.recoveredRecords().getFirst().envelopeBytes());
      assertArrayEquals(fullEnvelope, recovered.recoveredRecords().getLast().envelopeBytes());
      assertEquals(3, recovered.nextWalSequence());
    }
  }

  @Test
  void rolloverNeverWritesFirstRecordBeforeRenameAndDirectoryForceComplete() throws Exception {
    for (FaultPoint point :
        List.of(FaultPoint.AFTER_SEGMENT_ATOMIC_RENAME, FaultPoint.AFTER_DIRECTORY_FORCE)) {
      Path directory = temporaryDirectory.resolve("rollover-fault-" + point);
      WalConfig config = new WalConfig(directory, SHARD, MAX_SEGMENT, MAX_RECORD);
      byte[] fullEnvelope = new byte[M08EnvelopeCodec.MAX_ENVELOPE_BYTES];
      NthFault injector = new NthFault(point, 2);
      try (SegmentedWal wal = SegmentedWal.open(config, injector)) {
        wal.append(fullEnvelope, 1);
        WalAppendException failure =
            assertThrows(WalAppendException.class, () -> wal.append(fullEnvelope, 2));
        assertTrue(failure.attemptedPosition().isEmpty());
      }

      try (SegmentedWal recovered = SegmentedWal.open(config, FaultInjector.NONE)) {
        assertEquals(1, recovered.recoveredRecords().size());
        assertEquals(2, recovered.nextWalSequence());
        WalPosition retried = recovered.append(fullEnvelope, 2);
        assertEquals(2, retried.segmentId());
      }
    }
  }

  @Test
  void failedTempHeaderPublicationIsNeverRecoveredAsAuthority() throws Exception {
    for (FaultPoint point :
        List.of(FaultPoint.AFTER_SEGMENT_HEADER_WRITE, FaultPoint.AFTER_SEGMENT_HEADER_FORCE)) {
      Path directory = temporaryDirectory.resolve("header-fault-" + point);
      WalConfig config = WalConfig.defaults(directory, SHARD);
      assertThrows(IOException.class, () -> SegmentedWal.open(config, new NthFault(point, 1)));
      try (SegmentedWal recovered = SegmentedWal.open(config, FaultInjector.NONE)) {
        assertTrue(recovered.recoveredRecords().isEmpty());
        assertEquals(1, recovered.nextWalSequence());
      }
    }
  }

  @Test
  void secondWriterCannotOpenTheSameDirectory() throws Exception {
    WalConfig config = WalConfig.defaults(temporaryDirectory.resolve("lock"), SHARD);
    try (SegmentedWal first = SegmentedWal.open(config, FaultInjector.NONE)) {
      assertEquals(1, first.nextWalSequence());
      assertThrows(
          DirectoryLockException.class, () -> SegmentedWal.open(config, FaultInjector.NONE));
    }
  }

  @Test
  void onlyFinalIncompleteTailIsTruncatedAndForced() throws Exception {
    WalConfig config = WalConfig.defaults(temporaryDirectory.resolve("final-tail"), SHARD);
    byte[] envelope = new byte[] {1, 2, 3};
    Path segment = config.directory().resolve("segment-00000000000000000001.m08w1");
    long goodSize;
    try (SegmentedWal wal = SegmentedWal.open(config, FaultInjector.NONE)) {
      wal.append(envelope, 1);
    }
    goodSize = Files.size(segment);
    try (FileChannel channel =
        FileChannel.open(segment, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
      channel.write(ByteBuffer.wrap(new byte[] {0, 1}));
    }

    List<FaultPoint> seen = new ArrayList<>();
    try (SegmentedWal recovered = SegmentedWal.open(config, seen::add)) {
      assertEquals(1, recovered.recoveredRecords().size());
      assertArrayEquals(envelope, recovered.recoveredRecords().getFirst().envelopeBytes());
    }
    assertEquals(goodSize, Files.size(segment));
    assertTrue(seen.contains(FaultPoint.AFTER_TAIL_TRUNCATE));
    assertTrue(seen.contains(FaultPoint.AFTER_TAIL_TRUNCATE_FORCE));
  }

  @Test
  void incompleteTailInNonFinalSegmentFailsClosed() throws Exception {
    WalConfig config =
        new WalConfig(temporaryDirectory.resolve("mid-tail"), SHARD, MAX_SEGMENT, MAX_RECORD);
    byte[] fullEnvelope = new byte[M08EnvelopeCodec.MAX_ENVELOPE_BYTES];
    try (SegmentedWal wal = SegmentedWal.open(config, FaultInjector.NONE)) {
      wal.append(fullEnvelope, 1);
      wal.append(fullEnvelope, 2);
    }
    Path first = config.directory().resolve("segment-00000000000000000001.m08w1");
    try (FileChannel channel = FileChannel.open(first, StandardOpenOption.WRITE)) {
      channel.truncate(channel.size() - 1);
    }
    assertThrows(WalCorruptionException.class, () -> SegmentedWal.open(config, FaultInjector.NONE));
  }

  @Test
  void completeMidLogOrFinalCrcCorruptionIsNeverTailRepair() throws Exception {
    WalConfig config = WalConfig.defaults(temporaryDirectory.resolve("crc"), SHARD);
    try (SegmentedWal wal = SegmentedWal.open(config, FaultInjector.NONE)) {
      wal.append(new byte[] {1, 2, 3}, 1);
      wal.append(new byte[] {4, 5, 6}, 2);
    }
    Path segment = config.directory().resolve("segment-00000000000000000001.m08w1");
    byte[] bytes = Files.readAllBytes(segment);
    int firstPayloadOffset =
        M08WalFormat.HEADER_BYTES + M08WalFormat.RECORD_OVERHEAD - Integer.BYTES;
    bytes[firstPayloadOffset] ^= 1;
    Files.write(segment, bytes, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);

    assertThrows(WalCorruptionException.class, () -> SegmentedWal.open(config, FaultInjector.NONE));
  }

  @Test
  void corruptCompleteHeaderFailsGenesisRecovery() throws Exception {
    WalConfig config = WalConfig.defaults(temporaryDirectory.resolve("header"), SHARD);
    try (SegmentedWal genesis = SegmentedWal.open(config, FaultInjector.NONE)) {
      // Create and force the genesis header.
      assertEquals(1, genesis.nextWalSequence());
    }
    Path segment = config.directory().resolve("segment-00000000000000000001.m08w1");
    byte[] bytes = Files.readAllBytes(segment);
    bytes[8] ^= 1;
    Files.write(segment, bytes, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
    assertThrows(WalCorruptionException.class, () -> SegmentedWal.open(config, FaultInjector.NONE));
  }

  @Test
  void declaredButIncompleteFinalFrameIsTailNotCompleteCorruption() throws Exception {
    WalConfig config = WalConfig.defaults(temporaryDirectory.resolve("declared-tail"), SHARD);
    Path segment = config.directory().resolve("segment-00000000000000000001.m08w1");
    long goodSize;
    try (SegmentedWal wal = SegmentedWal.open(config, FaultInjector.NONE)) {
      wal.append(new byte[] {7}, 1);
    }
    goodSize = Files.size(segment);
    try (FileChannel channel =
        FileChannel.open(segment, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
      channel.write(ByteBuffer.allocate(Integer.BYTES).putInt(128).flip());
    }
    try (SegmentedWal recovered = SegmentedWal.open(config, FaultInjector.NONE)) {
      assertEquals(1, recovered.recoveredRecords().size());
    }
    assertEquals(goodSize, Files.size(segment));
  }

  private static final class NthFault implements FaultInjector {
    private final FaultPoint target;
    private final int occurrence;
    private int seen;

    private NthFault(FaultPoint target, int occurrence) {
      this.target = target;
      this.occurrence = occurrence;
    }

    @Override
    public void hit(FaultPoint point) throws IOException {
      if (point == target && ++seen == occurrence) {
        throw new IOException("injected " + point + " occurrence " + occurrence);
      }
    }
  }
}
