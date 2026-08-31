package io.github.lchareln.cex.matching.local;

/** Stable pre-journal failures for malformed or unsupported M08C1 ingress. */
public enum StructuralRejectionCode {
  MALFORMED_ENVELOPE,
  NON_CANONICAL_ENVELOPE,
  ENVELOPE_SIZE_LIMIT,
  COMMAND_SIZE_LIMIT,
  INVALID_ENVELOPE_IDENTITY,
  WRONG_SHARD,
  PAYLOAD_HASH_MISMATCH,
  UNSUPPORTED_COMMAND
}
