package io.github.lchareln.cex.matching.local;

import java.io.IOException;

/** Another local runtime already owns the journal directory. */
public final class DirectoryLockException extends IOException {
  private static final long serialVersionUID = 1L;

  public DirectoryLockException(String message) {
    super(message);
  }
}
