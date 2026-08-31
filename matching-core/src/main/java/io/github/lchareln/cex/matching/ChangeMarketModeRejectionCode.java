package io.github.lchareln.cex.matching;

/** Stable deterministic reasons why an operating-mode request was not applied. */
public enum ChangeMarketModeRejectionCode {
  APPLICATION_SEQUENCE_MISMATCH,
  EXPECTED_MODE_MISMATCH,
  NO_MODE_CHANGE,
  INVALID_TRANSITION
}
