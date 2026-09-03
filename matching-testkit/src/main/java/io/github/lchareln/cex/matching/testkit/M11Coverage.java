package io.github.lchareln.cex.matching.testkit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
  ObjectNode run(Path repositoryRoot, M11FixedSuite.Result fixed, M11MutantSuite.Result mutants) {
    JsonNode workload =
        JsonSupport.parse(read(repositoryRoot.resolve(M11StartCheckRunner.WORKLOAD_PATH)));
    List<String> required = strings(workload.path("coverageRequirements"));
    require(required.equals(M11StartCheckRunner.COVERAGE_IDS), "M11 obligation order changed");
    require(required.size() == 28, "M11 obligation count changed");

    List<M11FixedSuite.Fact> ledger = new ArrayList<>(fixed.facts());
    require(
        ledger.stream().noneMatch(fact -> "SYSTEM_ERROR_NEVER_PASS".equals(fact.obligation())),
        "fixed scenarios cannot pre-claim the system-error obligation");
    verifyControls(mutants);
    ledger.add(
        new M11FixedSuite.Fact(
            "SYSTEM_ERROR_NEVER_PASS",
            "M11_SYSTEM_ERROR_CONTROLS",
            "mutants.json",
            "all three executable infrastructure controls were classified SYSTEM_ERROR by the shared classifier and none counted as a semantic kill",
            "controls=3,systemErrors=3,countedAsKill=0"));

    Set<String> allowed = Set.copyOf(required);
    Map<String, List<M11FixedSuite.Fact>> witnesses = new LinkedHashMap<>();
    for (M11FixedSuite.Fact fact : ledger) {
      require(allowed.contains(fact.obligation()), "unknown assertion fact " + fact.obligation());
      witnesses.computeIfAbsent(fact.obligation(), ignored -> new ArrayList<>()).add(fact);
    }
    require(witnesses.keySet().containsAll(required), "M11 coverage is missing assertion facts");
    require(witnesses.keySet().size() == required.size(), "M11 coverage has extra facts");

    byte[] canonicalLedger = canonicalLedger(ledger);
    ObjectNode report = JsonSupport.MAPPER.createObjectNode();
    report.put("schemaVersion", "matching.m11.coverage.v2");
    report.put("status", M11CheckRunner.PASS);
    report.put("required", required.size());
    report.put("observed", witnesses.size());
    report.put("allWitnessed", true);
    report.put("source", "ASSERTION_FACT_LEDGER");
    report.put("systemErrorEvaluatedAfterControls", true);
    report.put("factCount", ledger.size());
    report.put("ledgerSha256", Hashing.sha256Hex(canonicalLedger));
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
      ArrayNode facts = entry.putArray("facts");
      obligationFacts.forEach(fact -> fact.write(facts.addObject()));
    }
    return report;
  }

  private static void verifyControls(M11MutantSuite.Result mutants) {
    require(mutants.killed() == 10, "M11 semantic mutants did not all fail");
    require(mutants.actualMutationActions() > 0, "M11 mutants performed no actual fault action");
    JsonNode controls = mutants.controls();
    require(controls.size() == 3, "M11 system control count changed");
    Set<String> unique = new HashSet<>();
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
    }
  }

  private static byte[] canonicalLedger(List<M11FixedSuite.Fact> ledger) {
    StringBuilder canonical = new StringBuilder("M11-ASSERTION-FACT-LEDGER-V2\n");
    for (M11FixedSuite.Fact fact : ledger) {
      canonical
          .append(fact.obligation())
          .append('|')
          .append(fact.scenarioId())
          .append('|')
          .append(fact.sourceArtifact())
          .append('|')
          .append(fact.assertion())
          .append('|')
          .append(fact.observedValue())
          .append('\n');
    }
    return canonical.toString().getBytes(StandardCharsets.UTF_8);
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
