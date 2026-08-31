package io.github.lchareln.cex.matching;

import java.util.Objects;

/** Opaque audit attribution for a control-plane caller, not an authorization credential. */
public record OperatorId(String value) {
  public OperatorId {
    Objects.requireNonNull(value, "value");
    if (value.isBlank() || value.length() > 128) {
      throw new IllegalArgumentException("operator id must contain 1 to 128 non-blank characters");
    }
  }
}
