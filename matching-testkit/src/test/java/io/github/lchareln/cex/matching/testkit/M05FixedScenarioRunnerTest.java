package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class M05FixedScenarioRunnerTest {
  @Test
  void executesTwelveFreshScenariosAndFreezesAllFiftyFourOutcomes() {
    Path root = M05TestPaths.root();
    M05FixedScenarioRunner runner = new M05FixedScenarioRunner();

    M05FixedScenarioRunner.Result first = runner.run(root, M05ProductionCandidate::new);
    M05FixedScenarioRunner.Result second = runner.run(root, M05ProductionCandidate::new);

    assertEquals(12, first.scenarioCount());
    assertEquals(54, first.commandCount());
    assertEquals(54, first.comparisons());
    assertEquals(54, first.ledgerChecks());
    assertEquals(
        Map.of("PLACE", 21, "CANCEL", 3, "PREPARE_RULE_SET", 16, "ACTIVATE_RULE_SET", 14),
        first.commandCounts());
    assertEquals(67, first.canonicalLines());
    assertTrue(new String(first.canonicalBytes(), StandardCharsets.UTF_8).startsWith("M05F1|"));
    assertEquals("PASS", first.scenarioPack().path("status").stringValue());
    assertEquals(12, first.scenarioPack().path("scenarios").size());
    assertEquals("PASS", first.eventBatches().path("status").stringValue());
    assertEquals(12, first.eventBatches().path("scenarios").size());
    assertArrayEquals(first.canonicalBytes(), second.canonicalBytes());
    assertEquals(first.canonicalDigest(), second.canonicalDigest());
  }
}
