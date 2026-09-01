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

/** Validates the frozen M09 inputs and writes the intentional structured RED report. */
public final class M09StartCheckRunner {
  public static final String STATUS = "GOAL_NOT_IMPLEMENTED";
  public static final String FIXED_CORPUS_SHA256 =
      "b9fd2679d3c82c52875e2a756a26f9c17c19072534477139331426d38f5393cd";
  public static final String GENERATOR_SHA256 =
      "794621a446f7896cd43b741809393025e063b0ffb190570d9057b90ce1dabda8";

  static final String FIXED_CORPUS_PATH =
      "matching-testkit/src/test/resources/m09/fixtures/snapshot-recovery-v1.json";
  static final String GENERATOR_PATH =
      "matching-testkit/src/test/resources/m09/fixtures/property-suite-v1.json";
  static final String FIXED_SCHEMA_PATH = "schemas/matching.m09.scenario.v1.schema.json";
  static final String GENERATOR_SCHEMA_PATH = "schemas/matching.m09.generator.v1.schema.json";
  static final String CHECK_SCHEMA_PATH = "schemas/matching.m09.check.v1.schema.json";

  static final int SCENARIOS = 22;
  static final int FIXED_OPERATIONS = 88;
  static final int HISTORIES = 96;
  static final int OPERATIONS_PER_HISTORY = 40;
  static final int TOTAL_GENERATED_OPERATIONS = HISTORIES * OPERATIONS_PER_HISTORY;
  static final int LANES = 4;
  static final int HISTORIES_PER_LANE = HISTORIES / LANES;
  static final int COVERAGE_OBLIGATIONS = 32;
  static final int CRASH_WINDOWS = 7;
  static final int FAILURE_SEAMS = 8;

  static final List<String> SCENARIO_IDS =
      List.of(
          "FULL_CORE_STATE_ROUND_TRIP",
          "TERMINAL_ORDER_NON_RESURRECTION",
          "DURABLE_IDENTITY_AND_ORIGINAL_RESULT_ROUND_TRIP",
          "RULE_SET_AND_ACTIVATION_FENCE_ROUND_TRIP",
          "CANCEL_ONLY_MODE_ROUND_TRIP",
          "HALTED_MASS_CANCEL_FENCE_ROUND_TRIP",
          "TRANSCRIPT_AND_DIGEST_ROUND_TRIP",
          "SNAPSHOT_SUFFIX_EQUALS_GENESIS_REPLAY",
          "EMPTY_SUFFIX_RECOVERY",
          "MULTI_SEGMENT_SUFFIX_RECOVERY",
          "RECOVERY_BUDGET_REJECTS_PRE_WAL",
          "ORPHAN_TEMP_SNAPSHOT_IS_NOT_AUTHORITY",
          "SNAPSHOT_PUBLICATION_ORDER",
          "NEWEST_PUBLISHED_GENERATION_WINS",
          "UNKNOWN_SNAPSHOT_VERSION_FAILS_CLOSED",
          "SNAPSHOT_CORRUPTION_FAILS_CLOSED",
          "SNAPSHOT_IDENTITY_MISMATCH_FAILS_CLOSED",
          "RETIRE_ONLY_FULLY_COVERED_SEGMENTS",
          "RETIREMENT_REQUIRES_PUBLISHED_SNAPSHOT",
          "RETIREMENT_DELETE_DIRECTORY_FORCE_ORDER",
          "RETIREMENT_DELETE_CRASH_WINDOW",
          "MISSING_PREFIX_WITHOUT_VALID_SNAPSHOT_FAILS_CLOSED");

  static final List<String> LANE_IDS =
      List.of(
          "STATE_AND_IDENTITY", "CUT_AND_SUFFIX", "PUBLISH_AND_SELECTION", "RETIREMENT_AND_BUDGET");

