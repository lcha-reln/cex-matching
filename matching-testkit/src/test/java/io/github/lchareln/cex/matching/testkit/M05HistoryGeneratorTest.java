package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class M05HistoryGeneratorTest {
  @Test
  void regeneratesTheSameFiveLaneSuite() {
    M05GeneratorProfile profile = M05GeneratorProfile.load(M05TestPaths.root());
    M05ScenarioCorpus.Corpus corpus = M05ScenarioCorpus.load(M05TestPaths.root());
    M05HistoryGenerator generator = new M05HistoryGenerator();
    var first = generator.generate(profile, corpus);
    var second = generator.generate(profile, corpus);

    assertEquals(first, second);
    assertEquals(160, first.size());
    assertEquals(10_240, first.stream().mapToInt(history -> history.commands().size()).sum());
    assertEquals(
        M05StartCheckRunner.LANE_IDS,
        first.subList(0, 5).stream().map(M05GeneratedHistory::laneId).toList());
  }
}
