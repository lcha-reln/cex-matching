package io.github.lchareln.cex.matching.testkit;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Validates the frozen M08 inputs and writes the intentional structured RED report. */
public final class M08StartCheckRunner {
  public static final String STATUS = "GOAL_NOT_IMPLEMENTED";
  public static final String FIXED_CORPUS_SHA256 =
      "5160121600c151c91db5431a4e1a8ef8fcd4a73ba67683d96a09daba389100a3";
  public static final String GENERATOR_SHA256 =
      "477a2b16be5d2d6f6f378b203660bf1106db89409ad06e77bdb7837edbfc74ea";

  static final String FIXED_CORPUS_PATH =
      "matching-testkit/src/test/resources/m08/fixtures/local-wal-durability-v1.json";
  static final String GENERATOR_PATH =
      "matching-testkit/src/test/resources/m08/fixtures/property-suite-v1.json";
  static final String FIXED_SCHEMA_PATH = "schemas/matching.m08.scenario.v1.schema.json";
  static final String GENERATOR_SCHEMA_PATH = "schemas/matching.m08.generator.v1.schema.json";
  static final String CHECK_SCHEMA_PATH = "schemas/matching.m08.check.v1.schema.json";

  static final int SCENARIOS = 20;
  static final int HISTORIES = 96;
  static final int OPERATIONS_PER_HISTORY = 48;
  static final int TOTAL_GENERATED_OPERATIONS = HISTORIES * OPERATIONS_PER_HISTORY;
  static final int LANES = 4;
  static final int HISTORIES_PER_LANE = HISTORIES / LANES;
  static final int COVERAGE_OBLIGATIONS = 24;

  static final List<String> SCENARIO_IDS =
      List.of(
          "CANONICAL_VALID_AND_BUSINESS_REJECTION",
          "STRUCTURAL_REJECTIONS_BEFORE_WAL",
          "LIVE_EXACT_DUPLICATE",
          "RESTART_EXACT_DUPLICATE",
          "COMMAND_ID_PAYLOAD_CONFLICT",
          "COMMAND_ID_SLOT_CONFLICT",
          "SLOT_IDENTITY_CONFLICT",
          "PRODUCER_SEQUENCE_GAP_AND_BOUND_STALE",
          "PRODUCER_EPOCH_FENCE",
          "HIGHER_EPOCH_MUST_START_AT_ONE",
          "TORN_LENGTH_PREFIX_UNKNOWN",
          "COMPLETE_BODY_BEFORE_FORCE_UNKNOWN",
          "FORCED_RECORD_BEFORE_APPLY_UNKNOWN",
          "APPLIED_BEFORE_ACK_UNKNOWN",
          "ROLLOVER_DIRECTORY_FORCE_ORDER",
          "ORPHAN_TEMP_IS_NOT_AUTHORITY",
          "FINAL_TORN_TAIL_REPAIR",
          "COMPLETE_FINAL_FRAME_CORRUPTION",
          "NON_FINAL_OR_MIDDLE_CORRUPTION",
          "SINGLE_WRITER_AND_APPLY_FAILURE");

  static final List<String> LANE_IDS =
      List.of(
          "CANONICAL_AND_BUSINESS",
          "IDENTITY_SLOT_AND_EPOCH",
          "ACK_AND_FAIL_CLOSED",
          "ROLLOVER_AND_RECOVERY");

  static final List<String> COVERAGE_IDS =
      List.of(
          "CANONICAL_ENVELOPE",
          "WRONG_SHARD_PRE_WAL",
          "PAYLOAD_HASH_PRE_WAL",
          "BUSINESS_REJECTION_JOURNALED",
          "LIVE_EXACT_DUPLICATE",
          "RESTART_EXACT_DUPLICATE",
          "COMMAND_ID_PAYLOAD_CONFLICT",
          "COMMAND_ID_SLOT_CONFLICT",
          "SLOT_IDENTITY_CONFLICT",
          "PRODUCER_SEQUENCE_GAP",
          "STALE_SLOT_RESOLVES_BY_BINDING_PRECEDENCE",
          "PRODUCER_EPOCH_FENCED",
          "HIGHER_EPOCH_MUST_START_AT_ONE",
          "HIGHER_EPOCH_ACTIVATION",
          "APPEND_BEFORE_FORCE",
          "RECORD_FORCE_BEFORE_ACK",
          "DIRECTORY_FORCE_BEFORE_FIRST_RECORD_ACK",
          "APPLY_BEFORE_ACK",
          "FAIL_CLOSED_UNKNOWN",
          "ROLLOVER_CHAIN",
          "ORPHAN_TEMP_IGNORED",
          "FINAL_TORN_TAIL_TRUNCATED",
          "COMPLETE_CORRUPTION_FAIL_CLOSED",
          "DIRECTORY_SINGLE_WRITER_LOCK");

