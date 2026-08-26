package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.github.lchareln.cex.matching.PlaceLimitOrderValidator;
import io.github.lchareln.cex.matching.ValidationResult;
import org.junit.jupiter.api.Test;

final class M00ValidationContractTest {
  @Test
  void everyFrozenRecordMatchesTheIndependentFixtureOracle() {
    M00Fixture fixture =
        new M00FixtureLoader().load(M00TestPaths.fixture(), M00TestPaths.fixtureSchema());
    PlaceLimitOrderValidator validator = new PlaceLimitOrderValidator();

    for (M00Fixture.Record record : fixture.records()) {
      ValidationResult actual = validator.validate(record.input());
      assertEquals(record.expected().status(), actual.status(), record.caseId());
      if (record.expected().code() != null) {
        ValidationResult.Invalid invalid = assertInstanceOf(ValidationResult.Invalid.class, actual);
        assertEquals(record.expected().code(), invalid.code(), record.caseId());
        assertEquals(record.expected().field(), invalid.field(), record.caseId());
      }
    }
  }
}
