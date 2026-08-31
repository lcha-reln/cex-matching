package io.github.lchareln.cex.matching.testkit;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Validates the frozen M07 declarations and inputs, then writes the intentional RED report. */
public final class M07StartCheckRunner {
  public static final String STATUS = "GOAL_NOT_IMPLEMENTED";
  public static final String FIXED_CORPUS_SHA256 =
      "3534e100419bed0ee51babb612b6ec708b80c1574330dbf7346914056e40a207";
  public static final String GENERATOR_SHA256 =
      "0687ea8edf85b6a16c11a085f32da3695abb3014afb9261360ecef6388431308";

  static final String FIXED_CORPUS_PATH =
      "matching-testkit/src/test/resources/m07/fixtures/self-trade-prevention-v1.json";
  static final String FIXED_CORPUS_SCHEMA_PATH = "schemas/matching.m07.scenario.v1.schema.json";
  static final String GENERATOR_PATH =
      "matching-testkit/src/test/resources/m07/fixtures/property-suite-v1.json";
  static final String GENERATOR_SCHEMA_PATH = "schemas/matching.m07.generator.v1.schema.json";
  static final String CHECK_SCHEMA_PATH = "schemas/matching.m07.check.v1.schema.json";

  static final int SCENARIOS = 16;
  static final int FIXED_COMMANDS = 72;
  static final int HISTORIES = 160;
  static final int COMMANDS_PER_HISTORY = 64;
  static final int TOTAL_GENERATED_COMMANDS = HISTORIES * COMMANDS_PER_HISTORY;
  static final int LANES = 5;
  static final int HISTORIES_PER_LANE = HISTORIES / LANES;
  static final int COVERAGE_OBLIGATIONS = 24;
  static final int FIXED_SCHEMA_PROBES = 5;
  static final int GENERATOR_SCHEMA_PROBES = 5;

  static final List<String> SCENARIO_IDS =
      List.of(
          "RAW_GROUP_VALIDATION",
          "RAW_POLICY_VALIDATION",
          "GROUP_POLICY_PAIR_VALIDATION",
          "LEGACY_NONE_REGRESSION",
          "DIFFERENT_GROUP_TRADES",
          "CANCEL_TAKER_SAME_GROUP",
          "CANCEL_MAKER_SAME_GROUP",
          "CANCEL_BOTH_SAME_GROUP",
          "SAME_PRICE_FIFO_INTERLEAVE",
          "CANCEL_MAKER_CROSS_LEVEL",
          "PARTIAL_BEFORE_STP",
          "GTC_REMAINDER_AFTER_STP",
          "IOC_STP_REMAINDER",
          "FOK_STP_AWARE_ATOMICITY",
          "POST_ONLY_RAW_BOOK_PRIORITY",
          "RULE_MODE_ATTRIBUTION_FAILURE_ATOMICITY");

  static final List<String> LANE_IDS =
      List.of(
          "VALIDATION_AND_LEGACY",
          "CANCEL_TAKER",
          "CANCEL_MAKER_AND_CROSS_LEVEL",
          "CANCEL_BOTH",
          "POLICY_RULE_MODE_MIXED");

  static final List<String> COVERAGE_IDS =
      List.of(
          "INVALID_STP_GROUP_ID",
          "INVALID_STP_POLICY",
          "INVALID_STP_INSTRUCTION",
          "VALIDATION_FAILURE_ATOMIC",
          "LEGACY_ZERO_NONE_COMPATIBILITY",
          "GROUP_ZERO_NEVER_SELF",
          "DIFFERENT_GROUP_TRADE",
          "SAME_GROUP_NO_TRADE",
          "CANCEL_TAKER_CANCELS_FULL_REMAINDER",
          "CANCEL_TAKER_PRESERVES_MAKER",
          "CANCEL_MAKER_CANCELS_MAKER",
          "CANCEL_MAKER_CONTINUES_SAME_LEVEL",
          "CANCEL_MAKER_CONTINUES_CROSS_LEVEL",
          "CANCEL_BOTH_CANCELS_BOTH",
          "PRICE_TIME_EVENT_INTERLEAVING",
          "PARTIAL_TRADE_BEFORE_STP",
          "GTC_REMAINDER_RESTS",
          "IOC_STP_AND_REMAINDER_REASONS",
          "FOK_TAKER_OR_BOTH_PRECHECK",
          "FOK_CANCEL_MAKER_PRECHECK",
          "FOK_FAILURE_ATOMIC",
          "POST_ONLY_RAW_BOOK_FIRST",
          "RULE_SET_ATTRIBUTION",
          "MARKET_MODE_BEFORE_STP");