  static final List<String> COVERAGE_IDS =
      List.of(
          "QUIESCENT_COMMAND_BOUNDARY_CAPTURE",
          "FULL_ORDER_REGISTRY_ROUND_TRIP",
          "PRICE_TIME_FIFO_ROUND_TRIP",
          "TERMINAL_ORDER_NON_RESURRECTION",
          "STP_STATE_ROUND_TRIP",
          "ACTIVE_RULE_SET_ROUND_TRIP",
          "PREPARED_RULE_SET_ROUND_TRIP",
          "CONTROL_FENCES_ROUND_TRIP",
          "MARKET_MODE_ROUND_TRIP",
          "MASS_CANCEL_FENCE_ROUND_TRIP",
          "SEQUENCE_CURSORS_ROUND_TRIP",
          "DURABLE_IDENTITY_INDEX_ROUND_TRIP",
          "ORIGINAL_RESULT_REPLAY_ROUND_TRIP",
          "TRANSCRIPT_DIGEST_ROUND_TRIP",
          "SEMANTIC_SERIALIZATION_DIGEST_SEPARATION",
          "SNAPSHOT_SUFFIX_GENESIS_EQUIVALENCE",
          "EMPTY_SUFFIX_RECOVERY",
          "MULTI_SEGMENT_SUFFIX_RECOVERY",
          "CUT_RECORD_EXACTLY_ONCE",
          "RECOVERY_BUDGET_ENFORCED_PRE_WAL",
          "ORPHAN_TEMP_IGNORED",
          "SNAPSHOT_FORCE_BEFORE_RENAME",
          "SNAPSHOT_RENAME_BEFORE_DIRECTORY_FORCE",
          "SNAPSHOT_DIRECTORY_FORCE_BEFORE_RETIREMENT",
          "NEWEST_PUBLISHED_GENERATION_SELECTED",
          "UNKNOWN_SNAPSHOT_VERSION_FAIL_CLOSED",
          "SNAPSHOT_CORRUPTION_FAIL_CLOSED",
          "GENERATION_SHARD_CUT_MISMATCH_FAIL_CLOSED",
          "RETIRE_ONLY_FULLY_COVERED_CLOSED_SEGMENTS",
          "ACTIVE_OR_CROSSING_SEGMENT_RETAINED",
          "RETIREMENT_DELETE_DIRECTORY_FORCE",
          "MISSING_PREFIX_WITHOUT_VALID_SNAPSHOT_FAIL_CLOSED");

  static final List<String> CRASH_WINDOW_IDS =
      List.of(
          "BEFORE_SNAPSHOT_TEMP_WRITE",
          "AFTER_PARTIAL_SNAPSHOT_TEMP_WRITE",
          "AFTER_SNAPSHOT_FILE_FORCE_BEFORE_RENAME",
          "AFTER_SNAPSHOT_RENAME_BEFORE_DIRECTORY_FORCE",
          "AFTER_SNAPSHOT_DIRECTORY_FORCE_BEFORE_RETIREMENT",
          "AFTER_FIRST_SEGMENT_DELETE_BEFORE_DIRECTORY_FORCE",
          "AFTER_RETIREMENT_DIRECTORY_FORCE_BEFORE_RETURN");

  static final List<String> FAILURE_SEAM_IDS =
      List.of(
          "SNAPSHOT_TEMP_WRITE",
          "SNAPSHOT_FILE_FORCE",
          "SNAPSHOT_ATOMIC_RENAME",
          "SNAPSHOT_DIRECTORY_FORCE",
          "RETIREMENT_SEGMENT_DELETE",
          "RETIREMENT_DIRECTORY_FORCE",
          "SNAPSHOT_READ",
          "WAL_SUFFIX_READ");

  static final List<String> REQUIRED_MUTANTS =
      List.of(
          "M09-SNAPSHOT-DROPS-RESTING-ORDER",
          "M09-SNAPSHOT-RESETS-MARKET-MODE",
          "M09-SNAPSHOT-DROPS-PREPARED-RULE-SET",
          "M09-SNAPSHOT-DROPS-DURABLE-IDENTITY-RESULT",
          "M09-SUFFIX-REPLAYS-CUT-RECORD",
          "M09-SUFFIX-SKIPS-FIRST-RECORD",
          "M09-UNKNOWN-VERSION-ACCEPTED",
          "M09-CORRUPT-SNAPSHOT-ACCEPTED",
          "M09-SNAPSHOT-IDENTITY-MISMATCH-ACCEPTED",
          "M09-RETIREMENT-BEFORE-SNAPSHOT-DIRECTORY-FORCE",
          "M09-RETIREMENT-DELETES-CROSSING-SEGMENT",
          "M09-GENESIS-FALLBACK-WITH-MISSING-PREFIX");

