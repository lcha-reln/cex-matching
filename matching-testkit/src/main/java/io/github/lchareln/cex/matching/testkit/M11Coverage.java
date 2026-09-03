package io.github.lchareln.cex.matching.testkit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Aggregates only facts emitted after executable assertions and system controls have run. */
final class M11Coverage {
  ObjectNode run(
      Path repositoryRoot,
      M11FixedSuite.Result fixed,
      M11ProtocolSuite.Result protocol,
      M11GeneratedSuite.Result generated,
      ObjectNode architecture,
      M11MutantSuite.Result mutants) {
    JsonNode workload =
        JsonSupport.parse(read(repositoryRoot.resolve(M11StartCheckRunner.WORKLOAD_PATH)));
    List<String> required = strings(workload.path("coverageRequirements"));
    require(required.equals(M11StartCheckRunner.COVERAGE_IDS), "M11 obligation order changed");
    require(required.size() == 28, "M11 obligation count changed");

    M11FixedSuite.AssertionReplay replay =
        M11FixedSuite.replayAssertions(repositoryRoot, protocol, generated, architecture);
    verifyFixedResult(fixed, replay);
    List<String> fixedRequired =
        required.stream().filter(value -> !"SYSTEM_ERROR_NEVER_PASS".equals(value)).toList();
    verifyAssertionLedger(
        fixedRequired, replay.facts(), fixed.facts(), fixed.executedAssertionIds());

    List<M11FixedSuite.Fact> ledger = new ArrayList<>(fixed.facts());
    require(
        ledger.stream().noneMatch(fact -> "SYSTEM_ERROR_NEVER_PASS".equals(fact.obligation())),
        "fixed scenarios cannot pre-claim the system-error obligation");
    ledger.add(verifiedSystemControlFact(mutants));

    return buildReport(required, ledger);
  }

  /** Verifies the serialized report and the in-memory facts against a fresh assertion replay. */
  private static void verifyFixedResult(
      M11FixedSuite.Result fixed, M11FixedSuite.AssertionReplay replay) {
    require(fixed.passed() == 22, "fixed suite did not pass all 22 scenarios");
    require(fixed.facts().equals(replay.facts()), "fixed facts differ from fresh assertion replay");
    require(
        fixed.executedAssertionIds().equals(replay.executedAssertionIds()),
        "fixed executed assertion IDs differ from fresh replay");
    String replayDigest = M11FixedSuite.ledgerSha256(replay.facts());
    require(fixed.ledgerSha256().equals(replayDigest), "fixed ledger digest differs from replay");

    JsonNode report = fixed.report();
    require(
        M11CheckRunner.PASS.equals(report.path("status").stringValue()), "fixed report not PASS");
    require(report.path("scenarios").intValue() == 22, "fixed scenario count changed");
    require(report.path("passed").intValue() == 22, "fixed passed count changed");
    require(
        M11FixedSuite.WITNESS_CONTRACT.equals(report.path("witnessContract").stringValue()),
        "fixed witness contract changed");
    require(
        "EXECUTED_ASSERTION_WITNESS_LEDGER".equals(report.path("factSource").stringValue()),
        "fixed fact source changed");
    require(report.path("allAssertionsPassed").booleanValue(), "fixed assertions not all PASS");
    require(
        report.path("assertionFacts").intValue() == replay.facts().size(),
        "fixed assertion fact count changed");
    require(
        report.path("assertionsExecuted").intValue() == replay.executedAssertionIds().size(),
        "fixed assertion execution count changed");
    require(
        replayDigest.equals(report.path("ledgerSha256").stringValue()),
        "fixed serialized ledger digest changed");
    require(
        strings(report.path("executedAssertionIds")).equals(replay.executedAssertionIds()),
        "fixed serialized execution IDs changed");
    List<M11FixedSuite.Fact> serialized = new ArrayList<>();
    report.path("factLedger").forEach(node -> serialized.add(M11FixedSuite.Fact.read(node)));
    require(serialized.equals(replay.facts()), "fixed serialized facts differ from replay");
  }