  static final List<String> REQUIRED_MUTANTS =
      List.of(
          "M07-SAME-GROUP-TRADE-ALLOWED",
          "M07-DIFFERENT-GROUP-CANCELED",
          "M07-CANCEL-TAKER-SKIPS-SELF",
          "M07-CANCEL-MAKER-CANCELS-TAKER",
          "M07-CANCEL-BOTH-LEAVES-MAKER",
          "M07-FOK-COUNTS-RAW-SELF-LIQUIDITY",
          "M07-POST-ONLY-RUNS-STP-FIRST",
          "M07-CANCEL-MAKER-BEST-LEVEL-ONLY");

  static final List<String> TUTORIAL_PERMALINKS =
      List.of(
          "stp-command-and-opaque-participant-group",
          "cancel-taker-maker-both-state-machine",
          "stp-price-time-scan-and-cross-level-cases",
          "stp-with-ioc-fok-post-only-and-rule-sets",
          "stp-property-evidence-and-mutants");

  private static final Map<String, Integer> COMMAND_COUNTS =
      Map.of(
          "PLACE", 61,
          "CANCEL", 8,
          "PREPARE_RULE_SET", 1,
          "ACTIVATE_RULE_SET", 1,
          "CHANGE_MARKET_MODE", 1);

  public Result run(Path repositoryRoot, Path reportDirectory) {
    return run(repositoryRoot, reportDirectory, repositoryRoot);
  }

  Result run(Path repositoryRoot, Path reportDirectory, Path trustedOutputRoot) {
    Path root = repositoryRoot.toAbsolutePath().normalize();
    Path reports = SafeOutputPaths.resolveTrustedOutput(trustedOutputRoot, reportDirectory);
    clear(reports);
    CourseDeclaration declaration = verifyCourseDeclaration(root);
    FrozenFixed fixed = verifyFixedCorpus(root);
    FrozenGenerator generator = verifyGenerator(root);

    ObjectNode report = JsonSupport.MAPPER.createObjectNode();
    report.put("schemaVersion", "matching.m07.check.v1");
    report.put("unit", "M07");
    report.put("status", STATUS);
    report.put("contractPlanVersion", "0.9");
    report.put(
        "objective",
        "Add opaque participant groups and taker-owned self-trade prevention to the deterministic matcher.");
    declaration.values().forEach(report.putObject("courseDeclaration")::put);
    writeInheritedBaseline(report.putObject("inheritedBaseline"));
    writeFixed(report.putObject("fixedCorpus"), fixed);
    writeGenerator(report.putObject("generator"), generator);
    writeStpContract(report.putObject("stpContract"));
    writeStrings(report.putArray("tutorialPermalinks"), TUTORIAL_PERMALINKS);
    writeStrings(report.putArray("requiredMutants"), REQUIRED_MUTANTS);
    writeStrings(
        report.putArray("missingCapabilities"),
        List.of(
            "M07_PRODUCTION_STP_SEMANTICS",
            "M07_INDEPENDENT_REFERENCE_MODEL",
            "M07_EVENT_DERIVED_LEDGER",
            "M07_FIXED_DIFFERENTIAL_HISTORY",
            "M07_GENERATED_PROPERTY_JUDGE",
            "M07_COVERAGE_WITNESSES",
            "M07_SEMANTIC_MUTANT_KILLS",
            "M07_REPLAYABLE_COUNTEREXAMPLES"));
    ObjectNode release = report.putObject("releaseTarget");
    release.put("unitTag", "course/m07-complete");
    release.putNull("productRelease");
    release.put("verification", "M07_EVIDENCE_ONLY");

    JsonSupport.validate(report, readString(root.resolve(CHECK_SCHEMA_PATH)), false);
    Path reportPath = reports.resolve("check.json");
    AtomicFiles.write(reportPath, JsonSupport.prettyBytes(report));
    return new Result(STATUS, reportPath);
  }

