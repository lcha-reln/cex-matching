package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class M05PropertyJudgeTest {
  private static final Path ROOT = Path.of(System.getProperty("matching.repositoryRoot"));

  @Test
  void productionMatchesReferenceAndLedgerForTheEntireFrozenGeneratedSuite() {
    M05GeneratorProfile profile = M05GeneratorProfile.load(ROOT);
    M05ScenarioCorpus.Corpus corpus = M05ScenarioCorpus.load(ROOT);
    List<M05GeneratedHistory> histories = new M05HistoryGenerator().generate(profile, corpus);
    int commands = 0;
    for (M05GeneratedHistory history : histories) {
      M05PropertyJudge.Observation observation =
          new M05PropertyJudge().judge(history, M05ProductionCandidate::new);
      assertEquals(
          M05PropertyJudge.PASS,
          observation.classification(),
          () -> history.historyIndex() + ": " + observation.message());
      assertEquals(64, observation.differentialComparisons());
      assertEquals(64, observation.ledgerChecks());
      assertEquals(64, observation.bookChecks());
      assertEquals(64, observation.marketControlChecks());
      commands += observation.completedCommands();
    }
    assertEquals(10_240, commands);
  }

  @Test
  void fixedCorpusMatchesAtEveryCommandBoundary() {
    M05ScenarioCorpus.Corpus corpus = M05ScenarioCorpus.load(ROOT);
    int commands = 0;
    for (M05ScenarioCorpus.Scenario scenario : corpus.scenarios()) {
      List<M05Command> inputs =
          scenario.steps().stream().map(M05ScenarioCorpus.Step::command).toList();
      M05PropertyJudge.Observation observation =
          new M05PropertyJudge()
              .judge(scenario.scenarioId(), "fixed", inputs, M05ProductionCandidate::new);
      assertEquals(M05PropertyJudge.PASS, observation.classification(), observation.message());
      commands += observation.completedCommands();
    }
    assertEquals(54, commands);
  }
}