  /**
   * Compares every presented witness with an expected witness produced by executed assertions.
   * Matching obligation labels alone is intentionally insufficient.
   */
  static void verifyAssertionLedger(
      List<String> required,
      List<M11FixedSuite.Fact> expected,
      List<M11FixedSuite.Fact> presented,
      List<String> executedAssertionIds) {
    require(new LinkedHashSet<>(required).size() == required.size(), "duplicate obligation ID");
    require(!expected.isEmpty(), "expected assertion ledger is empty");
    require(expected.equals(presented), "presented facts differ from executed assertion witnesses");

    List<String> expectedAssertionIds =
        expected.stream().map(M11FixedSuite.Fact::assertionId).toList();
    require(
        new LinkedHashSet<>(expectedAssertionIds).size() == expectedAssertionIds.size(),
        "duplicate assertion ID");
    require(
        new LinkedHashSet<>(executedAssertionIds).size() == executedAssertionIds.size(),
        "duplicate executed assertion ID");
    require(
        executedAssertionIds.equals(expectedAssertionIds),
        "an assertion witness was published without an executed assertion");

    Set<String> witnessDigests = new HashSet<>();
    Map<String, List<M11FixedSuite.Fact>> expectedByObligation = group(expected, required);
    Map<String, List<M11FixedSuite.Fact>> presentedByObligation = group(presented, required);
    require(
        expectedByObligation.equals(presentedByObligation),
        "obligation-specific evidence differs from executed assertions");
    for (M11FixedSuite.Fact fact : presented) {
      verifyFactOrigin(fact);
      require(witnessDigests.add(fact.witnessSha256()), "duplicate assertion witness digest");
    }
  }

  private static Map<String, List<M11FixedSuite.Fact>> group(
      List<M11FixedSuite.Fact> facts, List<String> required) {
    Set<String> allowed = Set.copyOf(required);
    Map<String, List<M11FixedSuite.Fact>> grouped = new LinkedHashMap<>();
    required.forEach(obligation -> grouped.put(obligation, new ArrayList<>()));
    for (M11FixedSuite.Fact fact : facts) {
      require(allowed.contains(fact.obligation()), "unknown assertion fact " + fact.obligation());
      grouped.get(fact.obligation()).add(fact);
    }
    grouped.forEach(
        (obligation, factsForObligation) ->
            require(!factsForObligation.isEmpty(), "missing evidence for " + obligation));
    return grouped;
  }

