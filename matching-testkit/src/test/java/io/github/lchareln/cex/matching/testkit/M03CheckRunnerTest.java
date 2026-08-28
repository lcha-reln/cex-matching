package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

final class M03CheckRunnerTest {
  @Test
  void writesStrictCompletionReportsAndReplayableMinimalCounterexamples(@TempDir Path outputRoot)
      throws IOException {
    Path reports = outputRoot.resolve("build/reports/m03");

    M03CheckRunner.Result result =
        new M03CheckRunner().run(M02TestPaths.root(), reports, outputRoot);

    assertEquals(M03CheckRunner.PASS, result.status());
    JsonNode check = JsonSupport.parse(Files.readAllBytes(result.reportPath()));
    assertEquals(M03CheckRunner.SCHEMA_VERSION, check.path("schemaVersion").stringValue());
    assertEquals(16_384, check.path("properties").path("commands").intValue());
    assertEquals(
        M03CheckRunner.EXPECTED_COMMAND_DIGEST,
        check.path("determinism").path("commandDigest").stringValue());
    assertEquals(6, check.path("counterexamples").path("replayed").intValue());
    assertEquals(6, check.path("counterexamples").path("oneMinimal").intValue());
    assertEquals(6, check.path("mutants").path("killed").intValue());
    assertEquals(
        M03PropertyJudge.SYSTEM_ERROR,
        check.path("mutants").path("systemErrorControl").stringValue());
    assertEquals(0, check.path("architecture").path("violations").intValue());
    assertEquals(
        M03CheckRunner.CHECK_ARTIFACTS,
        java.util.stream.StreamSupport.stream(check.path("artifacts").spliterator(), false)
            .map(JsonNode::stringValue)
            .toList());
    for (String output : M03CheckRunner.OUTPUTS) {
      assertTrue(Files.isRegularFile(reports.resolve(output)), output);
    }

    JsonNode persisted =
        JsonSupport.parse(Files.readAllBytes(reports.resolve("counterexamples-v1.json")));
    assertEquals(6, persisted.path("scenarios").size());
    assertTrue(
        java.util.stream.StreamSupport.stream(persisted.path("scenarios").spliterator(), false)
            .allMatch(
                scenario ->
                    scenario.path("oneMinimal").booleanValue()
                        && scenario.path("originalCommandCount").intValue() == 64
                        && scenario.path("minimizedCommandCount").intValue() < 64));
    JsonSupport.validate(
        check,
        Files.readString(
            M02TestPaths.root().resolve(M03CheckRunner.CHECK_SCHEMA_PATH), StandardCharsets.UTF_8),
        false);
    JsonSupport.validate(
        persisted,
        Files.readString(
            M02TestPaths.root().resolve(M03CheckRunner.COUNTEREXAMPLE_SCHEMA_PATH),
            StandardCharsets.UTF_8),
        false);

    ObjectNode duplicateScenario = (ObjectNode) persisted.deepCopy();
    ArrayNode duplicateScenarios = (ArrayNode) duplicateScenario.path("scenarios");
    duplicateScenarios.set(1, duplicateScenarios.get(0).deepCopy());
    assertThrows(
        FixtureSchemaException.class,
        () ->
            JsonSupport.validate(
                duplicateScenario,
                Files.readString(
                    M02TestPaths.root().resolve(M03CheckRunner.COUNTEREXAMPLE_SCHEMA_PATH),
                    StandardCharsets.UTF_8),
                false));

    JsonNode replay = JsonSupport.parse(Files.readAllBytes(reports.resolve("replay.json")));
    assertTrue(
        replay
            .path("scenarios")
            .valueStream()
            .allMatch(
                scenario ->
                    scenario.path("provenanceExact").booleanValue()
                        && scenario.path("oneMinimalReverified").booleanValue()));

    ObjectNode forgedProvenance = (ObjectNode) persisted.deepCopy();
    ((ObjectNode) forgedProvenance.path("scenarios").get(0)).put("historyIndex", 1);
    M03GeneratorProfile profile =
        M03GeneratorProfile.load(
            M02TestPaths.root().resolve(M03StartCheckRunner.GENERATOR_PATH),
            M02TestPaths.root().resolve(M03StartCheckRunner.GENERATOR_SCHEMA_PATH));
    M03CounterexampleReplay.ReplayReport forgedReplay =
        new M03CounterexampleReplay().replay(forgedProvenance, mutantFactories(), profile);
    assertFalse(forgedReplay.allPassed());
    assertFalse(forgedReplay.scenarios().getFirst().provenanceExact());
  }

  @Test
  void candidateExceptionFailsClosedAsSystemError(@TempDir Path outputRoot) throws IOException {
    M03CheckRunner runner =
        new M03CheckRunner(M03Mutants.throwingControl(), List.of(), M03Mutants.throwingControl());

    M03CheckRunner.Result result =
        runner.run(M02TestPaths.root(), outputRoot.resolve("reports"), outputRoot);

    assertEquals(M03CheckRunner.SYSTEM_ERROR, result.status());
    JsonNode check = JsonSupport.parse(Files.readAllBytes(result.reportPath()));
    assertEquals(M03CheckRunner.SYSTEM_ERROR, check.path("status").stringValue());
    assertTrue(check.path("failure").path("message").stringValue().contains("SYSTEM_ERROR"));
  }

  private static Map<String, M03Candidate.Factory> mutantFactories() {
    return Map.of(
        M03Mutants.BEST_PRICE_LAST_ID,
        M03Mutants.bestPriceLast(M03ProductionCandidate::new),
        M03Mutants.SAME_PRICE_LIFO_ID,
        M03Mutants.samePriceLifo(M03ProductionCandidate::new),
        M03Mutants.TAKER_PRICE_ID,
        M03Mutants.takerPrice(M03ProductionCandidate::new),
        M03Mutants.QUANTITY_OVERFLOW_ID,
        M03Mutants.tradeQuantityOverflow(M03ProductionCandidate::new),
        M03Mutants.CANCEL_GHOST_ID,
        M03Mutants.cancelGhostBook(M03ProductionCandidate::new),
        M03Mutants.CANCELED_REUSE_ID,
        M03Mutants.canceledIdentityReuse(M03ProductionCandidate::new));
  }
}
