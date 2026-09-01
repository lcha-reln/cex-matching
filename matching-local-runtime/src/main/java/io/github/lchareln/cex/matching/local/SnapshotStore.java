package io.github.lchareln.cex.matching.local;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Immutable-generation M09S1 publication and discovery under the WAL directory lock. */
final class SnapshotStore {
  private static final Pattern FINAL_NAME = Pattern.compile("snapshot-(\\d{20})-(\\d{20})\\.m09s1");
  private static final Pattern TEMP_NAME =
      Pattern.compile("snapshot-(\\d{20})-(\\d{20})\\.m09s1\\.tmp");

  private final Path directory;
  private final long shardId;
  private final FaultInjector faultInjector;
  private final M09SnapshotCodec codec = new M09SnapshotCodec();
  private final List<PublishedSnapshot> published = new ArrayList<>();
  private Optional<M09SnapshotCodec.DecodedSnapshot> latest = Optional.empty();

  SnapshotStore(Path directory, long shardId, FaultInjector faultInjector) {
    this.directory = directory;
    this.shardId = shardId;
    this.faultInjector = faultInjector;
  }

  Optional<M09SnapshotCodec.DecodedSnapshot> discover() throws IOException {
    removeOrphanTemps();
    published.clear();
    try (DirectoryStream<Path> paths = Files.newDirectoryStream(directory)) {
      for (Path path : paths) {
        String name = path.getFileName().toString();
        Matcher matcher = FINAL_NAME.matcher(name);
        if (matcher.matches()) {
          published.add(
              new PublishedSnapshot(
                  parseLong(matcher.group(1), "snapshot generation"),
                  parseLong(matcher.group(2), "snapshot WAL anchor"),
                  path));
        } else if (name.startsWith("snapshot-") && !TEMP_NAME.matcher(name).matches()) {
          throw new SnapshotCorruptionException("unrecognized M09S1 snapshot filename: " + name);
        }
      }
    }
    published.sort(Comparator.comparingLong(PublishedSnapshot::generation));
    requireCanonicalGenerations();
    if (published.isEmpty()) {
      latest = Optional.empty();
      return latest;
    }
    PublishedSnapshot candidate = published.getLast();
    faultInjector.hit(FaultPoint.BEFORE_SNAPSHOT_READ);
    M09SnapshotCodec.DecodedSnapshot decoded = decode(candidate.path());
    if (decoded.anchor().generation() != candidate.generation()
        || decoded.anchor().lastWalSequence() != candidate.lastWalSequence()
        || decoded.anchor().shardId() != shardId) {
      throw new SnapshotCorruptionException("latest M09S1 filename and header disagree");
    }
    latest = Optional.of(decoded);
    return latest;
  }

  SnapshotAnchor publish(LocalRuntimeStateImage state) throws IOException {
    long generation =
        latest.map(snapshot -> Math.incrementExact(snapshot.anchor().generation())).orElse(1L);
    SnapshotAnchor anchor =
        new SnapshotAnchor(
            generation, shardId, state.lastWalSequence(), state.lastApplicationSequence());
    byte[] encoded = codec.encode(anchor, state);
    Path temporary = directory.resolve(tempName(anchor));
    Path target = directory.resolve(finalName(anchor));
    try (FileChannel channel =
        FileChannel.open(
            temporary,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE)) {
      faultInjector.hit(FaultPoint.BEFORE_SNAPSHOT_TEMP_WRITE);
      int partialLength = Math.max(1, encoded.length / 2);
      writeFully(channel, ByteBuffer.wrap(encoded, 0, partialLength));
      faultInjector.hit(FaultPoint.AFTER_PARTIAL_SNAPSHOT_TEMP_WRITE);
      writeFully(channel, ByteBuffer.wrap(encoded, partialLength, encoded.length - partialLength));
      faultInjector.hit(FaultPoint.BEFORE_SNAPSHOT_FILE_FORCE);
      channel.force(true);
    }
    faultInjector.hit(FaultPoint.BEFORE_SNAPSHOT_READ);
    M09SnapshotCodec.DecodedSnapshot readBack = decode(temporary);
    if (!readBack.anchor().equals(anchor) || !readBack.state().equals(state)) {
      throw new SnapshotCorruptionException("M09S1 forced read-back disagrees with state image");
    }
    try {
      faultInjector.hit(FaultPoint.BEFORE_SNAPSHOT_ATOMIC_RENAME);
      Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException failure) {
      throw new IOException("M09S1 requires atomic snapshot publication", failure);
    }
    faultInjector.hit(FaultPoint.BEFORE_SNAPSHOT_DIRECTORY_FORCE);
    forceDirectory();
    faultInjector.hit(FaultPoint.AFTER_SNAPSHOT_DIRECTORY_FORCE_BEFORE_RETENTION);
    PublishedSnapshot publication =
        new PublishedSnapshot(generation, state.lastWalSequence(), target);
    published.add(publication);
    published.sort(Comparator.comparingLong(PublishedSnapshot::generation));
    latest = Optional.of(readBack);
    return anchor;
  }

