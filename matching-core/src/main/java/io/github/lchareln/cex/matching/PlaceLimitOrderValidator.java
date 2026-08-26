package io.github.lchareln.cex.matching;

import java.math.BigInteger;

/** Deterministic M00 validation and normalization for one schema-valid input. */
public final class PlaceLimitOrderValidator {
  public static final String INSTRUMENT_ID = "BTC-USDT";

  private static final BigInteger MINIMUM = BigInteger.ONE;
  private static final BigInteger MAXIMUM = BigInteger.valueOf(Long.MAX_VALUE);

  public ValidationResult validate(PlaceLimitOrderInput input) {
    if (!INSTRUMENT_ID.equals(input.instrumentId())) {
      return new ValidationResult.Invalid(ValidationCode.UNKNOWN_INSTRUMENT);
    }
    if (!isPositiveLong(input.orderId())) {
      return new ValidationResult.Invalid(ValidationCode.INVALID_ORDER_ID);
    }
    if (!isSide(input.side())) {
      return new ValidationResult.Invalid(ValidationCode.INVALID_SIDE);
    }
    if (!isPositiveLong(input.priceTicks())) {
      return new ValidationResult.Invalid(ValidationCode.INVALID_PRICE);
    }
    if (!isPositiveLong(input.quantityLots())) {
      return new ValidationResult.Invalid(ValidationCode.INVALID_QUANTITY);
    }
    return new ValidationResult.Valid();
  }

  public PlaceLimitOrder normalize(PlaceLimitOrderInput input) {
    ValidationResult result = validate(input);
    if (result instanceof ValidationResult.Invalid invalid) {
      throw new IllegalArgumentException(
          "cannot normalize invalid " + invalid.code() + " at " + invalid.field());
    }
    return new PlaceLimitOrder(
        input.instrumentId(),
        new OrderId(input.orderId().longValueExact()),
        Side.valueOf(input.side()),
        new PriceTicks(input.priceTicks().longValueExact()),
        new QuantityLots(input.quantityLots().longValueExact()));
  }

  private static boolean isPositiveLong(BigInteger value) {
    return value.compareTo(MINIMUM) >= 0 && value.compareTo(MAXIMUM) <= 0;
  }

  private static boolean isSide(String value) {
    return "BUY".equals(value) || "SELL".equals(value);
  }
}
