package io.github.lchareln.cex.matching.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class QualificationRecoveryPlanTest {
  @Test
  void validatesPrefixAndPostCheckpointSuffixSeparately() {
    QualificationRecoveryPlan.PhasePlan plan =
        QualificationRecoveryPlan.requireFits(
            QualificationProfile.CI_SMOKE, 100, Duration.ofSeconds(2), 10, 2_080, 64);

    assertEquals(200, plan.plannedInitialOffers());
    assertEquals(11, plan.plannedBeforeLatestCheckpointAdmission());
    assertEquals(65, plan.ownerInFlightBound());
    assertEquals(65, plan.conservativeRetryDurableBound());
    assertEquals(86, plan.worstRecordsBeforeCheckpoint());
    assertEquals(2_080L + 76L * 1_024, plan.worstBytesBeforeCheckpoint());
    assertEquals(265, plan.worstSuffixRecordsAfterCheckpoint());
    assertEquals(265L * 1_024, plan.worstSuffixBytesAfterCheckpoint());
  }

  @Test
  void rejectsPrefixOverflowWithoutAddingPostCheckpointSide() {
    assertThrows(
        IllegalStateException.class,
        () ->
            QualificationRecoveryPlan.requireFits(
                QualificationProfile.CI_SMOKE, 1, Duration.ofSeconds(1), 999_950, 0, 64));
  }

  @Test
  void rejectsThirtyMinuteSuffixAboveDedicatedMillionRecordBound() {
    assertThrows(
        IllegalStateException.class,
        () ->
            QualificationRecoveryPlan.requireFits(
                QualificationProfile.RELEASE_QUALIFICATION,
                556,
                Duration.ofSeconds(1_800),
                0,
                0,
                64));
  }
}