  /** Retains the latest and previous immutable generations; returns the older protected anchor. */
  long retainLatestTwo() throws IOException {
    if (published.size() > 2) {
      List<PublishedSnapshot> obsolete = List.copyOf(published.subList(0, published.size() - 2));
      faultInjector.hit(FaultPoint.BEFORE_RETENTION_DELETE);
      for (PublishedSnapshot snapshot : obsolete) {
        Files.delete(snapshot.path());
      }
      faultInjector.hit(FaultPoint.BEFORE_RETENTION_DIRECTORY_FORCE);
      forceDirectory();
      published.removeAll(obsolete);
    }
    if (published.size() < 2) {
      return 0;
    }
    PublishedSnapshot older = published.get(published.size() - 2);
    M09SnapshotCodec.DecodedSnapshot decoded = decode(older.path());
    if (decoded.anchor().generation() != older.generation()
        || decoded.anchor().lastWalSequence() != older.lastWalSequence()
        || decoded.anchor().shardId() != shardId) {
      throw new SnapshotCorruptionException("protected M09S1 generation is inconsistent");
    }
    return older.lastWalSequence();
  }

  private M09SnapshotCodec.DecodedSnapshot decode(Path path) throws IOException {
    long size = Files.size(path);
    if (size <= 0 || size > M09SnapshotCodec.MAX_SNAPSHOT_BYTES || size > Integer.MAX_VALUE) {
      throw new SnapshotCorruptionException("M09S1 file size is outside the format bound");
    }
    return codec.decodeCanonical(Files.readAllBytes(path));
  }

  private void requireCanonicalGenerations() throws SnapshotCorruptionException {
    if (published.size() < 2) {
      return;
    }
    long previous = published.getFirst().generation();
    for (int index = 1; index < published.size(); index++) {
      long current = published.get(index).generation();
      if (current != Math.incrementExact(previous)) {
        throw new SnapshotCorruptionException("M09S1 published generations contain a gap");
      }
      previous = current;
    }
  }

  private void removeOrphanTemps() throws IOException {
    boolean removed = false;
    try (DirectoryStream<Path> paths = Files.newDirectoryStream(directory)) {
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
    try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
      channel.force(true);
    }
  }

  private static void writeFully(FileChannel channel, ByteBuffer source) throws IOException {
    while (source.hasRemaining()) {
      if (channel.write(source) == 0) {
        throw new IOException("M09S1 write made no progress");
      }
    }
  }

  private static long parseLong(String value, String field) throws SnapshotCorruptionException {
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException failure) {
      throw new SnapshotCorruptionException(field + " cannot be represented", failure);
    }
  }

  private static String finalName(SnapshotAnchor anchor) {
    return "snapshot-%020d-%020d.m09s1".formatted(anchor.generation(), anchor.lastWalSequence());
  }

  private static String tempName(SnapshotAnchor anchor) {
    return finalName(anchor) + ".tmp";
  }

  private record PublishedSnapshot(long generation, long lastWalSequence, Path path) {}
}
