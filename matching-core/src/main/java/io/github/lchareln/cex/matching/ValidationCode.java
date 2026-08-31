package io.github.lchareln.cex.matching;

/** Stable business validation codes, appended by course unit in decision-priority order. */
public enum ValidationCode {
  UNKNOWN_INSTRUMENT("instrumentId"),
  INVALID_ORDER_ID("orderId"),
  INVALID_SIDE("side"),
  INVALID_PRICE("priceTicks"),
  INVALID_QUANTITY("quantityLots"),
  INVALID_EXECUTION_POLICY("executionPolicy"),
  INVALID_STP_GROUP_ID("participantGroupId"),
  INVALID_STP_POLICY("stpPolicy"),
  INVALID_STP_INSTRUCTION("stpInstruction");

  private final String field;

  ValidationCode(String field) {
    this.field = field;
  }

  public String field() {
    return field;
  }
}
