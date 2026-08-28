package io.github.lchareln.cex.matching;

/** Frozen M00 business validation codes in validation-priority order. */
public enum ValidationCode {
  UNKNOWN_INSTRUMENT("instrumentId"),
  INVALID_ORDER_ID("orderId"),
  INVALID_SIDE("side"),
  INVALID_PRICE("priceTicks"),
  INVALID_QUANTITY("quantityLots"),
  INVALID_EXECUTION_POLICY("executionPolicy");

  private final String field;

  ValidationCode(String field) {
    this.field = field;
  }

  public String field() {
    return field;
  }
}
