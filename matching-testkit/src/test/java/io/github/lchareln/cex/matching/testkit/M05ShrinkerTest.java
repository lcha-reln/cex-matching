package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

final class M05ShrinkerTest {
  @Test
  void requiredGeneratedFailuresShrinkDeterministicallyToOneDeletionMinimalHistories() {
    M05ScenarioCorpus.Corpus corpus = M05ScenarioCorpus.load(M05TestPaths.root());
    M05GeneratorProfile profile = M05GeneratorProfile.load(M05TestPaths.root());
    List<M05GeneratedHistory> histories = new M05HistoryGenerator().generate(profile, corpus);

    for (M05RequiredMutants.RequiredMutant mutant : M05RequiredMutants.all()) {
      M05GeneratedHistory source = find(histories, mutant);
      M05Shrinker.Result first =
          new M05Shrinker()
              .shrink(
                  "history-" + source.historyIndex(),
                  source.seedHex(),
                  source.commands(),
                  mutant.factory(),
                  mutant.fingerprint());
      M05Shrinker.Result second =
          new M05Shrinker()
              .shrink(
                  "history-" + source.historyIndex(),
                  source.seedHex(),
                  source.commands(),
                  mutant.factory(),
                  mutant.fingerprint());

      assertTrue(first.oneMinimal(), mutant.id());
      assertTrue(first.commands().size() < source.commands().size(), mutant.id());
      assertEquals(first.commands(), second.commands(), mutant.id());
      assertEquals(first.trials(), second.trials(), mutant.id());
      assertEquals(mutant.fingerprint().value(), first.observation().failure().fingerprint());
    }
  }

  private static M05GeneratedHistory find(
      List<M05GeneratedHistory> histories, M05RequiredMutants.RequiredMutant mutant) {
    for (M05GeneratedHistory history : histories) {
      M05PropertyJudge.Observation observation =
          new M05PropertyJudge().judge(history, mutant.factory());
      if (mutant.fingerprint().matches(observation)) {
        return history;
      }
    }
    throw new AssertionError("no generated failure for " + mutant.id());
  }
}
