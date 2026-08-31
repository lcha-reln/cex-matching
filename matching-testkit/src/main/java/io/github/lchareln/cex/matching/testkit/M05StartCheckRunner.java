package io.github.lchareln.cex.matching.testkit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Validates the frozen M05 versioned price-band inputs and writes the intentional RED report. */
public final class M05StartCheckRunner {
  public static final String STATUS = "GOAL_NOT_IMPLEMENTED";
  public static final String FIXED_CORPUS_SHA256 =
      "cd56fbbb0bc56dc809f741ed15ac53c7e8e41162745db7841cb853fc2768c53e";
  public static final String GENERATOR_SHA256 =
      "52dba5c70152eac7ae41464ec7e669526845ca7460deda160de3d9d614c69d57";

  static final String FIXED_CORPUS_PATH =
      "matching-testkit/src/test/resources/m05/fixtures/versioned-price-band-v1.json";
  static final String FIXED_CORPUS_SCHEMA_PATH = "schemas/matching.m05.scenario.v1.schema.json";
  static final String GENERATOR_PATH =
      "matching-testkit/src/test/resources/m05/fixtures/property-suite-v1.json";
  static final String GENERATOR_SCHEMA_PATH = "schemas/matching.m05.generator.v1.schema.json";
  static final String CHECK_SCHEMA_PATH = "schemas/matching.m05.check.v1.schema.json";

  static final int SCENARIOS = 12;
  static final int FIXED_COMMANDS = 54;
  static final int HISTORIES = 160;
  static final int COMMANDS_PER_HISTORY = 64;
  static final int TOTAL_GENERATED_COMMANDS = HISTORIES * COMMANDS_PER_HISTORY;
  static final int LANES = 5;
  static final int HISTORIES_PER_LANE = HISTORIES / LANES;
  static final int COVERAGE_OBLIGATIONS = 20;
  static final int FIXED_SCHEMA_PROBES = 5;
  static final int GENERATOR_SCHEMA_PROBES = 5;

  static final List<String> SCENARIO_IDS =
      List.of(
          "legacy-unbounded-regression",
          "prepare-activate-current-fence",
          "hash-mismatch-and-retry",
          "idempotent-prepare-and-version-conflict",
          "monotonic-prepared-supersession",
          "activation-rejection-matrix",
          "stale-place-fence-and-identity",
          "inclusive-and-outside-buy",
          "sell-side-band-symmetry",
          "duplicate-precedes-fence-and-band",
          "band-precedes-policy-precheck",
          "grandfathered-cross-version-maker");

  static final List<String> LANE_IDS =
      List.of(
          "RULE_SET_LIFECYCLE_AND_HASH",
          "ACTIVATION_AND_PLACE_FENCE",
          "INCLUSIVE_BAND_BUY_SELL",
          "GRANDFATHERED_CROSS_VERSION",
          "MIXED_M04_POLICY_AND_CONTROL");

  static final List<String> REQUIRED_MUTANTS =
      List.of(
          "M05-HASH-MISMATCH-PREPARED",
          "M05-SAME-VERSION-DIFFERENT-HASH-ACCEPTED",
          "M05-ACTIVATE-WITHOUT-PREPARE",
          "M05-STALE-ACTIVATION-FENCE-ACCEPTED",
          "M05-FAILED-ACTIVATION-CHANGES-ACTIVE",
          "M05-OUT-OF-BAND-PLACE-ACCEPTED",
          "M05-STALE-PLACE-RULE-ACCEPTED",
          "M05-ACTIVATION-REVALIDATES-RESTING");

  public Result run(Path repositoryRoot, Path reportDirectory) {
    return run(repositoryRoot, reportDirectory, repositoryRoot);
  }