  private static CourseDeclaration verifyCourseDeclaration(Path root) {
    Properties properties = new Properties();
    try (Reader reader = Files.newBufferedReader(root.resolve("course.properties"))) {
      properties.load(reader);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot read course.properties", failure);
    }
    Map<String, String> expected =
        Map.ofEntries(
            Map.entry("case", "high-availability-cex"),
            Map.entry("profile", "SPOT-CEX-1.0"),
            Map.entry("planVersion", "0.9"),
            Map.entry("project", "matching"),
            Map.entry("unit", "M07"),
            Map.entry("lifecycle", "READY"),
            Map.entry("designDepth", "CONTRACT"),
            Map.entry("startRef", "course/m07-start"),
            Map.entry("completeRef", "course/m07-complete"),
            Map.entry("m07Check.expectedStatus", STATUS),
            Map.entry("evidencePath", "build/lab-evidence/M07/manifest.json"));
    require(properties.size() == expected.size(), "course declaration field set changed");
    expected.forEach(
        (key, value) ->
            require(
                value.equals(properties.getProperty(key)), "course declaration changed: " + key));
    return new CourseDeclaration(expected);
  }

  private static FrozenFixed verifyFixedCorpus(Path root) {
    byte[] bytes = readBytes(root.resolve(FIXED_CORPUS_PATH));
    String digest = Hashing.sha256Hex(bytes);
    require(FIXED_CORPUS_SHA256.equals(digest), "M07 fixed corpus SHA-256 changed");
    String schema = readString(root.resolve(FIXED_CORPUS_SCHEMA_PATH));
    JsonNode fixture = JsonSupport.parse(bytes);
    JsonSupport.validate(fixture, schema, false);

    List<String> scenarios = new ArrayList<>();
    Set<String> caseIds = new LinkedHashSet<>();
    Set<String> proofObligations = new LinkedHashSet<>();
    Map<String, Integer> counts = new LinkedHashMap<>();
    COMMAND_COUNTS.keySet().forEach(type -> counts.put(type, 0));
    int commands = 0;
    for (JsonNode scenario : fixture.path("scenarios")) {
      scenarios.add(scenario.path("scenarioId").stringValue());
      proofObligations.addAll(strings(scenario.path("proofObligations")));
      for (JsonNode command : scenario.path("commands")) {
        commands++;
        require(caseIds.add(command.path("caseId").stringValue()), "duplicate M07 caseId");
        String type = command.path("type").stringValue();
        require(counts.containsKey(type), "unexpected M07 command type: " + type);
        counts.put(type, counts.get(type) + 1);
      }
    }
    require(SCENARIO_IDS.equals(scenarios), "M07 scenario identity or order changed");
    require(commands == FIXED_COMMANDS, "M07 fixed command count changed");
    require(COMMAND_COUNTS.equals(counts), "M07 fixed command counts changed");
    require(
        Set.copyOf(COVERAGE_IDS).equals(Set.copyOf(proofObligations)),
        "M07 fixed proof-obligation set changed");
    int probes = verifyFixedSchemaBoundary(fixture, schema);
    return new FrozenFixed(digest, Map.copyOf(counts), proofObligations.size(), probes);
  }

