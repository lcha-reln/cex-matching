package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

final class M12SemanticJudgeTest {
  @Test
  void loadsTheExactWorkloadAndBuildsRealCanonicalM11Requests() throws Exception {
    M12WorkloadLoader.Workload workload = M12WorkloadLoader.load(root());
    assertEquals(M12StartCheckRunner.WORKLOAD_SHA256, workload.sha256());
    assertEquals(
        M12StartCheckRunner.SCENARIO_IDS,
        workload.scenarios().stream().map(M12WorkloadLoader.Scenario::id).toList());
    assertEquals(M12StartCheckRunner.COVERAGE_IDS, workload.coverageRequirements());

    M12DeterministicCorpus.Corpus first = M12DeterministicCorpus.generate(workload);
    M12DeterministicCorpus.Corpus second = M12DeterministicCorpus.generate(workload);
    assertEquals(first.corpusSha256(), second.corpusSha256());
    assertTrue(first.corpusSha256().matches("[0-9a-f]{64}"));
    assertEquals(first.expectedFinalSemanticDigest(), second.expectedFinalSemanticDigest());
    assertTrue(first.expectedFinalSemanticDigest().matches("[0-9a-f]{64}"));
    assertTrue(first.expectedIdentityResultDigest().matches("[0-9a-f]{64}"));
    assertEquals(66, first.identities().size());
    assertEquals(85, first.attempts().size());
    assertEquals(84, first.ingressAttemptCount());
    assertEquals(66, first.bindings().size());
    assertEquals(66, first.requests().size());

    for (int index = 0; index < first.identities().size(); index++) {
      M12DeterministicCorpus.DurableIdentity identity = first.identities().get(index);
      var request = first.requests().get(index);
      assertEquals(identity.commandId(), request.commandId());
      assertEquals(identity.slot(), request.slot());
      assertEquals(identity.payloadSha256(), request.payloadHash());
      assertArrayEquals(identity.payloadBytes(), request.envelope().commandPayload());
      assertArrayEquals(identity.canonicalBytes(), request.envelopeBytes());
      assertEquals(identity.canonicalSha256(), Hashing.sha256Hex(request.envelopeBytes()));
    }
    assertEquals(
        85,
        new HashSet<>(
                first.attempts().stream()
                    .map(M12DeterministicCorpus.Attempt::correlationId)
                    .toList())
            .size());

    for (M12DeterministicCorpus.Attempt retry :
        first.attempts().stream()
            .filter(attempt -> attempt.retryOfAttemptOrdinal() != null)
            .toList()) {
      M12DeterministicCorpus.Attempt original =
          first.attempts().get(retry.retryOfAttemptOrdinal() - 1);
      assertArrayEquals(original.identity().canonicalBytes(), retry.identity().canonicalBytes());
      assertEquals(original.identity().commandId(), retry.identity().commandId());
      assertEquals(original.identity().slot(), retry.identity().slot());
      assertEquals(original.identity().payloadSha256(), retry.identity().payloadSha256());
      assertNotEquals(original.correlationId(), retry.correlationId());
    }

    Path temporary = Files.createTempDirectory("m12-tampered-workload-");
    try {
      Path workloadPath = temporary.resolve(M12StartCheckRunner.WORKLOAD_PATH);
      Path schemaPath = temporary.resolve(M12StartCheckRunner.WORKLOAD_SCHEMA_PATH);
      Files.createDirectories(workloadPath.getParent());
      Files.createDirectories(schemaPath.getParent());
      Files.writeString(
          workloadPath,
          Files.readString(root().resolve(M12StartCheckRunner.WORKLOAD_PATH))
              .replace("\"seed\": \"6120\"", "\"seed\": \"6121\""));
      Files.copy(root().resolve(M12StartCheckRunner.WORKLOAD_SCHEMA_PATH), schemaPath);
      assertThrows(IllegalStateException.class, () -> M12WorkloadLoader.load(temporary));
    } finally {
      M09ScenarioSupport.deleteTree(temporary);
    }
  }

