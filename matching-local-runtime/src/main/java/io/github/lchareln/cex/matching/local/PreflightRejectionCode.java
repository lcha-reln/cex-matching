package io.github.lchareln.cex.matching.local;

/** Stable pre-WAL identity and producer-stream rejection vocabulary. */
public enum PreflightRejectionCode {
  COMMAND_ID_PAYLOAD_CONFLICT,
  COMMAND_ID_SLOT_CONFLICT,
  SLOT_IDENTITY_CONFLICT,
  PRODUCER_EPOCH_FENCED,
  PRODUCER_EPOCH_MUST_START_AT_ONE,
  PRODUCER_SEQUENCE_GAP,
  PRODUCER_SEQUENCE_STALE,
  PRODUCER_SEQUENCE_EXHAUSTED
}
