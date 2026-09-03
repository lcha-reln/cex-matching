package io.github.lchareln.cex.matching.testkit;

/** A reproducible M11 contract failure attributable to the implementation. */
final class M11SemanticFailure extends RuntimeException {
  private static final long serialVersionUID = 1L;

  M11SemanticFailure(String message) {
    super(message);
  }
}
