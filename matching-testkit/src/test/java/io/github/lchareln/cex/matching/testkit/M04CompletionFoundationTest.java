package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

final class M04CompletionFoundationTest {
  @Test
  void productionMatchesReferenceAndLedgerForFixedCorpus() {
    M04FixedScenarioRunner.Result result =
        new M04FixedScenarioRunner().run(M04TestPaths.root(), M04ProductionCandidate::new);

    assertEquals(14, result.scenarioCount());
    assertEquals(48, result.commandCount());
    assertEquals(
        "matching.m04.fixed-scenario-pack.v1",
        result.scenarioPack().path("schemaVersion").stringValue());
    assertEquals(
        "matching.m04.fixed-event-batches.v1",
        result.eventBatches().path("schemaVersion").stringValue());
    assertEquals(M04CheckRunner.EXPECTED_FIXED_DIGEST, result.canonicalDigest());
    assertEquals(M04CheckRunner.EXPECTED_FIXED_BYTES, result.canonicalBytes().length);
    assertEquals(M04CheckRunner.EXPECTED_FIXED_LINES, result.canonicalLines());
    for (int scenarioIndex = 0; scenarioIndex < result.scenarioCount(); scenarioIndex++) {
      var commands = result.scenarioPack().path("scenarios").get(scenarioIndex).path("commands");
      var batches = result.eventBatches().path("scenarios").get(scenarioIndex).path("cases");
      assertEquals(commands.size(), batches.size());
      for (int commandIndex = 0; commandIndex < commands.size(); commandIndex++) {
        var command = commands.get(commandIndex);
        var batch = batches.get(commandIndex);
        assertEquals(command.path("caseId"), batch.path("caseId"));
        assertEquals(command.path("type"), batch.path("type"));
        assertEquals(command.path("input"), batch.path("input"));
        assertEquals(command.path("expected").path("events"), batch.path("events"));
        assertEquals(command.path("expected").path("bookAfter"), batch.path("bookAfter"));
        assertEquals(5, batch.size());
      }
    }
  }

  @Test
  void fixedBusinessDivergenceHasAStudentFailureSignal() {
    assertThrows(
        M04FixedScenarioRunner.CandidateFailure.class,
        () ->
            new M04FixedScenarioRunner().run(M04TestPaths.root(), M04Mutants.iocRemainderRests()));
  }

  @Test
  void generatedSuiteIsDeterministicAndPassesAllBoundaries() {
    M04GeneratorProfile profile = profile();
    M04HistoryGenerator generator = new M04HistoryGenerator();
    List<M04GeneratedHistory> first = generator.generate(profile);
    List<M04GeneratedHistory> second = generator.generate(profile);
    M04CommandCanonicalizer.CanonicalCommands firstCanonical =
        new M04CommandCanonicalizer().canonicalize(profile, first);
    M04CommandCanonicalizer.CanonicalCommands secondCanonical =
        new M04CommandCanonicalizer().canonicalize(profile, second);

    assertArrayEquals(firstCanonical.bytes(), secondCanonical.bytes());
    assertEquals(firstCanonical.digest(), secondCanonical.digest());
    assertEquals(12_288, firstCanonical.commandCount());
    int commands = 0;
    for (M04GeneratedHistory history : first) {
      M04PropertyJudge.Observation observation =
          new M04PropertyJudge().judge(history, M04ProductionCandidate::new);
      assertEquals(M04PropertyJudge.PASS, observation.classification(), observation.message());
      commands += observation.completedCommands();
    }
    assertEquals(12_288, commands);
  }

  private static M04GeneratorProfile profile() {
    return M04GeneratorProfile.load(
        M04TestPaths.root().resolve(M04StartCheckRunner.GENERATOR_PATH),
        M04TestPaths.root().resolve(M04StartCheckRunner.GENERATOR_SCHEMA_PATH));
  }
}
