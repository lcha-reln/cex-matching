package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class M02MutantJudgeTest {
  private static final M02Candidate.Factory PRODUCTION = M02ProductionCandidate::new;

  @Test
  void killsFourMutantsAtTheirExactCommandsAndClassifiesThrowingControlSeparately() {
    M02ScenarioPack pack = M02TestPaths.load();
    M02Assertions assertions = new M02Assertions();

    assertKilled(
        assertions.judge(pack, M02Mutants.wrongFifoAfterMiddleCancel(PRODUCTION)),
        "cancel-middle-preserves-fifo",
        "cancel-middle-maker-only");
    assertKilled(
        assertions.judge(pack, M02Mutants.ghostRestingOrder(PRODUCTION)),
        "cancel-only-resting-order-removes-level",
        "cancel-only-ask-removes-level");
    assertKilled(
        assertions.judge(pack, M02Mutants.terminalIdentityReuse(PRODUCTION)),
        "duplicate-canceled-order-id-does-not-resurrect",
        "duplicate-canceled-place-rejected");
    assertKilled(
        assertions.judge(pack, M02Mutants.repeatedCancelSucceeds(PRODUCTION)),
        "repeat-cancel-stable",
        "repeat-cancel-reports-canceled-terminal");

    assertEquals(
        M02Assertions.SYSTEM_ERROR,
        assertions.judge(pack, M02Mutants.throwingControl()).classification());
  }

  private static void assertKilled(
      M02Assertions.Observation observation, String scenarioId, String caseId) {
    assertEquals(
        M02Assertions.STUDENT_FAILURE, observation.classification(), observation.message());
    assertEquals(scenarioId, observation.scenarioId());
    assertEquals(caseId, observation.caseId());
  }
}
