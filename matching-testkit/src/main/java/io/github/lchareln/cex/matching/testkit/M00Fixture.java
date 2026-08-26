package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.PlaceLimitOrderInput;
import io.github.lchareln.cex.matching.ValidationCode;
import java.util.List;
import java.util.Objects;

/** Parsed form of one {@code matching.m00.fixture.v1} history. */
public record M00Fixture(List<Record> records) {
  public M00Fixture {
    records = List.copyOf(records);
  }

  public record Record(String caseId, PlaceLimitOrderInput input, Expected expected) {
    public Record {
      Objects.requireNonNull(caseId, "caseId");
      Objects.requireNonNull(input, "input");
      Objects.requireNonNull(expected, "expected");
    }
  }

  public record Expected(String status, ValidationCode code, String field) {
    public Expected {
      Objects.requireNonNull(status, "status");
      if ("VALID".equals(status)) {
        if (code != null || field != null) {
          throw new IllegalArgumentException("VALID expectation cannot contain code or field");
        }
      } else if ("INVALID".equals(status)) {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(field, "field");
        if (!code.field().equals(field)) {
          throw new IllegalArgumentException("expectation code and field do not match");
        }
      } else {
        throw new IllegalArgumentException("unknown expectation status: " + status);
      }
    }
  }
}
