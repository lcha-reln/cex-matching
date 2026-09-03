package io.github.lchareln.cex.matching.testkit;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Recomputes coverage from executed assertion witnesses; obligation labels alone carry no weight.
 */
final class M12CoverageLedger {
  static final String WITNESS_CONTRACT = "M12_EXECUTED_ASSERTION_WITNESS_V1";

  Result run(
      M12WorkloadLoader.Workload workload,
      M12ExecutionTrace trace,
      List<SystemControlObservation> systemControls) {
    M12HistoryJudge.Inspection inspection = new M12HistoryJudge().inspect(workload, trace);
    List<Fact> facts = new ArrayList<>();
    for (M12HistoryJudge.AssertionObservation observation : inspection.observations()) {
      facts.add(
          Fact.executed(
              observation.obligation(),
              observation.scenarioId(),
              trace.sourceArtifact(),
              observation.assertionId(),
              observation.producer(),
              observation.assertion(),
              observation.observedValue()));
    }
    facts.add(systemControlFact(workload, systemControls));
    verifyFacts(workload, facts);
    ObjectNode report = report(workload, trace, inspection, facts);
    verifySerialized(workload, trace, systemControls, report);
    return new Result(
        report,
        List.copyOf(facts),
        ledgerSha256(facts),
        inspection.qualifiesAsRealClusterEvidence());
  }

  static void verifySerialized(
      M12WorkloadLoader.Workload workload,
      M12ExecutionTrace trace,
      List<SystemControlObservation> controls,
      JsonNode presented) {
    M12HistoryJudge.Inspection replay = new M12HistoryJudge().inspect(workload, trace);
    List<Fact> expected = new ArrayList<>();
    replay
        .observations()
        .forEach(
            observation ->
                expected.add(
                    Fact.executed(
                        observation.obligation(),
                        observation.scenarioId(),
                        trace.sourceArtifact(),
                        observation.assertionId(),
                        observation.producer(),
                        observation.assertion(),
                        observation.observedValue())));
    expected.add(systemControlFact(workload, controls));
    verifyFacts(workload, expected);

    require(
        "matching.m12.coverage.v1".equals(text(presented, "schemaVersion")),
        "coverage schema version changed");
    require("PASS".equals(text(presented, "status")), "coverage status is not PASS");
    require(
        trace.scope().name().equals(text(presented, "executionScope")), "execution scope changed");
    require(
        replay.qualifiesAsRealClusterEvidence()
            == presented.path("clusterEvidenceQualified").booleanValue(),
        "cluster evidence qualification changed");
    require(
        workload.coverageRequirements().size() == presented.path("required").intValue(),
        "required count changed");
    require(
        workload.coverageRequirements().size() == presented.path("observed").intValue(),
        "observed count changed");
    require(
        expected.size() == presented.path("assertionsExecuted").intValue(),
        "assertion count changed");
    require(
        ledgerSha256(expected).equals(text(presented, "ledgerSha256")), "ledger digest changed");

    List<Fact> serialized = new ArrayList<>();
    presented.path("factLedger").forEach(node -> serialized.add(Fact.read(node)));
    require(serialized.equals(expected), "presented facts differ from executed assertion replay");
    List<String> labels = new ArrayList<>();
    presented.path("witnesses").forEach(node -> labels.add(text(node, "obligation")));
    require(labels.equals(workload.coverageRequirements()), "coverage obligation order changed");
    for (int index = 0; index < labels.size(); index++) {
      JsonNode witness = presented.path("witnesses").get(index);
      Fact fact = expected.get(index);
      require(text(witness, "assertionId").equals(fact.assertionId()), "assertion ID changed");
      require(
          text(witness, "witnessSha256").equals(fact.witnessSha256()), "witness digest changed");
    }
  }

  private static ObjectNode report(
      M12WorkloadLoader.Workload workload,
      M12ExecutionTrace trace,
      M12HistoryJudge.Inspection inspection,
      List<Fact> facts) {
    ObjectNode report = JsonSupport.MAPPER.createObjectNode();
    report.put("schemaVersion", "matching.m12.coverage.v1");
    report.put("status", "PASS");
    report.put("executionScope", trace.scope().name());
    report.put("clusterEvidenceQualified", inspection.qualifiesAsRealClusterEvidence());
    report.put(
        "scopeDisclosure",
        inspection.qualifiesAsRealClusterEvidence()
            ? "ASSERTIONS_RECOMPUTED_FROM_REAL_AERON_CHILD_PROCESS_TRACE"
            : "DETERMINISTIC_MODEL_CONTROL_ONLY_NOT_REAL_CLUSTER_EVIDENCE");
    report.put("source", "EXECUTED_ASSERTION_WITNESS_LEDGER");
    report.put("witnessContract", WITNESS_CONTRACT);
    report.put("labelsAcceptedWithoutWitness", false);
    report.put("required", workload.coverageRequirements().size());
    report.put("observed", facts.size());
    report.put("assertionsExecuted", facts.size());
    report.put("allAssertionsPassed", true);
    report.put("systemErrorsEvaluatedAfterControls", true);
    report.put("ledgerVerifiedAgainstExecutionReplay", true);
    report.put("semanticDigest", inspection.semanticDigest());
    report.put("ledgerSha256", ledgerSha256(facts));

    ArrayNode ledger = report.putArray("factLedger");
    facts.forEach(fact -> fact.write(ledger.addObject()));
    ArrayNode witnesses = report.putArray("witnesses");
    for (int index = 0; index < workload.coverageRequirements().size(); index++) {
      String obligation = workload.coverageRequirements().get(index);
      Fact fact = facts.get(index);
      require(obligation.equals(fact.obligation()), "fact order changed");
      ObjectNode witness = witnesses.addObject();
      witness.put("obligation", obligation);
      witness.put("scenarioId", fact.scenarioId());
      witness.put("assertionId", fact.assertionId());
      witness.put("witnessSha256", fact.witnessSha256());
      witness.put("executed", true);
      witness.put("passed", true);
    }
    return report;
  }

