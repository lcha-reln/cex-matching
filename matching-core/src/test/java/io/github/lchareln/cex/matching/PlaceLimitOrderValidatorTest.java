package io.github.lchareln.cex.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;

final class PlaceLimitOrderValidatorTest {
  private static final BigInteger MAX = BigInteger.valueOf(Long.MAX_VALUE);
  private final PlaceLimitOrderValidator validator = new PlaceLimitOrderValidator();

  @Test
  void acceptsTheFrozenMinimumAndMaximumDomain() {
    assertInstanceOf(ValidationResult.Valid.class, validator.validate(input(1, "BUY", 1, 1)));
    assertInstanceOf(
        ValidationResult.Valid.class, validator.validate(input(MAX, "SELL", MAX, MAX)));
  }

  @Test
  void validatesInTheFrozenShortCircuitOrder() {
    assertInvalid(
        new PlaceLimitOrderInput(
            "ETH-USDT", BigInteger.ZERO, "HOLD", BigInteger.ZERO, BigInteger.ZERO),
        ValidationCode.UNKNOWN_INSTRUMENT);
    assertInvalid(input(0, "HOLD", 0, 0), ValidationCode.INVALID_ORDER_ID);
    assertInvalid(input(1, "HOLD", 0, 0), ValidationCode.INVALID_SIDE);
    assertInvalid(input(1, "BUY", 0, 0), ValidationCode.INVALID_PRICE);
    assertInvalid(input(1, "BUY", 1, 0), ValidationCode.INVALID_QUANTITY);
  }

  @Test
  void rejectsZeroNegativeAndPositiveOverflowForEveryLongBackedField() {
    BigInteger overflow = MAX.add(BigInteger.ONE);
    for (BigInteger invalid :
        new BigInteger[] {
          BigInteger.ZERO,
          BigInteger.ONE.negate(),
          BigInteger.valueOf(Long.MIN_VALUE),
          overflow,
          BigInteger.TEN.pow(100)
        }) {
      assertInvalid(
          input(invalid, "BUY", BigInteger.ONE, BigInteger.ONE), ValidationCode.INVALID_ORDER_ID);
      assertInvalid(
          input(BigInteger.ONE, "BUY", invalid, BigInteger.ONE), ValidationCode.INVALID_PRICE);
      assertInvalid(
          input(BigInteger.ONE, "BUY", BigInteger.ONE, invalid), ValidationCode.INVALID_QUANTITY);
    }
  }

  @Test
  void doesNotTrimFoldCaseOrCalculateNotional() {
    assertInvalid(input(1, "buy", 1, 1), ValidationCode.INVALID_SIDE);
    assertInvalid(input(1, " BUY", 1, 1), ValidationCode.INVALID_SIDE);
    assertInvalid(input(1, "", 1, 1), ValidationCode.INVALID_SIDE);
    assertInvalid(
        new PlaceLimitOrderInput("btc-usdt", BigInteger.ONE, "BUY", BigInteger.ONE, BigInteger.ONE),
        ValidationCode.UNKNOWN_INSTRUMENT);
    assertInvalid(
        new PlaceLimitOrderInput(
            "BTC-USDT ", BigInteger.ONE, "BUY", BigInteger.ONE, BigInteger.ONE),
        ValidationCode.UNKNOWN_INSTRUMENT);
    assertInstanceOf(ValidationResult.Valid.class, validator.validate(input(MAX, "BUY", MAX, MAX)));
  }

  @Test
  void remainsStatelessWhenTheSameOrderIdentityIsValidatedTwice() {
    PlaceLimitOrderInput repeated = input(42, "BUY", 6_500_000, 3);
    assertInstanceOf(ValidationResult.Valid.class, validator.validate(repeated));
    assertInstanceOf(ValidationResult.Valid.class, validator.validate(repeated));
  }

  @Test
  void onlyNormalizesBusinessValidInput() {
    PlaceLimitOrder command = validator.normalize(input(42, "BUY", 6_500_000, 3));
    assertEquals(42, command.orderId().value());
    assertEquals(6_500_000, command.priceTicks().value());
    assertEquals(3, command.quantityLots().value());
    assertThrows(
        IllegalArgumentException.class, () -> validator.normalize(input(43, "BUY", 6_500_000, 0)));
  }

  private void assertInvalid(PlaceLimitOrderInput input, ValidationCode expected) {
    ValidationResult.Invalid invalid =
        assertInstanceOf(ValidationResult.Invalid.class, validator.validate(input));
    assertEquals(expected, invalid.code());
    assertEquals(expected.field(), invalid.field());
  }

  private static PlaceLimitOrderInput input(long orderId, String side, long price, long quantity) {
    return input(
        BigInteger.valueOf(orderId), side, BigInteger.valueOf(price), BigInteger.valueOf(quantity));
  }

  private static PlaceLimitOrderInput input(
      BigInteger orderId, String side, BigInteger price, BigInteger quantity) {
    return new PlaceLimitOrderInput(
        PlaceLimitOrderValidator.INSTRUMENT_ID, orderId, side, price, quantity);
  }
}
