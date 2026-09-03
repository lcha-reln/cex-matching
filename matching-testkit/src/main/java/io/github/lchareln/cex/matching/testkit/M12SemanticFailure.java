package io.github.lchareln.cex.matching.testkit;

/** A business-contract failure; infrastructure failures must never use this type. */
final class M12SemanticFailure extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final String fingerprint;

  M12SemanticFailure(String message) {
    this("M12_CONTRACT_FAILURE", message);
  }

  M12SemanticFailure(String fingerprint, String message) {
    super(message);
    if (fingerprint == null || !fingerprint.matches("[A-Z0-9_]+")) {
      throw new IllegalArgumentException("invalid M12 semantic fingerprint");
    }
    this.fingerprint = fingerprint;
  }

  String fingerprint() {
    return fingerprint;
  }
}
