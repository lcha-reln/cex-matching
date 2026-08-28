package io.github.lchareln.cex.matching;

import java.math.BigInteger;

/** Deterministic validation and normalization for one schema-valid M02 cancellation. */
public final class CancelOrderValidator {
  private static final BigInteger MINIMUM = BigInteger.ONE;
  private static final BigInteger MAXIMUM = BigInteger.valueOf(Long.MAX_VALUE);

  public ValidationResult validate(CancelOrderInput input) {
    if (!PlaceLimitOrderValidator.INSTRUMENT_ID.equals(input.instrumentId())) {
      return new ValidationResult.Invalid(ValidationCode.UNKNOWN_INSTRUMENT);
    }
    if (!isPositiveLong(input.orderId())) {
      return new ValidationResult.Invalid(ValidationCode.INVALID_ORDER_ID);
    }
    return new ValidationResult.Valid();
  }

  public CancelOrder normalize(CancelOrderInput input) {
    ValidationResult result = validate(input);
    if (result instanceof ValidationResult.Invalid invalid) {
      throw new IllegalArgumentException(
          "cannot normalize invalid " + invalid.code() + " at " + invalid.field());
    }
    return new CancelOrder(input.instrumentId(), new OrderId(input.orderId().longValueExact()));
  }

  private static boolean isPositiveLong(BigInteger value) {
    return value.compareTo(MINIMUM) >= 0 && value.compareTo(MAXIMUM) <= 0;
  }
}