  static final List<String> TUTORIAL_PERMALINKS =
      List.of(
          "snapshot-state-and-consistent-cut",
          "atomic-snapshot-publication",
          "snapshot-suffix-recovery-equivalence",
          "wal-prefix-retirement-and-replay-bound",
          "snapshot-fault-injection-and-recovery-evidence");

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
    report.put("schemaVersion", "matching.m09.check.v1");
    report.put("unit", "M09");
    report.put("status", STATUS);
    report.put("contractPlanVersion", "0.11");
    report.put(
        "objective",
        "Add one M09S1 snapshot, bounded WAL-suffix recovery, and safe whole-segment retirement.");
    ObjectNode course = report.putObject("courseDeclaration");
    declaration.forEach(course::put);
    ObjectNode inherited = report.putObject("inheritedBaseline");
    inherited.put("unit", "M08");
    inherited.put("completeRef", "course/m08-complete");
    inherited.put("expectedStatus", "PASS");
    writeFrozen(report.putObject("fixedCorpus"), fixed, SCENARIOS, false);
    ObjectNode generated = report.putObject("generator");
    writeFrozen(generated, generator, HISTORIES, true);
    generated.put("baseSeed", "5909");
    generated.put("lanes", LANES);
    generated.put("historiesPerLane", HISTORIES_PER_LANE);
    generated.put("generatedOperations", TOTAL_GENERATED_OPERATIONS);
    generated.put("coverageObligations", COVERAGE_OBLIGATIONS);
    generated.put("crashWindows", CRASH_WINDOWS);
    generated.put("failureSeams", FAILURE_SEAMS);
    writeBudget(generated.putObject("recoveryBudget"));

