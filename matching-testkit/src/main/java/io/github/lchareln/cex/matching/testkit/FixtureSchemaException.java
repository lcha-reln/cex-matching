package io.github.lchareln.cex.matching.testkit;

/** A system-level failure at the frozen JSON fixture boundary. */
public final class FixtureSchemaException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public FixtureSchemaException(String message) {
    super(message);
  }

  public FixtureSchemaException(String message, Throwable cause) {
    super(message, cause);
  }
}
