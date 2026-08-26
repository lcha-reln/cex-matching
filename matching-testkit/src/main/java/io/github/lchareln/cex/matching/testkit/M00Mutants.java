package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.PlaceLimitOrderInput;
import io.github.lchareln.cex.matching.PlaceLimitOrderValidator;
import io.github.lchareln.cex.matching.ValidationResult;
import java.math.BigInteger;
import java.util.function.Function;

/** Required semantic mutant registry for M00. Mutants never enter matching-core. */
public final class M00Mutants {
  public static final String QUANTITY_ZERO_ACCEPTED = "M00-QTY-ZERO-ACCEPTED";

  private M00Mutants() {}

  public static Function<PlaceLimitOrderInput, ValidationResult> quantityZeroAccepted() {
    PlaceLimitOrderValidator production = new PlaceLimitOrderValidator();
    return input -> {
      if (isEarlierFieldValid(input) && BigInteger.ZERO.equals(input.quantityLots())) {
        return new ValidationResult.Valid();
      }
      return production.validate(input);
    };
  }

  public static Function<PlaceLimitOrderInput, ValidationResult> throwingControl() {
    return input -> {
      throw new IllegalStateException("intentional harness control");
    };
  }

  private static boolean isEarlierFieldValid(PlaceLimitOrderInput input) {
    PlaceLimitOrderInput positiveQuantity =
        new PlaceLimitOrderInput(
            input.instrumentId(),
            input.orderId(),
            input.side(),
            input.priceTicks(),
            BigInteger.ONE);
    return new PlaceLimitOrderValidator().validate(positiveQuantity)
        instanceof ValidationResult.Valid;
  }
}
