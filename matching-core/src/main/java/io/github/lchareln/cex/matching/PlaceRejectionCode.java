package io.github.lchareln.cex.matching;

/** Lifecycle rejection reasons for a business-valid place command. */
public enum PlaceRejectionCode {
  DUPLICATE_ORDER_ID,
  FOK_NOT_FILLABLE,
  POST_ONLY_WOULD_TAKE
}