    ObjectNode contract = report.putObject("snapshotContract");
    contract.put("format", "M09S1");
    contract.put("capture", "QUIESCENT_CALLER_SERIALIZED_COMMAND_BOUNDARY");
    contract.put("state", "COMPLETE_CORE_IDENTITY_ORIGINAL_RESULTS_AND_TRANSCRIPT");
    contract.put("publication", "WRITE_TEMP_FORCE_ATOMIC_RENAME_DIRECTORY_FORCE");
    contract.put("recovery", "VALID_SNAPSHOT_THEN_CONTIGUOUS_M08W1_SUFFIX");
    contract.put("retirement", "PUBLISHED_CUT_THEN_FULLY_COVERED_CLOSED_SEGMENTS_ONLY");
    writeBudget(contract.putObject("recoveryBudget"));
    contract.put("faultEvidence", "CODE_LEVEL_INJECTION_AND_PROCESS_HALT");
    contract.put("unknownVersion", "FAIL_CLOSED");
    contract.put("formatEvolution", false);
    contract.put("realPowerLossClaim", false);
    contract.put("replication", false);
    contract.put("aeron", false);
    writeStrings(report.putArray("tutorialPermalinks"), TUTORIAL_PERMALINKS);
    writeStrings(report.putArray("requiredMutants"), REQUIRED_MUTANTS);
    writeStrings(
        report.putArray("missingCapabilities"),
        List.of(
            "M09S1_SNAPSHOT_CODEC",
            "COMPLETE_STATE_EXPORT_IMPORT",
            "DURABLE_SNAPSHOT_PUBLICATION",
            "SNAPSHOT_SUFFIX_RECOVERY",
            "RECOVERY_BUDGET_ENFORCEMENT",
            "WHOLE_SEGMENT_RETIREMENT",
            "M09_CRASH_WINDOW_SUITE",
            "M09_INDEPENDENT_RECOVERY_MODEL",
            "M09_MUTATION_COUNTEREXAMPLES"));
    ObjectNode release = report.putObject("releaseTarget");
    release.put("unitTag", "course/m09-complete");
    release.putNull("productRelease");
    release.put("verification", "M09_EVIDENCE_ONLY");

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
            Map.entry("planVersion", "0.11"),
            Map.entry("project", "matching"),
            Map.entry("unit", "M09"),
            Map.entry("lifecycle", "READY"),
            Map.entry("designDepth", "CONTRACT"),
            Map.entry("startRef", "course/m09-start"),
            Map.entry("completeRef", "course/m09-complete"),
            Map.entry("m09Check.expectedStatus", STATUS),
            Map.entry("evidencePath", "build/lab-evidence/M09/manifest.json"));
    require(properties.size() == expected.size(), "M09 course declaration field set changed");
    expected.forEach(
        (key, value) ->
            require(
                value.equals(properties.getProperty(key)),
                "M09 course declaration changed: " + key));
    return expected;
  }

  private static FrozenInput verifyFixed(Path root) {
    byte[] bytes = readBytes(root.resolve(FIXED_CORPUS_PATH));
    String digest = Hashing.sha256Hex(bytes);
    require(FIXED_CORPUS_SHA256.equals(digest), "M09 fixed corpus SHA-256 changed");
    String schema = readString(root.resolve(FIXED_SCHEMA_PATH));
    JsonNode fixture = JsonSupport.parse(bytes);
    JsonSupport.validate(fixture, schema, false);
    require(
        "CODE_LEVEL_INJECTION_AND_PROCESS_HALT".equals(fixture.path("faultModel").stringValue()),
        "M09 fault evidence model changed");
    List<String> scenarioIds = new ArrayList<>();
    Set<String> obligations = new LinkedHashSet<>();
    int operations = 0;
    for (JsonNode scenario : fixture.path("scenarios")) {
      scenarioIds.add(scenario.path("scenarioId").stringValue());
      operations += scenario.path("operations").size();
      obligations.addAll(strings(scenario.path("proofObligations")));
    }
    require(SCENARIO_IDS.equals(scenarioIds), "M09 scenario identity or order changed");
    require(operations == FIXED_OPERATIONS, "M09 fixed operation count changed");
    require(Set.copyOf(COVERAGE_IDS).equals(obligations), "M09 fixed obligation set changed");
    return new FrozenInput(digest, operations, negativeFixedProbes(fixture, schema));
  }

  private static FrozenInput verifyGenerator(Path root) {
    byte[] bytes = readBytes(root.resolve(GENERATOR_PATH));
    String digest = Hashing.sha256Hex(bytes);
    require(GENERATOR_SHA256.equals(digest), "M09 generator SHA-256 changed");
    String schema = readString(root.resolve(GENERATOR_SCHEMA_PATH));
    JsonNode profile = JsonSupport.parse(bytes);
    JsonSupport.validate(profile, schema, false);
    require("splitmix64-v1".equals(profile.path("algorithm").stringValue()), "M09 PRNG changed");
    require("5909".equals(profile.path("baseSeed").stringValue()), "M09 base seed changed");
    require(profile.path("histories").intValue() == HISTORIES, "M09 histories changed");
    require(
        profile.path("operationsPerHistory").intValue() == OPERATIONS_PER_HISTORY,
        "M09 operations per history changed");
    List<String> laneIds = new ArrayList<>();
    List<Integer> modulos = new ArrayList<>();
    for (JsonNode lane : profile.path("lanes")) {
      laneIds.add(lane.path("id").stringValue());
      modulos.add(lane.path("historyModulo").intValue());
      require(
          SCENARIO_IDS.contains(lane.path("prefixScenario").stringValue()),
          "M09 lane references unknown scenario");
    }
    require(LANE_IDS.equals(laneIds), "M09 lane identity or order changed");
    require(List.of(0, 1, 2, 3).equals(modulos), "M09 lane modulo changed");
    require(
        COVERAGE_IDS.equals(strings(profile.path("coverageRequirements"))),
        "M09 coverage order changed");
    require(
        REQUIRED_MUTANTS.equals(strings(profile.path("requiredMutants"))),
        "M09 mutant order changed");
    require(
        CRASH_WINDOW_IDS.equals(strings(profile.path("crashWindows"))),
        "M09 crash-window order changed");
    require(
        FAILURE_SEAM_IDS.equals(strings(profile.path("failureSeams"))),
        "M09 failure-seam order changed");
    JsonNode domain = profile.path("operationDomain");
    int weights =
        domain.path("submitWeight").intValue()
            + domain.path("duplicateOrConflictWeight").intValue()
            + domain.path("snapshotWeight").intValue()
            + domain.path("restartWeight").intValue()
            + domain.path("rolloverWeight").intValue()
            + domain.path("retireWeight").intValue()
            + domain.path("crashWeight").intValue();
    require(weights == 100, "M09 operation weights no longer total 100");
    JsonNode budget = profile.path("recoveryBudget");
    require(budget.path("maxSuffixRecords").intValue() == 64, "M09 record budget changed");
    require(budget.path("maxSuffixBytes").intValue() == 1_048_576, "M09 byte budget changed");
    require(
        "CHECKPOINT_REQUIRED_BEFORE_WAL".equals(budget.path("exhaustionResult").stringValue()),
        "M09 budget exhaustion result changed");
    return new FrozenInput(
        digest, TOTAL_GENERATED_OPERATIONS, negativeGeneratorProbes(profile, schema));
  }

  private static int negativeFixedProbes(JsonNode valid, String schema) {
    List<JsonNode> probes = new ArrayList<>();
    ObjectNode missing = (ObjectNode) valid.deepCopy();
    missing.remove("scenarios");
    probes.add(missing);
    ObjectNode extra = (ObjectNode) valid.deepCopy();
    extra.put("nMinusOneFormat", "M09S0");
    probes.add(extra);
    ObjectNode wrongFault = (ObjectNode) valid.deepCopy();
    wrongFault.put("faultModel", "REAL_POWER_LOSS");
    probes.add(wrongFault);
    ObjectNode invalidScenarioId = (ObjectNode) valid.deepCopy();
    ((ObjectNode) invalidScenarioId.path("scenarios").get(1)).put("scenarioId", "invalid-id");
    probes.add(invalidScenarioId);
    ObjectNode emptyObligations = (ObjectNode) valid.deepCopy();
    ((ObjectNode) emptyObligations.path("scenarios").get(0)).putArray("proofObligations");
    probes.add(emptyObligations);
    probes.forEach(value -> expectSchemaFailure(value, schema, "fixed"));
    return probes.size();
  }

  private static int negativeGeneratorProbes(JsonNode valid, String schema) {
    List<JsonNode> probes = new ArrayList<>();
    ObjectNode missing = (ObjectNode) valid.deepCopy();
    missing.remove("lanes");
    probes.add(missing);
    ObjectNode wrongSeed = (ObjectNode) valid.deepCopy();
    wrongSeed.put("baseSeed", "5910");
    probes.add(wrongSeed);
    ObjectNode duplicateCoverage = (ObjectNode) valid.deepCopy();
    ((ArrayNode) duplicateCoverage.path("coverageRequirements"))
        .set(1, duplicateCoverage.path("coverageRequirements").get(0));
    probes.add(duplicateCoverage);
    ObjectNode missingMutant = (ObjectNode) valid.deepCopy();
    ((ArrayNode) missingMutant.path("requiredMutants")).remove(11);
    probes.add(missingMutant);
    ObjectNode wrongBudget = (ObjectNode) valid.deepCopy();
    ((ObjectNode) wrongBudget.path("recoveryBudget")).put("maxSuffixRecords", 65);
    probes.add(wrongBudget);
    ObjectNode formatEvolution = (ObjectNode) valid.deepCopy();
    formatEvolution.put("nMinusOneFormat", "M09S0");
    probes.add(formatEvolution);
    probes.forEach(value -> expectSchemaFailure(value, schema, "generator"));
    return probes.size();
  }

  private static void expectSchemaFailure(JsonNode value, String schema, String boundary) {
    try {
      JsonSupport.validate(value, schema, false);
      throw new IllegalStateException("M09 " + boundary + " schema accepted a negative probe");
    } catch (FixtureSchemaException expected) {
      // Expected strict boundary rejection.
    }
  }

  private static void writeFrozen(
      ObjectNode target, FrozenInput frozen, int primaryCount, boolean generated) {
    target.put("sha256", frozen.digest());
    target.put("primaryCount", primaryCount);
    target.put("operations", frozen.operations());
    target.put("schemaProbes", frozen.schemaProbes());
    if (!generated) {
      require(frozen.operations() == FIXED_OPERATIONS, "M09 fixed operations changed");
    }
  }

  private static void writeBudget(ObjectNode budget) {
    budget.put("maxSuffixRecords", 64);
    budget.put("maxSuffixBytes", 1_048_576);
    budget.put("exhaustionResult", "CHECKPOINT_REQUIRED_BEFORE_WAL");
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
        throw new IllegalStateException("cannot clear M09 report directory", failure);
      }
    }
    try {
      Files.createDirectories(path);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot create M09 report directory", failure);
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