  Result run(Path repositoryRoot, Path reportDirectory, Path trustedOutputRoot) {
    Path root = repositoryRoot.toAbsolutePath().normalize();
    Path reports = SafeOutputPaths.resolveTrustedOutput(trustedOutputRoot, reportDirectory);
    clear(reports);
    FrozenFixed fixed = verifyFixedCorpus(root);
    FrozenGenerator generator = verifyGenerator(root);
    InheritedM04 inherited = runInheritedM04(root, reports, realPath(trustedOutputRoot));

    ObjectNode report = JsonSupport.MAPPER.createObjectNode();
    report.put("schemaVersion", "matching.m05.check.v1");
    report.put("unit", "M05");
    report.put("status", STATUS);
    report.put("contractPlanVersion", "0.7");
    report.put(
        "objective",
        "Add one content-addressed, versioned order-entry price-band axis with deterministic Prepare/Activate fencing.");
    writeInherited(report.putObject("inheritedM04"), inherited);
    writeFixed(report.putObject("fixedCorpus"), fixed);
    writeGenerator(report.putObject("generator"), generator);
    writeRuleContract(report.putObject("ruleContract"));
    writeStrings(report.putArray("requiredMutants"), REQUIRED_MUTANTS);
    writeStrings(
        report.putArray("missingCapabilities"),
        List.of(
            "MARKET_RULE_SET_ARTIFACT",
            "M05RS1_CANONICAL_HASH",
            "PREPARE_RULE_SET",
            "ACTIVATE_RULE_SET",
            "APPLICATION_SEQUENCE_FENCE",
            "GOVERNED_PLACE_RULE_FENCE",
            "INCLUSIVE_ORDER_ENTRY_PRICE_BAND",
            "RULE_SET_ATTRIBUTION",
            "M05_INDEPENDENT_REFERENCE_MODEL",
            "M05_GENERATED_PROPERTY_JUDGE",
            "M05_REPLAYABLE_COUNTEREXAMPLES"));
    ObjectNode release = report.putObject("releaseTarget");
    release.put("unitTag", "course/m05-complete");
    release.putNull("productRelease");
    release.put("verification", "M05_EVIDENCE_ONLY");

    JsonSupport.validate(report, readString(root.resolve(CHECK_SCHEMA_PATH)), false);
    Path reportPath = reports.resolve("check.json");
    AtomicFiles.write(reportPath, JsonSupport.prettyBytes(report));
    return new Result(STATUS, reportPath);
  }

  private static FrozenFixed verifyFixedCorpus(Path root) {
    byte[] bytes = readBytes(root.resolve(FIXED_CORPUS_PATH));
    String digest = Hashing.sha256Hex(bytes);
    require(FIXED_CORPUS_SHA256.equals(digest), "M05 fixed corpus SHA-256 changed");
    String schema = readString(root.resolve(FIXED_CORPUS_SCHEMA_PATH));
    JsonNode fixture = JsonSupport.parse(bytes);
    JsonSupport.validate(fixture, schema, false);

    List<String> scenarios = new ArrayList<>();
    Set<String> caseIds = new LinkedHashSet<>();
    Map<String, Integer> counts = new LinkedHashMap<>();
    counts.put("PLACE", 0);
    counts.put("CANCEL", 0);
    counts.put("PREPARE_RULE_SET", 0);
    counts.put("ACTIVATE_RULE_SET", 0);
    int commands = 0;
    for (JsonNode scenario : fixture.path("scenarios")) {
      scenarios.add(scenario.path("scenarioId").stringValue());
      for (JsonNode command : scenario.path("commands")) {
        commands++;
        require(caseIds.add(command.path("caseId").stringValue()), "duplicate M05 caseId");
        String type = command.path("type").stringValue();
        require(counts.containsKey(type), "unexpected M05 command type: " + type);
        counts.put(type, counts.get(type) + 1);
      }
    }
    require(SCENARIO_IDS.equals(scenarios), "M05 scenario identity or order changed");
    require(commands == FIXED_COMMANDS, "M05 fixed command count changed");
    require(
        counts.equals(
            Map.of(
                "PLACE", 21,
                "CANCEL", 3,
                "PREPARE_RULE_SET", 16,
                "ACTIVATE_RULE_SET", 14)),
        "M05 fixed command counts changed");
    int probes = verifyFixedSchemaBoundary(fixture, schema);
    return new FrozenFixed(digest, Map.copyOf(counts), probes);
  }

  private static FrozenGenerator verifyGenerator(Path root) {
    byte[] bytes = readBytes(root.resolve(GENERATOR_PATH));
    String digest = Hashing.sha256Hex(bytes);
    require(GENERATOR_SHA256.equals(digest), "M05 generator profile SHA-256 changed");
    String schema = readString(root.resolve(GENERATOR_SCHEMA_PATH));
    JsonNode profile = JsonSupport.parse(bytes);
    JsonSupport.validate(profile, schema, false);
    require("splitmix64-v1".equals(profile.path("algorithm").stringValue()), "M05 PRNG changed");
    require("5505".equals(profile.path("baseSeed").stringValue()), "M05 base seed changed");
    require(profile.path("histories").intValue() == HISTORIES, "M05 histories changed");
    require(
        profile.path("commandsPerHistory").intValue() == COMMANDS_PER_HISTORY,
        "M05 commands per history changed");
    List<String> laneIds = new ArrayList<>();
    List<Integer> modulos = new ArrayList<>();
    for (JsonNode lane : profile.path("lanes")) {
      laneIds.add(lane.path("id").stringValue());
      modulos.add(lane.path("historyModulo").intValue());
      require(
          SCENARIO_IDS.contains(lane.path("prefixScenario").stringValue()),
          "M05 lane references an unknown fixed scenario");
    }
    require(LANE_IDS.equals(laneIds), "M05 lane identity or order changed");
    require(List.of(0, 1, 2, 3, 4).equals(modulos), "M05 lane modulo changed");
    require(
        profile.path("coverageRequirements").size() == COVERAGE_OBLIGATIONS,
        "M05 coverage obligation count changed");
    require(
        REQUIRED_MUTANTS.equals(strings(profile.path("requiredMutants"))),
        "M05 mutant identity or order changed");
    JsonNode domain = profile.path("randomDomain");
    require(
        domain.path("placeWeight").intValue()
                + domain.path("cancelWeight").intValue()
                + domain.path("prepareWeight").intValue()
                + domain.path("activateWeight").intValue()
            == 100,
        "M05 command weights no longer total 100");
    int probes = verifyGeneratorSchemaBoundary(profile, schema);
    return new FrozenGenerator(digest, List.copyOf(laneIds), probes);
  }

