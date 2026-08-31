package io.github.lchareln.cex.matching;

/** Taker-side disposition applied when a positive participant group meets itself. */
public enum SelfTradePreventionPolicy {
  NONE,
  CANCEL_TAKER,
  CANCEL_MAKER,
  CANCEL_BOTH
}
