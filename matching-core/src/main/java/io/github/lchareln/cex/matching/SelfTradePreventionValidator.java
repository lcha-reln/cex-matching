package io.github.lchareln.cex.matching;

/** Exact, case-sensitive validation for the raw M07 participant group and policy. */
public final class SelfTradePreventionValidator {

  public ValidationResult validateGroup(long participantGroupId) {
    return participantGroupId < 0
        ? new ValidationResult.Invalid(ValidationCode.INVALID_STP_GROUP_ID)
        : new ValidationResult.Valid();
  }

  public ValidationResult validatePolicy(String rawPolicy) {
    return isSupported(rawPolicy)
        ? new ValidationResult.Valid()
        : new ValidationResult.Invalid(ValidationCode.INVALID_STP_POLICY);
  }

  public ValidationResult validateInstruction(long participantGroupId, String rawPolicy) {
    ValidationResult group = validateGroup(participantGroupId);
    if (group instanceof ValidationResult.Invalid) {
      return group;
    }
    ValidationResult policyValidation = validatePolicy(rawPolicy);
    if (policyValidation instanceof ValidationResult.Invalid) {
      return policyValidation;
    }
    SelfTradePreventionPolicy policy = SelfTradePreventionPolicy.valueOf(rawPolicy);
    boolean valid =
        (participantGroupId == 0 && policy == SelfTradePreventionPolicy.NONE)
            || (participantGroupId > 0 && policy != SelfTradePreventionPolicy.NONE);
    return valid
        ? new ValidationResult.Valid()
        : new ValidationResult.Invalid(ValidationCode.INVALID_STP_INSTRUCTION);
  }

  public SelfTradePreventionInstruction normalize(long participantGroupId, String rawPolicy) {
    ValidationResult instruction = validateInstruction(participantGroupId, rawPolicy);
    if (instruction instanceof ValidationResult.Invalid invalid) {
      throw cannotNormalize(invalid);
    }
    return new SelfTradePreventionInstruction(
        participantGroupId, SelfTradePreventionPolicy.valueOf(rawPolicy));
  }

  private static IllegalArgumentException cannotNormalize(ValidationResult.Invalid invalid) {
    return new IllegalArgumentException(
        "cannot normalize invalid " + invalid.code() + " at " + invalid.field());
  }

  private static boolean isSupported(String rawPolicy) {
    return "NONE".equals(rawPolicy)
        || "CANCEL_TAKER".equals(rawPolicy)
        || "CANCEL_MAKER".equals(rawPolicy)
        || "CANCEL_BOTH".equals(rawPolicy);
  }
}
