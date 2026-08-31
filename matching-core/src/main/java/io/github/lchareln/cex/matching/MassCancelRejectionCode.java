package io.github.lchareln.cex.matching;

/** Stable deterministic reasons why an operator Mass Cancel was not applied. */
public enum MassCancelRejectionCode {
  APPLICATION_SEQUENCE_MISMATCH,
  EXPECTED_MODE_MISMATCH,
  MARKET_NOT_HALTED
}
