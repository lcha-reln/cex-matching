package io.github.lchareln.cex.matching.local;

import java.io.IOException;
import java.util.Optional;

/** An append did not return normally, so the caller must treat durability as unknown. */
final class WalAppendException extends IOException {
  private static final long serialVersionUID = 1L;

  private final transient WalPosition attemptedPosition;

  WalAppendException(String message, WalPosition attemptedPosition, Throwable cause) {
    super(message, cause);
    this.attemptedPosition = attemptedPosition;
  }

  Optional<WalPosition> attemptedPosition() {
    return Optional.ofNullable(attemptedPosition);
  }
}
