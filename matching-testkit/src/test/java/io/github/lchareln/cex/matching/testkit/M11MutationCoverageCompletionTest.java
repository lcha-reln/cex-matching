package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lchareln.cex.matching.cluster.DirectM11MatchingRuntime;
import io.github.lchareln.cex.matching.cluster.M11ApplicationObserver;
import io.github.lchareln.cex.matching.cluster.M11ClientCompletionBoundary;
import io.github.lchareln.cex.matching.cluster.M11ClusterStartupControl;
import io.github.lchareln.cex.matching.cluster.M11CommandRequest;
import io.github.lchareln.cex.matching.cluster.M11FaultPolicy;
import io.github.lchareln.cex.matching.cluster.M11ProtocolException;
import io.github.lchareln.cex.matching.cluster.M11RequestCodec;
import io.github.lchareln.cex.matching.cluster.M11ResponseStatus;
import io.github.lchareln.cex.matching.cluster.M11SingleNodeCluster;
import io.github.lchareln.cex.matching.cluster.M11SingleNodeConfig;
import io.github.lchareln.cex.matching.cluster.M11SnapshotCodec;
import io.github.lchareln.cex.matching.local.M08Command;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
    assertEquals(
        List.of(
            "OFFER_AS_SUCCESS",
            "SESSION_AS_IDENTITY",
            "CORRELATION_AS_IDENTITY",
            "RESPOND_BEFORE_BIND",
            "DROP_IDENTITY_FROM_SNAPSHOT",
            "CORRUPT_SNAPSHOT_TO_GENESIS",
            "REJECT_N_MINUS_ONE",
            "INCLUDE_RUNTIME_METADATA_IN_DIGEST",
            "DOUBLE_WRITE_LOCAL_WAL",
            "ACCEPT_UNSUPPORTED_VERSION"),
        strings(mutants.candidates(), "singleFaultMode"));
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
    assertEquals(
        List.of("REQUEST_CODEC_COMPONENT", "SINGLE_NODE_CLUSTER_LAUNCHER", "HARNESS_REPORT_PARSER"),
        strings(mutants.controls(), "executedPath"));
    assertEquals(
        "IllegalStateException", mutants.controls().get(0).path("failureType").stringValue());
    assertEquals(
        "IllegalStateException", mutants.controls().get(1).path("failureType").stringValue());
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
  void productionComponentsOwnFaultEffectsAndCrashRecoveryPreservesAppliedState() throws Exception {
    M11CommandRequest request = productionRequest();
    M11FaultPolicy none = M11FaultPolicy.none();
    assertEquals(M11FaultPolicy.Mode.NONE, none.mode());
    assertEquals(0, none.activationCount());
    assertFalse(
        new M11ClientCompletionBoundary(none)
            .onIngressOfferAccepted(request, 1)
            .businessComplete());
    DirectM11MatchingRuntime production = new DirectM11MatchingRuntime(none);
    assertEquals(
        M11ResponseStatus.NEW_APPLIED, production.submit(request, "session-a").response().status());
    assertEquals(
        M11ResponseStatus.DUPLICATE_REPLAYED,
        production
            .submit(request.withCorrelationId(new UUID(90, 91)), "session-b")
            .response()
            .status());
    assertEquals(0, none.activationCount());
    assertThrows(
        IllegalArgumentException.class, () -> M11FaultPolicy.single(M11FaultPolicy.Mode.NONE));

    M11FaultPolicy respond = M11FaultPolicy.single(M11FaultPolicy.Mode.RESPOND_BEFORE_BIND);
    DirectM11MatchingRuntime faultyRuntime = new DirectM11MatchingRuntime(respond);
    var genesis = faultyRuntime.stateImage();
    var first = faultyRuntime.submit(request, "session-a");
    assertEquals(1, first.response().applicationSequence().orElseThrow());
    assertFalse(faultyRuntime.hasIdentityBinding(request.commandId()));
    assertTrue(faultyRuntime.hasUnboundApplication());
    DirectM11MatchingRuntime recovered = faultyRuntime.recoverAfterCrash(genesis);
    assertEquals(2, recovered.nextApplicationSequence());
    var retry = recovered.submit(request.withCorrelationId(new UUID(92, 93)), "session-a");
    assertEquals(M11ResponseStatus.NEW_APPLIED, retry.response().status());
    assertEquals(2, retry.response().applicationSequence().orElseThrow());

    M11FaultPolicy drop = M11FaultPolicy.single(M11FaultPolicy.Mode.DROP_IDENTITY_FROM_SNAPSHOT);
    DirectM11MatchingRuntime snapshotRuntime = new DirectM11MatchingRuntime(drop);
    snapshotRuntime.submit(request);
    M11SnapshotCodec snapshotCodec = new M11SnapshotCodec(drop);
    byte[] snapshot = snapshotCodec.encodeCurrent(snapshotRuntime.stateImage());
    DirectM11MatchingRuntime dropped =
        DirectM11MatchingRuntime.restore(snapshotCodec.decodeForRecovery(snapshot).state(), drop);
    assertEquals(2, dropped.nextApplicationSequence());
    assertEquals(0, dropped.identityBindingCount());
    var duplicateBecameNew = dropped.submit(request.withCorrelationId(new UUID(94, 95)));
    assertEquals(M11ResponseStatus.NEW_APPLIED, duplicateBecameNew.response().status());
    assertEquals(2, duplicateBecameNew.response().applicationSequence().orElseThrow());
  }

  @Test
  void infrastructureControlsEnterProductionCodecAndClusterLaunchWhilePolicyHasNoIoPower()
      throws Exception {
    M11CommandRequest request = productionRequest();
    byte[] encoded = new M11RequestCodec().encode(request);
    M11FaultPolicy codecFault =
        M11FaultPolicy.single(M11FaultPolicy.Mode.REQUEST_CODEC_SYSTEM_ERROR);
    IllegalStateException codecFailure =
        assertThrows(
            IllegalStateException.class,
            () -> new M11RequestCodec(codecFault).decodeCanonical(encoded, 1));
    assertTrue(codecFailure.getMessage().contains("request codec component failure"));
    assertEquals(1, codecFault.activationCount());

    Path clusterRoot = Files.createTempDirectory("m11-startup-control-");
    try {
      M11FaultPolicy clusterFault =
          M11FaultPolicy.single(M11FaultPolicy.Mode.CLUSTER_STARTUP_SYSTEM_ERROR);
      IllegalStateException clusterFailure =
          assertThrows(
              IllegalStateException.class,
              () ->
                  M11ClusterStartupControl.launch(
                      M11SingleNodeConfig.defaults(clusterRoot, 1, 41_111),
                      M11ApplicationObserver.NO_OP,
                      clusterFault));
      assertTrue(clusterFailure.getMessage().contains("Cluster startup component failure"));
      assertEquals(1, clusterFault.activationCount());
    } finally {
      M09ScenarioSupport.deleteTree(clusterRoot);
    }

    String policySource =
        Files.readString(
            root()
                .resolve(
                    "matching-cluster-runtime/src/main/java/io/github/lchareln/cex/matching/cluster/M11FaultPolicy.java"));
    assertFalse(policySource.contains("java.nio.file"));
    assertFalse(policySource.contains("FileChannel"));
    assertFalse(policySource.contains("Consumer<byte[]>"));
    String candidateSource =
        Files.readString(
            root()
                .resolve(
                    "matching-testkit/src/main/java/io/github/lchareln/cex/matching/testkit/M11MutantSuite.java"));
    assertFalse(candidateSource.contains("if (mutation =="));
    assertFalse(candidateSource.contains("trace.mutated"));
    assertTrue(candidateSource.contains("new M11RequestCodec(faultPolicy)"));
    assertTrue(candidateSource.contains("M11ClusterStartupControl.launch("));
    String startupControlSource =
        Files.readString(
            root()
                .resolve(
                    "matching-cluster-runtime/src/main/java/io/github/lchareln/cex/matching/cluster/M11ClusterStartupControl.java"));
    assertTrue(startupControlSource.contains("M11SingleNodeCluster.launch("));
    assertTrue(
        java.util.Arrays.stream(M11SingleNodeCluster.class.getDeclaredMethods())
            .filter(method -> method.getName().equals("launch"))
            .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
            .noneMatch(
                method ->
                    java.util.Arrays.asList(method.getParameterTypes())
                        .contains(M11FaultPolicy.class)));
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
  void coveragePublishesAttestedFactsAndOnlyAddsSystemFactAfterExecutedControls() throws Exception {
    List<String> fixedRequired = fixedRequired();
    List<M11FixedSuite.Fact> executedFacts = executedFacts("executed-observation");
    List<String> executedAssertionIds = assertionIds(executedFacts);
    M11Coverage.verifyAssertionLedger(
        fixedRequired, executedFacts, executedFacts, executedAssertionIds);

    List<M11FixedSuite.Fact> completeLedger = new ArrayList<>(executedFacts);
    completeLedger.add(M11Coverage.verifiedSystemControlFact(mutants));
    ObjectNode coverage = M11Coverage.buildReport(M11StartCheckRunner.COVERAGE_IDS, completeLedger);
    String coverageSchema = Files.readString(root().resolve(M11CheckRunner.COVERAGE_SCHEMA_PATH));
    JsonSupport.validate(coverage, coverageSchema, false);
    assertEquals(28, coverage.path("observed").intValue());
    assertEquals("EXECUTED_ASSERTION_WITNESS_LEDGER", coverage.path("source").stringValue());
    assertEquals(
        "M11_EXECUTED_ASSERTION_WITNESS_V1", coverage.path("witnessContract").stringValue());
    assertTrue(coverage.path("systemErrorEvaluatedAfterControls").booleanValue());
    assertTrue(coverage.path("ledgerVerifiedAgainstExecutionReplay").booleanValue());
    assertTrue(coverage.path("obligationEvidenceRecomputed").booleanValue());
    assertEquals(completeLedger.size(), coverage.path("assertionsExecuted").intValue());
    JsonNode system = coverage.path("witnesses").get(27);
    assertEquals("SYSTEM_ERROR_NEVER_PASS", system.path("obligation").stringValue());
    assertEquals("M11_SYSTEM_ERROR_CONTROLS", system.path("scenarios").get(0).stringValue());
    JsonNode systemFact = system.path("facts").get(0);
    assertTrue(systemFact.path("executed").booleanValue());
    assertTrue(systemFact.path("passed").booleanValue());
    assertFalse(systemFact.path("observationSha256").stringValue().isBlank());
    assertFalse(systemFact.path("witnessSha256").stringValue().isBlank());
    ObjectNode incompleteReport = coverage.deepCopy();
    incompleteReport.put("observed", 27);
    assertThrows(
        FixtureSchemaException.class,
        () -> JsonSupport.validate(incompleteReport, coverageSchema, false));

    ArrayNode tamperedControls = mutants.controls();
    ((ObjectNode) tamperedControls.get(0)).put("classification", "STUDENT_FAILURE");
    assertThrows(
        M11SemanticFailure.class,
        () -> M11Coverage.verifiedSystemControlFact(withControls(tamperedControls)));
  }

  @Test
  void coverageRejectsLabelsForgedValuesDuplicatesMissingAndUnexecutedAssertions() {
    List<String> required = fixedRequired();
    List<M11FixedSuite.Fact> expected = executedFacts("executed-observation");
    List<String> executed = assertionIds(expected);

    List<M11FixedSuite.Fact> labelsOnly = executedFacts("copied-label-without-execution");
    assertThrows(
        M11SemanticFailure.class,
        () -> M11Coverage.verifyAssertionLedger(required, expected, labelsOnly, executed));

    List<M11FixedSuite.Fact> forgedValue = new ArrayList<>(expected);
    M11FixedSuite.Fact original = forgedValue.getFirst();
    forgedValue.set(
        0,
        M11FixedSuite.Fact.executed(
            original.obligation(),
            original.scenarioId(),
            original.sourceArtifact(),
            original.assertionId(),
            original.producer(),
            original.assertion(),
            "forged-observed-value"));
    assertThrows(
        M11SemanticFailure.class,
        () -> M11Coverage.verifyAssertionLedger(required, expected, forgedValue, executed));

    List<M11FixedSuite.Fact> forgedAssertionId = new ArrayList<>(expected);
    forgedAssertionId.set(
        0,
        M11FixedSuite.Fact.executed(
            original.obligation(),
            original.scenarioId(),
            original.sourceArtifact(),
            "M11.FORGED." + original.obligation() + ".V1",
            original.producer(),
            original.assertion(),
            original.observedValue()));
    assertThrows(
        M11SemanticFailure.class,
        () -> M11Coverage.verifyAssertionLedger(required, expected, forgedAssertionId, executed));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new M11FixedSuite.Fact(
                original.obligation(),
                original.scenarioId(),
                original.sourceArtifact(),
                original.assertionId(),
                original.producer(),
                original.assertion(),
                "forged-with-stale-digests",
                original.observationSha256(),
                original.witnessSha256()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new M11FixedSuite.Fact(
                original.obligation(),
                original.scenarioId(),
                original.sourceArtifact(),
                original.assertionId(),
                original.producer(),
                original.assertion(),
                original.observedValue(),
                original.observationSha256(),
                "0".repeat(64)));

    List<M11FixedSuite.Fact> missing = new ArrayList<>(expected);
    missing.removeFirst();
    assertThrows(
        M11SemanticFailure.class,
        () -> M11Coverage.verifyAssertionLedger(required, expected, missing, executed));

    List<M11FixedSuite.Fact> duplicate = new ArrayList<>(expected);
    duplicate.add(expected.getFirst());
    assertThrows(
        M11SemanticFailure.class,
        () -> M11Coverage.verifyAssertionLedger(required, expected, duplicate, executed));

    List<String> notExecuted = new ArrayList<>(executed);
    notExecuted.removeFirst();
    assertThrows(
        M11SemanticFailure.class,
        () -> M11Coverage.verifyAssertionLedger(required, expected, expected, notExecuted));
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

  private static List<String> fixedRequired() {
    return M11StartCheckRunner.COVERAGE_IDS.stream()
        .filter(value -> !"SYSTEM_ERROR_NEVER_PASS".equals(value))
        .toList();
  }

  private static List<M11FixedSuite.Fact> executedFacts(String observedPrefix) {
    List<M11FixedSuite.Fact> facts = new ArrayList<>();
    int observation = 0;
    for (String scenario : M11StartCheckRunner.SCENARIO_IDS) {
      for (String obligation : M11FixedSuite.assertedObligations(scenario)) {
        facts.add(
            M11FixedSuite.Fact.executed(
                obligation,
                scenario,
                "fixed-scenarios.json",
                "M11." + scenario + "." + obligation + ".V1",
                "M11FixedSuite#assertScenario(" + scenario + ")",
                "executed obligation-specific assertion " + obligation,
                observedPrefix + '=' + observation));
        observation++;
      }
    }
    return List.copyOf(facts);
  }

  private static List<String> assertionIds(List<M11FixedSuite.Fact> facts) {
    return facts.stream().map(M11FixedSuite.Fact::assertionId).toList();
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

  private static M11CommandRequest productionRequest() throws M11ProtocolException {
    return new M11RequestCodec()
        .create(
            2,
            2,
            new UUID(80, 81),
            "m11-production-fault-test",
            1,
            1,
            1,
            new UUID(82, 83),
            new M08Command.Place(
                "BTC-USDT",
                BigInteger.valueOf(8_001),
                "BUY",
                BigInteger.valueOf(5_000_000),
                BigInteger.ONE,
                "GTC",
                0,
                "NONE",
                Optional.empty()));
  }
}
