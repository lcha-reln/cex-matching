package io.github.lchareln.cex.matching.local;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Single-writer segmented M08W1 WAL with strict recovery and final-tail repair. */
final class SegmentedWal implements AutoCloseable {
  private static final Pattern FINAL_NAME = Pattern.compile("segment-(\\d{20})\\.m08w1");
  private static final Pattern TEMP_NAME = Pattern.compile("segment-(\\d{20})\\.m08w1\\.tmp");
  private static final String LOCK_NAME = ".m08w1.lock";

  private final WalConfig config;
  private final FaultInjector faultInjector;
  private final StorageOperations storageOperations;
  private final FileChannel lockChannel;
  private final FileLock directoryLock;
  private final List<RecoveredRecord> recoveredRecords;
  private final SnapshotStore snapshotStore;
  private final Optional<M09SnapshotCodec.DecodedSnapshot> recoveredSnapshot;

  private FileChannel activeChannel;
  private long activeSegmentId;
  private long activeSize;
  private long nextWalSequence;
  private long suffixRecordCount;
  private long suffixBytes;
  private boolean closed;

  private SegmentedWal(
      WalConfig config, FaultInjector faultInjector, StorageOperations storageOperations)
      throws IOException {
    this.config = Objects.requireNonNull(config, "config");
    this.faultInjector = Objects.requireNonNull(faultInjector, "faultInjector");
    this.storageOperations = Objects.requireNonNull(storageOperations, "storageOperations");
    if (!Files.isDirectory(config.directory(), LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException(
          "M08W1 requires a pre-provisioned, non-symlink WAL directory: " + config.directory());
    }
    lockChannel =
        FileChannel.open(
            config.directory().resolve(LOCK_NAME),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE);
    try {
      faultInjector.hit(FaultPoint.BEFORE_DIRECTORY_LOCK);
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
      snapshotStore =
          new SnapshotStore(config.directory(), config.shardId(), faultInjector, storageOperations);
      recoveredSnapshot = snapshotStore.discover();
      removeOrphanTemps();
      recoveredRecords = recoverOrCreate(recoveredSnapshot.map(value -> value.anchor()));
      suffixRecordCount = recoveredRecords.size();
      suffixBytes =
          recoveredRecords.stream().mapToLong(record -> record.position().recordLength()).sum();
    } catch (Throwable failure) {
      closeResourcesAfterFailedOpen(failure);
      throw failure;
    }
  }

  static SegmentedWal open(
      WalConfig config, FaultInjector faultInjector, StorageOperations storageOperations)
      throws IOException {
    return new SegmentedWal(config, faultInjector, storageOperations);
  }

  static SegmentedWal open(WalConfig config, FaultInjector faultInjector) throws IOException {
    return open(config, faultInjector, JdkStorageOperations.INSTANCE);
  }

  List<RecoveredRecord> recoveredRecords() {
    return List.copyOf(recoveredRecords);
  }

  Optional<M09SnapshotCodec.DecodedSnapshot> recoveredSnapshot() {
    return recoveredSnapshot;
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
    int recordLength = Math.addExact(M08WalFormat.RECORD_OVERHEAD, envelopeBytes.length);
    try {
      if (activeSize + recordLength > config.maxSegmentBytes()) {
        rollover();
      }
    } catch (IOException failure) {
      throw new WalAppendException("M08W1 rollover did not complete", null, failure);
    }

    WalPosition position =
        new WalPosition(
            activeSegmentId, nextWalSequence, applicationSequence, activeSize, recordLength);
    try {
      activeChannel.position(activeSize);
      faultInjector.hit(FaultPoint.BEFORE_RECORD_LENGTH_WRITE);
      // Build the immutable record only after the pre-write hook. This makes the ingress ownership
      // regression deterministic: without LocalMatchingRuntime's private clone, a hook that mutates
      // the caller array here would persist different bytes from those already decoded.
      byte[] record = M08WalFormat.record(nextWalSequence, applicationSequence, envelopeBytes);
      writeFully(activeChannel, ByteBuffer.wrap(record, 0, Integer.BYTES));
      faultInjector.hit(FaultPoint.AFTER_RECORD_LENGTH_WRITE);
      faultInjector.hit(FaultPoint.BEFORE_RECORD_BODY_WRITE);
      writeFully(
          activeChannel, ByteBuffer.wrap(record, Integer.BYTES, record.length - Integer.BYTES));
      faultInjector.hit(FaultPoint.AFTER_RECORD_BODY_WRITE);
      faultInjector.hit(FaultPoint.BEFORE_RECORD_FORCE);
      activeChannel.force(true);
      faultInjector.hit(FaultPoint.AFTER_RECORD_FORCE);
    } catch (IOException failure) {
      throw new WalAppendException("M08W1 append did not complete normally", position, failure);
    }
    activeSize += recordLength;
    nextWalSequence = Math.incrementExact(nextWalSequence);
    suffixRecordCount = Math.incrementExact(suffixRecordCount);
    suffixBytes = Math.addExact(suffixBytes, recordLength);
    return position;
  }

  boolean hasRecoveryBudgetFor(int envelopeBytes) {
    if (!acceptsEnvelope(envelopeBytes)) {
      return false;
    }
    int recordLength = Math.addExact(M08WalFormat.RECORD_OVERHEAD, envelopeBytes);
    return config.recoveryBudget().accepts(suffixRecordCount, suffixBytes, recordLength);
  }

  SubmissionResult.CheckpointRequired checkpointRequired() {
    RecoveryBudget budget = config.recoveryBudget();
    return new SubmissionResult.CheckpointRequired(
        suffixRecordCount, suffixBytes, budget.maxSuffixRecords(), budget.maxSuffixBytes());
  }

  CheckpointResult checkpoint(LocalRuntimeStateImage stateImage) throws IOException {
    ensureOpen();
    if (stateImage.lastWalSequence() != nextWalSequence - 1) {
      throw new IllegalArgumentException("checkpoint state is not at the current WAL boundary");
    }
    SnapshotAnchor anchor = snapshotStore.publish(stateImage);
    // A published snapshot remains recoverable with the old WAL if the process stops here. Before
    // any prefix retirement, make cut+1 durable as the active header.
    if (activeSize > M08WalFormat.HEADER_BYTES) {
      rollover();
    }
    long protectedPrefix = snapshotStore.retainLatestTwo();
    long prunedThrough = pruneClosedSegmentsThrough(protectedPrefix);
    suffixRecordCount = 0;
    suffixBytes = 0;
    return new CheckpointResult(anchor, prunedThrough);
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

  private List<RecoveredRecord> recoverOrCreate(Optional<SnapshotAnchor> snapshot)
      throws IOException {
    List<SegmentFile> segments = discoverFinalSegments();
    if (segments.isEmpty()) {
      if (snapshot.isPresent()) {
        throw new WalCorruptionException("published M09S1 has no WAL cut+1 segment");
      }
      createSegment(1, 1);
      return List.of();
    }
    if (snapshot.isEmpty() && segments.getFirst().segmentId() != 1) {
      throw new WalCorruptionException("M08W1 genesis segment must have id 1");
    }

    List<RecoveredRecord> records = new ArrayList<>();
    RecoveryUsage recoveryUsage = new RecoveryUsage();
    long expectedSegmentId = segments.getFirst().segmentId();
    long expectedWalSequence = snapshot.isPresent() ? 0 : 1;
    long minimumWalExclusive = snapshot.map(SnapshotAnchor::lastWalSequence).orElse(0L);
    for (int index = 0; index < segments.size(); index++) {
      SegmentFile segment = segments.get(index);
      boolean isFinal = index == segments.size() - 1;
      if (segment.segmentId() != expectedSegmentId) {
        throw new WalCorruptionException("M08W1 segment ids contain a gap");
      }
      RecoveryCursor cursor =
          recoverSegment(
              segment, isFinal, expectedWalSequence, minimumWalExclusive, recoveryUsage, records);
      if (index == 0
          && snapshot.isPresent()
          && cursor.firstWalSequence() > Math.incrementExact(minimumWalExclusive)) {
        throw new WalCorruptionException("M08W1 suffix begins after the M09S1 recovery anchor");
      }
      expectedWalSequence = cursor.nextWalSequence();
      if (!isFinal && cursor.size() == M08WalFormat.HEADER_BYTES) {
        throw new WalCorruptionException("a non-final M08W1 segment contains no record");
      }
      expectedSegmentId = Math.incrementExact(expectedSegmentId);
    }
    if (snapshot.isPresent() && expectedWalSequence <= minimumWalExclusive) {
      throw new WalCorruptionException("M08W1 does not reach the M09S1 recovery anchor");
    }

    SegmentFile last = segments.getLast();
    activeSegmentId = last.segmentId();
    activeChannel =
        FileChannel.open(last.path(), StandardOpenOption.READ, StandardOpenOption.WRITE);
    // A previous recovery may have truncated an incomplete tail and then lost the process before
    // force completed. The next open cannot infer that history from an aligned EOF, so every
    // successful recovery conservatively forces the active segment before accepting submissions.
    faultInjector.hit(FaultPoint.BEFORE_RECOVERY_ACTIVE_FORCE);
    activeChannel.force(true);
    faultInjector.hit(FaultPoint.AFTER_RECOVERY_ACTIVE_FORCE);
    // A segment rename can become visible to this process before its directory entry is durable.
    // Publish that recovered namespace state only after the active file contents are forced.
    faultInjector.hit(FaultPoint.BEFORE_RECOVERY_DIRECTORY_FORCE);
    forceDirectory();
    faultInjector.hit(FaultPoint.AFTER_RECOVERY_DIRECTORY_FORCE);
    activeSize = activeChannel.size();
    nextWalSequence = expectedWalSequence;
    return List.copyOf(records);
  }

  private RecoveryCursor recoverSegment(
      SegmentFile segment,
      boolean isFinal,
      long expectedFirstWalSequence,
      long minimumWalExclusive,
      RecoveryUsage recoveryUsage,
      List<RecoveredRecord> records)
      throws IOException {
    try (FileChannel channel =
        FileChannel.open(segment.path(), StandardOpenOption.READ, StandardOpenOption.WRITE)) {
      long size = channel.size();
      if (size > config.maxSegmentBytes()) {
        throw new WalCorruptionException("M08W1 segment exceeds the configured size bound");
      }
      if (size < M08WalFormat.HEADER_BYTES) {
        throw new WalCorruptionException("M08W1 segment header is incomplete");
      }
      byte[] headerBytes = readExactly(channel, 0, M08WalFormat.HEADER_BYTES);
      M08WalFormat.SegmentHeader header = M08WalFormat.decodeHeader(headerBytes);
      if (header.shardId() != config.shardId()
          || header.segmentId() != segment.segmentId()
          || (expectedFirstWalSequence != 0
              && header.firstWalSequence() != expectedFirstWalSequence)) {
        throw new WalCorruptionException("M08W1 segment header identity is inconsistent");
      }

      long offset = M08WalFormat.HEADER_BYTES;
      long expectedWalSequence =
          expectedFirstWalSequence == 0 ? header.firstWalSequence() : expectedFirstWalSequence;
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
        if (minimumWalExclusive > 0 && expectedWalSequence > minimumWalExclusive) {
          faultInjector.hit(FaultPoint.BEFORE_SNAPSHOT_SUFFIX_READ);
        }
        if (expectedWalSequence > minimumWalExclusive) {
          recoveryUsage.requireAccepts(config.recoveryBudget(), recordLength, expectedWalSequence);
        }
        byte[] encoded = readExactly(channel, offset, recordLength);
        M08WalFormat.DecodedRecord decoded = M08WalFormat.decodeRecord(encoded);
        if (decoded.walSequence() != expectedWalSequence) {
          throw new WalCorruptionException("M08W1 WAL sequence is not contiguous");
        }
        if (minimumWalExclusive > 0 && decoded.walSequence() <= minimumWalExclusive) {
          if (decoded.applicationSequence() != decoded.walSequence()) {
            throw new WalCorruptionException("M08W1 application sequence is not contiguous");
          }
          requireCanonicalEnvelope(decoded.envelopeBytes());
        }
        WalPosition position =
            new WalPosition(
                segment.segmentId(),
                decoded.walSequence(),
                decoded.applicationSequence(),
                offset,
                recordLength);
        if (decoded.walSequence() > minimumWalExclusive) {
          records.add(new RecoveredRecord(position, decoded.envelopeBytes()));
          recoveryUsage.include(recordLength);
        }
        expectedWalSequence = Math.incrementExact(expectedWalSequence);
        offset += recordLength;
      }
      return new RecoveryCursor(header.firstWalSequence(), expectedWalSequence, size);
    }
  }

  private long repairFinalTail(FileChannel channel, boolean isFinal, long goodOffset)
      throws IOException {
    if (!isFinal) {
      throw new WalCorruptionException("only the final M08W1 segment may have an incomplete tail");
    }
    faultInjector.hit(FaultPoint.BEFORE_TAIL_TRUNCATE);
    channel.truncate(goodOffset);
    faultInjector.hit(FaultPoint.AFTER_TAIL_TRUNCATE);
    faultInjector.hit(FaultPoint.BEFORE_TAIL_TRUNCATE_FORCE);
    channel.force(true);
    faultInjector.hit(FaultPoint.AFTER_TAIL_TRUNCATE_FORCE);
    return goodOffset;
  }

  private void rollover() throws IOException {
    long newSegment = Math.incrementExact(activeSegmentId);
    createSegment(newSegment, nextWalSequence);
  }

  private long pruneClosedSegmentsThrough(long walSequence) throws IOException {
    if (walSequence <= 0) {
      return 0;
    }
    long prunedThrough = 0;
    boolean removed = false;
    boolean firstSegmentDeleted = false;
    for (SegmentFile segment : discoverFinalSegments()) {
      if (segment.segmentId() == activeSegmentId) {
        continue;
      }
      long lastWal = lastWalSequence(segment);
      if (lastWal <= walSequence) {
        faultInjector.hit(FaultPoint.BEFORE_RETENTION_DELETE);
        storageOperations.delete(segment.path());
        if (!firstSegmentDeleted) {
          firstSegmentDeleted = true;
          faultInjector.hit(FaultPoint.AFTER_FIRST_RETENTION_SEGMENT_DELETE);
        }
        removed = true;
        prunedThrough = Math.max(prunedThrough, lastWal);
      }
    }
    if (removed) {
      faultInjector.hit(FaultPoint.BEFORE_RETENTION_DIRECTORY_FORCE);
      forceDirectoryWithHooks();
      faultInjector.hit(FaultPoint.AFTER_RETENTION_DIRECTORY_FORCE_BEFORE_RETURN);
    }
    return prunedThrough;
  }

  private long lastWalSequence(SegmentFile segment) throws IOException {
    try (FileChannel channel = FileChannel.open(segment.path(), StandardOpenOption.READ)) {
      long size = channel.size();
      if (size <= M08WalFormat.HEADER_BYTES) {
        throw new WalCorruptionException("closed M08W1 segment contains no record");
      }
      M08WalFormat.SegmentHeader header =
          M08WalFormat.decodeHeader(readExactly(channel, 0, M08WalFormat.HEADER_BYTES));
      if (header.shardId() != config.shardId() || header.segmentId() != segment.segmentId()) {
        throw new WalCorruptionException("closed M08W1 segment header identity is inconsistent");
      }
      long offset = M08WalFormat.HEADER_BYTES;
      long expected = header.firstWalSequence();
      while (offset < size) {
        if (size - offset < Integer.BYTES) {
          throw new WalCorruptionException("closed M08W1 segment has an incomplete length");
        }
        int recordLength = ByteBuffer.wrap(readExactly(channel, offset, Integer.BYTES)).getInt();
        if (recordLength < M08WalFormat.MIN_RECORD_BYTES
            || recordLength > config.maxRecordBytes()
            || size - offset < recordLength) {
          throw new WalCorruptionException("closed M08W1 segment has an invalid record boundary");
        }
        M08WalFormat.DecodedRecord record =
            M08WalFormat.decodeRecord(readExactly(channel, offset, recordLength));
        if (record.walSequence() != expected) {
          throw new WalCorruptionException("closed M08W1 WAL sequence is not contiguous");
        }
        if (record.applicationSequence() != record.walSequence()) {
          throw new WalCorruptionException("closed M08W1 application sequence is not contiguous");
        }
        requireCanonicalEnvelope(record.envelopeBytes());
        expected = Math.incrementExact(expected);
        offset += recordLength;
      }
      return expected - 1;
    }
  }

  private void requireCanonicalEnvelope(byte[] envelopeBytes) throws WalCorruptionException {
    try {
      new M08EnvelopeCodec().decodeCanonical(envelopeBytes, config.shardId());
    } catch (StructuralRejectionException failure) {
      throw new WalCorruptionException("M08W1 contains a non-canonical M08C1 envelope", failure);
    }
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
      faultInjector.hit(FaultPoint.BEFORE_SEGMENT_HEADER_WRITE);
      writeFully(channel, ByteBuffer.wrap(header));
      faultInjector.hit(FaultPoint.AFTER_SEGMENT_HEADER_WRITE);
      faultInjector.hit(FaultPoint.BEFORE_SEGMENT_HEADER_FORCE);
      storageOperations.forceFile(temporary, channel);
      faultInjector.hit(FaultPoint.AFTER_SEGMENT_HEADER_FORCE);
    }
    try {
      faultInjector.hit(FaultPoint.BEFORE_SEGMENT_ATOMIC_RENAME);
      storageOperations.atomicMove(temporary, target);
    } catch (AtomicMoveNotSupportedException failure) {
      throw new IOException("M08W1 requires an atomic segment rename", failure);
    }
    faultInjector.hit(FaultPoint.AFTER_SEGMENT_ATOMIC_RENAME);
    forceDirectoryWithHooks();

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
          storageOperations.delete(path);
          removed = true;
        }
      }
    }
    if (removed) {
      forceDirectoryWithHooks();
    }
  }

  private void forceDirectoryWithHooks() throws IOException {
    faultInjector.hit(FaultPoint.BEFORE_DIRECTORY_FORCE);
    forceDirectory();
    faultInjector.hit(FaultPoint.AFTER_DIRECTORY_FORCE);
  }

  private void forceDirectory() throws IOException {
    storageOperations.forceDirectory(config.directory());
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

  private record RecoveryCursor(long firstWalSequence, long nextWalSequence, long size) {}

  private static final class RecoveryUsage {
    private long records;
    private long bytes;

    private void requireAccepts(RecoveryBudget budget, int nextRecordBytes, long walSequence)
        throws RecoveryException {
      if (!budget.accepts(records, bytes, nextRecordBytes)) {
        throw new RecoveryException(
            "M09 recovery suffix exceeds the configured records-and-bytes budget before WAL "
                + walSequence);
      }
    }

    private void include(int recordBytes) {
      records = Math.incrementExact(records);
      bytes = Math.addExact(bytes, recordBytes);
    }
  }
}
