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

/** Validates the frozen M10 qualification inputs and writes the intentional structured RED. */
public final class M10StartCheckRunner {
  public static final String STATUS = "GOAL_NOT_IMPLEMENTED";
  public static final String WORKLOAD_SHA256 =
      "dfa26aed0f41d29c3c4a1d3ad85c6a8793239e8f634100cadafaa496941a29cb";

  static final String WORKLOAD_PATH = "matching-testkit/src/test/resources/m10/workload-v1.json";
  static final String WORKLOAD_SCHEMA_PATH = "schemas/matching.m10.workload.v1.schema.json";
  static final String CHECK_SCHEMA_PATH = "schemas/matching.m10.check.v1.schema.json";

  static final int FIXED_SCENARIOS = 20;
  static final int HISTORIES = 64;
  static final int ACTIONS_PER_HISTORY = 256;
  static final int GENERATED_ACTIONS = HISTORIES * ACTIONS_PER_HISTORY;
  static final int LANES = 4;
  static final int QUEUE_CAPACITY = 64;
  static final int COVERAGE_OBLIGATIONS = 28;
  static final int MUTANTS = 12;
  static final int SCHEMA_PROBES = 8;

  static final List<String> SCENARIO_IDS =
      List.of(
          "CAPACITY_REJECTS_NON_POSITIVE",
          "TRY_SUBMIT_OWNS_CALLER_BYTES",
          "FULL_QUEUE_REJECTS_IMMEDIATELY",
          "OVERLOAD_REJECTION_PRECEDES_WAL",
          "OVERLOAD_REJECTION_PRESERVES_IDENTITY",
          "ENQUEUE_IS_NOT_DURABLE_ACK",
          "ONE_WORKER_PRESERVES_FIFO",
          "SUBMISSION_RESULT_PASSES_THROUGH_UNCHANGED",
          "CHECKPOINT_RETRY_REUSES_ENVELOPE",
          "CHECKPOINT_PAUSE_COUNTS_IN_LATENCY",
          "WORKER_FAILURE_CLOSES_ADMISSION",
          "ACCEPTED_PENDING_COMMANDS_FAIL_EXPLICITLY",
          "QUIESCE_REJECTS_NEW_OFFERS",
          "QUIESCE_DRAINS_ACCEPTED_OFFERS",
          "OFFER_AND_COMPLETION_TOTALS_RECONCILE",
          "OPEN_LOOP_USES_SCHEDULED_ARRIVAL",
          "RAW_SAMPLES_RECONCILE_PERCENTILES",
          "ENVIRONMENT_AND_MICRO_RESULTS_ARE_SEPARATE",
          "KNEE_AND_ABOVE_KNEE_ARE_EXPLICIT",
          "LOAD_RECOVERY_REMAINS_EXACT");

  static final List<String> LANE_IDS =
      List.of("BELOW_CAPACITY", "QUEUE_FULL", "CHECKPOINT_PAUSE", "FAIL_CLOSE_RETRY");

  static final List<String> COVERAGE_IDS =
      List.of(
          "POSITIVE_FINITE_CAPACITY",
          "TRY_SUBMIT_NON_BLOCKING",
          "CALLER_BYTES_OWNED",
          "FULL_REJECTS_OVERLOADED",
          "REJECTION_PRE_WAL",
          "REJECTION_PRE_APPLY_IDENTITY",
          "ENQUEUE_NOT_ACK",
          "SINGLE_WORKER_FIFO",
          "SUBMISSION_RESULT_UNCHANGED",
          "CHECKPOINT_SAME_ENVELOPE_RETRY",
          "CHECKPOINT_LATENCY_INCLUDED",
          "FAILURE_CLOSES_ADMISSION",
          "PENDING_EXPLICIT_FAILURE",
          "QUIESCE_REJECTS_NEW",
          "QUIESCE_DRAINS_ACCEPTED",
          "QUEUE_BOUNDED",
          "OFFER_RECONCILIATION",
          "COMPLETION_RECONCILIATION",
          "OPEN_LOOP_INDEPENDENT",
          "SCHEDULED_ARRIVAL_ORIGIN",
          "RAW_PERCENTILE_RECONCILIATION",
          "ENVIRONMENT_FINGERPRINT",
          "MICRO_E2E_SEPARATED",
          "DETERMINISTIC_KNEE",
          "ABOVE_KNEE_EXPLICIT_REJECTION",
          "RESOURCE_DIMENSIONS_PRESENT",
          "LOAD_REPLAY_RECOVERY_EXACT",
          "SYSTEM_ERROR_NEVER_PASS");

