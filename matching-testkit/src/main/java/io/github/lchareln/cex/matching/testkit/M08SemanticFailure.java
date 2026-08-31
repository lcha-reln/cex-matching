package io.github.lchareln.cex.matching.testkit;

/** A deterministic candidate/semantic mismatch; infrastructure and judge faults use other types. */
final class M08SemanticFailure extends RuntimeException {
  private static final long serialVersionUID = 1L;

  M08SemanticFailure(String message) {
    super(message);
  }
}