  private static FrozenGenerator verifyGenerator(Path root) {
    byte[] bytes = readBytes(root.resolve(GENERATOR_PATH));
    String digest = Hashing.sha256Hex(bytes);
    require(GENERATOR_SHA256.equals(digest), "M07 generator profile SHA-256 changed");
    String schema = readString(root.resolve(GENERATOR_SCHEMA_PATH));
    JsonNode profile = JsonSupport.parse(bytes);
    JsonSupport.validate(profile, schema, false);
    require("splitmix64-v1".equals(profile.path("algorithm").stringValue()), "M07 PRNG changed");
    require("5707".equals(profile.path("baseSeed").stringValue()), "M07 base seed changed");
    require(profile.path("histories").intValue() == HISTORIES, "M07 histories changed");
    require(
        profile.path("commandsPerHistory").intValue() == COMMANDS_PER_HISTORY,
        "M07 commands per history changed");

    List<String> laneIds = new ArrayList<>();
    List<Integer> modulos = new ArrayList<>();
    for (JsonNode lane : profile.path("lanes")) {
      laneIds.add(lane.path("id").stringValue());
      modulos.add(lane.path("historyModulo").intValue());
      require(
          lane.path("historiesPerLane").intValue() == HISTORIES_PER_LANE,
          "M07 histories per lane changed");
      require(
          SCENARIO_IDS.contains(lane.path("prefixScenario").stringValue()),
          "M07 lane references an unknown fixed scenario");
    }
    require(LANE_IDS.equals(laneIds), "M07 lane identity or order changed");
    require(List.of(0, 1, 2, 3, 4).equals(modulos), "M07 lane modulo changed");
    require(
        COVERAGE_IDS.equals(strings(profile.path("coverageRequirements"))),
        "M07 coverage identity or order changed");
    require(
        REQUIRED_MUTANTS.equals(strings(profile.path("requiredMutants"))),
        "M07 mutant identity or order changed");
    List<String> expectedPaths =
        TUTORIAL_PERMALINKS.stream()
            .map(value -> "/practice/high-availability-cex/m07/" + value + "/")
            .toList();
    require(
        expectedPaths.equals(strings(profile.path("tutorialPermalinks"))),
        "M07 tutorial identity or order changed");

    JsonNode domain = profile.path("randomDomain");
    int totalWeight =
        domain.path("legacyPlaceWeight").intValue()
            + domain.path("stpPlaceWeight").intValue()
            + domain.path("governedStpPlaceWeight").intValue()
            + domain.path("cancelWeight").intValue()
            + domain.path("prepareWeight").intValue()
            + domain.path("activateWeight").intValue()
            + domain.path("changeModeWeight").intValue()
            + domain.path("massCancelWeight").intValue();
    require(totalWeight == 100, "M07 command weights no longer total 100");
    int probes = verifyGeneratorSchemaBoundary(profile, schema);
    return new FrozenGenerator(digest, List.copyOf(laneIds), probes);
  }

  private static int verifyFixedSchemaBoundary(JsonNode valid, String schema) {
    List<JsonNode> invalid = new ArrayList<>();
    ObjectNode missingScenarios = (ObjectNode) valid.deepCopy();
    missingScenarios.remove("scenarios");
    invalid.add(missingScenarios);
    ObjectNode extraRoot = (ObjectNode) valid.deepCopy();
    extraRoot.put("wallClock", "forbidden");
    invalid.add(extraRoot);
    ObjectNode wrongVersion = (ObjectNode) valid.deepCopy();
    wrongVersion.put("schemaVersion", "matching.m07.scenario.v0");
    invalid.add(wrongVersion);
    ObjectNode invalidPolicy = (ObjectNode) valid.deepCopy();
    ((ObjectNode) invalidPolicy.path("scenarios").get(0).path("commands").get(0))
        .put("stpPolicy", "DROP_MAKER");
    invalid.add(invalidPolicy);
    ObjectNode invalidGroup = (ObjectNode) valid.deepCopy();
    ((ObjectNode) invalidGroup.path("scenarios").get(0).path("commands").get(0))
        .put("participantGroupId", -2);
    invalid.add(invalidGroup);
    invalid.forEach(probe -> expectSchemaFailure(probe, schema, "fixed"));
    return invalid.size();
  }

  private static int verifyGeneratorSchemaBoundary(JsonNode valid, String schema) {
    List<JsonNode> invalid = new ArrayList<>();
    ObjectNode missingLanes = (ObjectNode) valid.deepCopy();
    missingLanes.remove("lanes");
    invalid.add(missingLanes);
    ObjectNode extraRoot = (ObjectNode) valid.deepCopy();
    extraRoot.put("filesystem", true);
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
    invalid.forEach(probe -> expectSchemaFailure(probe, schema, "generator"));
    return invalid.size();
  }

  private static void expectSchemaFailure(JsonNode value, String schema, String boundary) {
    try {
      JsonSupport.validate(value, schema, false);
      throw new IllegalStateException("M07 " + boundary + " schema accepted a negative probe");
    } catch (FixtureSchemaException expected) {
      // Expected strict boundary rejection.
    }
  }

  private static void writeInheritedBaseline(ObjectNode node) {
    node.put("unit", "M06");
    node.put("completeRef", "course/m06-complete");
    node.put("contractPlanVersion", "0.8");
    node.put("expectedStatus", "PASS");
    node.put("verification", "ROOT_BUILD_GATES_M06");
  }

