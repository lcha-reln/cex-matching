package io.github.lchareln.cex.matching.benchmark;

import java.math.BigInteger;
import java.time.Duration;

/** Conservative fail-fast proof that a scheduled phase cannot plan beyond the frozen suffix. */
final class QualificationRecoveryPlan {
  private static final BigInteger NANOS_PER_SECOND = BigInteger.valueOf(1_000_000_000L);

  private QualificationRecoveryPlan() {}

  static PhasePlan requireFits(
      QualificationProfile profile,
      long offeredRate,
      Duration duration,
      long actualSuffixRecordsAtPhaseStart,
      long actualSuffixBytesAtPhaseStart,
      int queueCapacity) {
    long operations = ScheduledArrival.operationsFor(offeredRate, duration.toNanos());
    long latestCheckpointAdmissionOffset =
        Math.addExact(
            profile.proactiveCheckpointOffsetNanos(),
            QualificationProfile.PROACTIVE_CHECKPOINT_ADMISSION_LAG_LIMIT_NANOS);
    long plannedBeforeLatestCheckpointAdmission =
        Math.min(operations, ceilOperations(offeredRate, latestCheckpointAdmissionOffset));
    long ownerInFlightBound = Math.addExact((long) queueCapacity, 1L);
    long worstBeforeCheckpoint =
        Math.addExact(
            Math.addExact(actualSuffixRecordsAtPhaseStart, plannedBeforeLatestCheckpointAdmission),
            ownerInFlightBound);
    long worstBytesBeforeCheckpoint =
        Math.addExact(
            actualSuffixBytesAtPhaseStart,
            Math.multiplyExact(
                Math.addExact(plannedBeforeLatestCheckpointAdmission, ownerInFlightBound),
                (long) profile.plannedWalRecordCeilingBytes()));
    // Producer lag can move any planned arrival, including one scheduled before the checkpoint,
    // behind the maintenance item. Therefore the post-checkpoint proof conservatively retains all
    // planned arrivals. A CheckpointRequired retry is pre-WAL and consumes at most the one durable
    // record already assigned to its logical operation; the owner bound is its conservative cap.
    long conservativeRetryDurableBound = ownerInFlightBound;
    long worstSuffixAfterCheckpoint = Math.addExact(operations, conservativeRetryDurableBound);
    long worstSuffixBytesAfterCheckpoint =
        Math.multiplyExact(
            worstSuffixAfterCheckpoint, (long) profile.plannedWalRecordCeilingBytes());
    requireWithinBudget(
        profile, worstBeforeCheckpoint, worstBytesBeforeCheckpoint, "before proactive checkpoint");
    requireWithinBudget(
        profile,
        worstSuffixAfterCheckpoint,
        worstSuffixBytesAfterCheckpoint,
        "after proactive checkpoint");
    return new PhasePlan(
        operations,
        plannedBeforeLatestCheckpointAdmission,
        actualSuffixRecordsAtPhaseStart,
        actualSuffixBytesAtPhaseStart,
        ownerInFlightBound,
        conservativeRetryDurableBound,
        worstBeforeCheckpoint,
        worstBytesBeforeCheckpoint,
        worstSuffixAfterCheckpoint,
        worstSuffixBytesAfterCheckpoint);
  }

  private static long ceilOperations(long offeredRate, long durationNanos) {
    BigInteger numerator =
        BigInteger.valueOf(offeredRate).multiply(BigInteger.valueOf(durationNanos));
    return numerator
        .add(NANOS_PER_SECOND.subtract(BigInteger.ONE))
        .divide(NANOS_PER_SECOND)
        .longValueExact();
  }

  private static void requireWithinBudget(
      QualificationProfile profile, long plannedRecords, long plannedBytes, String position) {
    if (plannedRecords > profile.recoveryBudgetMaxSuffixRecords()
        || plannedBytes > profile.recoveryBudgetMaxSuffixBytes()) {
      throw new IllegalStateException(
          "planned WAL suffix exceeds dedicated M10 recovery budget "
              + position
              + ": records="
              + plannedRecords
              + "/"
              + profile.recoveryBudgetMaxSuffixRecords()
              + ", plannedBytes="
              + plannedBytes
              + "/"
              + profile.recoveryBudgetMaxSuffixBytes());
    }
  }

  record PhasePlan(
      long plannedInitialOffers,
      long plannedBeforeLatestCheckpointAdmission,
      long actualSuffixRecordsAtPhaseStart,
      long actualSuffixBytesAtPhaseStart,
      long ownerInFlightBound,
      long conservativeRetryDurableBound,
      long worstRecordsBeforeCheckpoint,
      long worstBytesBeforeCheckpoint,
      long worstSuffixRecordsAfterCheckpoint,
      long worstSuffixBytesAfterCheckpoint) {
    PhasePlan {
      if (plannedInitialOffers <= 0
          || plannedBeforeLatestCheckpointAdmission < 0
          || actualSuffixRecordsAtPhaseStart < 0
          || actualSuffixBytesAtPhaseStart < 0
          || ownerInFlightBound <= 0
          || conservativeRetryDurableBound < 0
          || worstRecordsBeforeCheckpoint < 0
          || worstBytesBeforeCheckpoint < 0
          || worstSuffixRecordsAfterCheckpoint < 0
          || worstSuffixBytesAfterCheckpoint < 0) {
        throw new IllegalArgumentException("invalid qualification recovery plan");
      }
    }
  }
}
