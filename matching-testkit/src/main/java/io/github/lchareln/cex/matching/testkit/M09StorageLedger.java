package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.local.CheckpointResult;
import io.github.lchareln.cex.matching.local.SubmissionResult;
import java.util.ArrayList;
import java.util.List;

/**
 * No-I/O storage ledger for the new M09 axis.
 *
 * <p>It deliberately knows only the frozen M08W1 frame sizes and public runtime observations. It
 * never imports the production WAL parser, snapshot codec, or matching state image.
 */
final class M09StorageLedger {
  static final int WAL_HEADER_BYTES = 36;
  static final int WAL_RECORD_OVERHEAD = 32;

  private final long maxSegmentBytes;
  private final long maxSuffixRecords;
  private final long maxSuffixBytes;
  private final List<Segment> segments = new ArrayList<>();
  private final List<Snapshot> retainedSnapshots = new ArrayList<>();
  private long nextWalSequence = 1;
  private long nextApplicationSequence = 1;
  private long suffixRecords;
  private long suffixBytes;
  private long snapshotGeneration;
  private long publishedCut;
  private long retiredThrough;

  M09StorageLedger(long maxSegmentBytes, long maxSuffixRecords, long maxSuffixBytes) {
    this.maxSegmentBytes = maxSegmentBytes;
    this.maxSuffixRecords = maxSuffixRecords;
    this.maxSuffixBytes = maxSuffixBytes;
    segments.add(new Segment(1, 1, 0, WAL_HEADER_BYTES, true));
  }

  boolean accepts(int envelopeBytes) {
    long length = Math.addExact(WAL_RECORD_OVERHEAD, envelopeBytes);
    return suffixRecords < maxSuffixRecords && suffixBytes + length <= maxSuffixBytes;
  }

  void observeNewSubmit(byte[] envelope, boolean predictedAccepts, SubmissionResult result) {
    if (result instanceof SubmissionResult.NewDurablyApplied applied) {
      require(predictedAccepts, "M09 runtime appended after independent budget rejection");
      observeAppend(envelope, applied.position());
      return;
    }
    if (result instanceof SubmissionResult.CheckpointRequired required) {
      require(!predictedAccepts, "M09 runtime requested checkpoint after ledger predicted accept");
      require(
          required.suffixRecords() == suffixRecords
              && required.suffixBytes() == suffixBytes
              && required.maxSuffixRecords() == maxSuffixRecords
              && required.maxSuffixBytes() == maxSuffixBytes,
          "M09 CHECKPOINT_REQUIRED counters disagree with independent ledger");
      return;
    }
    throw new M09SemanticFailure(
        "M09 new submission disagreed with independent budget prediction: " + result);
  }

  void observePreflightNoAppend(SubmissionResult result) {
    require(
        result instanceof SubmissionResult.DuplicateReplayed
            || result instanceof SubmissionResult.PreflightRejected,
        "M09 identity preflight unexpectedly reached WAL: " + result);
  }

  void observeForcedDurable(
      byte[] envelope, boolean predictedAccepts, SubmissionResult.DurabilityUnknown unknown) {
    require(predictedAccepts, "M09 forced append crossed independent recovery budget");
    require(
        "APPLY_OR_ACK".equals(unknown.stage()),
        "forced M09 generated boundary did not reach post-force apply/ACK stage");
    observeAppend(
        envelope,
        unknown
            .attemptedPosition()
            .orElseThrow(
                () -> new M09SemanticFailure("forced durable boundary omitted WAL position")));
  }

  void observeCheckpoint(CheckpointResult result) {
    require(
        result.anchor().generation() == snapshotGeneration + 1,
        "M09 snapshot generation is not contiguous");
    require(
        result.anchor().lastWalSequence() == nextWalSequence - 1
            && result.anchor().lastApplicationSequence() == nextApplicationSequence - 1,
        "M09 snapshot cut is not the current command boundary");
    snapshotGeneration = result.anchor().generation();
    publishedCut = result.anchor().lastWalSequence();
    if (active().bytes() > WAL_HEADER_BYTES) {
      Segment previous = active();
      segments.set(
          segments.size() - 1,
          new Segment(
              previous.id(), previous.firstWal(), previous.lastWal(), previous.bytes(), false));
      segments.add(
          new Segment(
              previous.id() + 1, nextWalSequence, nextWalSequence - 1, WAL_HEADER_BYTES, true));
    }
    retainedSnapshots.add(new Snapshot(snapshotGeneration, publishedCut));
    if (retainedSnapshots.size() > 2) {
      retainedSnapshots.removeFirst();
    }
    long protectedCut = retainedSnapshots.size() == 2 ? retainedSnapshots.getFirst().cut() : 0;
    List<Segment> eligible =
        segments.stream()
            .filter(segment -> !segment.active() && segment.lastWal() <= protectedCut)
            .toList();
    long expectedPruned = eligible.stream().mapToLong(Segment::lastWal).max().orElse(0L);
    require(
        result.prunedThroughWalSequence() == expectedPruned,
        "M09 retirement result disagrees with exact whole-segment eligibility");
    segments.removeAll(eligible);
    retiredThrough = Math.max(retiredThrough, expectedPruned);
    suffixRecords = 0;
    suffixBytes = 0;
  }

