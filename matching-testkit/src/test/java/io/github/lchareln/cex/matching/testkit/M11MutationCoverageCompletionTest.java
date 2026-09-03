package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class M11MutationCoverageCompletionTest {
  private M11MutantSuite.Result mutants;

  @BeforeAll
  void executeFreshCandidatesAndPersistedReplay() {
    mutants = new M11MutantSuite().run(root());
  }

  @Test
  void executesTenSingleFaultCandidatesAndThreeNonKillSystemControls() throws Exception {
    assertEquals(10, mutants.killed());
    assertTrue(mutants.actualMutationActions() >= 10);
    assertEquals(M11StartCheckRunner.MUTANT_IDS, strings(mutants.candidates(), "id"));
    assertEquals(M11StartCheckRunner.SYSTEM_ERROR_IDS, strings(mutants.controls(), "id"));
    mutants
        .candidates()
        .forEach(
            candidate -> {
              assertEquals("PASS", candidate.path("productionClassification").stringValue());
              assertEquals("STUDENT_FAILURE", candidate.path("classification").stringValue());
              assertEquals(
                  "M11_UNIFIED_TYPED_TRACE_OBSERVER_V2", candidate.path("observer").stringValue());
              assertTrue(candidate.path("actualMutationActions").intValue() > 0);
            });
    mutants
        .controls()
        .forEach(
            control -> {
              assertEquals("SYSTEM_ERROR", control.path("classification").stringValue());
              assertEquals(
                  "M11_SHARED_FAILURE_CLASSIFIER_V1", control.path("classifier").stringValue());
              assertFalse(control.path("countedAsKill").booleanValue());
              assertFalse(control.path("executedPath").stringValue().isBlank());
              assertFalse(control.path("failureType").stringValue().isBlank());
            });

    ObjectNode mutantReport = M11CheckRunner.mutantReport(mutants);
    String mutantSchema = Files.readString(root().resolve(M11CheckRunner.MUTANTS_SCHEMA_PATH));
    JsonSupport.validate(mutantReport, mutantSchema, false);
    mutantReport.put("systemErrorCountedAsKill", true);
    assertThrows(
        FixtureSchemaException.class,
        () -> JsonSupport.validate(mutantReport, mutantSchema, false));
  }

  @Test
  void validatesPersistedTypedCounterexamplesAndFreshReplay() throws Exception {
    JsonSupport.validate(
        mutants.counterexamples(),
        Files.readString(root().resolve(M11CheckRunner.COUNTEREXAMPLE_SCHEMA_PATH)),
        false);
    JsonSupport.validate(
        mutants.replayReport(),
        Files.readString(root().resolve(M11CheckRunner.REPLAY_SCHEMA_PATH)),
        false);
    assertArrayEquals(JsonSupport.prettyBytes(mutants.counterexamples()), mutants.persistedBytes());
    assertEquals(
        Hashing.sha256Hex(mutants.persistedBytes()),
        mutants.replayReport().path("persistedBytesSha256").stringValue());
    assertEquals(10, mutants.replayReport().path("productionControlsPassed").intValue());
    assertEquals(10, mutants.replayReport().path("oneDeleteAudits").intValue());
    assertTrue(mutants.replayReport().path("orderedUniqueIds").booleanValue());
    assertTrue(mutants.replayReport().path("stepCountsExact").booleanValue());
    assertTrue(mutants.replayReport().path("fingerprintsExact").booleanValue());

    JsonNode witnesses = mutants.counterexamples().path("witnesses");
    assertEquals(List.of(1, 2, 2, 3, 4, 5, 1, 3, 1, 1), integers(witnesses, "minimalActions"));
    for (int index = 0; index < witnesses.size(); index++) {
      JsonNode witness = witnesses.get(index);
      assertEquals(witness.path("minimalActions").intValue(), witness.path("steps").size());
      assertTrue(witness.path("rawActions").intValue() > witness.path("minimalActions").intValue());
      assertTrue(witness.path("actualMutationActions").intValue() > 0);
      assertTrue(witness.path("oneMinimal").booleanValue());
      assertTrue(witness.path("strictFreshReplay").booleanValue());
    }
  }

  @Test
  void schemasRejectCounterexampleOrderAndReplayFingerprintDrift() throws Exception {
    String counterexampleSchema =
        Files.readString(root().resolve(M11CheckRunner.COUNTEREXAMPLE_SCHEMA_PATH));
    ObjectNode wrongOrder = mutants.counterexamples();
    ArrayNode witnesses = (ArrayNode) wrongOrder.path("witnesses");
    JsonNode first = witnesses.get(0).deepCopy();
    witnesses.set(0, witnesses.get(1).deepCopy());
    witnesses.set(1, first);
    assertThrows(
        FixtureSchemaException.class,
        () -> JsonSupport.validate(wrongOrder, counterexampleSchema, false));

    String replaySchema = Files.readString(root().resolve(M11CheckRunner.REPLAY_SCHEMA_PATH));
    ObjectNode wrongFingerprint = mutants.replayReport();
    ((ObjectNode) wrongFingerprint.path("results").get(0)).put("fingerprintExact", false);
    assertThrows(
        FixtureSchemaException.class,
        () -> JsonSupport.validate(wrongFingerprint, replaySchema, false));
  }

  @Test
  void coverageComesFromFactsAndOnlyAddsSystemFactAfterExecutedControls() throws Exception {
    M11FixedSuite.Result fixed = fixed(allNonSystemFacts());
    ObjectNode coverage = new M11Coverage().run(root(), fixed, mutants);
    String coverageSchema = Files.readString(root().resolve(M11CheckRunner.COVERAGE_SCHEMA_PATH));
    JsonSupport.validate(coverage, coverageSchema, false);
    assertEquals(28, coverage.path("observed").intValue());
    assertEquals("ASSERTION_FACT_LEDGER", coverage.path("source").stringValue());
    assertTrue(coverage.path("systemErrorEvaluatedAfterControls").booleanValue());
    JsonNode system = coverage.path("witnesses").get(27);
    assertEquals("SYSTEM_ERROR_NEVER_PASS", system.path("obligation").stringValue());
    assertEquals("M11_SYSTEM_ERROR_CONTROLS", system.path("scenarios").get(0).stringValue());
    ObjectNode incompleteReport = coverage.deepCopy();
    incompleteReport.put("observed", 27);
    assertThrows(
        FixtureSchemaException.class,
        () -> JsonSupport.validate(incompleteReport, coverageSchema, false));

    List<M11FixedSuite.Fact> missing = new ArrayList<>(allNonSystemFacts());
    missing.removeFirst();
    assertThrows(
        M11SemanticFailure.class, () -> new M11Coverage().run(root(), fixed(missing), mutants));

    List<M11FixedSuite.Fact> preclaimed = new ArrayList<>(allNonSystemFacts());
    preclaimed.add(fact("SYSTEM_ERROR_NEVER_PASS"));
    assertThrows(
        M11SemanticFailure.class, () -> new M11Coverage().run(root(), fixed(preclaimed), mutants));

    ArrayNode tamperedControls = mutants.controls();
    ((ObjectNode) tamperedControls.get(0)).put("classification", "STUDENT_FAILURE");
    assertThrows(
        M11SemanticFailure.class,
        () -> new M11Coverage().run(root(), fixed, withControls(tamperedControls)));
  }

  @Test
  void fixedAssertionLedgerMapsEveryFrozenDeclarationWithoutCopyingItAsEvidence() throws Exception {
    JsonNode workload =
        JsonSupport.parse(Files.readAllBytes(root().resolve(M11StartCheckRunner.WORKLOAD_PATH)));
    List<String> observed = new ArrayList<>();
    for (JsonNode scenario : workload.path("fixedScenarios")) {
      String id = scenario.path("id").stringValue();
      assertEquals(
          strings(scenario.path("proofObligations")), M11FixedSuite.declaredObligations(id));
      observed.addAll(M11FixedSuite.assertedObligations(id));
    }
    assertFalse(observed.contains("SYSTEM_ERROR_NEVER_PASS"));
    assertEquals(
        M11StartCheckRunner.COVERAGE_IDS.stream()
            .filter(value -> !"SYSTEM_ERROR_NEVER_PASS".equals(value))
            .collect(java.util.stream.Collectors.toSet()),
        observed.stream().collect(java.util.stream.Collectors.toSet()));
  }

  @Test
  void completionRunnerUsesSharedFailureClassifierAndBindsReplayOutput() {
    assertEquals(
        "STUDENT_FAILURE", M11FailureClassifier.classify(new M11SemanticFailure("semantic")));
    assertEquals("SYSTEM_ERROR", M11FailureClassifier.classify(new IllegalStateException("io")));
    assertEquals(12, M11CheckRunner.OUTPUTS.size());
    assertTrue(M11CheckRunner.OUTPUTS.contains("replay.json"));
    assertEquals(
        List.of(
            "inherited-m10.json",
            "fixed-scenarios.json",
            "generated-differential.json",
            "generated-requests.canonical.bin",
            "cluster-runtime.json",
            "protocol-goldens.json",
            "coverage.json",
            "mutants.json",
            "counterexamples.json",
            "replay.json",
            "architecture.json",
            "environment.json"),
        M11CheckRunner.OUTPUTS);
  }

  @Test
  void completionCheckSchemaKeepsSystemAndSemanticFailuresFailClosed() throws Exception {
    String schema = Files.readString(root().resolve(M11CheckRunner.CHECK_SCHEMA_PATH));
    ObjectNode failure = JsonSupport.MAPPER.createObjectNode();
    failure.put("schemaVersion", "matching.m11.check.v2");
    failure.put("unit", "M11");
    failure.put("status", "SYSTEM_ERROR");
    failure.put("contractPlanVersion", "0.14");
    failure.put("failure", "bounded infrastructure failure");
    ObjectNode target = failure.putObject("releaseTarget");
    target.put("unitTag", "course/m11-complete");
    target.putNull("productRelease");
    target.put("verification", "CLEAN_TREE_ANNOTATED_TAG_EVIDENCE");
    JsonSupport.validate(failure, schema, false);
    failure.put("status", "PASS");
    assertThrows(FixtureSchemaException.class, () -> JsonSupport.validate(failure, schema, false));
  }

  private static M11FixedSuite.Result fixed(List<M11FixedSuite.Fact> facts) {
    ObjectNode report = JsonSupport.MAPPER.createObjectNode();
    report.put("schemaVersion", "matching.m11.fixed-scenarios.v1");
    report.put("status", "PASS");
    report.put("scenarios", 22);
    report.put("passed", 22);
    return new M11FixedSuite.Result(report, 22, facts);
  }

  private static List<M11FixedSuite.Fact> allNonSystemFacts() {
    return M11StartCheckRunner.COVERAGE_IDS.stream()
        .filter(obligation -> !"SYSTEM_ERROR_NEVER_PASS".equals(obligation))
        .map(M11MutationCoverageCompletionTest::fact)
        .toList();
  }

  private static M11FixedSuite.Fact fact(String obligation) {
    return new M11FixedSuite.Fact(
        obligation,
        "FACT_LEDGER_TEST",
        "fixed-scenarios.json",
        "executed assertion for " + obligation,
        "observed=true");
  }

  private M11MutantSuite.Result withControls(ArrayNode controls) {
    return new M11MutantSuite.Result(
        mutants.counterexamples(),
        mutants.persistedBytes(),
        mutants.candidates(),
        controls,
        mutants.replayReport(),
        mutants.canonicalBytes(),
        mutants.digest(),
        mutants.killed(),
        mutants.rawActions(),
        mutants.minimalActions(),
        mutants.shrinkTrials(),
        mutants.actualMutationActions());
  }

  private static List<String> strings(JsonNode values, String field) {
    List<String> result = new ArrayList<>();
    values.forEach(value -> result.add(value.path(field).stringValue()));
    return List.copyOf(result);
  }

  private static List<String> strings(JsonNode values) {
    List<String> result = new ArrayList<>();
    values.forEach(value -> result.add(value.stringValue()));
    return List.copyOf(result);
  }

  private static List<Integer> integers(JsonNode values, String field) {
    List<Integer> result = new ArrayList<>();
    values.forEach(value -> result.add(value.path(field).intValue()));
    return List.copyOf(result);
  }

  private static Path root() {
    return Path.of(System.getProperty("matching.repositoryRoot")).toAbsolutePath().normalize();
  }
}
