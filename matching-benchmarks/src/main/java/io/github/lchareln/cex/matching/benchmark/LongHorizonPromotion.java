package io.github.lchareln.cex.matching.benchmark;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Strict M10Q2 state machine for descending full-duration soak promotion. */
final class LongHorizonPromotion {
  static final String POLICY_ID = "M10Q2_DESCENDING_FULL_DURATION_FIRST_PASS";

  private final List<Long> candidates;
  private final List<Attempt> attempts = new ArrayList<>();
  private int nextIndex;
  private Long inFlightRate;
  private Long qualifiedOperatingPoint;
  private boolean systemError;

  LongHorizonPromotion(List<Long> candidates) {
    this.candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
    if (this.candidates.isEmpty()) {
      throw new IllegalArgumentException("promotion requires at least one candidate");
    }
    long previous = Long.MAX_VALUE;
    for (long candidate : this.candidates) {
      if (candidate <= 0 || candidate >= previous) {
        throw new IllegalArgumentException(
            "promotion candidates must be positive and strictly descending");
      }
      previous = candidate;
    }
  }

  boolean hasNextCandidate() {
    return inFlightRate == null
        && qualifiedOperatingPoint == null
        && !systemError
        && nextIndex < candidates.size();
  }

  long beginNextAttempt() {
    if (!hasNextCandidate()) {
      throw new IllegalStateException("promotion has no next candidate");
    }
    inFlightRate = candidates.get(nextIndex);
    return inFlightRate;
  }

  int currentAttemptNumber() {
    if (inFlightRate == null) {
      throw new IllegalStateException("promotion has no in-flight attempt");
    }
    return nextIndex + 1;
  }

  void recordDecision(long offeredRate, SaturationAnalysis.SaturationDecision decision) {
    requireInFlight(offeredRate);
    Objects.requireNonNull(decision, "decision");
    Outcome outcome = decision.saturated() ? Outcome.SATURATED : Outcome.QUALIFIED;
    attempts.add(new Attempt(nextIndex + 1, offeredRate, outcome, decision.reasons()));
    nextIndex++;
    inFlightRate = null;
    if (outcome == Outcome.QUALIFIED) {
      qualifiedOperatingPoint = offeredRate;
    }
  }

  void recordSystemError(long offeredRate) {
    requireInFlight(offeredRate);
    attempts.add(new Attempt(nextIndex + 1, offeredRate, Outcome.SYSTEM_ERROR, List.of()));
    nextIndex++;
    inFlightRate = null;
    systemError = true;
  }

  boolean qualified() {
    return qualifiedOperatingPoint != null;
  }

  long qualifiedOperatingPoint() {
    if (qualifiedOperatingPoint == null) {
      throw new IllegalStateException("promotion has no qualified operating point");
    }
    return qualifiedOperatingPoint;
  }

  int qualifiedAttemptNumber() {
    if (qualifiedOperatingPoint == null) {
      throw new IllegalStateException("promotion has no qualified attempt");
    }
    return attempts.getLast().attemptNumber();
  }

  boolean exhaustedWithoutQualification() {
    return !systemError
        && inFlightRate == null
        && qualifiedOperatingPoint == null
        && nextIndex == candidates.size();
  }

  List<Long> candidates() {
    return candidates;
  }

  List<Attempt> attempts() {
    return List.copyOf(attempts);
  }

  private void requireInFlight(long offeredRate) {
    if (inFlightRate == null || inFlightRate != offeredRate) {
      throw new IllegalStateException("promotion result does not match the in-flight candidate");
    }
  }

  enum Outcome {
    SATURATED,
    QUALIFIED,
    SYSTEM_ERROR
  }

  record Attempt(int attemptNumber, long offeredRate, Outcome outcome, List<String> reasons) {
    Attempt {
      if (attemptNumber <= 0 || offeredRate <= 0) {
        throw new IllegalArgumentException("invalid promotion attempt identity");
      }
      Objects.requireNonNull(outcome, "outcome");
      reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
      if ((outcome == Outcome.SATURATED) != !reasons.isEmpty()) {
        throw new IllegalArgumentException("promotion outcome and saturation reasons disagree");
      }
    }
  }
}