  private static Fact systemControlFact(
      M12WorkloadLoader.Workload workload, List<SystemControlObservation> controls) {
    require(controls.size() == 3, "system control count changed");
    StringBuilder observed = new StringBuilder("controls=3");
    Set<String> ids = new HashSet<>();
    for (int index = 0; index < controls.size(); index++) {
      SystemControlObservation control = controls.get(index);
      String expected = workload.systemErrorControls().get(index);
      require(expected.equals(control.id()), "system control order changed");
      require(ids.add(control.id()), "duplicate system control");
      require("SYSTEM_ERROR".equals(control.classification()), "control is not SYSTEM_ERROR");
      require(!control.countedAsKill(), "SYSTEM_ERROR counted as mutant kill");
      require(!control.executedPath().isBlank(), "system control path is blank");
      require(!control.failureType().isBlank(), "system control failure type is blank");
      observed
          .append(';')
          .append(control.id())
          .append("=SYSTEM_ERROR,countedAsKill=false,path=")
          .append(control.executedPath())
          .append(",failureType=")
          .append(control.failureType());
    }
    String scenario =
        workload.scenarios().stream()
            .filter(value -> value.proofObligations().contains("SYSTEM_ERROR_NEVER_SEMANTIC"))
            .map(M12WorkloadLoader.Scenario::id)
            .findFirst()
            .orElseThrow();
    return Fact.executed(
        "SYSTEM_ERROR_NEVER_SEMANTIC",
        scenario,
        "m12-system-controls.json",
        "M12." + scenario + ".SYSTEM_ERROR_NEVER_SEMANTIC.V1",
        "M12CoverageLedger#assertSystemControls(SYSTEM_ERROR_NEVER_SEMANTIC)",
        "all three executable infrastructure controls classify as SYSTEM_ERROR and none counts as a"
            + " semantic mutant kill",
        observed.toString());
  }

  private static void verifyFacts(M12WorkloadLoader.Workload workload, List<Fact> facts) {
    require(facts.size() == 25, "coverage fact count changed");
    require(
        facts.stream().map(Fact::obligation).toList().equals(workload.coverageRequirements()),
        "coverage facts do not match frozen obligation order");
    require(
        new LinkedHashSet<>(facts.stream().map(Fact::assertionId).toList()).size() == facts.size(),
        "duplicate assertion ID");
    require(
        new LinkedHashSet<>(facts.stream().map(Fact::witnessSha256).toList()).size()
            == facts.size(),
        "duplicate witness digest");
    for (Fact fact : facts) {
      M12WorkloadLoader.Scenario scenario = workload.scenariosById().get(fact.scenarioId());
      require(scenario != null, "unknown fact scenario");
      require(
          scenario.proofObligations().contains(fact.obligation()),
          "scenario is not allowed to witness obligation");
      String expectedAssertion = "M12." + fact.scenarioId() + '.' + fact.obligation() + ".V1";
      require(expectedAssertion.equals(fact.assertionId()), "assertion ID is not origin-bound");
      if ("SYSTEM_ERROR_NEVER_SEMANTIC".equals(fact.obligation())) {
        require(
            "M12CoverageLedger#assertSystemControls(SYSTEM_ERROR_NEVER_SEMANTIC)"
                .equals(fact.producer()),
            "system fact producer changed");
      } else {
        require(
            ("M12HistoryJudge#assertObligation(" + fact.obligation() + ')').equals(fact.producer()),
            "history fact producer changed");
      }
    }
  }

  static String ledgerSha256(List<Fact> facts) {
    StringBuilder canonical = new StringBuilder("M12-EXECUTED-ASSERTION-LEDGER-V1\n");
    facts.forEach(fact -> append(canonical, "witness", fact.witnessSha256()));
    return Hashing.sha256Hex(canonical.toString().getBytes(StandardCharsets.UTF_8));
  }

