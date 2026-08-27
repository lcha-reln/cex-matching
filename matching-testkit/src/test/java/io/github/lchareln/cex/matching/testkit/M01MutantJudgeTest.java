package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class M01MutantJudgeTest {
  private static final M01Candidate.Factory PRODUCTION = M01ProductionCandidate::new;

  @Test
  void killsAllRequiredSemanticMutantsAndRejectsSystemErrorsAsKills() {
    M01ScenarioPack pack = M01TestPaths.load();
    M01Assertions assertions = new M01Assertions();

    assertKilled(
        assertions.judge(pack, M01Mutants.makerUsesTakerPrice(PRODUCTION)),
        "buy-takes-better-price-first");
    assertKilled(assertions.judge(pack, M01Mutants.samePriceLifo(PRODUCTION)), "fifo-taker");
    assertKilled(
        assertions.judge(pack, M01Mutants.skipsFirstMaker(PRODUCTION)),
        "buy-takes-better-price-first");

    M01Assertions.Observation systemError = assertions.judge(pack, M01Mutants.throwingControl());
    assertEquals(M01Assertions.SYSTEM_ERROR, systemError.classification());
  }

  private static void assertKilled(M01Assertions.Observation observation, String expectedCaseId) {
    assertEquals(
        M01Assertions.STUDENT_FAILURE, observation.classification(), observation.message());
    assertEquals(expectedCaseId, observation.caseId());
  }
}
