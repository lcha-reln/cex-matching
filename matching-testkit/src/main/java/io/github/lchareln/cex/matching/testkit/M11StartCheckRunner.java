package io.github.lchareln.cex.matching.testkit;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Validates the frozen M11 inputs and writes the intentional schema-valid RED report. */
public final class M11StartCheckRunner {
  public static final String STATUS = "GOAL_NOT_IMPLEMENTED";
  public static final String WORKLOAD_SHA256 =
      "f856c8dcf2e902add248a59cdb97525083bae469745682eed0ea7ae9169033b6";

  static final String WORKLOAD_PATH = "matching-testkit/src/test/resources/m11/workload-v1.json";
  static final String WORKLOAD_SCHEMA_PATH = "schemas/matching.m11.workload.v1.schema.json";
  static final String CHECK_SCHEMA_PATH = "schemas/matching.m11.check.v1.schema.json";

  static final List<String> SCENARIO_IDS =
      List.of(
          "CODEC_V1_GOLDENS",
          "CODEC_V2_GOLDENS",
          "MALFORMED_FAILS_CLOSED",
          "UNSUPPORTED_VERSION_FAILS_CLOSED",
          "REAL_SINGLE_MEMBER_LEADER",
          "OFFER_IS_NOT_SUCCESS",
          "CORRELATION_ROUND_TRIP",
          "SESSION_NOT_BUSINESS_IDENTITY",
          "NEW_RESPONSE_AFTER_APPLY",
          "DUPLICATE_REPLAYS_ORIGINAL",
          "COMMAND_ID_CONFLICT_NO_MUTATION",
          "SLOT_CONFLICT_NO_MUTATION",
          "DIRECT_CLUSTER_EVENTS_EQUAL",
          "DIRECT_CLUSTER_DIGEST_EQUAL",
          "RUNTIME_METADATA_EXCLUDED",
          "NO_STANDALONE_WAL_WRITE",
          "SNAPSHOT_ACCEPTANCE_AND_COMPLETION_DISTINCT",
          "SNAPSHOT_STATE_EXACT_AFTER_RESTART",
          "SNAPSHOT_IDENTITY_RESULT_SURVIVES",
          "SNAPSHOT_SEQUENCE_CONTINUES",
          "CURRENT_READS_PREVIOUS_SNAPSHOT",
          "CURRENT_DOWN_ENCODES_PREVIOUS_RESPONSE");

  static final List<String> LANE_IDS =
      List.of("CURRENT_NEW", "DUPLICATE_REPLAY", "PREVIOUS_NEW", "IDENTITY_CONFLICT");

  static final List<String> SEGMENT_SCHEDULE =
      List.of(
          "CURRENT_NEW[0]",
          "CURRENT_NEW[1]",
          "CURRENT_NEW[2]",
          "CURRENT_NEW[3]",
          "CURRENT_NEW[4]",
          "CURRENT_NEW[5]",
          "CURRENT_NEW[6]",
          "CURRENT_NEW[7]",
          "DUPLICATE_REPLAY[0]",
          "DUPLICATE_REPLAY[1]",
          "DUPLICATE_REPLAY[2]",
          "DUPLICATE_REPLAY[3]",
          "PREVIOUS_NEW[0]",
          "PREVIOUS_NEW[1]",
          "PREVIOUS_NEW[2]",
          "PREVIOUS_NEW[3]",
          "PREVIOUS_NEW[4]",
          "PREVIOUS_NEW[5]",
          "PREVIOUS_NEW[6]",
          "PREVIOUS_NEW[7]",
          "DUPLICATE_REPLAY[4]",
          "DUPLICATE_REPLAY[5]",
          "DUPLICATE_REPLAY[6]",
          "DUPLICATE_REPLAY[7]",
          "IDENTITY_CONFLICT[0]",
          "IDENTITY_CONFLICT[1]",
          "IDENTITY_CONFLICT[2]",
          "IDENTITY_CONFLICT[3]",
          "IDENTITY_CONFLICT[4]",
          "IDENTITY_CONFLICT[5]",
          "IDENTITY_CONFLICT[6]",
          "IDENTITY_CONFLICT[7]");

