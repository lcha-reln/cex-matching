package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lchareln.cex.matching.CancelOrderInput;
import io.github.lchareln.cex.matching.PlaceLimitOrderInput;
import org.junit.jupiter.api.Test;

final class M02StatefulPriorityProbesTest {
  @Test
  void productionPassesEveryStatefulValidationPriorityProbe() {
    M02StatefulPriorityProbes.Result result =
        new M02StatefulPriorityProbes().verify(M02ProductionCandidate::new);

    assertTrue(result.passed());
    assertEquals(M02StatefulPriorityProbes.EXPECTED_CHECKS, result.checks());
  }

  @Test
  void lifecycleLookupBeforeInstrumentValidationFailsTheActiveIdentityProbe() {
    M02StatefulPriorityProbes.Result result =
        new M02StatefulPriorityProbes().verify(PrematureLifecycleCancel::new);

    assertFalse(result.passed());
    assertTrue(result.message().contains("active identity"));
  }

  @Test
  void numericNormalizationBeforeValidationRemainsASystemError() {
    assertThrows(
        ArithmeticException.class,
        () -> new M02StatefulPriorityProbes().verify(PrematureNumericCancel::new));
  }

  private static class DelegatingCandidate implements M02Candidate {
    final M02Candidate delegate = new M02ProductionCandidate();

    @Override
    public Outcome place(PlaceLimitOrderInput input) {
      return delegate.place(input);
    }

    @Override
    public Outcome cancel(CancelOrderInput input) {
      return delegate.cancel(input);
    }
  }

  private static final class PrematureLifecycleCancel extends DelegatingCandidate {
    @Override
    public Outcome cancel(CancelOrderInput input) {
      if (input.instrumentId().equals("ETH-USDT") && input.orderId().longValueExact() == 9200) {
        return delegate.cancel(new CancelOrderInput("BTC-USDT", input.orderId()));
      }
      return delegate.cancel(input);
    }
  }

  private static final class PrematureNumericCancel extends DelegatingCandidate {
    @Override
    public Outcome cancel(CancelOrderInput input) {
      input.orderId().longValueExact();
      return delegate.cancel(input);
    }
  }
}
