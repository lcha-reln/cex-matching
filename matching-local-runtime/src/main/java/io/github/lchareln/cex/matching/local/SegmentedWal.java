package io.github.lchareln.cex.matching.local;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Single-writer segmented M08W1 WAL with strict recovery and final-tail repair. */
final class SegmentedWal implements AutoCloseable {
  private static final Pattern FINAL_NAME = Pattern.compile("segment-(\\d{20})\\.m08w1");
  private static final Pattern TEMP_NAME = Pattern.compile("segment-(\\d{20})\\.m08w1\\.tmp");
  private static final String LOCK_NAME = ".m08w1.lock";

  private final WalConfig config;
  private final FaultInjector faultInjector;
  private final FileChannel lockChannel;
  private final FileLock directoryLock;
  private final List<RecoveredRecord> recoveredRecords;

  private FileChannel activeChannel;
  private long activeSegmentId;
  private long activeSize;
  private long nextWalSequence;
  private boolean closed;

  private SegmentedWal(WalConfig config, FaultInjector faultInjector) throws IOException {
    this.config = Objects.requireNonNull(config, "config");
    this.faultInjector = Objects.requireNonNull(faultInjector, "faultInjector");
    Files.createDirectories(config.directory());
    lockChannel =
        FileChannel.open(
            config.directory().resolve(LOCK_NAME),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE);
    try {
      directoryLock = acquireLock(lockChannel, config.directory());
    } catch (IOException | RuntimeException failure) {
      try {
        lockChannel.close();
      } catch (IOException closeFailure) {
        failure.addSuppressed(closeFailure);
      }
      throw failure;
    }
    try {
      faultInjector.hit(FaultPoint.AFTER_DIRECTORY_LOCK);
      removeOrphanTemps();
      recoveredRecords = recoverOrCreate();
    } catch (Throwable failure) {
      closeResourcesAfterFailedOpen(failure);
      throw failure;
    }
  }

  static SegmentedWal open(WalConfig config, FaultInjector faultInjector) throws IOException {
    return new SegmentedWal(config, faultInjector);
  }

  List<RecoveredRecord> recoveredRecords() {
    return List.copyOf(recoveredRecords);
  }

  boolean acceptsEnvelope(int envelopeBytes) {
    if (envelopeBytes < 0) {
      return false;
    }
    long total = (long) M08WalFormat.RECORD_OVERHEAD + envelopeBytes;
    return total <= config.maxRecordBytes();
  }

  WalPosition append(byte[] envelopeBytes, long applicationSequence) throws IOException {
    ensureOpen();
    Objects.requireNonNull(envelopeBytes, "envelopeBytes");
    if (applicationSequence <= 0) {
      throw new IllegalArgumentException("applicationSequence must be positive");
    }
    if (!acceptsEnvelope(envelopeBytes.length)) {
      throw new IllegalArgumentException("M08W1 record exceeds configured maximum");
    }
    byte[] record = M08WalFormat.record(nextWalSequence, applicationSequence, envelopeBytes);
    try {
      if (activeSize + record.length > config.maxSegmentBytes()) {
        rollover();
      }
    } catch (IOException failure) {
      throw new WalAppendException("M08W1 rollover did not complete", null, failure);
    }

    WalPosition position =
        new WalPosition(
            activeSegmentId, nextWalSequence, applicationSequence, activeSize, record.length);
    try {
      activeChannel.position(activeSize);
      writeFully(activeChannel, ByteBuffer.wrap(record, 0, Integer.BYTES));
      faultInjector.hit(FaultPoint.AFTER_RECORD_LENGTH_WRITE);
      writeFully(
          activeChannel, ByteBuffer.wrap(record, Integer.BYTES, record.length - Integer.BYTES));
      faultInjector.hit(FaultPoint.AFTER_RECORD_BODY_WRITE);
      activeChannel.force(true);
      faultInjector.hit(FaultPoint.AFTER_RECORD_FORCE);
    } catch (IOException failure) {
      throw new WalAppendException("M08W1 append did not complete normally", position, failure);
    }
    activeSize += record.length;
    nextWalSequence = Math.incrementExact(nextWalSequence);
    return position;
  }

  long nextWalSequence() {
    return nextWalSequence;
  }

  @Override
  public void close() throws IOException {
    if (closed) {
      return;
    }
    closed = true;
    IOException failure = null;
    if (activeChannel != null) {
      try {
        activeChannel.close();
      } catch (IOException closeFailure) {
        failure = closeFailure;
      }
    }
    try {
      directoryLock.release();
    } catch (IOException closeFailure) {
      failure = combine(failure, closeFailure);
    }
    try {
      lockChannel.close();
    } catch (IOException closeFailure) {
      failure = combine(failure, closeFailure);
    }
    if (failure != null) {
      throw failure;
    }
  }

