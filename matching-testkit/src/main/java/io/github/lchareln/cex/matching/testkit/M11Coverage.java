package io.github.lchareln.cex.matching.testkit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Requires at least one executed fixed-scenario witness for every frozen M11 obligation. */
final class M11Coverage {
  ObjectNode run(Path repositoryRoot, M11FixedSuite.Result fixed) {
    JsonNode workload =
        JsonSupport.parse(read(repositoryRoot.resolve(M11StartCheckRunner.WORKLOAD_PATH)));
    Map<String, List<String>> witnesses = new LinkedHashMap<>();
    for (JsonNode scenario : fixed.report().path("results")) {
      require(
          M11CheckRunner.PASS.equals(scenario.path("status").stringValue()), "non-PASS scenario");
      String scenarioId = scenario.path("id").stringValue();
      for (JsonNode obligation : scenario.path("proofObligations")) {
        witnesses
            .computeIfAbsent(obligation.stringValue(), ignored -> new ArrayList<>())
            .add(scenarioId);
      }
    }
    List<String> required = strings(workload.path("coverageRequirements"));
    require(required.equals(M11StartCheckRunner.COVERAGE_IDS), "M11 obligation order changed");
    require(required.size() == 28, "M11 obligation count changed");
    require(witnesses.keySet().containsAll(required), "M11 coverage is missing witnesses");

    ObjectNode report = JsonSupport.MAPPER.createObjectNode();
    report.put("schemaVersion", "matching.m11.coverage.v1");
    report.put("status", M11CheckRunner.PASS);
    report.put("required", required.size());
    report.put("observed", required.size());
    report.put("allWitnessed", true);
    ArrayNode entries = report.putArray("witnesses");
    for (String obligation : required) {
      ObjectNode entry = entries.addObject();
      entry.put("obligation", obligation);
      ArrayNode scenarios = entry.putArray("scenarios");
      witnesses.get(obligation).forEach(scenarios::add);
    }
    return report;
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