  static final List<String> REQUIRED_MUTANTS =
      List.of(
          "M08-ACK-BEFORE-RECORD-FORCE",
          "M08-ACK-BEFORE-DIRECTORY-FORCE",
          "M08-DUPLICATE-REAPPLIES",
          "M08-COMMAND-ID-PAYLOAD-CONFLICT-ACCEPTED",
          "M08-SLOT-IDENTITY-CONFLICT-ACCEPTED",
          "M08-GAP-ADVANCES-PRODUCER",
          "M08-FENCED-EPOCH-ACCEPTED",
          "M08-BUSINESS-REJECTION-NOT-JOURNALED",
          "M08-TORN-TAIL-REPLAYED",
          "M08-CORRUPTION-SKIPPED");

  static final List<String> TUTORIAL_PERMALINKS =
      List.of(
          "canonical-command-envelope-and-identity",
          "append-force-apply-ack-boundary",
          "durable-idempotency-slot-and-epoch",
          "segmented-wal-rollover-and-recovery",
          "wal-fault-injection-and-property-evidence");

  public Result run(Path repositoryRoot, Path reportDirectory) {
    return run(repositoryRoot, reportDirectory, repositoryRoot);
  }

  Result run(Path repositoryRoot, Path reportDirectory, Path trustedOutputRoot) {
    Path root = repositoryRoot.toAbsolutePath().normalize();
    Path reports = SafeOutputPaths.resolveTrustedOutput(trustedOutputRoot, reportDirectory);
    clear(reports);
    Map<String, String> declaration = verifyCourseDeclaration(root);
    FrozenInput fixed = verifyFixed(root);
    FrozenInput generator = verifyGenerator(root);

    ObjectNode report = JsonSupport.MAPPER.createObjectNode();
    report.put("schemaVersion", "matching.m08.check.v1");
    report.put("unit", "M08");
    report.put("status", STATUS);
    report.put("contractPlanVersion", "0.10");
    report.put(
        "objective",
        "Add a caller-serialized single-shard local WAL, durable identity, recovery, and ACK boundary.");
    ObjectNode course = report.putObject("courseDeclaration");
    declaration.forEach(course::put);
    ObjectNode inherited = report.putObject("inheritedBaseline");
    inherited.put("unit", "M07");
    inherited.put("completeRef", "course/m07-complete");
    inherited.put("expectedStatus", "PASS");
    writeFrozen(report.putObject("fixedCorpus"), fixed, SCENARIOS, 0);
    writeFrozen(report.putObject("generator"), generator, HISTORIES, TOTAL_GENERATED_OPERATIONS);
    ObjectNode contract = report.putObject("durabilityContract");
    contract.put("envelopeFormat", "M08C1");
    contract.put("walFormat", "M08W1");
    contract.put("submissionOrder", "VALIDATE_PREFLIGHT_APPEND_FORCE_APPLY_ACK");
    contract.put("writerModel", "SINGLE_PROCESS_SINGLE_SHARD_CALLER_SERIALIZED");
    contract.put("recovery", "GENESIS_REPLAY_TO_FRESH_CORE");
    contract.put("faultEvidence", "CODE_LEVEL_DETERMINISTIC_INJECTION");
    contract.put("realPowerLossClaim", false);
    contract.put("snapshot", false);
    contract.put("replication", false);
    contract.put("aeron", false);
    writeStrings(report.putArray("tutorialPermalinks"), TUTORIAL_PERMALINKS);
    writeStrings(report.putArray("requiredMutants"), REQUIRED_MUTANTS);
    writeStrings(
        report.putArray("missingCapabilities"),
        List.of(
            "M08C1_CANONICAL_INGRESS",
            "M08W1_SEGMENTED_WAL",
            "DURABLE_BIDIRECTIONAL_IDENTITY",
            "APPEND_FORCE_APPLY_ACK",
            "GENESIS_RECOVERY",
            "TORN_TAIL_REPAIR",
            "CORRUPTION_FAIL_CLOSED",
            "M08_GENERATED_DURABILITY_MODEL",
            "M08_MUTATION_COUNTEREXAMPLES"));
    ObjectNode release = report.putObject("releaseTarget");
    release.put("unitTag", "course/m08-complete");
    release.putNull("productRelease");
    release.put("verification", "M08_EVIDENCE_ONLY");

    JsonSupport.validate(report, readString(root.resolve(CHECK_SCHEMA_PATH)), false);
    Path reportPath = reports.resolve("check.json");
    AtomicFiles.write(reportPath, JsonSupport.prettyBytes(report));
    return new Result(STATUS, reportPath);
  }

