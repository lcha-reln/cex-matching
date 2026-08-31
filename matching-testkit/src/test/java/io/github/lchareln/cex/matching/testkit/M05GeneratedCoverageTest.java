package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

final class M05GeneratedCoverageTest {
  @Test
  void allFrozenObligationsHaveReferenceSemanticWitnesses() {
    Path root = M05TestPaths.root();
    M05GeneratorProfile profile = M05GeneratorProfile.load(root);
    M05ScenarioCorpus.Corpus corpus = M05ScenarioCorpus.load(root);
    List<M05GeneratedHistory> histories = new M05HistoryGenerator().generate(profile, corpus);

    M05GeneratedCoverage.Result coverage = new M05GeneratedCoverage().analyze(profile, histories);

    assertEquals(20, coverage.counts().size());
    assertEquals(20, coverage.satisfiedObligations());
    assertEquals(profile.coverageRequirements(), M05GeneratedCoverage.requiredKeys());
    assertEquals(
        profile.coverageRequirements(),
        coverage.orderedWitnesses().stream().map(M05GeneratedCoverage.Obligation::id).toList());
    coverage
        .orderedWitnesses()
        .forEach(
            obligation -> {
              assertTrue(obligation.satisfied(), obligation.id());
              assertTrue(obligation.historyIndex() >= 0, obligation.id());
              assertTrue(obligation.commandIndex() >= 0, obligation.id());
              assertTrue(
                  coverage.observedAt(
                      obligation.id(), obligation.historyIndex(), obligation.commandIndex()),
                  obligation.id());
            });
  }
}