  static ObjectNode buildReport(List<String> required, List<M11FixedSuite.Fact> ledger) {

    Set<String> allowed = Set.copyOf(required);
    Map<String, List<M11FixedSuite.Fact>> witnesses = new LinkedHashMap<>();
    Set<String> assertionIds = new HashSet<>();
    Set<String> witnessDigests = new HashSet<>();
    for (M11FixedSuite.Fact fact : ledger) {
      require(allowed.contains(fact.obligation()), "unknown assertion fact " + fact.obligation());
      verifyFactOrigin(fact);
      require(assertionIds.add(fact.assertionId()), "duplicate assertion ID in coverage ledger");
      require(
          witnessDigests.add(fact.witnessSha256()),
          "duplicate assertion witness in coverage ledger");
      witnesses.computeIfAbsent(fact.obligation(), ignored -> new ArrayList<>()).add(fact);
    }
    require(witnesses.keySet().containsAll(required), "M11 coverage is missing assertion facts");
    require(witnesses.keySet().size() == required.size(), "M11 coverage has extra facts");

    ObjectNode report = JsonSupport.MAPPER.createObjectNode();
    report.put("schemaVersion", "matching.m11.coverage.v2");
    report.put("status", M11CheckRunner.PASS);
    report.put("required", required.size());
    report.put("observed", witnesses.size());
    report.put("allWitnessed", true);
    report.put("source", "EXECUTED_ASSERTION_WITNESS_LEDGER");
    report.put("witnessContract", M11FixedSuite.WITNESS_CONTRACT);
    report.put("systemErrorEvaluatedAfterControls", true);
    report.put("assertionsExecuted", assertionIds.size());
    report.put("allAssertionsPassed", true);
    report.put("ledgerVerifiedAgainstExecutionReplay", true);
    report.put("obligationEvidenceRecomputed", true);
    report.put("duplicateAssertionIdsRejected", true);
    report.put("factCount", ledger.size());
    report.put("ledgerSha256", M11FixedSuite.ledgerSha256(ledger));
    ArrayNode entries = report.putArray("witnesses");
    for (String obligation : required) {
      ObjectNode entry = entries.addObject();
      entry.put("obligation", obligation);
      List<M11FixedSuite.Fact> obligationFacts = witnesses.get(obligation);
      entry.put("factCount", obligationFacts.size());
      ArrayNode scenarios = entry.putArray("scenarios");
      obligationFacts.stream()
          .map(M11FixedSuite.Fact::scenarioId)
          .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))
          .forEach(scenarios::add);
      ArrayNode assertionIdNodes = entry.putArray("assertionIds");
      obligationFacts.forEach(fact -> assertionIdNodes.add(fact.assertionId()));
      ArrayNode digestNodes = entry.putArray("witnessSha256s");
      obligationFacts.forEach(fact -> digestNodes.add(fact.witnessSha256()));
      ArrayNode facts = entry.putArray("facts");
      obligationFacts.forEach(fact -> fact.write(facts.addObject()));
    }
    return report;
  }

  private static void verifyFactOrigin(M11FixedSuite.Fact fact) {
    require(
        fact.assertionId().equals("M11." + fact.scenarioId() + "." + fact.obligation() + ".V1"),
        "assertion ID is not bound to scenario and obligation");
    if ("SYSTEM_ERROR_NEVER_PASS".equals(fact.obligation())) {
      require(
          "M11_SYSTEM_ERROR_CONTROLS".equals(fact.scenarioId()),
          "system-error fact has the wrong scenario");
      require(
          "M11Coverage#verifiedSystemControlFact(M11_SYSTEM_ERROR_CONTROLS)"
              .equals(fact.producer()),
          "system-error fact was not produced by executed controls");
      return;
    }
    require(
        M11StartCheckRunner.SCENARIO_IDS.contains(fact.scenarioId()),
        "fact has an unknown fixed scenario");
    require(
        M11FixedSuite.assertedObligations(fact.scenarioId()).contains(fact.obligation()),
        "scenario cannot witness this obligation");
    require(
        fact.producer().equals("M11FixedSuite#assertScenario(" + fact.scenarioId() + ")"),
        "fact producer is not the executed fixed-suite assertion");
  }

  static M11FixedSuite.Fact verifiedSystemControlFact(M11MutantSuite.Result mutants) {
    require(mutants.killed() == 10, "M11 semantic mutants did not all fail");
    require(mutants.actualMutationActions() > 0, "M11 mutants performed no actual fault action");
    JsonNode controls = mutants.controls();
    require(controls.size() == 3, "M11 system control count changed");
    Set<String> unique = new HashSet<>();
    StringBuilder observed = new StringBuilder("controls=3");
    for (int index = 0; index < controls.size(); index++) {
      JsonNode control = controls.get(index);
      String expected = M11StartCheckRunner.SYSTEM_ERROR_IDS.get(index);
      require(expected.equals(control.path("id").stringValue()), "system control order changed");
      require(unique.add(expected), "duplicate system control " + expected);
      require(
          M11CheckRunner.SYSTEM_ERROR.equals(control.path("classification").stringValue()),
          "system control was not SYSTEM_ERROR " + expected);
      require(!control.path("countedAsKill").booleanValue(), "system control counted as kill");
      require(control.path("executedPath").isString(), "system control path was not executed");
      require(control.path("failureType").isString(), "system control failure type is missing");
      require(
          !control.path("executedPath").stringValue().isBlank(), "system control path is blank");
      require(
          !control.path("failureType").stringValue().isBlank(), "system control failure is blank");
      observed
          .append(';')
          .append(expected)
          .append("=SYSTEM_ERROR,countedAsKill=false,path=")
          .append(control.path("executedPath").stringValue())
          .append(",failureType=")
          .append(control.path("failureType").stringValue());
    }
    return M11FixedSuite.Fact.executed(
        "SYSTEM_ERROR_NEVER_PASS",
        "M11_SYSTEM_ERROR_CONTROLS",
        "mutants.json",
        "M11.M11_SYSTEM_ERROR_CONTROLS.SYSTEM_ERROR_NEVER_PASS.V1",
        "M11Coverage#verifiedSystemControlFact(M11_SYSTEM_ERROR_CONTROLS)",
        "all three executable infrastructure controls were classified SYSTEM_ERROR by the shared classifier and none counted as a semantic kill",
        observed.toString());
  }

  private static List<String> strings(JsonNode values) {
    List<String> result = new ArrayList<>();
    values.forEach(value -> result.add(value.stringValue()));
    return List.copyOf(result);
  }

  private static byte[] read(Path path) {
    try {
      return Files.readAllBytes(path);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot read " + path, failure);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new M11SemanticFailure(message);
    }
  }
}