  private static void writeFixed(ObjectNode node, FrozenFixed fixed) {
    node.put("path", FIXED_CORPUS_PATH);
    node.put("schemaPath", FIXED_CORPUS_SCHEMA_PATH);
    node.put("sha256", fixed.digest());
    node.put("scenarios", SCENARIOS);
    node.put("commands", FIXED_COMMANDS);
    ObjectNode counts = node.putObject("commandCounts");
    fixed.commandCounts().forEach(counts::put);
    node.put("proofObligations", fixed.proofObligations());
    node.put("schemaProbes", fixed.schemaProbes());
  }

  private static void writeGenerator(ObjectNode node, FrozenGenerator generator) {
    node.put("path", GENERATOR_PATH);
    node.put("schemaPath", GENERATOR_SCHEMA_PATH);
    node.put("sha256", generator.digest());
    node.put("algorithm", "splitmix64-v1");
    node.put("baseSeed", "5707");
    node.put("histories", HISTORIES);
    node.put("commandsPerHistory", COMMANDS_PER_HISTORY);
    node.put("totalCommands", TOTAL_GENERATED_COMMANDS);
    node.put("lanes", LANES);
    node.put("historiesPerLane", HISTORIES_PER_LANE);
    writeStrings(node.putArray("laneIds"), generator.laneIds());
    node.put("coverageObligations", COVERAGE_OBLIGATIONS);
    node.put("schemaProbes", generator.schemaProbes());
  }

  private static void writeStpContract(ObjectNode node) {
    node.put("participantBoundary", "UPSTREAM_RESOLVED_OPAQUE_EQUALITY_KEY");
    node.put("accountRelationshipLookupInCore", false);
    node.put("groupZeroNeverSelf", true);
    writeStrings(
        node.putArray("policies"), List.of("NONE", "CANCEL_TAKER", "CANCEL_MAKER", "CANCEL_BOTH"));
    writeStrings(
        node.putArray("validPairs"), List.of("group==0&&policy==NONE", "group>0&&policy!=NONE"));
    writeStrings(
        node.putArray("legacyEntrypoints"), List.of("place", "placeRequest", "placeGoverned"));
    node.put("legacyMapping", "0/NONE");
    writeStrings(
        node.putArray("rawValidationOrder"),
        List.of(
            "M00_ORDER_FIELDS",
            "EXECUTION_POLICY",
            "PARTICIPANT_GROUP",
            "STP_POLICY",
            "GROUP_POLICY_PAIR"));
    writeStrings(
        node.putArray("statefulPrecedence"),
        List.of(
            "DUPLICATE_ORDER_ID",
            "EXPECTED_ACTIVE_RULE_SET",
            "ACTIVE_ORDER_ENTRY_PRICE_BAND",
            "MARKET_MODE",
            "POST_ONLY_RAW_BOOK_OR_FOK_STP_AWARE_PRECHECK",
            "ACCEPT_AND_EXECUTE"));
    node.put("dispositionOwner", "INCOMING_TAKER");
    node.put("samePositiveGroupTradeAllowed", false);
    node.put("cancelTakerQuantity", "COMPLETE_CURRENT_TAKER_REMAINDER");
    node.put("cancelMakerQuantity", "COMPLETE_CURRENT_MAKER_REMAINDER");
    node.put("cancelMakerContinuesScan", true);
    node.put("fokPreflight", "READ_ONLY_STP_AWARE");
    node.put("postOnlyPreflight", "RAW_OPPOSITE_BOOK_BEFORE_STP");
    node.put("marketModeGateBeforeStp", true);
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

  private static void clear(Path directory) {
    deleteTree(directory);
    try {
      Files.createDirectories(directory);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot create M07 report directory", failure);
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
      throw new IllegalStateException("cannot clear M07 temporary output", failure);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }

  private record CourseDeclaration(Map<String, String> values) {
    private CourseDeclaration {
      values = Map.copyOf(values);
    }
  }

  record FrozenFixed(
      String digest, Map<String, Integer> commandCounts, int proofObligations, int schemaProbes) {}

  record FrozenGenerator(String digest, List<String> laneIds, int schemaProbes) {}

  public record Result(String status, Path reportPath) {}
}
