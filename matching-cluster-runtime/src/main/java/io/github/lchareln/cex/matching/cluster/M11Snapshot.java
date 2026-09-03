package io.github.lchareln.cex.matching.cluster;

import java.util.Objects;

/** Decoded canonical M11 application snapshot. */
public record M11Snapshot(int schemaVersion, M11RuntimeState state) {
  public M11Snapshot {
    if (schemaVersion < M11SnapshotCodec.MIN_READABLE_VERSION
        || schemaVersion > M11SnapshotCodec.CURRENT_VERSION) {
      throw new IllegalArgumentException("unsupported snapshot schema version");
    }
    Objects.requireNonNull(state, "state");
  }
}
