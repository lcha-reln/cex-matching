package io.github.lchareln.cex.matching.benchmark;

import java.util.Objects;

/** Immutable scheduled-window evidence kept separate from the later producer closure and drain. */
record PhaseEvidence(ObservationCut observationCut, TerminalDrain terminalDrain) {
  PhaseEvidence {
    Objects.requireNonNull(observationCut, "observationCut");
    Objects.requireNonNull(terminalDrain, "terminalDrain");
    if (terminalDrain.observedNanos() < observationCut.observedNanos()
        || terminalDrain.elapsedAfterObservationCutNanos()
            != terminalDrain.observedNanos() - observationCut.observedNanos()) {
      throw new IllegalArgumentException("terminal drain does not follow observation capture");
    }
  }

  record ObservationCut(
      long phaseOriginNanos,
      long scheduledWindowEndNanos,
      long observedNanos,
      long observationLagNanos,
      RunAccounting attemptAccounting,
      int queueCapacity,
      long startingBacklog,
      long plannedInitialOffers,
      long initialDecisionsAtCut,
      long scheduledDecisionBacklogAtCut,
      long servicePendingAtCut,
      long endingBacklog,
      long p99QueueDepth,
      long postCutOverloaded,
      PacingFidelity pacingFidelity) {
    ObservationCut {
      Objects.requireNonNull(attemptAccounting, "attemptAccounting");
      Objects.requireNonNull(pacingFidelity, "pacingFidelity");
      if (phaseOriginNanos < 0
          || scheduledWindowEndNanos <= phaseOriginNanos
          || observedNanos < scheduledWindowEndNanos
          || observationLagNanos != observedNanos - scheduledWindowEndNanos
          || queueCapacity <= 0
          || startingBacklog < 0
          || plannedInitialOffers <= 0
          || initialDecisionsAtCut < 0
          || scheduledDecisionBacklogAtCut < 0
          || servicePendingAtCut < 0
          || endingBacklog < 0
          || p99QueueDepth < 0
          || postCutOverloaded < 0) {
        throw new IllegalArgumentException("invalid scheduled-window observation cut");
      }
      if (plannedInitialOffers != initialDecisionsAtCut + scheduledDecisionBacklogAtCut) {
        throw new IllegalArgumentException("planned arrivals do not reconcile at scheduled cut");
      }
      if (servicePendingAtCut != attemptAccounting.pendingAtObservationCut()
          || endingBacklog != scheduledDecisionBacklogAtCut + servicePendingAtCut) {
        throw new IllegalArgumentException("scheduled and service backlog do not reconcile");
      }
      if (pacingFidelity.plannedInitialOffers() != plannedInitialOffers) {
        throw new IllegalArgumentException("pacing and cut planned-arrival counts disagree");
      }
    }

    RateMeasurement rateMeasurement(long offeredRate) {
      return new RateMeasurement(
          offeredRate,
          queueCapacity,
          attemptAccounting.admitted(),
          attemptAccounting.terminalCompletions(),
          attemptAccounting.overloaded(),
          startingBacklog,
          endingBacklog,
          p99QueueDepth,
          postCutOverloaded);
    }
  }

  record TerminalDrain(
      long observedNanos,
      long elapsedAfterObservationCutNanos,
      RunAccounting attemptAccounting,
      long logicalTerminalCompletions,
      long logicalLatencySamples) {
    TerminalDrain {
      Objects.requireNonNull(attemptAccounting, "attemptAccounting");
      if (observedNanos < 0
          || elapsedAfterObservationCutNanos < 0
          || logicalTerminalCompletions < 0
          || logicalLatencySamples != logicalTerminalCompletions
          || attemptAccounting.pendingAtObservationCut() != 0) {
        throw new IllegalArgumentException("invalid terminal drain evidence");
      }
    }
  }

  record PacingFidelity(
      long plannedInitialOffers,
      long producedInitialOffers,
      long producerLagP99Nanos,
      long producerLagMaxNanos,
      long producerLagP99LimitNanos,
      long producerLagMaxLimitNanos,
      boolean allScheduledArrivalsMaterialized,
      boolean allAdmissionDecisionsWithinLagLimits,
      boolean passed) {
    PacingFidelity {
      if (plannedInitialOffers <= 0
          || producedInitialOffers < 0
          || producerLagP99Nanos < 0
          || producerLagMaxNanos < producerLagP99Nanos
          || producerLagP99LimitNanos <= 0
          || producerLagMaxLimitNanos < producerLagP99LimitNanos) {
        throw new IllegalArgumentException("invalid producer pacing evidence");
      }
      boolean expectedMaterialized = plannedInitialOffers == producedInitialOffers;
      if (allScheduledArrivalsMaterialized != expectedMaterialized) {
        throw new IllegalArgumentException("scheduled-arrival materialization verdict disagrees");
      }
      boolean expectedWithinLimits =
          producedInitialOffers == plannedInitialOffers
              && producerLagP99Nanos <= producerLagP99LimitNanos
              && producerLagMaxNanos <= producerLagMaxLimitNanos;
      if (allAdmissionDecisionsWithinLagLimits != expectedWithinLimits
          || passed != (allScheduledArrivalsMaterialized && allAdmissionDecisionsWithinLagLimits)) {
        throw new IllegalArgumentException("producer pacing verdict disagrees with frozen limits");
      }
    }
  }
}
