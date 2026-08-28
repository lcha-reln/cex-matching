package io.github.lchareln.cex.matching;

/** Stable lifecycle results for a business-valid cancellation that cannot be applied. */
public enum CancelRejectionCode {
  ORDER_NOT_FOUND,
  ORDER_ALREADY_FILLED,
  ORDER_ALREADY_CANCELED
}