  private static InheritedM04 runInheritedM04(Path root, Path reports, Path trustedOutputRoot) {
    Path inheritedReports = reports.resolve(".inherited-m04");
    try {
      M05LegacyRegression.Result result =
          new M05LegacyRegression().run(root, inheritedReports, trustedOutputRoot);
      require(
          result.fixedScenarios() == 14 && result.fixedCommands() == 48,
          "inherited M04 fixed corpus changed");
      require(
          result.generatedHistories() == 192 && result.generatedCommands() == 12_288,
          "inherited M04 generated suite changed");
      require(
          result.counterexamples() == 8 && result.mutantsKilled() == 8,
          "inherited M04 semantic mutants changed");
      return new InheritedM04();
    } finally {
      deleteTree(inheritedReports);
    }
  }

  private static int verifyFixedSchemaBoundary(JsonNode valid, String schema) {
    List<JsonNode> invalid = new ArrayList<>();
    ObjectNode missingScenarios = (ObjectNode) valid.deepCopy();
    missingScenarios.remove("scenarios");
    invalid.add(missingScenarios);
    ObjectNode extraRoot = (ObjectNode) valid.deepCopy();
    extraRoot.put("clock", "forbidden");
    invalid.add(extraRoot);
    ObjectNode wrongVersion = (ObjectNode) valid.deepCopy();
    wrongVersion.put("schemaVersion", "matching.m05.scenario.v0");
    invalid.add(wrongVersion);
    ObjectNode missingCase = (ObjectNode) valid.deepCopy();
    ((ObjectNode) missingCase.path("scenarios").get(0).path("commands").get(0)).remove("caseId");
    invalid.add(missingCase);
    ObjectNode badHash = (ObjectNode) valid.deepCopy();
    badHash
        .path("scenarios")
        .get(1)
        .path("commands")
        .get(0)
        .path("input")
        .path("artifact")
        .deepCopy();
    ((ObjectNode)
            badHash.path("scenarios").get(1).path("commands").get(0).path("input").path("artifact"))
        .put("contentHash", "not-a-hash");
    invalid.add(badHash);
    invalid.forEach(probe -> expectSchemaFailure(probe, schema));
    return invalid.size();
  }

  private static int verifyGeneratorSchemaBoundary(JsonNode valid, String schema) {
    List<JsonNode> invalid = new ArrayList<>();
    ObjectNode missingLanes = (ObjectNode) valid.deepCopy();
    missingLanes.remove("lanes");
    invalid.add(missingLanes);
    ObjectNode extraRoot = (ObjectNode) valid.deepCopy();
    extraRoot.put("wallClock", true);
    invalid.add(extraRoot);
    ObjectNode wrongHistories = (ObjectNode) valid.deepCopy();
    wrongHistories.put("histories", 159);
    invalid.add(wrongHistories);
    ObjectNode duplicateCoverage = (ObjectNode) valid.deepCopy();
    ((ArrayNode) duplicateCoverage.path("coverageRequirements"))
        .set(1, duplicateCoverage.path("coverageRequirements").get(0));
    invalid.add(duplicateCoverage);
    ObjectNode missingMutant = (ObjectNode) valid.deepCopy();
    ((ArrayNode) missingMutant.path("requiredMutants")).remove(7);
    invalid.add(missingMutant);
    invalid.forEach(probe -> expectSchemaFailure(probe, schema));
    return invalid.size();
  }

  private static void expectSchemaFailure(JsonNode value, String schema) {
    try {
      JsonSupport.validate(value, schema, false);
      throw new IllegalStateException("M05 schema accepted a negative probe");
    } catch (FixtureSchemaException expected) {
      // Expected strict boundary rejection.
    }
  }

