package io.github.lchareln.cex.matching.benchmark;

import io.github.lchareln.cex.matching.local.RecoveryBudget;
import io.github.lchareln.cex.matching.local.WalConfig;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** Frozen M10 open-loop profiles; smoke proves the method, never a release capacity claim. */
public record QualificationProfile(
    Id id,
    String resultScope,
    boolean eligibleForReleaseEvidence,
    Duration calibration,
    int sweeps,
    Duration warmupPerRate,
    Duration measurementPerRate,
    Duration soak,
    List<Integer> rateLadderPermille,
    long recoveryBudgetMaxSuffixRecords,
    long recoveryBudgetMaxSuffixBytes,
    int plannedWalRecordCeilingBytes,
    long proactiveCheckpointOffsetNanos,
    long producerLagP99LimitNanos,
    long producerLagMaxLimitNanos,
    long observationCutLagLimitNanos,
    long resourceIntervalNanos) {
  public static final List<Integer> FROZEN_RATE_LADDER =
      List.of(250, 500, 700, 850, 1000, 1150, 1350, 1600);
  public static final long M10_MAX_SUFFIX_RECORDS = 1_000_000L;
  public static final long M10_MAX_SUFFIX_BYTES = 1_073_741_824L;
  public static final int PLANNED_WAL_RECORD_CEILING_BYTES = 1_024;
  public static final long PROACTIVE_CHECKPOINT_OFFSET_NANOS = 100_000_000L;
  public static final long PROACTIVE_CHECKPOINT_ADMISSION_LAG_LIMIT_NANOS = 10_000_000L;
  public static final long PRODUCER_LAG_P99_LIMIT_NANOS = 50_000_000L;
  public static final long PRODUCER_LAG_MAX_LIMIT_NANOS = 250_000_000L;
  public static final long OBSERVATION_CUT_LAG_LIMIT_NANOS = 10_000_000L;
  public static final long RESOURCE_INTERVAL_NANOS = 1_000_000_000L;

  public static final QualificationProfile CI_SMOKE =
      new QualificationProfile(
          Id.CI_SMOKE,
          "METHOD_SMOKE_ONLY",
          false,
          Duration.ofSeconds(1),
          1,
          Duration.ofSeconds(1),
          Duration.ofSeconds(2),
          Duration.ofSeconds(3),
          FROZEN_RATE_LADDER,
          M10_MAX_SUFFIX_RECORDS,
          M10_MAX_SUFFIX_BYTES,
          PLANNED_WAL_RECORD_CEILING_BYTES,
          PROACTIVE_CHECKPOINT_OFFSET_NANOS,
          PRODUCER_LAG_P99_LIMIT_NANOS,
          PRODUCER_LAG_MAX_LIMIT_NANOS,
          OBSERVATION_CUT_LAG_LIMIT_NANOS,
          RESOURCE_INTERVAL_NANOS);

  public static final QualificationProfile RELEASE_QUALIFICATION =
      new QualificationProfile(
          Id.RELEASE_QUALIFICATION,
          "RELEASE_QUALIFICATION",
          true,
          Duration.ofSeconds(20),
          3,
          Duration.ofSeconds(10),
          Duration.ofSeconds(30),
          Duration.ofSeconds(1_800),
          FROZEN_RATE_LADDER,
          M10_MAX_SUFFIX_RECORDS,
          M10_MAX_SUFFIX_BYTES,
          PLANNED_WAL_RECORD_CEILING_BYTES,
          PROACTIVE_CHECKPOINT_OFFSET_NANOS,
          PRODUCER_LAG_P99_LIMIT_NANOS,
          PRODUCER_LAG_MAX_LIMIT_NANOS,
          OBSERVATION_CUT_LAG_LIMIT_NANOS,
          RESOURCE_INTERVAL_NANOS);

  public QualificationProfile {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(resultScope, "resultScope");
    requirePositive(calibration, "calibration");
    requirePositive(warmupPerRate, "warmupPerRate");
    requirePositive(measurementPerRate, "measurementPerRate");
    requirePositive(soak, "soak");
    if (sweeps <= 0) {
      throw new IllegalArgumentException("sweeps must be positive");
    }
    rateLadderPermille = List.copyOf(rateLadderPermille);
    if (rateLadderPermille.isEmpty() || rateLadderPermille.stream().anyMatch(value -> value <= 0)) {
      throw new IllegalArgumentException("rate ladder must contain positive permille values");
    }
    for (int index = 1; index < rateLadderPermille.size(); index++) {
      if (rateLadderPermille.get(index) <= rateLadderPermille.get(index - 1)) {
        throw new IllegalArgumentException("rate ladder must be strictly increasing");
      }
    }
    if (id == Id.CI_SMOKE
        && (eligibleForReleaseEvidence || !resultScope.equals("METHOD_SMOKE_ONLY"))) {
      throw new IllegalArgumentException("CI_SMOKE can never be release evidence");
    }
    if (recoveryBudgetMaxSuffixRecords <= 0
        || recoveryBudgetMaxSuffixBytes <= 0
        || plannedWalRecordCeilingBytes <= 0
        || proactiveCheckpointOffsetNanos <= 0
        || producerLagP99LimitNanos <= 0
        || producerLagMaxLimitNanos < producerLagP99LimitNanos
        || observationCutLagLimitNanos <= 0
        || resourceIntervalNanos <= 0) {
      throw new IllegalArgumentException(
          "qualification method bounds must be positive and ordered");
    }
    if (proactiveCheckpointOffsetNanos >= warmupPerRate.toNanos()
        || proactiveCheckpointOffsetNanos >= measurementPerRate.toNanos()
        || proactiveCheckpointOffsetNanos >= soak.toNanos()) {
      throw new IllegalArgumentException("proactive checkpoint must be early within every phase");
    }
  }

  public long offeredRate(long referenceRate, int ladderIndex) {
    if (referenceRate <= 0) {
      throw new IllegalArgumentException("referenceRate must be positive");
    }
    int permille = rateLadderPermille.get(ladderIndex);
    return Math.max(1, Math.floorDiv(Math.multiplyExact(referenceRate, permille), 1_000));
  }

  /** M10 qualification deliberately does not use the much smaller M09 default budget. */
  public WalConfig qualificationWalConfig(Path directory, long shardId) {
    return new WalConfig(
        directory,
        shardId,
        WalConfig.DEFAULT_MAX_SEGMENT_BYTES,
        WalConfig.DEFAULT_MAX_RECORD_BYTES,
        new RecoveryBudget(recoveryBudgetMaxSuffixRecords, recoveryBudgetMaxSuffixBytes));
  }

  private static void requirePositive(Duration duration, String name) {
    Objects.requireNonNull(duration, name);
    if (duration.isZero() || duration.isNegative()) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }

  public enum Id {
    CI_SMOKE,
    RELEASE_QUALIFICATION
  }
}
