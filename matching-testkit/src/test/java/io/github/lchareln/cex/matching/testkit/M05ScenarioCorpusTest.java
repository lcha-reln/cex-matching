package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.Test;

final class M05ScenarioCorpusTest {
  @Test
  void loadsTheExactFrozenCommandAlgebra() {
    M05ScenarioCorpus.Corpus corpus = M05ScenarioCorpus.load(M05TestPaths.root());
    assertEquals(12, corpus.scenarios().size());
    assertEquals(54, corpus.commandCount());
    assertEquals(
        M05StartCheckRunner.SCENARIO_IDS,
        corpus.scenarios().stream().map(M05ScenarioCorpus.Scenario::scenarioId).toList());
    assertInstanceOf(
        M05Command.ActivateRuleSet.class,
        corpus.scenario("activation-rejection-matrix").steps().getFirst().command());
  }
}