  private static void writeInherited(ObjectNode node, InheritedM04 ignored) {
    node.put("status", "PASS");
    node.put("checkSchemaVersion", "matching.m04.check.v2");
    node.put("fixedScenarios", 14);
    node.put("fixedCommands", 48);
    node.put("generatedHistories", 192);
    node.put("generatedCommands", 12_288);
    node.put(
        "fixedDigest", "sha256:68de35e41358ea72c9852fdf3fd652db116774964360f0b526f43612576bfa77");
    node.put(
        "generatedDigest",
        "sha256:6005c674d0c42927989f1c8c4d1ddce224d06ceff0b95bf58615d23c4496ba51");
    node.put("counterexamples", 8);
    node.put("mutantsKilled", 8);
    node.putNull("productRelease");
  }

  private static void writeFixed(ObjectNode node, FrozenFixed fixed) {
    node.put("path", FIXED_CORPUS_PATH);
    node.put("schemaPath", FIXED_CORPUS_SCHEMA_PATH);
    node.put("sha256", fixed.digest());
    node.put("scenarios", SCENARIOS);
    node.put("commands", FIXED_COMMANDS);
    ObjectNode counts = node.putObject("commandCounts");
    fixed.commandCounts().forEach(counts::put);
    node.put("schemaProbes", fixed.schemaProbes());
  }

  private static void writeGenerator(ObjectNode node, FrozenGenerator generator) {
    node.put("path", GENERATOR_PATH);
    node.put("schemaPath", GENERATOR_SCHEMA_PATH);
    node.put("sha256", generator.digest());
    node.put("algorithm", "splitmix64-v1");
    node.put("baseSeed", "5505");
    node.put("histories", HISTORIES);
    node.put("commandsPerHistory", COMMANDS_PER_HISTORY);
    node.put("totalCommands", TOTAL_GENERATED_COMMANDS);
    node.put("lanes", LANES);
    node.put("historiesPerLane", HISTORIES_PER_LANE);
    writeStrings(node.putArray("laneIds"), generator.laneIds());
    node.put("coverageObligations", COVERAGE_OBLIGATIONS);
    node.put("schemaProbes", generator.schemaProbes());
  }

  private static void writeRuleContract(ObjectNode node) {
    node.put("artifactSchema", "matching.market-rule-set.v1");
    node.put("canonicalFormat", "M05RS1");
    node.put("hashAlgorithm", "SHA-256");
    node.put("bootstrapVersion", 0);
    node.put("bootstrapLowerInclusive", 1);
    node.put("bootstrapUpperInclusive", Long.MAX_VALUE);
    node.put("entryBandInclusive", true);
    writeStrings(
        node.putArray("placePrecedence"),
        List.of(
            "M00_FIELD_VALIDATION",
            "EXECUTION_POLICY_VALIDATION",
            "DUPLICATE_ORDER_ID",
            "EXPECTED_ACTIVE_RULE_SET",
            "ACTIVE_ORDER_ENTRY_PRICE_BAND",
            "POLICY_STATE_PRECHECK",
            "ACCEPTANCE_SEQUENCE_CAPACITY",
            "ACCEPT_AND_EXECUTE"));
    node.put("activationFence", "APPLICATION_SEQUENCE");
    node.put("grandfatherExistingOrders", true);
    node.put("operatingModesDeferredTo", "M06");
    node.put("massCancelDeferredTo", "M06");
  }

  private static List<String> strings(JsonNode values) {
    List<String> result = new ArrayList<>();
    values.forEach(value -> result.add(value.stringValue()));
    return List.copyOf(result);
  }

  private static void writeStrings(ArrayNode node, List<String> values) {
    values.forEach(node::add);
  }

  private static byte[] readBytes(Path path) {
    try {
      return Files.readAllBytes(path);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot read " + path, failure);
    }
  }

  private static String readString(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot read " + path, failure);
    }
  }

  private static Path realPath(Path path) {
    try {
      return path.toRealPath();
    } catch (IOException failure) {
      throw new IllegalStateException("cannot resolve trusted M05 output root", failure);
    }
  }

  private static void clear(Path directory) {
    deleteTree(directory);
    try {
      Files.createDirectories(directory);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot create M05 report directory", failure);
    }
  }

  private static void deleteTree(Path path) {
    if (!Files.exists(path)) {
      return;
    }
    try (var paths = Files.walk(path)) {
      for (Path current : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(current);
      }
    } catch (IOException failure) {
      throw new IllegalStateException("cannot clear M05 temporary output", failure);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }

  record FrozenFixed(String digest, Map<String, Integer> commandCounts, int schemaProbes) {}

  record FrozenGenerator(String digest, List<String> laneIds, int schemaProbes) {}

  private record InheritedM04() {}

  public record Result(String status, Path reportPath) {}
}
