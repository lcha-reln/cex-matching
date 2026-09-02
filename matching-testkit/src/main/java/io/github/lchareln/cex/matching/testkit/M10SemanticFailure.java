package io.github.lchareln.cex.matching.testkit;

/** A reproducible violation of the frozen M10 admission or qualification contract. */
final class M10SemanticFailure extends RuntimeException {
  private static final long serialVersionUID = 1L;

  M10SemanticFailure(String message) {
    super(message);
  }
}
