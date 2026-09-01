package io.github.lchareln.cex.matching.testkit;

/** An observed violation of the frozen M09 semantic contract. */
public final class M09SemanticFailure extends RuntimeException {
  private static final long serialVersionUID = 1L;

  M09SemanticFailure(String message) {
    super(message);
  }

  M09SemanticFailure(String message, Throwable cause) {
    super(message, cause);
  }
}