  private List<RecoveredRecord> recoverOrCreate() throws IOException {
    List<SegmentFile> segments = discoverFinalSegments();
    if (segments.isEmpty()) {
      createSegment(1, 1);
      return List.of();
    }
    if (segments.getFirst().segmentId() != 1) {
      throw new WalCorruptionException("M08W1 genesis segment must have id 1");
    }

    List<RecoveredRecord> records = new ArrayList<>();
    long expectedSegmentId = 1;
    long expectedWalSequence = 1;
    for (int index = 0; index < segments.size(); index++) {
      SegmentFile segment = segments.get(index);
      boolean isFinal = index == segments.size() - 1;
      if (segment.segmentId() != expectedSegmentId) {
        throw new WalCorruptionException("M08W1 segment ids contain a gap");
      }
      RecoveryCursor cursor = recoverSegment(segment, isFinal, expectedWalSequence, records);
      expectedWalSequence = cursor.nextWalSequence();
      if (!isFinal && cursor.size() == M08WalFormat.HEADER_BYTES) {
        throw new WalCorruptionException("a non-final M08W1 segment contains no record");
      }
      expectedSegmentId = Math.incrementExact(expectedSegmentId);
    }

    SegmentFile last = segments.getLast();
    activeSegmentId = last.segmentId();
    activeChannel =
        FileChannel.open(last.path(), StandardOpenOption.READ, StandardOpenOption.WRITE);
    activeSize = activeChannel.size();
    nextWalSequence = expectedWalSequence;
    return List.copyOf(records);
  }

  private RecoveryCursor recoverSegment(
      SegmentFile segment,
      boolean isFinal,
      long expectedFirstWalSequence,
      List<RecoveredRecord> records)
      throws IOException {
    try (FileChannel channel =
        FileChannel.open(segment.path(), StandardOpenOption.READ, StandardOpenOption.WRITE)) {
      long size = channel.size();
      if (size < M08WalFormat.HEADER_BYTES) {
        throw new WalCorruptionException("M08W1 segment header is incomplete");
      }
      byte[] headerBytes = readExactly(channel, 0, M08WalFormat.HEADER_BYTES);
      M08WalFormat.SegmentHeader header = M08WalFormat.decodeHeader(headerBytes);
      if (header.shardId() != config.shardId()
          || header.segmentId() != segment.segmentId()
          || header.firstWalSequence() != expectedFirstWalSequence) {
        throw new WalCorruptionException("M08W1 segment header identity is inconsistent");
      }

      long offset = M08WalFormat.HEADER_BYTES;
      long expectedWalSequence = expectedFirstWalSequence;
      while (offset < size) {
        long remaining = size - offset;
        if (remaining < Integer.BYTES) {
          size = repairFinalTail(channel, isFinal, offset);
          break;
        }
        int recordLength = ByteBuffer.wrap(readExactly(channel, offset, Integer.BYTES)).getInt();
        if (recordLength < M08WalFormat.MIN_RECORD_BYTES
            || recordLength > config.maxRecordBytes()) {
          throw new WalCorruptionException("M08W1 record declares an invalid complete length");
        }
        if (remaining < recordLength) {
          size = repairFinalTail(channel, isFinal, offset);
          break;
        }
        byte[] encoded = readExactly(channel, offset, recordLength);
        M08WalFormat.DecodedRecord decoded = M08WalFormat.decodeRecord(encoded);
        if (decoded.walSequence() != expectedWalSequence) {
          throw new WalCorruptionException("M08W1 WAL sequence is not contiguous");
        }
        WalPosition position =
            new WalPosition(
                segment.segmentId(),
                decoded.walSequence(),
                decoded.applicationSequence(),
                offset,
                recordLength);
        records.add(new RecoveredRecord(position, decoded.envelopeBytes()));
        expectedWalSequence = Math.incrementExact(expectedWalSequence);
        offset += recordLength;
      }
      return new RecoveryCursor(expectedWalSequence, size);
    }
  }

  private long repairFinalTail(FileChannel channel, boolean isFinal, long goodOffset)
      throws IOException {
    if (!isFinal) {
      throw new WalCorruptionException("only the final M08W1 segment may have an incomplete tail");
    }
    channel.truncate(goodOffset);
    faultInjector.hit(FaultPoint.AFTER_TAIL_TRUNCATE);
    channel.force(true);
    faultInjector.hit(FaultPoint.AFTER_TAIL_TRUNCATE_FORCE);
    return goodOffset;
  }

  private void rollover() throws IOException {
    long newSegment = Math.incrementExact(activeSegmentId);
    createSegment(newSegment, nextWalSequence);
  }

