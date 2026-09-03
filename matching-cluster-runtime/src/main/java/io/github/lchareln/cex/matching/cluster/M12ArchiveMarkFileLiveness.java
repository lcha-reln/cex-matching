package io.github.lchareln.cex.matching.cluster;

import io.aeron.archive.ArchiveMarkFile;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import org.agrona.concurrent.SystemEpochClock;

/** Narrow read-only observation boundary for an Aeron Archive mark-file liveness timestamp. */
public final class M12ArchiveMarkFileLiveness {
  public static final String AERON_VERSION = "1.52.2";
  public static final String MARK_FILE_NAME = ArchiveMarkFile.FILENAME;
  public static final long LIVENESS_TIMEOUT_MILLIS = 10_000;
  public static final String PREDICATE = "ARCHIVE_MARK_FILE_ACTIVITY_AGE_GT_LIVENESS_TIMEOUT";

  private M12ArchiveMarkFileLiveness() {}

  public static Reader open(Path archiveDirectory) {
    Path directory =
        Objects.requireNonNull(archiveDirectory, "archiveDirectory").toAbsolutePath().normalize();
    Path markFile = directory.resolve(MARK_FILE_NAME).normalize();
    if (!Files.isRegularFile(markFile, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalStateException(
          "forced-stopped member has no regular Aeron Archive mark file: " + markFile);
    }
    String runtimeVersion = ArchiveMarkFile.class.getPackage().getImplementationVersion();
    if (!AERON_VERSION.equals(runtimeVersion)) {
      throw new IllegalStateException(
          "M12 Archive mark-file liveness contract requires Aeron "
              + AERON_VERSION
              + " but observed "
              + runtimeVersion);
    }
    try {
      ArchiveMarkFile archiveMark =
          new ArchiveMarkFile(
              directory.toFile(), MARK_FILE_NAME, SystemEpochClock.INSTANCE, 0, ignored -> {});
      return new Reader(markFile, runtimeVersion, archiveMark);
    } catch (RuntimeException failure) {
      throw new IllegalStateException("cannot open Aeron Archive mark file: " + markFile, failure);
    }
  }

  /** Mirrors Agrona MarkFile.isActive: inactive is the strict complement of age <= timeout. */
  public static boolean isInactive(
      long observedAtMillis, long lastActivityTimestampMillis, long livenessTimeoutMillis) {
    if (observedAtMillis < 0 || lastActivityTimestampMillis <= 0 || livenessTimeoutMillis <= 0) {
      return false;
    }
    return observedAtMillis >= lastActivityTimestampMillis
        && observedAtMillis - lastActivityTimestampMillis > livenessTimeoutMillis;
  }

  public static final class Reader implements AutoCloseable {
    private final Path markFile;
    private final String aeronVersion;
    private final ArchiveMarkFile archiveMark;
    private boolean closed;

    private Reader(Path markFile, String aeronVersion, ArchiveMarkFile archiveMark) {
      this.markFile = markFile;
      this.aeronVersion = aeronVersion;
      this.archiveMark = archiveMark;
    }

    public Observation observe() {
      if (closed) {
        throw new IllegalStateException("Aeron Archive mark-file reader is closed");
      }
      long lastActivityTimestampMillis = archiveMark.activityTimestampVolatile();
      long observedAtMillis = SystemEpochClock.INSTANCE.time();
      return new Observation(markFile, lastActivityTimestampMillis, observedAtMillis, aeronVersion);
    }

    @Override
    public void close() {
      if (!closed) {
        closed = true;
        archiveMark.close();
      }
    }
  }

  public record Observation(
      Path markFile, long lastActivityTimestampMillis, long observedAtMillis, String aeronVersion) {
    public Observation {
      markFile = Objects.requireNonNull(markFile, "markFile").toAbsolutePath().normalize();
      if (lastActivityTimestampMillis <= 0
          || observedAtMillis < 0
          || !AERON_VERSION.equals(aeronVersion)) {
        throw new IllegalArgumentException("invalid Aeron Archive mark-file observation");
      }
    }

    public long ageMillis() {
      return observedAtMillis >= lastActivityTimestampMillis
          ? observedAtMillis - lastActivityTimestampMillis
          : -1;
    }

    public boolean inactive() {
      return isInactive(observedAtMillis, lastActivityTimestampMillis, LIVENESS_TIMEOUT_MILLIS);
    }
  }
}
