package io.github.lchareln.cex.matching.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SaturationAnalysisTest {
  @Test
  void appliesEveryFrozenSaturationPredicateAtItsExactBoundary() {
    assertFalse(SaturationAnalysis.classify(point(100, 100, 100, 0, 0, 0, 50)).saturated());
    assertTrue(SaturationAnalysis.classify(point(100, 100, 100, 1, 0, 0, 50)).saturated());
    assertTrue(SaturationAnalysis.classify(point(100, 100, 100, 0, 0, 0, 80)).saturated());
    assertTrue(SaturationAnalysis.classify(point(100, 200, 198, 0, 0, 0, 50)).saturated());
    assertTrue(SaturationAnalysis.classify(point(100, 100, 100, 0, 0, 11, 50)).saturated());
  }

  @Test
  void producerClosureCanOnlyWorsenTheImmutableScheduledCut() {
    RateMeasurement cleanCutWithLateOverload =
        new RateMeasurement(100, 100, 100, 100, 0, 0, 0, 50, 1);

    var decision = SaturationAnalysis.classify(cleanCutWithLateOverload);

    assertTrue(decision.saturated());
    assertEquals(List.of("POST_CUT_PLANNED_OVERLOAD_REJECTION"), decision.reasons());
  }

  @Test
  void choosesFirstOfTwoConsecutiveAndPublishesMinimumThreeSweepKnee() {
    List<RateMeasurement> sweepA = sweep(40);
    List<RateMeasurement> sweepB = sweep(30);
    List<RateMeasurement> sweepC = sweep(40);

    assertEquals(40, SaturationAnalysis.perSweepKnee(sweepA));
    var published = SaturationAnalysis.publish(List.of(sweepA, sweepB, sweepC));
    assertEquals(List.of(40L, 30L, 40L), published.sweepKnees());
    assertEquals(30, published.publishedKnee());
    assertEquals(21, published.qopCandidate());
    assertEquals(20, published.qop());
  }

  @Test
  void neverSelectsARateThatWasSaturatedInAnySweep() {
    List<RateMeasurement> noisyFirstSweep =
        List.of(
            point(10, 100, 100, 0, 0, 0, 1),
            point(20, 100, 90, 0, 0, 11, 1),
            point(30, 100, 100, 0, 0, 0, 1),
            point(40, 100, 90, 1, 0, 11, 90),
            point(50, 100, 90, 1, 0, 11, 90));

    var published = SaturationAnalysis.publishSmoke(List.of(noisyFirstSweep));

    assertEquals(40, published.publishedKnee());
    assertEquals(28, published.qopCandidate());
    assertEquals(10, published.qop());
  }

  @Test
  void refusesToInventAKneeBeyondTheMeasuredLadder() {
    List<RateMeasurement> comfortable =
        List.of(
            point(10, 10, 10, 0, 0, 0, 1),
            point(20, 10, 10, 0, 0, 0, 1),
            point(30, 10, 10, 0, 0, 0, 1));
    assertThrows(IllegalStateException.class, () -> SaturationAnalysis.perSweepKnee(comfortable));
  }

  private static List<RateMeasurement> sweep(long knee) {
    return List.of(
        point(10, 100, 100, 0, 0, 0, 1),
        point(20, 100, 100, 0, 0, 0, 1),
        point(30, 100, 100, knee == 30 ? 1 : 0, 0, 0, 1),
        point(40, 100, 100, 1, 0, 0, 1),
        point(50, 100, 100, 1, 0, 0, 1));
  }

  private static RateMeasurement point(
      long rate,
      long admitted,
      long completed,
      long overloaded,
      long startingBacklog,
      long endingBacklog,
      int queueDepth) {
    return new RateMeasurement(
        rate, 100, admitted, completed, overloaded, startingBacklog, endingBacklog, queueDepth);
  }
}
