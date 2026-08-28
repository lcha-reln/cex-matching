package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import org.junit.jupiter.api.Test;

final class M03PropertyJudgeTest {
  private final M03PropertyJudge judge = new M03PropertyJudge();

  @Test
  void productionMatchesTheIndependentModelAcrossEveryGeneratedBoundary() {
    List<M03GeneratedHistory> histories = histories();
    int commands = 0;
    int comparisons = 0;
    for (M03GeneratedHistory history : histories) {
      M03PropertyJudge.Observation observation = judge.judge(history, M03ProductionCandidate::new);
      assertEquals(M03PropertyJudge.PASS, observation.classification(), observation.message());
      commands += observation.completedCommands();
      comparisons += observation.differentialComparisons();
    }
    assertEquals(256 * 64, commands);
    assertEquals(commands, comparisons);
  }

  @Test
  void generatedLanesKillAllRequiredBusinessMutantsAtNamedProperties() {
    List<M03GeneratedHistory> histories = histories();
    assertFailure(
        histories.get(0),
        M03Mutants.bestPriceLast(M03ProductionCandidate::new),
        "PRICE_TIME_PRIORITY",
        "WRONG_MAKER_ORDER");
    assertFailure(
        histories.get(1),
        M03Mutants.samePriceLifo(M03ProductionCandidate::new),
        "PRICE_TIME_PRIORITY",
        "WRONG_MAKER_ORDER");
    assertFailure(
        histories.get(2),
        M03Mutants.takerPrice(M03ProductionCandidate::new),
        "MAKER_PRICE",
        "TRADE_PRICE");
    assertFailure(
        histories.get(2),
        M03Mutants.tradeQuantityOverflow(M03ProductionCandidate::new),
        "QUANTITY_PARTITION",
        "TRADE_EXCEEDS_REMAINDER");
    assertFailure(
        histories.get(3),
        M03Mutants.cancelGhostBook(M03ProductionCandidate::new),
        "BOOK_LIFECYCLE_BIJECTION",
        "ACTIVE_ID_SET");
    assertFailure(
        histories.get(3),
        M03Mutants.canceledIdentityReuse(M03ProductionCandidate::new),
        "LIFECYCLE_IRREVERSIBILITY",
        "TERMINAL_OR_ACTIVE_ID_REUSED");
  }

  @Test
  void candidateExceptionRemainsASystemError() {
    M03PropertyJudge.Observation observation =
        judge.judge(histories().getFirst(), M03Mutants.throwingControl());

    assertEquals(M03PropertyJudge.SYSTEM_ERROR, observation.classification());
  }

  private void assertFailure(
      M03GeneratedHistory history,
      M03Candidate.Factory factory,
      String propertyId,
      String divergenceKind) {
    M03PropertyJudge.Observation observation = judge.judge(history, factory);
    assertEquals(M03PropertyJudge.STUDENT_FAILURE, observation.classification());
    assertNotNull(observation.failure());
    assertEquals(propertyId, observation.failure().propertyId());
    assertEquals(divergenceKind, observation.failure().divergenceKind());
  }

  private static List<M03GeneratedHistory> histories() {
    var root = M02TestPaths.root();
    M03GeneratorProfile profile =
        M03GeneratorProfile.load(
            root.resolve(M03StartCheckRunner.GENERATOR_PATH),
            root.resolve(M03StartCheckRunner.GENERATOR_SCHEMA_PATH));
    return new M03HistoryGenerator().generate(profile);
  }
}