  @Test
  void executesTwentyFiveAssertionsButDisclosesModelControlScope() {
    M12SemanticJudge.Judgement judgement =
        new M12SemanticJudge().runDeterministicModelControl(root());
    M12CoverageLedger.Result coverage = judgement.coverage();
    assertEquals(25, coverage.facts().size());
    assertEquals(25, coverage.report().path("assertionsExecuted").intValue());
    assertEquals(25, coverage.report().path("observed").intValue());
    assertTrue(coverage.report().path("allAssertionsPassed").booleanValue());
    assertFalse(coverage.qualifiesAsRealClusterEvidence());
    assertFalse(coverage.report().path("clusterEvidenceQualified").booleanValue());
    assertEquals(
        "DETERMINISTIC_MODEL_CONTROL_ONLY_NOT_REAL_CLUSTER_EVIDENCE",
        coverage.report().path("scopeDisclosure").stringValue());
    assertEquals(
        M12StartCheckRunner.COVERAGE_IDS,
        strings(coverage.report().path("witnesses"), "obligation"));
  }

  @Test
  void forgedObligationLabelsCannotPassFreshAssertionReplay() {
    M12SemanticJudge.Judgement judgement =
        new M12SemanticJudge().runDeterministicModelControl(root());
    ObjectNode forged = judgement.coverage().report();
    ((ObjectNode) forged.path("witnesses").get(0)).put("obligation", "SINGLE_INITIAL_LEADER");
    assertThrows(
        M12SemanticFailure.class,
        () ->
            M12CoverageLedger.verifySerialized(
                judgement.prepared().workload(),
                judgement.trace(),
                judgement.prepared().mutants().controls(),
                forged));

    ObjectNode forgedFact = judgement.coverage().report();
    ((ObjectNode) forgedFact.path("factLedger").get(0)).put("obligation", "SINGLE_INITIAL_LEADER");
    assertThrows(
        M12SemanticFailure.class,
        () ->
            M12CoverageLedger.verifySerialized(
                judgement.prepared().workload(),
                judgement.trace(),
                judgement.prepared().mutants().controls(),
                forgedFact));
  }

  @Test
  void killsAllFrozenMutantsAndNeverCountsSystemErrors() {
    M12SemanticJudge.Prepared prepared = new M12SemanticJudge().prepare(root());
    M12MutantSuite.Result mutants = prepared.mutants();
    assertEquals(8, mutants.killed());
    assertEquals(
        M12StartCheckRunner.MUTANT_IDS, strings(mutants.report().path("candidates"), "id"));
    assertEquals(
        M12StartCheckRunner.SYSTEM_ERROR_IDS, strings(mutants.report().path("controls"), "id"));
    mutants
        .report()
        .path("candidates")
        .forEach(
            candidate -> {
              assertEquals("PASS", candidate.path("productionClassification").stringValue());
              assertEquals("STUDENT_FAILURE", candidate.path("classification").stringValue());
              assertEquals(1, candidate.path("actualMutationActions").intValue());
              assertFalse(candidate.path("systemErrorCountedAsKill").booleanValue());
              assertTrue(candidate.path("semanticModelOnly").booleanValue());
              assertFalse(candidate.path("eligibleAsClusterEvidence").booleanValue());
            });
    mutants
        .report()
        .path("controls")
        .forEach(
            control -> {
              assertEquals("SYSTEM_ERROR", control.path("classification").stringValue());
              assertFalse(control.path("countedAsKill").booleanValue());
            });
    assertEquals(8, mutants.counterexamples().path("witnesses").size());
    mutants
        .counterexamples()
        .path("witnesses")
        .forEach(
            witness -> {
              assertEquals(1, witness.path("minimalActions").intValue());
              assertEquals(1, witness.path("steps").size());
              assertTrue(witness.path("oneMinimal").booleanValue());
              assertTrue(witness.path("strictFreshReplay").booleanValue());
            });
    assertEquals(8, mutants.replayReport().path("replayed").intValue());
    assertTrue(mutants.replayReport().path("fingerprintsExact").booleanValue());
    assertTrue(mutants.replayReport().path("oneMinimal").booleanValue());
    assertFalse(mutants.replayReport().path("systemErrorCountedAsKill").booleanValue());
    assertArrayEquals(
        JsonSupport.prettyBytes(mutants.counterexamples()), mutants.counterexampleBytes());
  }

