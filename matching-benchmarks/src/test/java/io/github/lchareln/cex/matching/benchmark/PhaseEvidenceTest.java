package io.github.lchareln.cex.matching.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

class PhaseEvidenceTest {
  @Test
  void scheduledDecisionAndServicePendingBacklogsMustReconcileExactly() {
    PhaseEvidence.PacingFidelity pacing = pacing(100, 100, true, true);
    RunAccounting cutAccounting = new RunAccounting(95, 90, 5, 0, Map.of(), 0, 4);

    PhaseEvidence.ObservationCut cut =
        new PhaseEvidence.ObservationCut(
            1, 1_000, 1_010, 10, cutAccounting, 64, 0, 100, 95, 5, 4, 9, 20, 0, pacing);

    assertEquals(9, cut.rateMeasurement(100).endingBacklog());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PhaseEvidence.ObservationCut(
                1, 1_000, 1_010, 10, cutAccounting, 64, 0, 100, 95, 5, 4, 8, 20, 0, pacing));
  }

  @Test
  void pacingAllowsDecisionsAfterCutWhenTheirIndividualLagBoundsPass() {
    PhaseEvidence.PacingFidelity pacing = pacing(100, 100, true, true);

    assertEquals(true, pacing.passed());
    assertThrows(IllegalArgumentException.class, () -> pacing(100, 99, true, true));
  }

  @Test
  void terminalDrainElapsedTimeIsMeasuredFromTheCapturedObservationCut() {
    PhaseEvidence.PacingFidelity pacing = pacing(1, 1, true, true);
    RunAccounting accounting = new RunAccounting(1, 1, 0, 0, Map.of(), 0, 0);
    PhaseEvidence.ObservationCut cut =
        new PhaseEvidence.ObservationCut(
            1, 1_000, 1_010, 10, accounting, 64, 0, 1, 1, 0, 0, 0, 1, 0, pacing);
    PhaseEvidence.TerminalDrain valid =
        new PhaseEvidence.TerminalDrain(1_020, 10, accounting, 1, 1);

    new PhaseEvidence(cut, valid);
    PhaseEvidence.TerminalDrain measuredFromScheduledEnd =
        new PhaseEvidence.TerminalDrain(1_020, 20, accounting, 1, 1);
    assertThrows(
        IllegalArgumentException.class, () -> new PhaseEvidence(cut, measuredFromScheduledEnd));
  }

  private static PhaseEvidence.PacingFidelity pacing(
      long planned, long produced, boolean materialized, boolean decisionsWithinLagLimits) {
    return new PhaseEvidence.PacingFidelity(
        planned,
        produced,
        10,
        20,
        50,
        250,
        materialized,
        decisionsWithinLagLimits,
        materialized && decisionsWithinLagLimits);
  }
}