  static final List<String> REQUIRED_MUTANTS =
      List.of(
          "M10-UNBOUNDED-QUEUE",
          "M10-BLOCKING-PUT",
          "M10-REJECT-AFTER-WAL",
          "M10-REJECT-BINDS-IDENTITY",
          "M10-ENQUEUE-AS-ACK",
          "M10-DUAL-WORKER-REORDER",
          "M10-DROPPED-COMPLETION",
          "M10-METRICS-UNDERCOUNT",
          "M10-CLOSED-LOOP-GENERATOR",
          "M10-LATENCY-FROM-ACTUAL-SEND",
          "M10-WRONG-PERCENTILE-KNEE",
          "M10-SKIP-LOAD-RECOVERY-CHECK");

  static final List<String> TUTORIAL_PERMALINKS =
      List.of(
          "performance-contract-and-open-loop-workload",
          "bounded-admission-and-overload-semantics",
          "percentiles-knee-point-and-capacity-envelope",
          "allocation-gc-resource-and-soak-evidence",
          "matching-0-5-0-release-evidence");

  public Result run(Path repositoryRoot, Path reportDirectory) {
    return run(repositoryRoot, reportDirectory, repositoryRoot);
  }

  Result run(Path repositoryRoot, Path reportDirectory, Path trustedOutputRoot) {
    Path root = repositoryRoot.toAbsolutePath().normalize();
    Path reports = SafeOutputPaths.resolveTrustedOutput(trustedOutputRoot, reportDirectory);
    clear(reports);
    Map<String, String> declaration = verifyCourseDeclaration(root);
    Workload workload = verifyWorkload(root);

    ObjectNode report = JsonSupport.MAPPER.createObjectNode();
    report.put("schemaVersion", "matching.m10.check.v1");
    report.put("unit", "M10");
    report.put("status", STATUS);
    report.put("contractPlanVersion", "0.12");
    report.put(
        "objective",
        "Add bounded local admission and honest open-loop performance qualification without changing durable matching semantics.");
    ObjectNode course = report.putObject("courseDeclaration");
    declaration.forEach(course::put);
    ObjectNode inherited = report.putObject("inheritedBaseline");
    inherited.put("unit", "M09");
    inherited.put("completeRef", "course/m09-complete");
    inherited.put("expectedStatus", "PASS");

    ObjectNode profile = report.putObject("workloadProfile");
    profile.put("sha256", workload.digest());
    profile.put("seed", "6010");
    profile.put("fixedScenarios", FIXED_SCENARIOS);
    profile.put("generatedHistories", HISTORIES);
    profile.put("actionsPerHistory", ACTIONS_PER_HISTORY);
    profile.put("generatedActions", GENERATED_ACTIONS);
    profile.put("lanes", LANES);
    profile.put("queueCapacity", QUEUE_CAPACITY);
    profile.put("coverageObligations", COVERAGE_OBLIGATIONS);
    profile.put("requiredMutants", MUTANTS);
    profile.put("schemaProbes", workload.schemaProbes());

    ObjectNode admission = report.putObject("admissionContract");
    admission.put("submission", "TRY_SUBMIT_NON_BLOCKING");
    admission.put("fullResult", "OVERLOADED_BEFORE_WAL");
    admission.put("callerBytes", "OWNED_BEFORE_RETURN");
    admission.put("workerModel", "ONE_CALLER_SERIALIZED_LOCAL_RUNTIME_WORKER");
    admission.put("acceptedCompletion", "EXACTLY_ONE_SUBMISSION_RESULT_OR_EXPLICIT_FAILURE");
    admission.put("failureMode", "CLOSE_ADMISSION_AND_FAIL_PENDING");
    admission.put("quiesce", "REJECT_NEW_THEN_DRAIN_ACCEPTED");

    ObjectNode measurement = report.putObject("measurementContract");
    ObjectNode micro = measurement.putObject("microbenchmark");
    micro.put("harness", "JMH");
    micro.put("mode", "SampleTime");
    micro.put("resultScope", "DIAGNOSTIC_ONLY");
    micro.put("releaseGate", false);
    ObjectNode release = measurement.putObject("releaseOpenLoop");
    release.put("calibrationSeconds", 20);
    release.put("calibrationPurpose", "RATE_SELECTION_ONLY");
    release.put("sweeps", 3);
    release.put("warmupSecondsPerRate", 10);
    release.put("measurementSecondsPerRate", 30);
    writeInts(
        release.putArray("rateLadderPermille"),
        List.of(250, 500, 700, 850, 1000, 1150, 1350, 1600));
    writeDoubles(release.putArray("percentiles"), List.of(0.5, 0.95, 0.99, 0.999));
    release.put("latencyOrigin", "SCHEDULED_ARRIVAL");
    release.put("resultScope", "RELEASE_QUALIFICATION");
    ObjectNode smoke = measurement.putObject("ciSmoke");
    smoke.put("profileId", "CI_SMOKE");
    smoke.put("resultScope", "METHOD_SMOKE_ONLY");
    smoke.put("eligibleForReleaseEvidence", false);
    smoke.put("methodIsomorphic", true);
    ObjectNode saturation = measurement.putObject("saturation");
    saturation.put("overloadRejectCount", "GREATER_THAN_ZERO");
    saturation.put("p99QueueDepthPermilleOfCapacity", 800);
    saturation.put("minimumCompletedPerAdmittedPermille", 995);
    saturation.put("maximumEndBacklogGrowthPermilleOfCapacity", 100);
    saturation.put("perSweepKnee", "FIRST_OF_TWO_CONSECUTIVE_SATURATED_RATES");
    saturation.put("publishedKnee", "MIN_OF_THREE_SWEEP_KNEES");
    saturation.put("qualifiedOperatingPoint", "FLOOR_70_PERCENT_OF_PUBLISHED_KNEE");
    ObjectNode soak = measurement.putObject("releaseSoak");
    soak.put("load", "QUALIFIED_OPERATING_POINT");
    soak.put("durationSeconds", 1800);
    soak.put("resultScope", "RELEASE_QUALIFICATION");
    writeStrings(
        measurement.putArray("resourceDimensions"),
        List.of("ALLOCATION", "GC", "CPU", "MEMORY", "QUEUE_DEPTH"));

    writeStrings(report.putArray("coverageObligations"), COVERAGE_IDS);
    writeStrings(report.putArray("requiredMutants"), REQUIRED_MUTANTS);
    writeStrings(report.putArray("tutorialPermalinks"), TUTORIAL_PERMALINKS);
    writeStrings(
        report.putArray("missingCapabilities"),
        List.of(
            "BOUNDED_LOCAL_MATCHING_SERVICE",
            "NON_BLOCKING_ADMISSION",
            "OVERLOAD_BEFORE_WAL",
            "EXPLICIT_COMPLETION_RECONCILIATION",
            "GENERATED_ADMISSION_MODEL",
            "JMH_DIAGNOSTIC_SUITE",
            "OPEN_LOOP_QUALIFICATION_RUNNER",
            "RAW_LATENCY_AND_RESOURCE_EVIDENCE",
            "DETERMINISTIC_KNEE_SELECTION",
            "QUALIFIED_OPERATING_POINT_SOAK",
            "LOAD_RECOVERY_EQUIVALENCE",
            "M10_MUTATION_COUNTEREXAMPLES"));
    ObjectNode target = report.putObject("releaseTarget");
    target.put("unitTag", "course/m10-complete");
    target.put("productRelease", "matching-0.5.0");
    target.put("verification", "FULL_RELEASE_PROFILE_AND_CLEAN_TREE_EVIDENCE");

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
            Map.entry("planVersion", "0.12"),
            Map.entry("project", "matching"),
            Map.entry("unit", "M10"),
            Map.entry("lifecycle", "READY"),
            Map.entry("designDepth", "CONTRACT"),
            Map.entry("startRef", "course/m10-start"),
            Map.entry("completeRef", "course/m10-complete"),
            Map.entry("m10Check.expectedStatus", STATUS),
            Map.entry("evidencePath", "build/lab-evidence/M10/manifest.json"));
    require(properties.size() == expected.size(), "M10 course declaration field set changed");
    expected.forEach(
        (key, value) ->
            require(
                value.equals(properties.getProperty(key)),
                "M10 course declaration changed: " + key));
    return expected;
  }

  private static Workload verifyWorkload(Path root) {
    byte[] bytes = readBytes(root.resolve(WORKLOAD_PATH));
    String digest = Hashing.sha256Hex(bytes);
    require(WORKLOAD_SHA256.equals(digest), "M10 workload SHA-256 changed");
    String schema = readString(root.resolve(WORKLOAD_SCHEMA_PATH));
    JsonNode workload = JsonSupport.parse(bytes);
    JsonSupport.validate(workload, schema, false);

    require("6010".equals(workload.path("seed").stringValue()), "M10 seed changed");
    List<String> scenarioIds = new ArrayList<>();
    Set<String> scenarioObligations = new LinkedHashSet<>();
    for (JsonNode scenario : workload.path("fixedAdmissionScenarios")) {
      scenarioIds.add(scenario.path("id").stringValue());
      scenarioObligations.addAll(strings(scenario.path("proofObligations")));
    }
    require(SCENARIO_IDS.equals(scenarioIds), "M10 fixed scenario identity or order changed");
    require(
        Set.copyOf(COVERAGE_IDS).equals(scenarioObligations),
        "M10 fixed scenarios no longer cover every obligation");

    JsonNode generated = workload.path("generatedAdmissionModel");
    require(generated.path("histories").intValue() == HISTORIES, "M10 histories changed");
    require(
        generated.path("actionsPerHistory").intValue() == ACTIONS_PER_HISTORY,
        "M10 actions per history changed");
    require(
        generated.path("totalActions").intValue() == GENERATED_ACTIONS,
        "M10 generated action count changed");
    List<String> laneIds = new ArrayList<>();
    List<Integer> laneModulos = new ArrayList<>();
    for (JsonNode lane : generated.path("lanes")) {
      laneIds.add(lane.path("id").stringValue());
      laneModulos.add(lane.path("historyModulo").intValue());
    }
    require(LANE_IDS.equals(laneIds), "M10 generated lane identity or order changed");
    require(List.of(0, 1, 2, 3).equals(laneModulos), "M10 lane modulo changed");
    require(
        workload.path("admissionContract").path("queueCapacity").intValue() == QUEUE_CAPACITY,
        "M10 queue capacity changed");
    require(
        COVERAGE_IDS.equals(strings(workload.path("coverageRequirements"))),
        "M10 obligation order changed");
    require(
        REQUIRED_MUTANTS.equals(strings(workload.path("requiredMutants"))),
        "M10 mutant order changed");
    require(
        TUTORIAL_PERMALINKS.equals(strings(workload.path("tutorialPermalinks"))),
        "M10 tutorial permalink order changed");
    require(
        !workload.path("ciSmoke").path("eligibleForReleaseEvidence").booleanValue(),
        "M10 CI smoke cannot become release evidence");
    require(
        "DIAGNOSTIC_ONLY".equals(workload.path("microbenchmark").path("resultScope").stringValue()),
        "M10 microbenchmark scope changed");
    require(
        workload.path("releaseSoak").path("durationSeconds").intValue() == 1800,
        "M10 release soak duration changed");
    return new Workload(digest, negativeWorkloadProbes(workload, schema));
  }

  private static int negativeWorkloadProbes(JsonNode valid, String schema) {
    List<JsonNode> probes = new ArrayList<>();
    ObjectNode missingGenerated = (ObjectNode) valid.deepCopy();
    missingGenerated.remove("generatedAdmissionModel");
    probes.add(missingGenerated);
    ObjectNode wrongSeed = (ObjectNode) valid.deepCopy();
    wrongSeed.put("seed", "6011");
    probes.add(wrongSeed);
    ObjectNode duplicateScenario = (ObjectNode) valid.deepCopy();
    ((ArrayNode) duplicateScenario.path("fixedAdmissionScenarios"))
        .set(1, duplicateScenario.path("fixedAdmissionScenarios").get(0));
    probes.add(duplicateScenario);
    ObjectNode missingObligation = (ObjectNode) valid.deepCopy();
    ((ArrayNode) missingObligation.path("coverageRequirements")).remove(27);
    probes.add(missingObligation);
    ObjectNode wrongCapacity = (ObjectNode) valid.deepCopy();
    ((ObjectNode) wrongCapacity.path("admissionContract")).put("queueCapacity", 65);
    probes.add(wrongCapacity);
    ObjectNode promotedSmoke = (ObjectNode) valid.deepCopy();
    ((ObjectNode) promotedSmoke.path("ciSmoke")).put("eligibleForReleaseEvidence", true);
    probes.add(promotedSmoke);
    ObjectNode missingPercentile = (ObjectNode) valid.deepCopy();
    ((ArrayNode) missingPercentile.path("releaseOpenLoop").path("percentiles")).remove(3);
    probes.add(missingPercentile);
    ObjectNode absoluteThroughput = (ObjectNode) valid.deepCopy();
    absoluteThroughput.put("minimumOrdersPerSecond", 1_000_000);
    probes.add(absoluteThroughput);
    probes.forEach(value -> expectSchemaFailure(value, schema));
    return probes.size();
  }

  private static void expectSchemaFailure(JsonNode value, String schema) {
    try {
      JsonSupport.validate(value, schema, false);
      throw new IllegalStateException("M10 workload schema accepted a negative probe");
    } catch (FixtureSchemaException expected) {
      // Expected strict boundary rejection.
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

  private static void writeInts(ArrayNode target, List<Integer> values) {
    values.forEach(target::add);
  }

  private static void writeDoubles(ArrayNode target, List<Double> values) {
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
        throw new IllegalStateException("cannot clear M10 report directory", failure);
      }
    }
    try {
      Files.createDirectories(path);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot create M10 report directory", failure);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }

  record Workload(String digest, int schemaProbes) {}

  public record Result(String status, Path reportPath) {}
}
