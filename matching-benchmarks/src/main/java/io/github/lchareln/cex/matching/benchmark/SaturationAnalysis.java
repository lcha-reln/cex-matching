package io.github.lchareln.cex.matching.benchmark;

import java.util.ArrayList;
import java.util.List;

/** Frozen M10 saturation predicates and first-of-two-consecutive knee rule. */
public final class SaturationAnalysis {
  public static final int QUEUE_DEPTH_PERMILLE = 800;
  public static final int MINIMUM_COMPLETED_PER_ADMITTED_PERMILLE = 995;
  public static final int MAXIMUM_BACKLOG_GROWTH_PERMILLE = 100;

  private SaturationAnalysis() {}

  public static SaturationDecision classify(RateMeasurement measurement) {
    List<String> reasons = new ArrayList<>();
    if (measurement.overloaded() > 0) {
      reasons.add("OVERLOAD_REJECTION");
    }
    // Producer-closure work may worsen a scheduled-window verdict, but it can never use later
    // completion or queue state to make the immutable cut look healthier.
    if (measurement.postCutOverloaded() > 0) {
      reasons.add("POST_CUT_PLANNED_OVERLOAD_REJECTION");
    }
    if (Math.multiplyExact(measurement.p99QueueDepth(), 1_000L)
        >= Math.multiplyExact(measurement.queueCapacity(), (long) QUEUE_DEPTH_PERMILLE)) {
      reasons.add("P99_QUEUE_DEPTH_AT_LEAST_80_PERCENT");
    }
    if (measurement.admitted() > 0
        && Math.multiplyExact(measurement.completed(), 1_000L)
            < Math.multiplyExact(
                measurement.admitted(), (long) MINIMUM_COMPLETED_PER_ADMITTED_PERMILLE)) {
      reasons.add("COMPLETED_PER_ADMITTED_BELOW_99_5_PERCENT");
    }
    if (Math.multiplyExact(measurement.backlogGrowth(), 1_000L)
        > Math.multiplyExact(measurement.queueCapacity(), (long) MAXIMUM_BACKLOG_GROWTH_PERMILLE)) {
      reasons.add("END_BACKLOG_GROWTH_ABOVE_10_PERCENT");
    }
    return new SaturationDecision(!reasons.isEmpty(), reasons);
  }

  public static long perSweepKnee(List<RateMeasurement> orderedMeasurements) {
    if (orderedMeasurements.size() < 2) {
      throw new IllegalArgumentException("a sweep requires at least two ordered rates");
    }
    long previousRate = 0;
    for (RateMeasurement measurement : orderedMeasurements) {
      if (measurement.offeredRate() <= previousRate) {
        throw new IllegalArgumentException("sweep rates must be strictly increasing");
      }
      previousRate = measurement.offeredRate();
    }
    for (int index = 0; index + 1 < orderedMeasurements.size(); index++) {
      if (classify(orderedMeasurements.get(index)).saturated()
          && classify(orderedMeasurements.get(index + 1)).saturated()) {
        return orderedMeasurements.get(index).offeredRate();
      }
    }
    throw new IllegalStateException("sweep has no pair of consecutive saturated rates");
  }

  public static PublishedEnvelope publish(List<List<RateMeasurement>> sweeps) {
    return publish(sweeps, 3);
  }

  /** CI uses the identical rule over its one frozen sweep but remains method-only evidence. */
  public static PublishedEnvelope publishSmoke(List<List<RateMeasurement>> sweeps) {
    return publish(sweeps, 1);
  }

  private static PublishedEnvelope publish(
      List<List<RateMeasurement>> sweeps, int requiredSweepCount) {
    if (sweeps.size() != requiredSweepCount) {
      throw new IllegalArgumentException("unexpected sweep count for qualification profile");
    }
    requireSameLadder(sweeps);
    List<Long> knees = sweeps.stream().map(SaturationAnalysis::perSweepKnee).toList();
    long publishedKnee = knees.stream().mapToLong(Long::longValue).min().orElseThrow();
    long qopCandidate = Math.floorDiv(Math.multiplyExact(publishedKnee, 70L), 100L);
    if (qopCandidate <= 0) {
      throw new IllegalStateException("qualified operating point candidate is not positive");
    }
    List<Long> provisionalSoakCandidates = selectProvisionalSoakCandidates(sweeps, qopCandidate);
    boolean aboveKneeRetained =
        sweeps.stream()
            .allMatch(
                sweep ->
                    sweep.stream()
                        .anyMatch(
                            point ->
                                point.offeredRate() > publishedKnee
                                    && classify(point).saturated()));
    if (!aboveKneeRetained) {
      throw new IllegalStateException("each sweep must retain saturated evidence above the knee");
    }
    return new PublishedEnvelope(knees, publishedKnee, qopCandidate, provisionalSoakCandidates);
  }

  private static void requireSameLadder(List<List<RateMeasurement>> sweeps) {
    List<RateMeasurement> referenceSweep = sweeps.getFirst();
    for (List<RateMeasurement> sweep : sweeps) {
      if (sweep.size() != referenceSweep.size()) {
        throw new IllegalStateException("sweep ladders differ while selecting soak candidates");
      }
      for (int index = 0; index < referenceSweep.size(); index++) {
        if (sweep.get(index).offeredRate() != referenceSweep.get(index).offeredRate()) {
          throw new IllegalStateException(
              "sweep offered rates differ while selecting soak candidates");
        }
      }
    }
  }

  private static List<Long> selectProvisionalSoakCandidates(
      List<List<RateMeasurement>> sweeps, long qopCandidate) {
    List<RateMeasurement> referenceSweep = sweeps.getFirst();
    List<Long> candidates = new ArrayList<>();
    for (int index = referenceSweep.size() - 1; index >= 0; index--) {
      long rate = referenceSweep.get(index).offeredRate();
      if (rate <= qopCandidate) {
        int ladderIndex = index;
        boolean unsaturatedInEverySweep =
            sweeps.stream().allMatch(sweep -> !classify(sweep.get(ladderIndex)).saturated());
        if (unsaturatedInEverySweep) {
          candidates.add(rate);
        }
      }
    }
    if (candidates.isEmpty()) {
      throw new IllegalStateException(
          "no all-sweep unsaturated measured rate at or below QOP candidate");
    }
    return List.copyOf(candidates);
  }

  public record SaturationDecision(boolean saturated, List<String> reasons) {
    public SaturationDecision {
      reasons = List.copyOf(reasons);
      if (saturated != !reasons.isEmpty()) {
        throw new IllegalArgumentException("saturation and reasons disagree");
      }
    }
  }

  public record PublishedEnvelope(
      List<Long> sweepKnees,
      long publishedKnee,
      long qopCandidate,
      List<Long> provisionalSoakCandidates) {
    public PublishedEnvelope {
      sweepKnees = List.copyOf(sweepKnees);
      provisionalSoakCandidates = List.copyOf(provisionalSoakCandidates);
      if ((sweepKnees.size() != 1 && sweepKnees.size() != 3)
          || publishedKnee <= 0
          || qopCandidate <= 0
          || provisionalSoakCandidates.isEmpty()) {
        throw new IllegalArgumentException("invalid published capacity envelope");
      }
      long previous = Long.MAX_VALUE;
      for (long candidate : provisionalSoakCandidates) {
        if (candidate <= 0 || candidate > qopCandidate || candidate >= previous) {
          throw new IllegalArgumentException(
              "provisional soak candidates must be positive, eligible, and strictly descending");
        }
        previous = candidate;
      }
    }
  }
}
