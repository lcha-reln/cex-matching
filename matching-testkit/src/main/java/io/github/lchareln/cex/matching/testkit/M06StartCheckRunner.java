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

/** Validates the frozen M06 declarations and inputs, then writes the intentional RED report. */
public final class M06StartCheckRunner {
  public static final String STATUS = "GOAL_NOT_IMPLEMENTED";
  public static final String FIXED_CORPUS_SHA256 =
      "358ecc4dc12c0f2f708cee58b069eff8a9604a349f91495a5c3212bfe38edb25";
  public static final String GENERATOR_SHA256 =
      "6276aa10601f8e55b1692635fc58c8f62b8cb369900c404c8e7c947d56cb8e08";

  static final String FIXED_CORPUS_PATH =
      "matching-testkit/src/test/resources/m06/fixtures/market-mode-mass-cancel-v1.json";
  static final String FIXED_CORPUS_SCHEMA_PATH = "schemas/matching.m06.scenario.v1.schema.json";
  static final String GENERATOR_PATH =
      "matching-testkit/src/test/resources/m06/fixtures/property-suite-v1.json";
  static final String GENERATOR_SCHEMA_PATH = "schemas/matching.m06.generator.v1.schema.json";
  static final String CHECK_SCHEMA_PATH = "schemas/matching.m06.check.v1.schema.json";

  static final int SCENARIOS = 15;
  static final int FIXED_COMMANDS = 64;
  static final int HISTORIES = 160;
  static final int COMMANDS_PER_HISTORY = 64;
  static final int TOTAL_GENERATED_COMMANDS = HISTORIES * COMMANDS_PER_HISTORY;
  static final int LANES = 5;
  static final int HISTORIES_PER_LANE = HISTORIES / LANES;
  static final int COVERAGE_OBLIGATIONS = 26;
  static final int FIXED_SCHEMA_PROBES = 5;
  static final int GENERATOR_SCHEMA_PROBES = 5;

  static final List<String> SCENARIO_IDS =
      List.of(
          "BOOTSTRAP_OPEN_REGRESSION",
          "CANCEL_ONLY_PERMISSION",
          "HALTED_CUSTOMER_PERMISSION",
          "SAFE_REOPEN_GRAPH",
          "STALE_APPLICATION_FENCE",
          "STALE_EXPECTED_MODE",
          "MODE_FAILURE_ATOMICITY",
          "TRANSITION_DOES_NOT_CLEAR_BOOK",
          "MASS_CANCEL_REQUIRES_HALT",
          "EMPTY_BOOK_MASS_CANCEL",
          "BID_ONLY_ACCEPTANCE_ORDER",
          "ASK_ONLY_ACCEPTANCE_ORDER",
          "CROSS_SIDE_GLOBAL_ACCEPTANCE_ORDER",
          "TERMINAL_IDENTITY_LATE_CANCEL",
          "RULE_SET_ATTRIBUTION_PRESERVED");

  static final List<String> LANE_IDS =
      List.of(
          "MODE_TRANSITION_AND_FENCE",
          "CUSTOMER_PERMISSION_MATRIX",
          "MASS_CANCEL_GLOBAL_ORDER",
          "MASS_CANCEL_FAILURE_ATOMICITY",
          "RULE_ATTRIBUTION_AND_LEGACY");

  static final List<String> COVERAGE_IDS =
      List.of(
          "BOOTSTRAP_OPEN",
          "VALID_MODE_TRANSITION",
          "DIRECT_REOPEN_REJECTED",
          "SAME_MODE_REJECTED",
          "STALE_APPLICATION_FENCE",
          "STALE_EXPECTED_MODE",
          "MODE_FAILURE_ATOMIC",
          "PLACE_OPEN_ALLOWED",
          "PLACE_CANCEL_ONLY_REJECTED",
          "PLACE_HALTED_REJECTED",
          "CANCEL_OPEN_ALLOWED",
          "CANCEL_CANCEL_ONLY_ALLOWED",
          "CANCEL_HALTED_REJECTED",
          "RULE_CONTROL_ALL_MODES",
          "TRANSITION_BOOK_UNCHANGED",
          "MASS_CANCEL_REQUIRES_HALTED",
          "MASS_CANCEL_EMPTY_SUCCESS",
          "MASS_CANCEL_GLOBAL_ACCEPTANCE_ORDER",
          "MASS_CANCEL_BID_SIDE",
          "MASS_CANCEL_ASK_SIDE",
          "MASS_CANCEL_CROSS_PRICE",
          "MASS_CANCEL_TERMINAL_IDENTITY",
          "MASS_CANCEL_RULE_ATTRIBUTION",
          "MASS_CANCEL_ACTIVE_RULE_UNCHANGED",
          "APPLICATION_SEQUENCE_CONTINUITY",
          "LEGACY_M00_M05_COMPATIBILITY");

