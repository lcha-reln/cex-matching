package io.github.lchareln.cex.matching.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lchareln.cex.matching.local.RecoveryBudget;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class QualificationProfileTest {
  @Test
  void freezesSmokeAsMethodOnlyAndReleaseAsThirtyMinuteSoak() {
    assertEquals("METHOD_SMOKE_ONLY", QualificationProfile.CI_SMOKE.resultScope());
    assertFalse(QualificationProfile.CI_SMOKE.eligibleForReleaseEvidence());
    assertEquals(1, QualificationProfile.CI_SMOKE.sweeps());
    assertEquals(Duration.ofSeconds(3), QualificationProfile.CI_SMOKE.soak());

    assertEquals(3, QualificationProfile.RELEASE_QUALIFICATION.sweeps());
    assertEquals(Duration.ofSeconds(20), QualificationProfile.RELEASE_QUALIFICATION.calibration());
    assertEquals(Duration.ofSeconds(1_800), QualificationProfile.RELEASE_QUALIFICATION.soak());
    assertTrue(QualificationProfile.RELEASE_QUALIFICATION.eligibleForReleaseEvidence());
    assertEquals(1_000_000, QualificationProfile.CI_SMOKE.recoveryBudgetMaxSuffixRecords());
    assertEquals(1_073_741_824, QualificationProfile.CI_SMOKE.recoveryBudgetMaxSuffixBytes());
    assertEquals(1_024, QualificationProfile.CI_SMOKE.plannedWalRecordCeilingBytes());
    assertEquals(100_000_000, QualificationProfile.CI_SMOKE.proactiveCheckpointOffsetNanos());
    assertEquals(10_000_000, QualificationProfile.PROACTIVE_CHECKPOINT_ADMISSION_LAG_LIMIT_NANOS);
    assertEquals(64, RecoveryBudget.M09_DEFAULT.maxSuffixRecords());
    assertEquals(1_048_576, RecoveryBudget.M09_DEFAULT.maxSuffixBytes());
  }

  @Test
  void derivesIntegerRatesFromTheFrozenPermilleLadder() {
    QualificationProfile profile = QualificationProfile.CI_SMOKE;
    assertEquals(25, profile.offeredRate(100, 0));
    assertEquals(160, profile.offeredRate(100, 7));
    assertThrows(IllegalArgumentException.class, () -> profile.offeredRate(0, 0));
  }
}
