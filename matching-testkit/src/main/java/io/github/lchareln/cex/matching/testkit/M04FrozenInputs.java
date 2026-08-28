package io.github.lchareln.cex.matching.testkit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Revalidates both frozen M04 input documents, exact identities, and six negative probes each. */
final class M04FrozenInputs {
  Result verify(Path root) {
    byte[] fixedBytes = read(root.resolve(M04StartCheckRunner.FIXED_CORPUS_PATH));
    require(
        M04StartCheckRunner.FIXED_CORPUS_SHA256.equals(Hashing.sha256Hex(fixedBytes)),
        "M04 fixed corpus SHA-256 changed");
    String fixedSchema = text(root.resolve(M04StartCheckRunner.FIXED_CORPUS_SCHEMA_PATH));
    JsonNode fixed = JsonSupport.parse(fixedBytes);
    JsonSupport.validate(fixed, fixedSchema, false);
    Map<String, Integer> policies = fixedCounts(fixed);
    int fixedProbes = fixedSchemaProbes(fixed, fixedSchema);

    byte[] profileBytes = read(root.resolve(M04StartCheckRunner.GENERATOR_PATH));
    require(
        M04StartCheckRunner.GENERATOR_SHA256.equals(Hashing.sha256Hex(profileBytes)),
        "M04 generator SHA-256 changed");
    String generatorSchema = text(root.resolve(M04StartCheckRunner.GENERATOR_SCHEMA_PATH));
    JsonNode generator = JsonSupport.parse(profileBytes);
    JsonSupport.validate(generator, generatorSchema, false);
    verifyGeneratorIdentity(generator);
    int generatorProbes = generatorSchemaProbes(generator, generatorSchema);
    return new Result(policies, fixedProbes, generatorProbes);
  }

  private static Map<String, Integer> fixedCounts(JsonNode fixed) {
    Map<String, Integer> policies = new LinkedHashMap<>();
    List.of("GTC", "IOC", "FOK", "POST_ONLY", "UNKNOWN").forEach(policy -> policies.put(policy, 0));
    List<String> scenarioIds = new ArrayList<>();
    int commands = 0;
    int places = 0;
    int cancels = 0;
    for (JsonNode scenario : fixed.path("scenarios")) {
      scenarioIds.add(scenario.path("scenarioId").stringValue());
      for (JsonNode command : scenario.path("commands")) {
        commands++;
        if ("PLACE".equals(command.path("type").stringValue())) {
          places++;
          String policy = command.path("input").path("executionPolicy").stringValue();
          require(policies.containsKey(policy), "unexpected fixed M04 policy");
          policies.put(policy, policies.get(policy) + 1);
        } else {
          cancels++;
        }
      }
    }
    require(M04StartCheckRunner.SCENARIO_IDS.equals(scenarioIds), "M04 scenario order changed");
    require(commands == 48 && places == 44 && cancels == 4, "M04 fixed command counts changed");
    require(
        policies.equals(Map.of("GTC", 25, "IOC", 5, "FOK", 6, "POST_ONLY", 6, "UNKNOWN", 2)),
        "M04 fixed policy counts changed");
    return Map.copyOf(policies);
  }

  private static void verifyGeneratorIdentity(JsonNode profile) {
    require(
        "matching.m04.generator.v1".equals(profile.path("schemaVersion").stringValue()),
        "M04 generator schemaVersion changed");
    require(
        "splitmix64-v1".equals(profile.path("algorithm").stringValue()),
        "M04 generator algorithm changed");
    require("4404".equals(profile.path("baseSeed").stringValue()), "M04 base seed changed");
    require(
        profile.path("histories").intValue() == 192
            && profile.path("commandsPerHistory").intValue() == 64,
        "M04 generated dimensions changed");
    List<String> lanes = new ArrayList<>();
    profile.path("lanes").forEach(lane -> lanes.add(lane.path("id").stringValue()));
    require(M04StartCheckRunner.LANE_IDS.equals(lanes), "M04 lane order changed");
    List<String> mutants = new ArrayList<>();
    profile.path("requiredMutants").forEach(mutant -> mutants.add(mutant.stringValue()));
    require(M04StartCheckRunner.REQUIRED_MUTANTS.equals(mutants), "M04 mutant order changed");
  }