  static final List<String> COVERAGE_IDS =
      List.of(
          "AERON_VERSION_PINNED",
          "REAL_SINGLE_MEMBER_CLUSTER",
          "CORE_INFRASTRUCTURE_FREE",
          "AERON_DEPENDENCY_CONFINED",
          "NO_STANDALONE_WAL_WRITE",
          "LOG_CALLBACK_ONLY_APPLY",
          "INGRESS_OFFER_NOT_ACK",
          "CORRELATION_ROUND_TRIP",
          "COMMAND_ID_STABLE",
          "SESSION_NOT_BUSINESS_IDENTITY",
          "RESPONSE_AFTER_RESULT_BIND",
          "EGRESS_OFFER_NOT_STATE_INPUT",
          "MALFORMED_FAILS_CLOSED",
          "UNSUPPORTED_VERSION_FAILS_CLOSED",
          "DUPLICATE_ORIGINAL_RESULT",
          "ID_CONFLICT_NO_MUTATION",
          "SLOT_CONFLICT_NO_MUTATION",
          "DIRECT_CLUSTER_EVENT_EQUIVALENCE",
          "DIRECT_CLUSTER_RESULT_EQUIVALENCE",
          "DIRECT_CLUSTER_STATE_EQUIVALENCE",
          "RUNTIME_METADATA_EXCLUDED",
          "SNAPSHOT_COMPLETION_RECORDED",
          "SNAPSHOT_RESTART_EXACT",
          "SNAPSHOT_IDENTITY_SURVIVES",
          "SNAPSHOT_SEQUENCE_CONTINUES",
          "N_MINUS_ONE_READABLE",
          "CURRENT_GOLDEN_WRITE_EXACT",
          "SYSTEM_ERROR_NEVER_PASS");

  static final List<String> MUTANT_IDS =
      List.of(
          "M11-OFFER-AS-SUCCESS",
          "M11-SESSION-AS-IDENTITY",
          "M11-CORRELATION-AS-IDENTITY",
          "M11-RESPOND-BEFORE-BIND",
          "M11-DROP-IDENTITY-FROM-SNAPSHOT",
          "M11-CORRUPT-SNAPSHOT-TO-GENESIS",
          "M11-REJECT-N-MINUS-ONE",
          "M11-INCLUDE-RUNTIME-METADATA-IN-DIGEST",
          "M11-DOUBLE-WRITE-LOCAL-WAL",
          "M11-ACCEPT-UNSUPPORTED-VERSION");

  static final List<String> SYSTEM_ERROR_IDS =
      List.of(
          "M11-THROWING-CODEC-CONTROL",
          "M11-CLUSTER-STARTUP-CONTROL",
          "M11-CORRUPT-HARNESS-OUTPUT-CONTROL");

  static final List<String> TUTORIAL_PERMALINKS =
      List.of(
          "aeron-cluster-adapter-and-single-recovery-truth",
          "cluster-codec-golden-bytes-and-compatibility",
          "cluster-ingress-log-apply-and-correlated-response",
          "cluster-snapshot-restart-and-direct-equivalence",
          "single-node-aeron-adapter-evidence");

  public Result run(Path repositoryRoot, Path reportDirectory) {
    return run(repositoryRoot, reportDirectory, repositoryRoot);
  }

