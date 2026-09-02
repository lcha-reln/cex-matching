package io.github.lchareln.cex.matching.testkit;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Derives obligation witnesses from executed fixed/model/mutant results and the frozen mapping. */
final class M10Coverage {
  Report derive(
      JsonNode workload,
      M10FixedSuite.Result fixed,
      M10GeneratedSuite.Result generated,
      M10MutantSuite.Result mutants) {
    Map<String, JsonNode> fixedResults = new LinkedHashMap<>();
    fixed.scenarios().forEach(result -> fixedResults.put(result.path("id").stringValue(), result));
    Map<String, LinkedHashSet<String>> witnesses = new LinkedHashMap<>();
    workload
        .path("coverageRequirements")
        .forEach(value -> witnesses.put(value.stringValue(), new LinkedHashSet<>()));

    for (JsonNode scenario : workload.path("fixedAdmissionScenarios")) {
      String id = scenario.path("id").stringValue();
      JsonNode result = fixedResults.get(id);
      require(
          result != null && M10CheckRunner.PASS.equals(result.path("status").stringValue()),
          "fixed coverage witness did not execute PASS: " + id);
      for (JsonNode obligation : scenario.path("proofObligations")) {
        Set<String> obligationWitnesses = witnesses.get(obligation.stringValue());
        require(obligationWitnesses != null, "scenario maps unknown obligation");
        obligationWitnesses.add("FIXED:" + id);
      }
    }

    require(
        generated.executedActions() == 16_384 && generated.terminalReconciliations() == 64,
        "generated coverage witness did not execute completely");
    add(
        witnesses,
        "GENERATED:64x256_FRESH_MODEL",
        "QUEUE_BOUNDED",
        "OFFER_RECONCILIATION",
        "COMPLETION_RECONCILIATION",
        "SINGLE_WORKER_FIFO",
        "CHECKPOINT_SAME_ENVELOPE_RETRY",
        "FAILURE_CLOSES_ADMISSION",
        "PENDING_EXPLICIT_FAILURE");
    require(
        mutants.killed() == 12 && mutants.controls().size() >= 3,
        "mutant/system-control coverage witness did not execute");
    add(witnesses, "MUTANT_CONTROLS:SYSTEM_ERROR_EXCLUDED", "SYSTEM_ERROR_NEVER_PASS");

    ArrayNode results = JsonSupport.MAPPER.createArrayNode();
    int observed = 0;
    for (Map.Entry<String, LinkedHashSet<String>> entry : witnesses.entrySet()) {
      require(!entry.getValue().isEmpty(), "obligation has no executed witness: " + entry.getKey());
      ObjectNode obligation = results.addObject();
      obligation.put("id", entry.getKey());
      obligation.put("status", M10CheckRunner.PASS);
      ArrayNode ids = obligation.putArray("witnessIds");
      entry.getValue().forEach(ids::add);
      observed++;
    }
    require(observed == 28 && witnesses.size() == 28, "derived M10 coverage is not exactly 28");
    return new Report(observed, results);
  }

  private static void add(
      Map<String, LinkedHashSet<String>> witnesses, String witness, String... obligations) {
    for (String obligation : obligations) {
      LinkedHashSet<String> values = witnesses.get(obligation);
      require(values != null, "generated witness maps unknown obligation " + obligation);
      values.add(witness);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new M10SemanticFailure(message);
  }

  record Report(int observed, ArrayNode obligations) {}
}
