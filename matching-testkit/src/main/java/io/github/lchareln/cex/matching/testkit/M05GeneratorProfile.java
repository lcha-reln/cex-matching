package io.github.lchareln.cex.matching.testkit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** Parsed immutable profile for the deterministic M05 generated suite. */
record M05GeneratorProfile(
    long baseSeed,
    int histories,
    int commandsPerHistory,
    List<Lane> lanes,
    RandomDomain randomDomain,
    List<String> coverageRequirements,
    List<String> requiredMutants) {
  static final String ALGORITHM = "splitmix64-v1";

  M05GeneratorProfile {
    lanes = List.copyOf(lanes);
    coverageRequirements = List.copyOf(coverageRequirements);
    requiredMutants = List.copyOf(requiredMutants);
  }

  static M05GeneratorProfile load(Path repositoryRoot) {
    Path root = repositoryRoot.toAbsolutePath().normalize();
    try {
      byte[] bytes = Files.readAllBytes(root.resolve(M05StartCheckRunner.GENERATOR_PATH));
      if (!M05StartCheckRunner.GENERATOR_SHA256.equals(Hashing.sha256Hex(bytes))) {
        throw new IllegalStateException("M05 generator profile SHA-256 changed");
      }
      JsonNode document = JsonSupport.parse(bytes);
      JsonSupport.validate(
          document,
          Files.readString(root.resolve(M05StartCheckRunner.GENERATOR_SCHEMA_PATH)),
          false);
      List<Lane> lanes = new ArrayList<>();
      for (JsonNode lane : document.path("lanes")) {
        lanes.add(
            new Lane(
                lane.path("id").stringValue(),
                lane.path("historyModulo").intValue(),
                lane.path("prefixScenario").stringValue()));
      }
      JsonNode domain = document.path("randomDomain");
      List<WeightedPolicy> policies = new ArrayList<>();
      for (JsonNode policy : domain.path("executionPolicies")) {
        policies.add(
            new WeightedPolicy(policy.path("id").stringValue(), policy.path("weight").intValue()));
      }
      return new M05GeneratorProfile(
          Long.parseLong(document.path("baseSeed").stringValue()),
          document.path("histories").intValue(),
          document.path("commandsPerHistory").intValue(),
          lanes,
          new RandomDomain(
              domain.path("placeWeight").intValue(),
              domain.path("cancelWeight").intValue(),
              domain.path("prepareWeight").intValue(),
              domain.path("activateWeight").intValue(),
              domain.path("invalidFieldOneIn").intValue(),
              domain.path("staleRuleOneIn").intValue(),
              domain.path("outOfBandOneIn").intValue(),
              domain.path("minimumPriceTicks").intValue(),
              domain.path("maximumPriceTicks").intValue(),
              domain.path("maximumQuantityLots").intValue(),
              policies),
          strings(document.path("coverageRequirements")),
          strings(document.path("requiredMutants")));
    } catch (IOException failure) {
      throw new IllegalStateException("cannot load M05 generator profile", failure);
    }
  }

  Lane laneForHistory(int historyIndex) {
    return lanes.get(Math.floorMod(historyIndex, lanes.size()));
  }

  private static List<String> strings(JsonNode values) {
    List<String> result = new ArrayList<>();
    values.forEach(value -> result.add(value.stringValue()));
    return List.copyOf(result);
  }

  record Lane(String id, int historyModulo, String prefixScenario) {}

  record WeightedPolicy(String id, int weight) {}

  record RandomDomain(
      int placeWeight,
      int cancelWeight,
      int prepareWeight,
      int activateWeight,
      int invalidFieldOneIn,
      int staleRuleOneIn,
      int outOfBandOneIn,
      int minimumPriceTicks,
      int maximumPriceTicks,
      int maximumQuantityLots,
      List<WeightedPolicy> policies) {
    RandomDomain {
      policies = List.copyOf(policies);
      if (placeWeight + cancelWeight + prepareWeight + activateWeight != 100) {
        throw new IllegalArgumentException("M05 command weights must total 100");
      }
      if (policies.stream().mapToInt(WeightedPolicy::weight).sum() != 100) {
        throw new IllegalArgumentException("M05 policy weights must total 100");
      }
    }
  }
}