  private static Map<String, String> verifyCourseDeclaration(Path root) {
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
            Map.entry("planVersion", "0.10"),
            Map.entry("project", "matching"),
            Map.entry("unit", "M08"),
            Map.entry("lifecycle", "READY"),
            Map.entry("designDepth", "CONTRACT"),
            Map.entry("startRef", "course/m08-start"),
            Map.entry("completeRef", "course/m08-complete"),
            Map.entry("m08Check.expectedStatus", STATUS),
            Map.entry("evidencePath", "build/lab-evidence/M08/manifest.json"));
    require(properties.size() == expected.size(), "M08 course declaration field set changed");
    expected.forEach(
        (key, value) ->
            require(
                value.equals(properties.getProperty(key)),
                "M08 course declaration changed: " + key));
    return expected;
  }

  private static FrozenInput verifyFixed(Path root) {
    byte[] bytes = readBytes(root.resolve(FIXED_CORPUS_PATH));
    String digest = Hashing.sha256Hex(bytes);
    require(FIXED_CORPUS_SHA256.equals(digest), "M08 fixed corpus SHA-256 changed");
    String schema = readString(root.resolve(FIXED_SCHEMA_PATH));
    JsonNode fixture = JsonSupport.parse(bytes);
    JsonSupport.validate(fixture, schema, false);
    require(
        "CODE_LEVEL_DETERMINISTIC_INJECTION".equals(fixture.path("faultModel").stringValue()),
        "M08 fault evidence model changed");
    List<String> scenarioIds = new ArrayList<>();
    Set<String> obligations = new LinkedHashSet<>();
    int operations = 0;
    for (JsonNode scenario : fixture.path("scenarios")) {
      scenarioIds.add(scenario.path("scenarioId").stringValue());
      operations += scenario.path("operations").size();
      obligations.addAll(strings(scenario.path("proofObligations")));
    }
    require(SCENARIO_IDS.equals(scenarioIds), "M08 scenario identity or order changed");
    require(Set.copyOf(COVERAGE_IDS).equals(obligations), "M08 fixed obligation set changed");
    return new FrozenInput(digest, operations, negativeFixedProbes(fixture, schema));
  }

  private static FrozenInput verifyGenerator(Path root) {
    byte[] bytes = readBytes(root.resolve(GENERATOR_PATH));
    String digest = Hashing.sha256Hex(bytes);
    require(GENERATOR_SHA256.equals(digest), "M08 generator SHA-256 changed");
    String schema = readString(root.resolve(GENERATOR_SCHEMA_PATH));
    JsonNode profile = JsonSupport.parse(bytes);
    JsonSupport.validate(profile, schema, false);
    require("splitmix64-v1".equals(profile.path("algorithm").stringValue()), "M08 PRNG changed");
    require("5808".equals(profile.path("baseSeed").stringValue()), "M08 base seed changed");
    require(profile.path("histories").intValue() == HISTORIES, "M08 histories changed");
    require(
        profile.path("operationsPerHistory").intValue() == OPERATIONS_PER_HISTORY,
        "M08 operations per history changed");
    List<String> laneIds = new ArrayList<>();
    List<Integer> modulos = new ArrayList<>();
    for (JsonNode lane : profile.path("lanes")) {
      laneIds.add(lane.path("id").stringValue());
      modulos.add(lane.path("historyModulo").intValue());
      require(
          SCENARIO_IDS.contains(lane.path("prefixScenario").stringValue()),
          "M08 lane references unknown scenario");
    }
    require(LANE_IDS.equals(laneIds), "M08 lane identity or order changed");
    require(List.of(0, 1, 2, 3).equals(modulos), "M08 lane modulo changed");
    require(
        COVERAGE_IDS.equals(strings(profile.path("coverageRequirements"))),
        "M08 coverage order changed");
    require(
        REQUIRED_MUTANTS.equals(strings(profile.path("requiredMutants"))),
        "M08 mutant order changed");
    JsonNode domain = profile.path("operationDomain");
    int weights =
        domain.path("submitWeight").intValue()
            + domain.path("duplicateWeight").intValue()
            + domain.path("conflictWeight").intValue()
            + domain.path("restartWeight").intValue()
            + domain.path("rolloverWeight").intValue()
            + domain.path("faultWeight").intValue();
    require(weights == 100, "M08 operation weights no longer total 100");
    return new FrozenInput(
        digest, TOTAL_GENERATED_OPERATIONS, negativeGeneratorProbes(profile, schema));
  }

  private static int negativeFixedProbes(JsonNode valid, String schema) {
    List<JsonNode> probes = new ArrayList<>();
    ObjectNode missing = (ObjectNode) valid.deepCopy();
    missing.remove("scenarios");
    probes.add(missing);
    ObjectNode extra = (ObjectNode) valid.deepCopy();
    extra.put("powerLossProven", true);
    probes.add(extra);
    ObjectNode wrongFault = (ObjectNode) valid.deepCopy();
    wrongFault.put("faultModel", "REAL_POWER_LOSS");
    probes.add(wrongFault);
    ObjectNode invalidScenarioId = (ObjectNode) valid.deepCopy();
    ((ObjectNode) invalidScenarioId.path("scenarios").get(1)).put("scenarioId", "invalid-id");
    probes.add(invalidScenarioId);
    probes.forEach(value -> expectSchemaFailure(value, schema, "fixed"));
    return probes.size();
  }

  private static int negativeGeneratorProbes(JsonNode valid, String schema) {
    List<JsonNode> probes = new ArrayList<>();
    ObjectNode missing = (ObjectNode) valid.deepCopy();
    missing.remove("lanes");
    probes.add(missing);
    ObjectNode wrongSeed = (ObjectNode) valid.deepCopy();
    wrongSeed.put("baseSeed", "5809");
    probes.add(wrongSeed);
    ObjectNode duplicateCoverage = (ObjectNode) valid.deepCopy();
    ((ArrayNode) duplicateCoverage.path("coverageRequirements"))
        .set(1, duplicateCoverage.path("coverageRequirements").get(0));
    probes.add(duplicateCoverage);
    ObjectNode missingMutant = (ObjectNode) valid.deepCopy();
    ((ArrayNode) missingMutant.path("requiredMutants")).remove(9);
    probes.add(missingMutant);
    probes.forEach(value -> expectSchemaFailure(value, schema, "generator"));
    return probes.size();
  }

  private static void expectSchemaFailure(JsonNode value, String schema, String boundary) {
    try {
      JsonSupport.validate(value, schema, false);
      throw new IllegalStateException("M08 " + boundary + " schema accepted a negative probe");
    } catch (FixtureSchemaException expected) {
      // Expected strict boundary rejection.
    }
  }

  private static void writeFrozen(
      ObjectNode target, FrozenInput frozen, int primaryCount, int generatedOperations) {
    target.put("sha256", frozen.digest());
    target.put("primaryCount", primaryCount);
    target.put("operations", frozen.operations());
    target.put("schemaProbes", frozen.schemaProbes());
    if (generatedOperations > 0) {
      target.put("baseSeed", "5808");
      target.put("lanes", LANES);
      target.put("historiesPerLane", HISTORIES_PER_LANE);
      target.put("generatedOperations", generatedOperations);
      target.put("coverageObligations", COVERAGE_OBLIGATIONS);
    }
  }

  private static List<String> strings(JsonNode values) {
    List<String> result = new ArrayList<>();
    values.forEach(value -> result.add(value.stringValue()));
    return List.copyOf(result);
  }

  private static void writeStrings(ArrayNode target, List<String> values) {
    values.forEach(target::add);
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

  private static void clear(Path path) {
    if (Files.exists(path)) {
      try (var paths = Files.walk(path)) {
        for (Path current : paths.sorted(Comparator.reverseOrder()).toList()) {
          Files.deleteIfExists(current);
        }
      } catch (IOException failure) {
        throw new IllegalStateException("cannot clear M08 report directory", failure);
      }
    }
    try {
      Files.createDirectories(path);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot create M08 report directory", failure);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }

  record FrozenInput(String digest, int operations, int schemaProbes) {}

  public record Result(String status, Path reportPath) {}
}