  private void createSegment(long segmentId, long firstWalSequence) throws IOException {
    Path temporary = config.directory().resolve(tempName(segmentId));
    Path target = config.directory().resolve(finalName(segmentId));
    Files.deleteIfExists(temporary);
    byte[] header = M08WalFormat.header(config.shardId(), segmentId, firstWalSequence);
    try (FileChannel channel =
        FileChannel.open(
            temporary,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE)) {
      writeFully(channel, ByteBuffer.wrap(header));
      faultInjector.hit(FaultPoint.AFTER_SEGMENT_HEADER_WRITE);
      channel.force(true);
      faultInjector.hit(FaultPoint.AFTER_SEGMENT_HEADER_FORCE);
    }
    try {
      Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException failure) {
      throw new IOException("M08W1 requires an atomic segment rename", failure);
    }
    faultInjector.hit(FaultPoint.AFTER_SEGMENT_ATOMIC_RENAME);
    forceDirectory();
    faultInjector.hit(FaultPoint.AFTER_DIRECTORY_FORCE);

    if (activeChannel != null) {
      activeChannel.close();
    }
    activeChannel = FileChannel.open(target, StandardOpenOption.READ, StandardOpenOption.WRITE);
    activeSegmentId = segmentId;
    activeSize = M08WalFormat.HEADER_BYTES;
    nextWalSequence = firstWalSequence;
  }

  private List<SegmentFile> discoverFinalSegments() throws IOException {
    List<SegmentFile> segments = new ArrayList<>();
    try (DirectoryStream<Path> paths = Files.newDirectoryStream(config.directory())) {
      for (Path path : paths) {
        Matcher matcher = FINAL_NAME.matcher(path.getFileName().toString());
        if (matcher.matches()) {
          try {
            segments.add(new SegmentFile(Long.parseLong(matcher.group(1)), path));
          } catch (NumberFormatException failure) {
            throw new WalCorruptionException("M08W1 segment id cannot be represented", failure);
          }
        }
      }
    }
    segments.sort(Comparator.comparingLong(SegmentFile::segmentId));
    return segments;
  }

  private void removeOrphanTemps() throws IOException {
    boolean removed = false;
    try (DirectoryStream<Path> paths = Files.newDirectoryStream(config.directory())) {
      for (Path path : paths) {
        if (TEMP_NAME.matcher(path.getFileName().toString()).matches()) {
          Files.delete(path);
          removed = true;
        }
      }
    }
    if (removed) {
      forceDirectory();
    }
  }

  private void forceDirectory() throws IOException {
    try (FileChannel directory = FileChannel.open(config.directory(), StandardOpenOption.READ)) {
      directory.force(true);
    }
  }

  private void ensureOpen() {
    if (closed) {
      throw new IllegalStateException("M08W1 WAL is closed");
    }
  }

  private void closeResourcesAfterFailedOpen(Throwable original) {
    if (activeChannel != null) {
      try {
        activeChannel.close();
      } catch (IOException closeFailure) {
        original.addSuppressed(closeFailure);
      }
    }
    try {
      directoryLock.release();
    } catch (IOException closeFailure) {
      original.addSuppressed(closeFailure);
    }
    try {
      lockChannel.close();
    } catch (IOException closeFailure) {
      original.addSuppressed(closeFailure);
    }
  }

  private static FileLock acquireLock(FileChannel channel, Path directory) throws IOException {
    try {
      FileLock lock = channel.tryLock();
      if (lock == null) {
        throw new DirectoryLockException("M08W1 directory is already locked: " + directory);
      }
      return lock;
    } catch (OverlappingFileLockException failure) {
      throw new DirectoryLockException("M08W1 directory is already locked: " + directory);
    }
  }

  private static byte[] readExactly(FileChannel channel, long offset, int length)
      throws IOException {
    ByteBuffer target = ByteBuffer.allocate(length);
    long position = offset;
    while (target.hasRemaining()) {
      int read = channel.read(target, position);
      if (read < 0) {
        throw new WalCorruptionException("M08W1 complete frame became unreadable");
      }
      if (read == 0) {
        throw new IOException("M08W1 read made no progress");
      }
      position += read;
    }
    return target.array();
  }

  private static void writeFully(FileChannel channel, ByteBuffer source) throws IOException {
    while (source.hasRemaining()) {
      int written = channel.write(source);
      if (written == 0) {
        throw new IOException("M08W1 write made no progress");
      }
    }
  }

  private static IOException combine(IOException first, IOException next) {
    if (first == null) {
      return next;
    }
    first.addSuppressed(next);
    return first;
  }

  private static String finalName(long segmentId) {
    return "segment-%020d.m08w1".formatted(segmentId);
  }

  private static String tempName(long segmentId) {
    return finalName(segmentId) + ".tmp";
  }

  private record SegmentFile(long segmentId, Path path) {}

  private record RecoveryCursor(long nextWalSequence, long size) {}
}
