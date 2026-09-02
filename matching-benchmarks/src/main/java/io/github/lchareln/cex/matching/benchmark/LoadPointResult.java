package io.github.lchareln.cex.matching.benchmark;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Derived view of one phase; every value remains independently reconstructible from raw shards. */
public record LoadPointResult(
    QualificationArtifactSink.PointIdentity point,
    RunAccounting attemptAccounting,
    long logicalOffers,
    long logicalInitiallyAdmitted,
    long logicalOverloaded,
    long logicalClosedOrInvalid,
    long logicalTerminalCompletions,
    List<Long> logicalLatencyNanos,
    List<Long> queueDepthSamples,
    Map<String, Long> latencyPercentilesNanos,
    RateMeasurement rateMeasurement,
    PhaseEvidence phaseEvidence,
    QualificationRecoveryPlan.PhasePlan recoveryPlan,
    long actualSuffixRecords,
    long actualSuffixBytes) {
  public LoadPointResult {
    Objects.requireNonNull(point, "point");
    Objects.requireNonNull(attemptAccounting, "attemptAccounting");
    logicalLatencyNanos = List.copyOf(logicalLatencyNanos);
    queueDepthSamples = List.copyOf(queueDepthSamples);
    latencyPercentilesNanos = Map.copyOf(latencyPercentilesNanos);
    Objects.requireNonNull(rateMeasurement, "rateMeasurement");
    Objects.requireNonNull(phaseEvidence, "phaseEvidence");
    Objects.requireNonNull(recoveryPlan, "recoveryPlan");
    if (logicalOffers < 0
        || logicalInitiallyAdmitted < 0
        || logicalOverloaded < 0
        || logicalClosedOrInvalid < 0
        || logicalTerminalCompletions < 0
        || actualSuffixRecords < 0
        || actualSuffixBytes < 0) {
      throw new IllegalArgumentException("logical counters must be non-negative");
    }
    if (logicalOffers != logicalInitiallyAdmitted + logicalOverloaded + logicalClosedOrInvalid) {
      throw new IllegalArgumentException("logical offer counters do not reconcile");
    }
    if (logicalInitiallyAdmitted != logicalTerminalCompletions) {
      throw new IllegalArgumentException("terminal point retained unresolved admitted operations");
    }
    if (!logicalLatencyNanos.isEmpty()
        && logicalLatencyNanos.size() != logicalTerminalCompletions) {
      throw new IllegalArgumentException("logical latency denominator changed");
    }
    if (!attemptAccounting.equals(phaseEvidence.terminalDrain().attemptAccounting())) {
      throw new IllegalArgumentException("terminal accounting views disagree");
    }
    if (!rateMeasurement.equals(
        phaseEvidence.observationCut().rateMeasurement(rateMeasurement.offeredRate()))) {
      throw new IllegalArgumentException("rate measurement is not the frozen observation cut");
    }
  }
}
