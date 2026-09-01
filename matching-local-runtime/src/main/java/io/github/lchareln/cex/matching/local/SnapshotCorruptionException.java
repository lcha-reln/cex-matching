package io.github.lchareln.cex.matching.local;

import java.io.IOException;

/** A published M09S1 snapshot is unsupported, malformed, corrupt, or semantically inconsistent. */
public final class SnapshotCorruptionException extends IOException {
  private static final long serialVersionUID = 1L;

  public SnapshotCorruptionException(String message) {
    super(message);
  }

  public SnapshotCorruptionException(String message, Throwable cause) {
    super(message, cause);
  }
}
