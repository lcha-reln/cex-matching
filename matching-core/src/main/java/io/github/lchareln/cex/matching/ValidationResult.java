package io.github.lchareln.cex.matching;

import java.util.Objects;

/** A business result. Fixture/schema failures are deliberately outside this type. */
public sealed interface ValidationResult permits ValidationResult.Valid, ValidationResult.Invalid {

  String status();

  record Valid() implements ValidationResult {
    @Override
    public String status() {
      return "VALID";
    }
  }

  record Invalid(ValidationCode code, String field) implements ValidationResult {
    public Invalid {
      Objects.requireNonNull(code, "code");
      Objects.requireNonNull(field, "field");
      if (!code.field().equals(field)) {
        throw new IllegalArgumentException("validation code and field do not match");
      }
    }

    public Invalid(ValidationCode code) {
      this(code, code.field());
    }

    @Override
    public String status() {
      return "INVALID";
    }
  }
}
