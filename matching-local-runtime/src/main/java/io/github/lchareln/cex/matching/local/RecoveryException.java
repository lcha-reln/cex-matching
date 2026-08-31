package io.github.lchareln.cex.matching.local;

import java.io.IOException;

/** WAL bytes were complete, but canonical recovery or deterministic apply could not finish. */
public final class RecoveryException extends IOException {
  private static final long serialVersionUID = 1L;

  public RecoveryException(String message) {
    super(message);
  }

  public RecoveryException(String message, Throwable cause) {
    super(message, cause);
  }
}