  @Test
  void leadershipTermDoesNotEnterTheSemanticDigest() {
    M12WorkloadLoader.Workload workload = M12WorkloadLoader.load(root());
    M12DeterministicCorpus.Corpus corpus = M12DeterministicCorpus.generate(workload);
    M12ExecutionTrace original = M12ExecutionTrace.deterministicModelControl(corpus);
    M12ExecutionTrace differentTerms = original.withRuntimeTerms(7, 12);
    assertEquals(
        M12HistoryJudge.semanticDigest(original), M12HistoryJudge.semanticDigest(differentTerms));
    assertEquals(corpus.expectedFinalSemanticDigest(), M12HistoryJudge.semanticDigest(original));
  }

  @Test
  void noQuorumUnknownMayConvergeThroughDuplicateReplay() {
    M12WorkloadLoader.Workload workload = M12WorkloadLoader.load(root());
    M12DeterministicCorpus.Corpus corpus = M12DeterministicCorpus.generate(workload);
    M12ExecutionTrace trace = M12ExecutionTrace.deterministicModelControl(corpus);
    M12DeterministicCorpus.Binding binding = corpus.bindings().getLast();
    List<M12DeterministicCorpus.Attempt> attempts = new ArrayList<>(trace.attempts());
    int unknownIndex = indexOf(attempts, "NO_QUORUM_UNKNOWN_1");
    int retryIndex = indexOf(attempts, "RESTORE_QUORUM_AND_SAME_IDENTITY_RETRY");
    M12DeterministicCorpus.Attempt unknown = attempts.get(unknownIndex);
    attempts.set(
        unknownIndex,
        unknown.withOutcomeAndResponse(
            M12DeterministicCorpus.ClientOutcome.UNKNOWN,
            false,
            null,
            null,
            binding.applicationSequence(),
            binding.resultDigest(),
            true,
            false));
    M12DeterministicCorpus.Attempt retry = attempts.get(retryIndex);
    attempts.set(
        retryIndex,
        retry.withOutcomeAndResponse(
            M12DeterministicCorpus.ClientOutcome.ACKNOWLEDGED,
            true,
            retry.correlationId(),
            M12DeterministicCorpus.ResponseStatus.DUPLICATE_REPLAYED,
            binding.applicationSequence(),
            binding.resultDigest(),
            false,
            false));
    M12HistoryJudge.Inspection inspection =
        new M12HistoryJudge().inspect(workload, trace.withAttempts(attempts));
    assertEquals(18, inspection.retries().duplicateReplayCount());
    assertEquals("DUPLICATE_REPLAYED", inspection.retries().noQuorumConvergedStatus());
  }

  private static int indexOf(List<M12DeterministicCorpus.Attempt> attempts, String phase) {
    for (int index = 0; index < attempts.size(); index++) {
      if (phase.equals(attempts.get(index).phase())) {
        return index;
      }
    }
    throw new AssertionError("missing phase " + phase);
  }

  private static List<String> strings(JsonNode array, String field) {
    List<String> values = new ArrayList<>();
    array.forEach(node -> values.add(node.path(field).stringValue()));
    return List.copyOf(values);
  }

  private static Path root() {
    return Path.of(System.getProperty("matching.repositoryRoot", ".")).toAbsolutePath().normalize();
  }
}
