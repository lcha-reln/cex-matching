package io.github.lchareln.cex.matching.cluster;

/** A fail-closed rejection of malformed, unsupported, or non-canonical M11 bytes. */
public final class M11ProtocolException extends Exception {
  private static final long serialVersionUID = 1L;

  private final Code code;

  public M11ProtocolException(Code code, String message) {
    super(message);
    this.code = code;
  }

  public M11ProtocolException(Code code, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
  }

  public Code code() {
    return code;
  }

  public enum Code {
    INVALID_MAGIC,
    UNSUPPORTED_VERSION,
    LENGTH_LIMIT,
    TRUNCATED,
    CHECKSUM_MISMATCH,
    NON_CANONICAL,
    INVALID_VALUE
  }
}
