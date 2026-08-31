package io.github.lchareln.cex.matching.local;

import java.io.IOException;

/** Complete M08W1 bytes violated their checksum, ordering, or segment contract. */
public final class WalCorruptionException extends IOException {
  private static final long serialVersionUID = 1L;

  public WalCorruptionException(String message) {
    super(message);
  }

  public WalCorruptionException(String message, Throwable cause) {
    super(message, cause);
  }
}
