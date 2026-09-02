package io.github.lchareln.cex.matching.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class LongHorizonPromotionTest {
  @Test
  void promotesTheFirstFullDurationPassAfterRetainingHigherSaturation() {
    LongHorizonPromotion promotion = new LongHorizonPromotion(List.of(166L, 83L));

    assertEquals(166, promotion.beginNextAttempt());
    promotion.recordDecision(
        166, new SaturationAnalysis.SaturationDecision(true, List.of("OVERLOAD_REJECTION")));
    assertEquals(83, promotion.beginNextAttempt());
    promotion.recordDecision(83, new SaturationAnalysis.SaturationDecision(false, List.of()));

    assertTrue(promotion.qualified());
    assertEquals(83, promotion.qualifiedOperatingPoint());
    assertEquals(2, promotion.qualifiedAttemptNumber());
    assertEquals(
        List.of(
            new LongHorizonPromotion.Attempt(
                1, 166, LongHorizonPromotion.Outcome.SATURATED, List.of("OVERLOAD_REJECTION")),
            new LongHorizonPromotion.Attempt(
                2, 83, LongHorizonPromotion.Outcome.QUALIFIED, List.of())),
        promotion.attempts());
  }

  @Test
  void aSystemErrorStopsPromotionInsteadOfTryingALowerRate() {
    LongHorizonPromotion promotion = new LongHorizonPromotion(List.of(166L, 83L));

    promotion.beginNextAttempt();
    promotion.recordSystemError(166);

    assertFalse(promotion.hasNextCandidate());
    assertFalse(promotion.qualified());
    assertFalse(promotion.exhaustedWithoutQualification());
    assertThrows(IllegalStateException.class, promotion::beginNextAttempt);
  }

  @Test
  void exhaustingEverySaturatedCandidatePublishesNoQop() {
    LongHorizonPromotion promotion = new LongHorizonPromotion(List.of(20L, 10L));

    for (long rate : List.of(20L, 10L)) {
      assertEquals(rate, promotion.beginNextAttempt());
      promotion.recordDecision(
          rate, new SaturationAnalysis.SaturationDecision(true, List.of("OVERLOAD_REJECTION")));
    }

    assertTrue(promotion.exhaustedWithoutQualification());
    assertFalse(promotion.qualified());
    assertThrows(IllegalStateException.class, promotion::qualifiedOperatingPoint);
  }

  @Test
  void rejectsManualReorderingAndNonDescendingCandidateLists() {
    assertThrows(IllegalArgumentException.class, () -> new LongHorizonPromotion(List.of(10L, 20L)));
    LongHorizonPromotion promotion = new LongHorizonPromotion(List.of(20L, 10L));
    promotion.beginNextAttempt();
    assertThrows(
        IllegalStateException.class,
        () ->
            promotion.recordDecision(
                10, new SaturationAnalysis.SaturationDecision(false, List.of())));
  }
}
