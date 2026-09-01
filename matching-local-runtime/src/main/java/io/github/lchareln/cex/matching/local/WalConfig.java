package io.github.lchareln.cex.matching.local;

import java.nio.file.Path;
import java.util.Objects;

/** Local M08W1 pre-provisioned directory and bounded-segment configuration. */
public record WalConfig(
    Path directory,
    long shardId,
    long maxSegmentBytes,
    int maxRecordBytes,
    RecoveryBudget recoveryBudget) {
  public static final long DEFAULT_MAX_SEGMENT_BYTES = 8L * 1024 * 1024;
  public static final int DEFAULT_MAX_RECORD_BYTES = 2 * 1024 * 1024;

  public WalConfig {
    directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
    recoveryBudget = Objects.requireNonNull(recoveryBudget, "recoveryBudget");
    if (shardId <= 0) {
      throw new IllegalArgumentException("shardId must be positive");
    }
    if (maxRecordBytes < M08EnvelopeCodec.MAX_ENVELOPE_BYTES + M08WalFormat.RECORD_OVERHEAD) {
      throw new IllegalArgumentException("maxRecordBytes cannot encode the largest M08C1 envelope");
    }
    if (maxSegmentBytes < M08WalFormat.HEADER_BYTES + (long) maxRecordBytes) {
      throw new IllegalArgumentException("one maximum-sized record must fit in one segment");
    }
  }

  public WalConfig(Path directory, long shardId, long maxSegmentBytes, int maxRecordBytes) {
    this(directory, shardId, maxSegmentBytes, maxRecordBytes, RecoveryBudget.LEGACY_M08_UNBOUNDED);
  }

  public static WalConfig defaults(Path directory, long shardId) {
    return new WalConfig(
        directory,
        shardId,
        DEFAULT_MAX_SEGMENT_BYTES,
        DEFAULT_MAX_RECORD_BYTES,
        RecoveryBudget.LEGACY_M08_UNBOUNDED);
  }

  /** M09 local snapshot defaults with the frozen records-and-bytes recovery bound. */
  public static WalConfig snapshotDefaults(Path directory, long shardId) {
    return new WalConfig(
        directory,
        shardId,
        DEFAULT_MAX_SEGMENT_BYTES,
        DEFAULT_MAX_RECORD_BYTES,
        RecoveryBudget.M09_DEFAULT);
  }
}
