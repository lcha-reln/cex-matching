package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lchareln.cex.matching.PlaceLimitOrderInput;
import io.github.lchareln.cex.matching.PlaceLimitOrderValidator;
import io.github.lchareln.cex.matching.ValidationResult;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

final class M01M00RegressionTest {
  @Test
  void inheritedM00InputValidationCanonicalAndReplayRemainFrozen() {
    M01M00Regression.Result result =
        new M01M00Regression().verify(M01TestPaths.root(), M01ProductionCandidate::new);

    assertTrue(result.passed(), result.message());
    assertEquals(17, result.records());
    assertEquals(2, result.valid());
    assertEquals(15, result.invalid());
    assertEquals(100, result.completedReplays());
    assertEquals(1, result.distinctDigests());
  }

  @Test
  void engineMustRejectEveryM00InvalidShapeInsteadOfOnlyTheM01ZeroPriceCase() {
    M01Candidate.Factory acceptsUnknownInstrument =
        () -> {
          M01Candidate delegate = new M01ProductionCandidate();
          return input -> {
            if (!"BTC-USDT".equals(input.instrumentId())) {
              return new M01Candidate.Outcome(
                  List.of(new M01ScenarioPack.Accepted(1, 2, "BUY", 1, 1)),
                  M01ScenarioPack.Book.empty());
            }
            return delegate.place(input);
          };
        };

    M01M00Regression.Result result =
        new M01M00Regression().verify(M01TestPaths.root(), acceptsUnknownInstrument);

    assertFalse(result.passed());
    assertTrue(result.message().contains("unknown-instrument"));
  }

  @Test
  void engineRejectionsMustNotSecretlyConsumeAcceptanceSequence() {
    M01Candidate.Factory consumesSequenceBeforeRejecting =
        () -> {
          M01Candidate delegate = new M01ProductionCandidate();
          PlaceLimitOrderValidator validator = new PlaceLimitOrderValidator();
          long[] hiddenOrderId = {1000};
          return input -> {
            ValidationResult validation = validator.validate(input);
            if (validation instanceof ValidationResult.Invalid invalid) {
              delegate.place(validInput(hiddenOrderId[0]++));
              return new M01Candidate.Outcome(
                  List.of(new M01ScenarioPack.Rejected(invalid.code().name(), invalid.field())),
                  M01ScenarioPack.Book.empty());
            }
            return delegate.place(input);
          };
        };

    M01M00Regression.Result result =
        new M01M00Regression().verify(M01TestPaths.root(), consumesSequenceBeforeRejecting);

    assertFalse(result.passed());
    assertTrue(result.message().contains("consumed acceptance sequence"));
  }

  @Test
  void engineRejectionsMustPreserveAPreExistingRestingBook() {
    M01Candidate.Factory clearsReportedBookOnInvalid =
        () -> {
          M01Candidate delegate = new M01ProductionCandidate();
          PlaceLimitOrderValidator validator = new PlaceLimitOrderValidator();
          return input -> {
            ValidationResult validation = validator.validate(input);
            if (validation instanceof ValidationResult.Invalid invalid) {
              return new M01Candidate.Outcome(
                  List.of(new M01ScenarioPack.Rejected(invalid.code().name(), invalid.field())),
                  M01ScenarioPack.Book.empty());
            }
            return delegate.place(input);
          };
        };

    M01M00Regression.Result result =
        new M01M00Regression().verify(M01TestPaths.root(), clearsReportedBookOnInvalid);

    assertFalse(result.passed());
    assertTrue(result.message().contains("changed an existing book"));
  }

  private static PlaceLimitOrderInput validInput(long orderId) {
    return new PlaceLimitOrderInput(
        "BTC-USDT", BigInteger.valueOf(orderId), "BUY", BigInteger.ONE, BigInteger.ONE);
  }
}