  void verifyRestart(long observedNextWalSequence) {
    require(
        observedNextWalSequence == nextWalSequence,
        "M09 restart sequence disagrees with independent ledger");
  }

  void verifyInventory(M09FileInventory.Inventory inventory) {
    require(inventory.unknown().isEmpty(), "M09 runtime inventory contains an unknown file");
    require(
        inventory.directoryLocks().size() == 1
            && ".m08w1.lock".equals(inventory.directoryLocks().getFirst().name())
            && inventory.directoryLocks().getFirst().size() == 0,
        "M09 runtime directory-lock inventory disagrees with ledger");
    require(
        inventory.count(M09FileInventory.Kind.SNAPSHOT_TEMP) == 0,
        "successful M09 boundary retained a temp snapshot");
    List<String> expectedSnapshots =
        retainedSnapshots.stream()
            .map(
                snapshot ->
                    "snapshot-%020d-%020d.m09s1".formatted(snapshot.generation(), snapshot.cut()))
            .toList();
    List<String> actualSnapshots =
        inventory.snapshots().stream().map(M09FileInventory.Entry::name).toList();
    require(
        actualSnapshots.equals(expectedSnapshots),
        "M09 exact snapshot generation/cut inventory disagrees with ledger");
    for (M09FileInventory.Entry snapshot : inventory.snapshots()) {
      require(
          snapshot.size() > 0 && snapshot.size() <= 256L * 1024 * 1024,
          "M09 snapshot inventory contains an impossible file size");
    }
    List<String> expectedNames =
        segments.stream().map(segment -> "segment-%020d.m08w1".formatted(segment.id())).toList();
    List<String> actualNames =
        inventory.segments().stream().map(M09FileInventory.Entry::name).toList();
    require(actualNames.equals(expectedNames), "M09 WAL segment inventory disagrees with ledger");
    for (int index = 0; index < segments.size(); index++) {
      require(
          inventory.segments().get(index).size() == segments.get(index).bytes(),
          "M09 WAL segment size disagrees with ledger");
    }
  }

  long nextWalSequence() {
    return nextWalSequence;
  }

  long suffixRecords() {
    return suffixRecords;
  }

  long suffixBytes() {
    return suffixBytes;
  }

  long snapshotGeneration() {
    return snapshotGeneration;
  }

  long publishedCut() {
    return publishedCut;
  }

  long retiredThrough() {
    return retiredThrough;
  }

  List<Segment> segments() {
    return List.copyOf(segments);
  }

  private void rolloverIfNeeded(int recordLength) {
    Segment current = active();
    if (current.bytes() + recordLength <= maxSegmentBytes) {
      return;
    }
    segments.set(
        segments.size() - 1,
        new Segment(current.id(), current.firstWal(), current.lastWal(), current.bytes(), false));
    segments.add(
        new Segment(
            current.id() + 1, nextWalSequence, nextWalSequence - 1, WAL_HEADER_BYTES, true));
  }

  private void observeAppend(
      byte[] envelope, io.github.lchareln.cex.matching.local.WalPosition position) {
    int expectedLength = Math.addExact(WAL_RECORD_OVERHEAD, envelope.length);
    require(
        position.recordLength() == expectedLength,
        "M09 WAL record length disagrees with independent ledger");
    rolloverIfNeeded(expectedLength);
    Segment active = active();
    require(
        position.segmentId() == active.id()
            && position.walSequence() == nextWalSequence
            && position.applicationSequence() == nextApplicationSequence
            && position.offset() == active.bytes(),
        "M09 WAL position disagrees with independent ledger");
    segments.set(
        segments.size() - 1,
        new Segment(
            active.id(),
            active.firstWal(),
            nextWalSequence,
            active.bytes() + expectedLength,
            true));
    nextWalSequence++;
    nextApplicationSequence++;
    suffixRecords++;
    suffixBytes += expectedLength;
  }

  private Segment active() {
    return segments.getLast();
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new M09SemanticFailure(message);
    }
  }

  record Segment(long id, long firstWal, long lastWal, long bytes, boolean active) {}

  private record Snapshot(long generation, long cut) {}
}
