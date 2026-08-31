package io.github.lchareln.cex.matching.local;

/** Checked decode failure that is rejected before WAL append or core apply. */
public final class StructuralRejectionException extends Exception {
  private static final long serialVersionUID = 1L;

  private final StructuralRejectionCode code;

  public StructuralRejectionException(StructuralRejectionCode code, String message) {
    super(message);
    this.code = code;
  }

  public StructuralRejectionException(
      StructuralRejectionCode code, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
  }

  public StructuralRejectionCode code() {
    return code;
  }
}
