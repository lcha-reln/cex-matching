package io.github.lchareln.cex.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;

final class CancelOrderValidatorTest {
  private final CancelOrderValidator validator = new CancelOrderValidator();

  @Test
  void instrumentValidationPrecedesOrderIdentityValidation() {
    ValidationResult result = validator.validate(new CancelOrderInput("ETH-USDT", BigInteger.ZERO));

    ValidationResult.Invalid invalid = assertInstanceOf(ValidationResult.Invalid.class, result);
    assertEquals(ValidationCode.UNKNOWN_INSTRUMENT, invalid.code());
    assertEquals("instrumentId", invalid.field());
  }

  @Test
  void orderIdentityUsesTheFrozenPositiveLongDomain() {
    assertInvalid(BigInteger.ZERO);
    assertInvalid(BigInteger.valueOf(-1));
    assertInvalid(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE));

    assertInstanceOf(
        ValidationResult.Valid.class,
        validator.validate(new CancelOrderInput("BTC-USDT", BigInteger.ONE)));
    assertInstanceOf(
        ValidationResult.Valid.class,
        validator.validate(new CancelOrderInput("BTC-USDT", BigInteger.valueOf(Long.MAX_VALUE))));
  }

  @Test
  void normalizeProducesOnlyInstrumentAndOpaqueOrderIdentity() {
    CancelOrder normalized =
        validator.normalize(new CancelOrderInput("BTC-USDT", BigInteger.valueOf(17)));

    assertEquals(new CancelOrder("BTC-USDT", new OrderId(17)), normalized);
  }

  @Test
  void invalidInputCannotBeNormalizedAndNullFieldsFailAtTheSchemaBoundary() {
    assertThrows(
        IllegalArgumentException.class,
        () -> validator.normalize(new CancelOrderInput("BTC-USDT", BigInteger.ZERO)));
    assertThrows(NullPointerException.class, () -> new CancelOrderInput(null, BigInteger.ONE));
    assertThrows(NullPointerException.class, () -> new CancelOrderInput("BTC-USDT", null));
    assertThrows(IllegalArgumentException.class, () -> new CancelOrder("ETH-USDT", new OrderId(1)));
  }

  private void assertInvalid(BigInteger orderId) {
    ValidationResult result = validator.validate(new CancelOrderInput("BTC-USDT", orderId));
    ValidationResult.Invalid invalid = assertInstanceOf(ValidationResult.Invalid.class, result);
    assertEquals(ValidationCode.INVALID_ORDER_ID, invalid.code());
    assertEquals("orderId", invalid.field());
  }
}
