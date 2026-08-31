package io.github.lchareln.cex.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class SelfTradePreventionValidatorTest {
  private final SelfTradePreventionValidator validator = new SelfTradePreventionValidator();

  @Test
  void rawGrammarIsExactAndPairGrammarIsSeparate() {
    assertInvalid(validator.validateGroup(-1), ValidationCode.INVALID_STP_GROUP_ID);
    assertInvalid(validator.validatePolicy("cancel_taker"), ValidationCode.INVALID_STP_POLICY);
    assertInvalid(validator.validatePolicy(" CANCEL_TAKER"), ValidationCode.INVALID_STP_POLICY);
    assertInvalid(validator.validateInstruction(-1, "bad"), ValidationCode.INVALID_STP_GROUP_ID);
    assertInvalid(validator.validateInstruction(7, "bad"), ValidationCode.INVALID_STP_POLICY);
    assertInvalid(
        validator.validateInstruction(0, "CANCEL_TAKER"), ValidationCode.INVALID_STP_INSTRUCTION);
    assertInvalid(validator.validateInstruction(7, "NONE"), ValidationCode.INVALID_STP_INSTRUCTION);

    assertEquals(
        new SelfTradePreventionInstruction(0, SelfTradePreventionPolicy.NONE),
        validator.normalize(0, "NONE"));
    assertEquals(
        new SelfTradePreventionInstruction(7, SelfTradePreventionPolicy.CANCEL_BOTH),
        validator.normalize(7, "CANCEL_BOTH"));
  }

  @Test
  void normalizedInstructionCannotRepresentAnAmbiguousPair() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new SelfTradePreventionInstruction(0, SelfTradePreventionPolicy.CANCEL_MAKER));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SelfTradePreventionInstruction(7, SelfTradePreventionPolicy.NONE));
  }

  private static void assertInvalid(ValidationResult result, ValidationCode code) {
    assertEquals(code, assertInstanceOf(ValidationResult.Invalid.class, result).code());
  }
}
