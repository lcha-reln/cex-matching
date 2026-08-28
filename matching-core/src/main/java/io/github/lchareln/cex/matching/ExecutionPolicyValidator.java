package io.github.lchareln.cex.matching;

/** Deterministic validation and normalization of the M04 execution-policy field. */
public final class ExecutionPolicyValidator {

  public ValidationResult validate(String rawPolicy) {
    if (!isSupported(rawPolicy)) {
      return new ValidationResult.Invalid(ValidationCode.INVALID_EXECUTION_POLICY);
    }
    return new ValidationResult.Valid();
  }

  public ExecutionPolicy normalize(String rawPolicy) {
    ValidationResult result = validate(rawPolicy);
    if (result instanceof ValidationResult.Invalid invalid) {
      throw new IllegalArgumentException(
          "cannot normalize invalid " + invalid.code() + " at " + invalid.field());
    }
    return ExecutionPolicy.valueOf(rawPolicy);
  }

  private static boolean isSupported(String rawPolicy) {
    return "GTC".equals(rawPolicy)
        || "IOC".equals(rawPolicy)
        || "FOK".equals(rawPolicy)
        || "POST_ONLY".equals(rawPolicy);
  }
}
