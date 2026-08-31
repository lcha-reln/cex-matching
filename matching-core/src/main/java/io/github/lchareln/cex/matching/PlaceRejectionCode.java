package io.github.lchareln.cex.matching;

/** Lifecycle rejection reasons for a business-valid place command. */
public enum PlaceRejectionCode {
  DUPLICATE_ORDER_ID,
  RULE_SET_MISMATCH,
  PRICE_OUTSIDE_ACTIVE_BAND,
  FOK_NOT_FILLABLE,
  POST_ONLY_WOULD_TAKE
}