  Result run(Path repositoryRoot, Path reportDirectory, Path trustedOutputRoot) {
    Path root = repositoryRoot.toAbsolutePath().normalize();
    Path reports = SafeOutputPaths.resolveTrustedOutput(trustedOutputRoot, reportDirectory);
    clear(reports);
    Map<String, String> declaration = verifyCourseDeclaration(root);
    Workload workload = verifyWorkload(root);
    verifyStartArchitecture(root);

    ObjectNode report = JsonSupport.MAPPER.createObjectNode();
    report.put("schemaVersion", "matching.m11.check.v1");
    report.put("unit", "M11");
    report.put("status", STATUS);
    report.put("contractPlanVersion", "0.14");
    report.put("objective", workload.document().path("objective").stringValue());
    ObjectNode course = report.putObject("courseDeclaration");
    declaration.forEach(course::put);
    ObjectNode inherited = report.putObject("inheritedBaseline");
    inherited.put("unit", "M10");
    inherited.put("completeRef", "course/m10-complete");
    inherited.put("productRelease", "matching-0.5.0");
    inherited.put("expectedStatus", "PASS");

    ObjectNode profile = report.putObject("workloadProfile");
    profile.put("sha256", workload.digest());
    profile.put("fixedScenarios", 22);
    profile.put("seed", "6111");
    profile.put("generatedHistories", 32);
    profile.put("actionsPerHistory", 128);
    profile.put("generatedActions", 4096);
    profile.put("composition", "ONE_CONTINUOUS_CORPUS_OF_32_ORDERED_SEGMENTS");
    profile.put("segmentOrder", "FIXED_DECLARED_32_SEGMENT_SCHEDULE");
    writeStrings(profile.putArray("segmentSchedule"), SEGMENT_SCHEDULE);
    profile.put("stateResetBetweenSegments", false);
    profile.put("sequenceContinuity", "APPLICATION_AND_PRODUCER_CURSORS_CONTINUE_ACROSS_SEGMENTS");
    profile.put("snapshotCutScope", "GLOBAL_ACTION_BOUNDARY_AFTER_2048");
    profile.put("lanes", 4);
    profile.put("snapshotAfterAction", 2048);
    profile.put("coverageObligations", 28);
    profile.put("requiredMutants", 10);
    profile.put("systemErrorControls", 3);

    ObjectNode protocol = report.putObject("protocolContract");
    writeVersion(protocol.putObject("applicationRequest"));
    writeVersion(protocol.putObject("applicationResponse"));
    writeVersion(protocol.putObject("clusterSnapshot"));
    protocol.put("identity", "M08C1_COMMAND_ID_SLOT_PAYLOAD_HASH");
    protocol.put("correlation", "INVOCATION_ONLY_NOT_BUSINESS_IDENTITY");
    protocol.put("sessionIdentity", "TRANSPORT_ONLY");
    protocol.put("offerMeaning", "INGRESS_PUBLICATION_ACCEPTED_NOT_BUSINESS_SUCCESS");
    protocol.put("responseMeaning", "BOUND_RESULT_AFTER_APPLY");
    protocol.put("requestV1ResponseVersion", 1);
    writeInts(protocol.putArray("requestV2AllowedResponseVersions"), List.of(1, 2));
    protocol.put(
        "unsupportedRequestedResponseVersion",
        "PRE_APPLY_PROTOCOL_FAILURE_NO_BUSINESS_RESPONSE_NO_STATE_MUTATION");
    protocol.put("responseDownEncodePolicy", "ALL_VALID_BUSINESS_OUTCOMES_TO_V1_OMIT_V2_EXTENSION");
    protocol.put("responseV2IdentityEcho", "COMMAND_ID_ONLY_SLOT_AND_PAYLOAD_HASH_NOT_PRESENT");
    protocol.put("payloadHashDomain", "SHA256_CANONICAL_M08_COMMAND_PAYLOAD_BYTES");
    protocol.put("compatibilityClaim", "BACKWARD_READ_AND_RESPONSE_DOWN_ENCODE_ONLY");
    protocol.put("previousVersionStatus", "FIXTURE_ONLY_NEVER_PRODUCTION");
    protocol.put("rollbackClaim", false);
    protocol.put("fullEventStreamInResponse", false);
    protocol.put("snapshotStateCoverage", "S1_AND_S2_COMPLETE_CORE_IDENTITY_FULL_ORIGINAL_RESULTS");
    protocol.put("s2Delta", "PROTOCOL_BOUNDS_AND_VALIDATION_FIELDS_ONLY");
    protocol.put("nMinusOneRestore", "NO_IDEMPOTENCY_OR_ORIGINAL_RESULT_LOSS");
    protocol.put(
        "snapshotIdentityOrdering", "STRICT_ASCENDING_ORIGINAL_APPLICATION_SEQUENCE_1_TO_N");
    protocol.put(
        "snapshotIdentityValidation",
        "UNIQUE_COMMAND_ID_AND_SLOT_CONTIGUOUS_PRODUCER_EPOCH_SEQUENCE");
    protocol.put("goldenSnapshotBindings", 2);

    ObjectNode cluster = report.putObject("clusterContract");
    cluster.put("aeronVersion", "1.52.2");
    cluster.put("agronaVersion", "2.5.0");
    cluster.put("memberCount", 1);
    cluster.put("memberId", 0);
    cluster.put("appointedLeaderId", 0);
    cluster.put("corpusActionsPerPath", 4096);
    cluster.put("clusterRuns", 2);
    cluster.put("totalActualClusterIngress", 8192);
    writeStrings(
        cluster.putArray("comparisonPaths"),
        List.of("DIRECT", "UNINTERRUPTED_CLUSTER", "SNAPSHOT_RESTART_CLUSTER"));
    cluster.put("snapshotAfterAction", 2048);
    cluster.put("snapshotPrefixNewApplied", 1536);
    cluster.put("snapshotPrefixDuplicateReplayed", 512);
    cluster.put("snapshotApplicationSequence", 1536);
    cluster.put("snapshotNextApplicationSequence", 1537);
    cluster.put("firstPostRestartLane", "PREVIOUS_NEW");
    cluster.put("firstPostRestartExpectedStatus", "NEW_APPLIED");
    cluster.put("postRestartCrossSnapshotDuplicates", 512);
    cluster.put("adminSnapshotAcceptance", "OK_REQUIRED_NOT_COMPLETION");
    writeStrings(
        cluster.putArray("snapshotCompletionWitnesses"),
        List.of(
            "SNAPSHOT_COUNTER_INCREMENTED",
            "CONTROL_TOGGLE_RESET_TO_NEUTRAL",
            "NEW_CONSENSUS_MINUS_ONE_AND_SERVICE_ZERO_RECORDING_LOG_ENTRIES_SAME_TERM_POSITION_NEW_RECORDING_IDS",
            "WRITTEN_SNAPSHOT_PAYLOAD_DIGEST_CAPTURED",
            "RESTART_LOADS_NON_NULL_SNAPSHOT_IMAGE_WITH_SAME_DIGEST_AND_SEQUENCE"));
    cluster.put("completionRequiredBeforeShutdown", true);
    cluster.put("controlledRestart", true);
    cluster.put("directComparison", "FULL_EVENTS_RESULT_AND_SEMANTIC_DIGEST");
    cluster.put("standaloneWalWrites", 0);
    cluster.put("highAvailabilityClaim", false);
    cluster.put("performanceClaim", false);
    cluster.put("externalServices", false);
    writeStrings(
        cluster.putArray("freshClusterRoots"),
        List.of("build/tmp/m11/uninterrupted", "build/tmp/m11/snapshot-restart"));
    cluster.put("portAllocation", "DISJOINT_LOCAL_PORT_BLOCKS");

    writeStrings(report.putArray("coverageObligations"), COVERAGE_IDS);
    writeStrings(report.putArray("requiredMutants"), MUTANT_IDS);
    writeStrings(report.putArray("systemErrorControls"), SYSTEM_ERROR_IDS);
    ArrayNode goldens = report.putArray("goldenFixtures");
    for (JsonNode fixture : workload.document().path("goldenFixtures")) {
      ObjectNode binding = goldens.addObject();
      binding.put("id", fixture.path("id").stringValue());
      binding.put("path", fixture.path("path").stringValue());
      binding.put("bytes", fixture.path("bytes").intValue());
      binding.put("sha256", fixture.path("sha256").stringValue());
      binding.put("byteExact", true);
    }
    writeStrings(report.putArray("tutorialPermalinks"), TUTORIAL_PERMALINKS);
    writeStrings(
        report.putArray("missingCapabilities"),
        List.of(
            "MATCHING_CLUSTER_RUNTIME_MODULE",
            "AERON_DEPENDENCIES",
            "APPLICATION_REQUEST_RESPONSE_CODECS",
            "DIRECT_ADAPTER",
            "CLUSTERED_SERVICE_ADAPTER",
            "REAL_SINGLE_MEMBER_CLUSTER_HARNESS",
            "CORRELATED_CLIENT_RESPONSE",
            "CLUSTER_SNAPSHOT_CODEC_AND_TRANSPORT",
            "CONTROLLED_CLUSTER_RESTART",
            "DIRECT_CLUSTER_DIFFERENTIAL_JUDGE",
            "M11_MUTANTS_AND_COUNTEREXAMPLES",
            "M11_EVIDENCE_WRITER"));
    ObjectNode release = report.putObject("releaseTarget");
    release.put("unitTag", "course/m11-complete");
    release.putNull("productRelease");
    release.put("verification", "CLEAN_TREE_TAG_BOUND_EVIDENCE");
    ObjectNode execution = report.putObject("executionContract");
    execution.put("command", "./gradlew m11Check --no-daemon");
    execution.put("structuredRedExitCode", 1);
    execution.put("defaultBuildGate", "M10_PASS");

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
            Map.entry("planVersion", "0.14"),
            Map.entry("project", "matching"),
            Map.entry("unit", "M11"),
            Map.entry("lifecycle", "READY"),
            Map.entry("designDepth", "CONTRACT"),
            Map.entry("startRef", "course/m11-start"),
            Map.entry("completeRef", "course/m11-complete"),
            Map.entry("m11Check.expectedStatus", STATUS),
            Map.entry("evidencePath", "build/lab-evidence/M11/manifest.json"));
    require(properties.size() == expected.size(), "M11 course declaration field set changed");
    expected.forEach(
        (key, value) ->
            require(value.equals(properties.getProperty(key)), "M11 declaration changed: " + key));
    return expected;
  }

  private static Workload verifyWorkload(Path root) {
    byte[] bytes = readBytes(root.resolve(WORKLOAD_PATH));
    String digest = Hashing.sha256Hex(bytes);
    require(WORKLOAD_SHA256.equals(digest), "M11 workload SHA-256 changed");
    JsonNode document = JsonSupport.parse(bytes);
    JsonSupport.validate(document, readString(root.resolve(WORKLOAD_SCHEMA_PATH)), false);

    List<String> scenarios = new ArrayList<>();
    Set<String> witnessed = new LinkedHashSet<>();
    for (JsonNode scenario : document.path("fixedScenarios")) {
      scenarios.add(scenario.path("id").stringValue());
      witnessed.addAll(strings(scenario.path("proofObligations")));
    }
    require(SCENARIO_IDS.equals(scenarios), "M11 scenario identity or order changed");
    require(Set.copyOf(COVERAGE_IDS).equals(witnessed), "M11 scenario obligations are incomplete");
    require(
        COVERAGE_IDS.equals(strings(document.path("coverageRequirements"))),
        "M11 coverage order changed");
    require(
        MUTANT_IDS.equals(strings(document.path("requiredMutants"))), "M11 mutant order changed");
    require(
        SYSTEM_ERROR_IDS.equals(strings(document.path("systemErrorControls"))),
        "M11 control order changed");
    require(
        TUTORIAL_PERMALINKS.equals(strings(document.path("tutorialPermalinks"))),
        "M11 permalink order changed");
    List<String> lanes = new ArrayList<>();
    for (JsonNode lane : document.path("generatedDifferential").path("lanes")) {
      lanes.add(lane.path("id").stringValue());
      require(lane.path("histories").intValue() == 8, "M11 lane size changed");
    }
    require(LANE_IDS.equals(lanes), "M11 lane identity or order changed");
    JsonNode generated = document.path("generatedDifferential");
    require(
        "ONE_CONTINUOUS_CORPUS_OF_32_ORDERED_SEGMENTS"
            .equals(generated.path("composition").stringValue()),
        "M11 corpus composition changed");
    require(
        "FIXED_DECLARED_32_SEGMENT_SCHEDULE".equals(generated.path("segmentOrder").stringValue()),
        "M11 segment order changed");
    require(
        SEGMENT_SCHEDULE.equals(strings(generated.path("segmentSchedule"))),
        "M11 segment schedule changed");
    require(
        !generated.path("stateResetBetweenSegments").booleanValue(), "M11 segment reset changed");
    require(
        "GLOBAL_ACTION_BOUNDARY_AFTER_2048"
            .equals(generated.path("snapshotCutScope").stringValue()),
        "M11 snapshot cut scope changed");
    verifyGoldens(root, document.path("goldenFixtures"));
    return new Workload(digest, document);
  }

  private static void verifyGoldens(Path root, JsonNode bindings) {
    List<M11ContractGoldens.Fixture> expected = M11ContractGoldens.fixtures();
    require(bindings.size() == expected.size(), "M11 golden count changed");
    for (int index = 0; index < expected.size(); index++) {
      M11ContractGoldens.Fixture fixture = expected.get(index);
      JsonNode binding = bindings.get(index);
      String expectedPath = "matching-testkit/src/test/resources/m11/goldens/" + fixture.fileName();
      require(fixture.id().equals(binding.path("id").stringValue()), "M11 golden ID changed");
      require(fixture.kind().equals(binding.path("kind").stringValue()), "M11 golden kind changed");
      require(
          fixture.schemaVersion() == binding.path("schemaVersion").intValue(),
          "M11 golden version changed");
      require(expectedPath.equals(binding.path("path").stringValue()), "M11 golden path changed");
      Path path = root.resolve(expectedPath).normalize();
      require(path.startsWith(root) && !Files.isSymbolicLink(path), "M11 golden path is unsafe");
      byte[] actual = readBytes(path);
      require(Arrays.equals(fixture.bytes(), actual), "M11 generated golden bytes changed");
      require(actual.length == binding.path("bytes").intValue(), "M11 golden length changed");
      require(
          Hashing.sha256Hex(actual).equals(binding.path("sha256").stringValue()),
          "M11 golden SHA-256 changed");
    }
  }

  private static void verifyStartArchitecture(Path root) {
    require(
        !Files.exists(root.resolve("matching-cluster-runtime")),
        "M11 start must not contain the Aeron module");
    String settings = readString(root.resolve("settings.gradle.kts"));
    require(
        !settings.contains("matching-cluster-runtime"),
        "M11 start settings already include the Aeron module");
  }

  private static void writeVersion(ObjectNode node) {
    node.put("minimumReadable", 1);
    node.put("currentWriter", 2);
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
        throw new IllegalStateException("cannot clear M11 report directory", failure);
      }
    }
    try {
      Files.createDirectories(path);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot create M11 report directory", failure);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }

  record Workload(String digest, JsonNode document) {}

  public record Result(String status, Path reportPath) {}
}
