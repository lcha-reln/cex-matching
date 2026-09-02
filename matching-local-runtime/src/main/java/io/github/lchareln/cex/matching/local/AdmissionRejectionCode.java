package io.github.lchareln.cex.matching.local;

/** Exhaustive reasons why a local offer was not admitted. */
public enum AdmissionRejectionCode {
  /** The caller did not supply an envelope reference. */
  INVALID_ENVELOPE_REFERENCE,

  /** The byte array cannot be a bounded M08C1 canonical envelope. */
  INVALID_ENVELOPE_SIZE,

  /** The bounded queue was full; no runtime, WAL, apply, or identity work was attempted. */
  OVERLOADED_BEFORE_WAL,

  /** Deliberate quiesce or close has already stopped admission. */
  NOT_ACCEPTING,

  /** An earlier runtime or worker failure permanently stopped admission. */
  SERVICE_FAILED_CLOSED
}