  static final List<String> REQUIRED_MUTANTS =
      List.of(
          "M06-CANCEL-ONLY-PLACE-ACCEPTED",
          "M06-HALTED-CUSTOMER-CANCEL-ACCEPTED",
          "M06-HALTED-DIRECTLY-REOPENED",
          "M06-STALE-MODE-FENCE-ACCEPTED",
          "M06-MODE-CHANGE-IMPLICITLY-CLEARS-BOOK",
          "M06-FAILED-MODE-CHANGE-RESETS-OPEN",
          "M06-MASS-CANCEL-WITHOUT-HALT",
          "M06-MASS-CANCEL-NON-ACCEPTANCE-ORDER",
          "M06-FAILED-MASS-CANCEL-PARTIALLY-CLEARS",
          "M06-MASS-CANCEL-DROPS-TERMINAL-ATTRIBUTION");

  static final List<String> TUTORIAL_PERMALINKS =
      List.of(
          "market-operating-mode-contract",
          "mode-transition-fence-and-permission-matrix",
          "deterministic-mass-cancel-order",
          "mass-cancel-atomicity-and-terminal-attribution",
          "market-mode-property-evidence");

  private static final Map<String, Integer> COMMAND_COUNTS =
      Map.of(
          "PLACE", 21,
          "CANCEL", 6,
          "PREPARE_RULE_SET", 2,
          "ACTIVATE_RULE_SET", 2,
          "CHANGE_MARKET_MODE", 24,
          "MASS_CANCEL", 9);

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
    report.put("schemaVersion", "matching.m06.check.v1");
    report.put("unit", "M06");
    report.put("status", STATUS);
    report.put("contractPlanVersion", "0.8");
    report.put(
        "objective",
        "Add one replicated-ready operating-mode axis and deterministic HALTED-only Mass Cancel.");
    writeCourseDeclaration(report.putObject("courseDeclaration"), declaration);
    writeInheritedBaseline(report.putObject("inheritedBaseline"));
    writeFixed(report.putObject("fixedCorpus"), fixed);
    writeGenerator(report.putObject("generator"), generator);
    writeModeContract(report.putObject("modeContract"));
    writeStrings(report.putArray("tutorialPermalinks"), TUTORIAL_PERMALINKS);
    writeStrings(report.putArray("requiredMutants"), REQUIRED_MUTANTS);
    writeStrings(
        report.putArray("missingCapabilities"),
        List.of(
            "MARKET_MODE_STATE",
            "MODE_TRANSITION_FENCE",
            "MODE_PERMISSION_MATRIX",
            "OPERATOR_AUDIT_ATTRIBUTION",
            "HALTED_ONLY_MASS_CANCEL",
            "GLOBAL_ACCEPTANCE_SEQUENCE_CANCEL_ORDER",
            "ATOMIC_MASS_CANCEL_BATCH",
            "MASS_CANCEL_TERMINAL_LIFECYCLE",
            "M06_INDEPENDENT_REFERENCE_MODEL",
            "M06_GENERATED_PROPERTY_JUDGE",
            "M06_REPLAYABLE_COUNTEREXAMPLES"));
    ObjectNode release = report.putObject("releaseTarget");
    release.put("unitTag", "course/m06-complete");
    release.putNull("productRelease");
    release.put("verification", "M06_EVIDENCE_ONLY");

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
            Map.entry("planVersion", "0.8"),
            Map.entry("project", "matching"),
            Map.entry("unit", "M06"),
            Map.entry("lifecycle", "READY"),
            Map.entry("designDepth", "CONTRACT"),
            Map.entry("startRef", "course/m06-start"),
            Map.entry("completeRef", "course/m06-complete"),
            Map.entry("m06Check.expectedStatus", STATUS),
            Map.entry("evidencePath", "build/lab-evidence/M06/manifest.json"));
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
    require(FIXED_CORPUS_SHA256.equals(digest), "M06 fixed corpus SHA-256 changed");
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
        require(caseIds.add(command.path("caseId").stringValue()), "duplicate M06 caseId");
        String type = command.path("type").stringValue();
        require(counts.containsKey(type), "unexpected M06 command type: " + type);
        counts.put(type, counts.get(type) + 1);
      }
    }
    require(SCENARIO_IDS.equals(scenarios), "M06 scenario identity or order changed");
    require(commands == FIXED_COMMANDS, "M06 fixed command count changed");
    require(COMMAND_COUNTS.equals(counts), "M06 fixed command counts changed");
    require(
        Set.copyOf(COVERAGE_IDS).equals(Set.copyOf(proofObligations)),
        "M06 fixed proof-obligation set changed");
    int probes = verifyFixedSchemaBoundary(fixture, schema);
    return new FrozenFixed(digest, Map.copyOf(counts), proofObligations.size(), probes);
  }

  private static FrozenGenerator verifyGenerator(Path root) {
    byte[] bytes = readBytes(root.resolve(GENERATOR_PATH));
    String digest = Hashing.sha256Hex(bytes);
    require(GENERATOR_SHA256.equals(digest), "M06 generator profile SHA-256 changed");
    String schema = readString(root.resolve(GENERATOR_SCHEMA_PATH));
    JsonNode profile = JsonSupport.parse(bytes);
    JsonSupport.validate(profile, schema, false);
    require("splitmix64-v1".equals(profile.path("algorithm").stringValue()), "M06 PRNG changed");
    require("6606".equals(profile.path("baseSeed").stringValue()), "M06 base seed changed");
    require(profile.path("histories").intValue() == HISTORIES, "M06 histories changed");
    require(
        profile.path("commandsPerHistory").intValue() == COMMANDS_PER_HISTORY,
        "M06 commands per history changed");

    List<String> laneIds = new ArrayList<>();
    List<Integer> modulos = new ArrayList<>();
    for (JsonNode lane : profile.path("lanes")) {
      laneIds.add(lane.path("id").stringValue());
      modulos.add(lane.path("historyModulo").intValue());
      require(
          SCENARIO_IDS.contains(lane.path("prefixScenario").stringValue()),
          "M06 lane references an unknown fixed scenario");
    }
    require(LANE_IDS.equals(laneIds), "M06 lane identity or order changed");
    require(List.of(0, 1, 2, 3, 4).equals(modulos), "M06 lane modulo changed");
    require(
        COVERAGE_IDS.equals(strings(profile.path("coverageRequirements"))),
        "M06 coverage identity or order changed");
    require(
        REQUIRED_MUTANTS.equals(strings(profile.path("requiredMutants"))),
        "M06 mutant identity or order changed");

    JsonNode domain = profile.path("randomDomain");
    int totalWeight =
        domain.path("placeWeight").intValue()
            + domain.path("cancelWeight").intValue()
            + domain.path("changeModeWeight").intValue()
            + domain.path("massCancelWeight").intValue()
            + domain.path("prepareWeight").intValue()
            + domain.path("activateWeight").intValue();
    require(totalWeight == 100, "M06 command weights no longer total 100");
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
    wrongVersion.put("schemaVersion", "matching.m06.scenario.v0");
    invalid.add(wrongVersion);
    ObjectNode invalidMode = (ObjectNode) valid.deepCopy();
    ((ObjectNode) invalidMode.path("scenarios").get(1).path("commands").get(1).path("input"))
        .put("targetMode", "PAUSED");
    invalid.add(invalidMode);
    ObjectNode blankOperator = (ObjectNode) valid.deepCopy();
    ((ObjectNode) blankOperator.path("scenarios").get(1).path("commands").get(1).path("input"))
        .put("operatorId", "   ");
    invalid.add(blankOperator);
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
    ((ArrayNode) missingMutant.path("requiredMutants")).remove(9);
    invalid.add(missingMutant);
    invalid.forEach(probe -> expectSchemaFailure(probe, schema, "generator"));
    return invalid.size();
  }

  private static void expectSchemaFailure(JsonNode value, String schema, String boundary) {
    try {
      JsonSupport.validate(value, schema, false);
      throw new IllegalStateException("M06 " + boundary + " schema accepted a negative probe");
    } catch (FixtureSchemaException expected) {
      // Expected strict boundary rejection.
    }
  }

  private static void writeCourseDeclaration(ObjectNode node, CourseDeclaration declaration) {
    declaration.values().forEach(node::put);
  }

  private static void writeInheritedBaseline(ObjectNode node) {
    node.put("unit", "M05");
    node.put("completeRef", "course/m05-complete");
    node.put("contractPlanVersion", "0.7");
    node.put("expectedStatus", "PASS");
    node.put("verification", "ROOT_BUILD_GATES_M05");
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
    node.put("baseSeed", "6606");
    node.put("histories", HISTORIES);
    node.put("commandsPerHistory", COMMANDS_PER_HISTORY);
    node.put("totalCommands", TOTAL_GENERATED_COMMANDS);
    node.put("lanes", LANES);
    node.put("historiesPerLane", HISTORIES_PER_LANE);
    writeStrings(node.putArray("laneIds"), generator.laneIds());
    node.put("coverageObligations", COVERAGE_OBLIGATIONS);
    node.put("schemaProbes", generator.schemaProbes());
  }

  private static void writeModeContract(ObjectNode node) {
    node.put("initialMode", "OPEN");
    writeStrings(node.putArray("modes"), List.of("OPEN", "CANCEL_ONLY", "HALTED"));
    node.put("operatorBoundary", "PRE_AUTHORIZED_AUDIT_ATTRIBUTION");
    node.put("authorizationVerifiedByCore", false);
    node.put(
        "changeModeEntrypoint",
        "changeMarketMode(ChangeMarketMode(expectedApplicationSequence,expectedMode,targetMode,operatorId))");
    node.put(
        "massCancelEntrypoint",
        "massCancel(MassCancel(expectedApplicationSequence,expectedMode,operatorId))");
    node.put("placeModeRejection", "MARKET_NOT_OPEN");
    node.put("cancelModeRejection", "MARKET_NOT_CANCELABLE");
    writeStrings(node.putArray("placeAllowedModes"), List.of("OPEN"));
    writeStrings(node.putArray("cancelAllowedModes"), List.of("OPEN", "CANCEL_ONLY"));
    writeStrings(
        node.putArray("ruleControlAllowedModes"), List.of("OPEN", "CANCEL_ONLY", "HALTED"));
    writeStrings(
        node.putArray("allowedTransitions"),
        List.of(
            "OPEN->CANCEL_ONLY",
            "OPEN->HALTED",
            "CANCEL_ONLY->OPEN",
            "CANCEL_ONLY->HALTED",
            "HALTED->CANCEL_ONLY"));
    node.put("directHaltedToOpenAllowed", false);
    node.put("sameModeTransitionAllowed", false);
    node.put("transitionFence", "APPLICATION_SEQUENCE");
    node.put("successfulTransitionModeRevision", "INCREMENT_EXACTLY_ONCE");
    node.put("transitionClearsBook", false);
    writeStrings(
        node.putArray("changeModeRejectionCodes"),
        List.of(
            "APPLICATION_SEQUENCE_MISMATCH",
            "EXPECTED_MODE_MISMATCH",
            "NO_MODE_CHANGE",
            "INVALID_TRANSITION"));
    writeStrings(
        node.putArray("placePrecedence"),
        List.of(
            "M00_FIELD_VALIDATION",
            "EXECUTION_POLICY_VALIDATION",
            "DUPLICATE_ORDER_ID",
            "EXPECTED_ACTIVE_RULE_SET",
            "ACTIVE_ORDER_ENTRY_PRICE_BAND",
            "MARKET_MODE_PERMISSION",
            "POLICY_STATE_PRECHECK",
            "ACCEPTANCE_SEQUENCE_CAPACITY",
            "ACCEPT_AND_EXECUTE"));
    writeStrings(
        node.putArray("cancelPrecedence"),
        List.of(
            "FIELD_VALIDATION", "MARKET_MODE_PERMISSION", "ORDER_LOOKUP_AND_LIFECYCLE", "CANCEL"));
    node.put("massCancelRequiredMode", "HALTED");
    writeStrings(
        node.putArray("massCancelRejectionCodes"),
        List.of("APPLICATION_SEQUENCE_MISMATCH", "EXPECTED_MODE_MISMATCH", "MARKET_NOT_HALTED"));
    node.put("massCancelOrder", "GLOBAL_ASCENDING_ACCEPTANCE_SEQUENCE");
    node.put("massCancelAtomic", true);
    writeStrings(
        node.putArray("massCancelEventGrammar"),
        List.of("STARTED", "ORDER_CANCELED*", "COMPLETED"));
    node.put("emptyMassCancelSucceeds", true);
    node.put("successfulMassCancelModeAfter", "HALTED");
    node.put("successfulMassCancelChangesModeRevision", false);
    node.put("terminalIdentityPreserved", true);
    node.put("ruleAttributionPreserved", true);
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
      throw new IllegalStateException("cannot create M06 report directory", failure);
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
      throw new IllegalStateException("cannot clear M06 temporary output", failure);
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
