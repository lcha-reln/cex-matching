package io.github.lchareln.cex.matching.testkit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Strict loader for the two immutable M09 start-contract fixtures. */
final class M09Corpus {
  private final List<Scenario> scenarios;
  private final GeneratorProfile generator;

  private M09Corpus(List<Scenario> scenarios, GeneratorProfile generator) {
    this.scenarios = List.copyOf(scenarios);
    this.generator = generator;
  }

  static M09Corpus load(Path repositoryRoot) {
    Path root = repositoryRoot.toAbsolutePath().normalize();
    byte[] fixed = read(root.resolve(M09StartCheckRunner.FIXED_CORPUS_PATH));
    byte[] generated = read(root.resolve(M09StartCheckRunner.GENERATOR_PATH));
    systemRequire(
        M09StartCheckRunner.FIXED_CORPUS_SHA256.equals(Hashing.sha256Hex(fixed)),
        "M09 fixed fixture digest changed");
    systemRequire(
        M09StartCheckRunner.GENERATOR_SHA256.equals(Hashing.sha256Hex(generated)),
        "M09 generator fixture digest changed");
    JsonNode fixedNode = JsonSupport.parse(fixed);
    JsonNode generatorNode = JsonSupport.parse(generated);
    JsonSupport.validate(
        fixedNode, readString(root.resolve(M09StartCheckRunner.FIXED_SCHEMA_PATH)), false);
    JsonSupport.validate(
        generatorNode, readString(root.resolve(M09StartCheckRunner.GENERATOR_SCHEMA_PATH)), false);

    List<Scenario> scenarios = new ArrayList<>();
    for (JsonNode node : fixedNode.path("scenarios")) {
      scenarios.add(
          new Scenario(
              node.path("scenarioId").stringValue(),
              strings(node.path("operations")),
              strings(node.path("proofObligations"))));
    }
    systemRequire(
        scenarios.stream().map(Scenario::id).toList().equals(M09StartCheckRunner.SCENARIO_IDS),
        "M09 fixed scenario order changed");

    List<Lane> lanes = new ArrayList<>();
    for (JsonNode node : generatorNode.path("lanes")) {
      lanes.add(
          new Lane(
              node.path("id").stringValue(),
              node.path("historyModulo").intValue(),
              node.path("prefixScenario").stringValue()));
    }
    JsonNode domain = generatorNode.path("operationDomain");
    Map<String, Integer> weights = new LinkedHashMap<>();
    weights.put("SUBMIT", domain.path("submitWeight").intValue());
    weights.put("DUPLICATE_OR_CONFLICT", domain.path("duplicateOrConflictWeight").intValue());
    weights.put("SNAPSHOT", domain.path("snapshotWeight").intValue());
    weights.put("RESTART", domain.path("restartWeight").intValue());
    weights.put("ROLLOVER", domain.path("rolloverWeight").intValue());
    weights.put("RETIRE", domain.path("retireWeight").intValue());
    weights.put("CRASH", domain.path("crashWeight").intValue());
    GeneratorProfile profile =
        new GeneratorProfile(
            Long.parseLong(generatorNode.path("baseSeed").stringValue()),
            generatorNode.path("histories").intValue(),
            generatorNode.path("operationsPerHistory").intValue(),
            lanes,
            weights,
            domain.path("businessRejectionOneIn").intValue(),
            domain.path("controlCommandOneIn").intValue(),
            strings(generatorNode.path("coverageRequirements")),
            strings(generatorNode.path("crashWindows")),
            strings(generatorNode.path("failureSeams")),
            strings(generatorNode.path("requiredMutants")));
    systemRequire(profile.baseSeed() == 5909, "M09 generated seed changed");
    systemRequire(
        profile.histories() == 96 && profile.operationsPerHistory() == 40,
        "M09 generated dimensions changed");
    systemRequire(
        profile.lanes().stream().map(Lane::id).toList().equals(M09StartCheckRunner.LANE_IDS),
        "M09 generated lanes changed");
    systemRequire(
        profile.weights().values().stream().mapToInt(Integer::intValue).sum() == 100,
        "M09 generator weights changed");
    systemRequire(
        profile.obligations().equals(M09StartCheckRunner.COVERAGE_IDS),
        "M09 obligation order changed");
    systemRequire(
        profile.crashWindows().equals(M09StartCheckRunner.CRASH_WINDOW_IDS),
        "M09 crash-window order changed");
    systemRequire(
        profile.failureSeams().equals(M09StartCheckRunner.FAILURE_SEAM_IDS),
        "M09 failure-seam order changed");
    systemRequire(
        profile.requiredMutants().equals(M09StartCheckRunner.REQUIRED_MUTANTS),
        "M09 mutant order changed");
    return new M09Corpus(scenarios, profile);
  }

  List<Scenario> scenarios() {
    return scenarios;
  }

  Scenario scenario(String id) {
    return scenarios.stream()
        .filter(value -> value.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("unknown M09 scenario " + id));
  }

  GeneratorProfile generator() {
    return generator;
  }

  private static List<String> strings(JsonNode array) {
    List<String> values = new ArrayList<>();
    array.forEach(value -> values.add(value.stringValue()));
    return List.copyOf(values);
  }

  private static byte[] read(Path path) {
    try {
      return Files.readAllBytes(path);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot read frozen M09 input " + path, failure);
    }
  }

  private static String readString(Path path) {
    return new String(read(path), java.nio.charset.StandardCharsets.UTF_8);
  }

  private static void systemRequire(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }

  record Scenario(String id, List<String> operations, List<String> obligations) {
    Scenario {
      operations = List.copyOf(operations);
      obligations = List.copyOf(obligations);
      systemRequire(!id.isBlank(), "blank M09 scenario id");
      systemRequire(operations.size() == 4, "M09 fixed scenario must retain four operations");
      systemRequire(!obligations.isEmpty(), "M09 scenario has no obligations");
    }
  }

  record Lane(String id, int historyModulo, String prefixScenario) {}

  record GeneratorProfile(
      long baseSeed,
      int histories,
      int operationsPerHistory,
      List<Lane> lanes,
      Map<String, Integer> weights,
      int businessRejectionOneIn,
      int controlCommandOneIn,
      List<String> obligations,
      List<String> crashWindows,
      List<String> failureSeams,
      List<String> requiredMutants) {
    GeneratorProfile {
      lanes = List.copyOf(lanes);
      weights = Map.copyOf(weights);
      obligations = List.copyOf(obligations);
      crashWindows = List.copyOf(crashWindows);
      failureSeams = List.copyOf(failureSeams);
      requiredMutants = List.copyOf(requiredMutants);
      systemRequire(
          new LinkedHashSet<>(obligations).size() == obligations.size(),
          "duplicate M09 obligation");
      systemRequire(
          new LinkedHashSet<>(requiredMutants).size() == requiredMutants.size(),
          "duplicate M09 mutant");
    }

    Set<String> obligationSet() {
      return Set.copyOf(obligations);
    }
  }
}