  private static int fixedSchemaProbes(JsonNode valid, String schema) {
    List<JsonNode> invalid = new ArrayList<>();
    ObjectNode missingScenarios = (ObjectNode) valid.deepCopy();
    missingScenarios.remove("scenarios");
    invalid.add(missingScenarios);
    ObjectNode extraRoot = (ObjectNode) valid.deepCopy();
    extraRoot.put("clock", "forbidden");
    invalid.add(extraRoot);
    ObjectNode extraScenario = (ObjectNode) valid.deepCopy();
    ((ObjectNode) extraScenario.path("scenarios").get(0)).put("expected", "forbidden");
    invalid.add(extraScenario);
    ObjectNode missingPolicy = (ObjectNode) valid.deepCopy();
    ((ObjectNode) missingPolicy.path("scenarios").get(1).path("commands").get(1).path("input"))
        .remove("executionPolicy");
    invalid.add(missingPolicy);
    ObjectNode policyOnCancel = (ObjectNode) valid.deepCopy();
    ((ObjectNode) policyOnCancel.path("scenarios").get(0).path("commands").get(1).path("input"))
        .put("executionPolicy", "GTC");
    invalid.add(policyOnCancel);
    ObjectNode floatingQuantity = (ObjectNode) valid.deepCopy();
    ((ObjectNode) floatingQuantity.path("scenarios").get(2).path("commands").get(0).path("input"))
        .put("quantityLots", 1.5);
    invalid.add(floatingQuantity);
    return rejected(invalid, schema, "fixed corpus");
  }

  private static int generatorSchemaProbes(JsonNode valid, String schema) {
    List<JsonNode> invalid = new ArrayList<>();
    ObjectNode missingLanes = (ObjectNode) valid.deepCopy();
    missingLanes.remove("lanes");
    invalid.add(missingLanes);
    ObjectNode extraRoot = (ObjectNode) valid.deepCopy();
    extraRoot.put("clock", "forbidden");
    invalid.add(extraRoot);
    ObjectNode wrongAlgorithm = (ObjectNode) valid.deepCopy();
    wrongAlgorithm.put("algorithm", "java-random");
    invalid.add(wrongAlgorithm);
    ObjectNode duplicateLane = (ObjectNode) valid.deepCopy();
    ((ArrayNode) duplicateLane.path("lanes")).add(valid.path("lanes").get(0).deepCopy());
    invalid.add(duplicateLane);
    ObjectNode missingPolicy = (ObjectNode) valid.deepCopy();
    ((ObjectNode) missingPolicy.path("lanes").get(1).path("prefix").get(0).path("input"))
        .remove("executionPolicy");
    invalid.add(missingPolicy);
    ObjectNode reorderedMutant = (ObjectNode) valid.deepCopy();
    ArrayNode mutants = (ArrayNode) reorderedMutant.path("requiredMutants");
    JsonNode first = mutants.get(0).deepCopy();
    mutants.set(0, mutants.get(1).deepCopy());
    mutants.set(1, first);
    invalid.add(reorderedMutant);
    return rejected(invalid, schema, "generator");
  }

  private static int rejected(List<JsonNode> invalid, String schema, String subject) {
    int rejected = 0;
    for (JsonNode probe : invalid) {
      try {
        JsonSupport.validate(probe, schema, false);
      } catch (FixtureSchemaException expected) {
        rejected++;
      }
    }
    require(rejected == 6, "M04 " + subject + " schema accepted a negative probe");
    return rejected;
  }

  private static byte[] read(Path path) {
    try {
      return Files.readAllBytes(path);
    } catch (IOException exception) {
      throw new IllegalStateException("cannot read " + path, exception);
    }
  }

  private static String text(Path path) {
    return new String(read(path), StandardCharsets.UTF_8);
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }

  record Result(
      Map<String, Integer> policyCounts, int fixedSchemaProbes, int generatorSchemaProbes) {
    Result {
      policyCounts = Map.copyOf(policyCounts);
    }
  }
}