  private static String observationDigest(
      String assertionId, String sourceArtifact, String observedValue) {
    StringBuilder canonical = new StringBuilder("M12-OBSERVATION-V1\n");
    append(canonical, "assertionId", assertionId);
    append(canonical, "sourceArtifact", sourceArtifact);
    append(canonical, "observedValue", observedValue);
    return Hashing.sha256Hex(canonical.toString().getBytes(StandardCharsets.UTF_8));
  }

  private static String witnessDigest(
      String obligation,
      String scenarioId,
      String sourceArtifact,
      String assertionId,
      String producer,
      String assertion,
      String observedValue,
      String observationSha256) {
    StringBuilder canonical = new StringBuilder("M12-ASSERTION-WITNESS-V1\n");
    append(canonical, "obligation", obligation);
    append(canonical, "scenarioId", scenarioId);
    append(canonical, "sourceArtifact", sourceArtifact);
    append(canonical, "assertionId", assertionId);
    append(canonical, "producer", producer);
    append(canonical, "assertion", assertion);
    append(canonical, "observedValue", observedValue);
    append(canonical, "observationSha256", observationSha256);
    return Hashing.sha256Hex(canonical.toString().getBytes(StandardCharsets.UTF_8));
  }

  private static void append(StringBuilder target, String name, String value) {
    target
        .append(name)
        .append(':')
        .append(value.getBytes(StandardCharsets.UTF_8).length)
        .append(':')
        .append(value)
        .append('\n');
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.path(field);
    require(value.isString(), "missing string " + field);
    return value.stringValue();
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new M12SemanticFailure("COVERAGE_WITNESS_INVALID", "M12 coverage: " + message);
    }
  }

  record SystemControlObservation(
      String id,
      String classification,
      boolean countedAsKill,
      String executedPath,
      String failureType) {
    SystemControlObservation {
      Objects.requireNonNull(id);
      Objects.requireNonNull(classification);
      Objects.requireNonNull(executedPath);
      Objects.requireNonNull(failureType);
    }
  }

  record Fact(
      String obligation,
      String scenarioId,
      String sourceArtifact,
      String assertionId,
      String producer,
      String assertion,
      String observedValue,
      String observationSha256,
      String witnessSha256) {
    Fact {
      Objects.requireNonNull(obligation);
      Objects.requireNonNull(scenarioId);
      Objects.requireNonNull(sourceArtifact);
      Objects.requireNonNull(assertionId);
      Objects.requireNonNull(producer);
      Objects.requireNonNull(assertion);
      Objects.requireNonNull(observedValue);
      Objects.requireNonNull(observationSha256);
      Objects.requireNonNull(witnessSha256);
      require(
          observationSha256.equals(observationDigest(assertionId, sourceArtifact, observedValue)),
          "observation digest does not bind value");
      require(
          witnessSha256.equals(
              witnessDigest(
                  obligation,
                  scenarioId,
                  sourceArtifact,
                  assertionId,
                  producer,
                  assertion,
                  observedValue,
                  observationSha256)),
          "witness digest does not bind fact");
    }

    static Fact executed(
        String obligation,
        String scenarioId,
        String sourceArtifact,
        String assertionId,
        String producer,
        String assertion,
        String observedValue) {
      String observation = observationDigest(assertionId, sourceArtifact, observedValue);
      return new Fact(
          obligation,
          scenarioId,
          sourceArtifact,
          assertionId,
          producer,
          assertion,
          observedValue,
          observation,
          witnessDigest(
              obligation,
              scenarioId,
              sourceArtifact,
              assertionId,
              producer,
              assertion,
              observedValue,
              observation));
    }

    static Fact read(JsonNode node) {
      require(node.path("executed").booleanValue(), "fact was not executed");
      require(node.path("passed").booleanValue(), "fact did not pass");
      return new Fact(
          text(node, "obligation"),
          text(node, "scenarioId"),
          text(node, "sourceArtifact"),
          text(node, "assertionId"),
          text(node, "producer"),
          text(node, "assertion"),
          text(node, "observedValue"),
          text(node, "observationSha256"),
          text(node, "witnessSha256"));
    }

    void write(ObjectNode target) {
      target.put("obligation", obligation);
      target.put("scenarioId", scenarioId);
      target.put("sourceArtifact", sourceArtifact);
      target.put("assertionId", assertionId);
      target.put("producer", producer);
      target.put("assertion", assertion);
      target.put("observedValue", observedValue);
      target.put("observationSha256", observationSha256);
      target.put("witnessSha256", witnessSha256);
      target.put("executed", true);
      target.put("passed", true);
    }
  }

  record Result(
      ObjectNode report,
      List<Fact> facts,
      String ledgerSha256,
      boolean qualifiesAsRealClusterEvidence) {
    Result {
      report = report.deepCopy();
      facts = List.copyOf(facts);
    }

    @Override
    public ObjectNode report() {
      return report.deepCopy();
    }
  }
}
