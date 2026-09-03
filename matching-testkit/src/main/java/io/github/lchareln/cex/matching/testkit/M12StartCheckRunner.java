package io.github.lchareln.cex.matching.testkit;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Validates the frozen M12 inputs and writes the intentional schema-valid RED report. */
public final class M12StartCheckRunner {
  public static final String STATUS = "GOAL_NOT_IMPLEMENTED";
  public static final String WORKLOAD_SHA256 =
      "20ed1e75cd3cd86dc15a7f1f64465524a5638757abe676eb51f20bc5423b89a1";

  static final String BASELINE_COMMIT = "6997e05cea81cb93b883e882c8d75887d0622a22";
  static final String WORKLOAD_PATH = "matching-testkit/src/test/resources/m12/workload-v1.json";
  static final String WORKLOAD_SCHEMA_PATH = "schemas/matching.m12.workload.v1.schema.json";
  static final String CHECK_SCHEMA_PATH = "schemas/matching.m12.check.v1.schema.json";

  static final List<String> SCENARIO_IDS =
      List.of(
          "THREE_REAL_MEMBERS_INITIAL_ELECTION",
          "CORRELATED_RESPONSE_IS_ACK",
          "INGRESS_OFFER_IS_NOT_ACK",
          "PRE_OFFER_IS_NOT_SUBMITTED",
          "APPLIED_RESPONSE_UNOBSERVED_IS_UNKNOWN",
          "UNKNOWN_RETRY_SAME_IDENTITY",
          "LEADER_KILL_AND_REPLACEMENT_ELECTION",
          "CLIENT_RECONNECTS_TO_REPLACEMENT",
          "POST_FAILOVER_CONTINUITY",
          "FORMER_LEADER_RESTART_CATCH_UP",
          "ALL_MEMBER_STATE_EQUIVALENCE",
          "MINORITY_CANNOT_ACK",
          "QUORUM_RESTORE_CONVERGES_UNKNOWN",
          "INHERITED_BOUNDARIES_REMAIN");

  static final List<String> PHASE_ORDER =
      List.of(
          "INITIAL_ELECTION",
          "PRE_FAILOVER_ACKNOWLEDGED_NEW_32",
          "ACKNOWLEDGED_DUPLICATE_RETRY_8",
          "APPLIED_RESPONSE_UNOBSERVED_UNKNOWN_1",
          "EXTERNAL_CURRENT_LEADER_KILL",
          "REPLACEMENT_LEADER_ELECTION",
          "SAME_IDENTITY_UNKNOWN_RETRY",
          "POST_FAILOVER_ACKNOWLEDGED_NEW_32",
          "POST_FAILOVER_DUPLICATE_RETRY_8",
          "FORMER_LEADER_RESTART_AND_CATCH_UP",
          "LOSE_MAJORITY",
          "NO_QUORUM_UNKNOWN_1",
          "RESTORE_QUORUM_AND_SAME_IDENTITY_RETRY",
          "RESTORE_ALL_MEMBERS_AND_CONVERGE");

  static final List<String> COVERAGE_IDS =
      List.of(
          "THREE_REAL_MEMBERS",
          "SINGLE_INITIAL_LEADER",
          "DISJOINT_MEMBER_OWNERSHIP",
          "CORRELATED_APPLY_ACK",
          "M11_PROTOCOL_UNCHANGED",
          "INGRESS_OFFER_NOT_ACK",
          "PRE_OFFER_NOT_SUBMITTED",
          "AFTER_OFFER_UNKNOWN",
          "SAME_IDENTITY_RETRY",
          "ORIGINAL_RESULT_REPLAY",
          "ONE_EFFECT_PER_IDENTITY",
          "EXTERNAL_LEADER_KILL",
          "NEW_LEADER_ELECTED",
          "LEADERSHIP_TERM_ADVANCES",
          "CLIENT_CURRENT_LEADER_AUTHORITY",
          "STALE_LEADER_NOT_ACKNOWLEDGED",
          "APPLICATION_SEQUENCE_CONTINUES",
          "FORMER_LEADER_RETURNS_AS_FOLLOWER",
          "FOLLOWER_CATCH_UP",
          "MEMBER_STATE_EQUIVALENCE",
          "RUNTIME_METADATA_EXCLUDED",
          "NO_QUORUM_NO_ACK",
          "QUORUM_RESTORE_CONVERGENCE",
          "CORE_UNCHANGED",
          "SYSTEM_ERROR_NEVER_SEMANTIC");

  static final List<String> MUTANT_IDS =
      List.of(
          "M12-OFFER-AS-ACK",
          "M12-TIMEOUT-AS-REJECTED",
          "M12-RETRY-WITH-NEW-IDENTITY",
          "M12-DUPLICATE-AS-NEW-EFFECT",
          "M12-MINORITY-ACK",
          "M12-ACCEPT-STALE-LEADER-AUTHORITY",
          "M12-DROP-IDENTITY-DURING-CATCH-UP",
          "M12-INCLUDE-TERM-IN-SEMANTIC-DIGEST");

  static final List<String> SYSTEM_ERROR_IDS =
      List.of(
          "M12-NON-LEADER-FAULT-TARGET-CONTROL",
          "M12-CLUSTER-STARTUP-CONTROL",
          "M12-CORRUPT-HISTORY-OUTPUT-CONTROL");

  static final List<String> TUTORIAL_PERMALINKS =
      List.of(
          "three-node-quorum-commit-and-apply-boundary",
          "unknown-outcome-and-same-identity-retry",
          "leader-failover-and-stale-leader-fencing",
          "follower-catch-up-and-replicated-state-equivalence",
          "three-node-leader-failure-evidence");

  public Result run(Path repositoryRoot, Path reportDirectory) {
    Path root = repositoryRoot.toAbsolutePath().normalize();
    Path expected = root.resolve("build/reports/m12").normalize();
    Path requested = reportDirectory.toAbsolutePath().normalize();
    require(requested.equals(expected), "M12 public report path must be build/reports/m12");
    return run(root, requested, root.resolve("build"));
  }

  Result run(Path repositoryRoot, Path reportDirectory, Path trustedOutputRoot) {
    Path root = repositoryRoot.toAbsolutePath().normalize();
    Path trusted = trustedOutputRoot.toAbsolutePath().normalize();
    Path reports = SafeOutputPaths.resolveTrustedOutput(trusted, reportDirectory);
    require(
        !reports.equals(realPath(trusted)),
        "M12 report path must be below the trusted output root");
    clear(reports);
    Map<String, String> declaration = verifyCourseDeclaration(root);
    Workload workload = verifyWorkload(root);
    verifyStartArchitecture(root);

    ObjectNode report = JsonSupport.MAPPER.createObjectNode();
    report.put("schemaVersion", "matching.m12.check.v1");
    report.put("unit", "M12");
    report.put("status", STATUS);
    report.put("contractPlanVersion", "0.15");
    report.put("objective", workload.document().path("objective").stringValue());
    ObjectNode course = report.putObject("courseDeclaration");
    declaration.forEach(course::put);

    ObjectNode inherited = report.putObject("inheritedBaseline");
    inherited.put("unit", "M11");
    inherited.put("completeRef", "course/m11-complete");
    inherited.put("commit", BASELINE_COMMIT);
    inherited.put("expectedStatus", "PASS");
    inherited.put("matchingCoreTreeUnchanged", true);
    inherited.put("m11ClusterRuntimeTreeUnchanged", true);
    inherited.put("m11ApplicationGoldensUnchanged", true);

    ObjectNode profile = report.putObject("workloadProfile");
    profile.put("sha256", workload.digest());
    profile.put("fixedScenarios", SCENARIO_IDS.size());
    profile.put("coverageObligations", COVERAGE_IDS.size());
    profile.put("requiredMutants", MUTANT_IDS.size());
    profile.put("systemErrorControls", SYSTEM_ERROR_IDS.size());
    profile.put("seed", "6120");
    writeStrings(profile.putArray("phaseOrder"), PHASE_ORDER);
    profile.put("distinctBusinessCommands", 66);
    profile.put("minimumIngressAttempts", 84);
    profile.put("expectedFinalNextApplicationSequence", 67);

    ObjectNode outcomes = report.putObject("clientOutcomeContract");
    outcomes.put("beforeAcceptance", "NOT_SUBMITTED");
    outcomes.put("afterAcceptanceWithoutResponse", "UNKNOWN");
    outcomes.put("trustedResponse", "ACKNOWLEDGED");
    outcomes.put("unknownRetry", "SAME_DURABLE_COMMAND_IDENTITY_FRESH_CORRELATION");
    outcomes.put("timeoutIsBusinessRejection", false);
    outcomes.put("unknownIsBusinessOutcome", false);

    ObjectNode cluster = report.putObject("clusterContract");
    cluster.put("aeronVersion", "1.52.2");
    cluster.put("agronaVersion", "2.5.0");
    cluster.put("memberCount", 3);
    writeInts(cluster.putArray("memberIds"), List.of(0, 1, 2));
    cluster.put("appointedInitialLeaderId", 0);
    cluster.put("quorumSize", 2);
    cluster.put("membership", "STATIC");
    cluster.put("faultController", "OUTSIDE_CLUSTERED_SERVICE");
    cluster.put("memberIsolation", "INDEPENDENT_COMPONENTS_DIRECTORIES_PORTS_AND_HANDLES");
    cluster.put("processIsolationClaim", true);
    cluster.put("oneMemberFailureProgress", true);
    cluster.put("twoMemberFailureAck", false);
    cluster.put("formerLeaderCatchUp", "FULL_BUSINESS_STATE_EQUIVALENCE");
    cluster.put("runtimeMetadataInBusinessIdentity", false);
    cluster.put("matchingCoreChanges", 0);
    cluster.put("m11WireVersionChanges", 0);
    cluster.put("performanceClaim", false);
    cluster.put("backupRestoreClaim", false);
    cluster.put("externalServices", false);

    writeStrings(report.putArray("coverageObligations"), COVERAGE_IDS);
    writeStrings(report.putArray("requiredMutants"), MUTANT_IDS);
    writeStrings(report.putArray("systemErrorControls"), SYSTEM_ERROR_IDS);
    writeStrings(report.putArray("tutorialPermalinks"), TUTORIAL_PERMALINKS);
    writeStrings(
        report.putArray("missingCapabilities"),
        List.of(
            "THREE_MEMBER_CHILD_PROCESS_LAUNCHER",
            "STATIC_MEMBER_CONFIGURATION",
            "CLIENT_INVOCATION_OUTCOME_STATE_MACHINE",
            "PENDING_ATTEMPT_AND_ABANDON_BOUNDARY",
            "EXTERNAL_LEADER_FAIL_STOP_CONTROLLER",
            "REPLACEMENT_LEADER_DETECTION",
            "SAME_IDENTITY_UNKNOWN_RETRY",
            "FORMER_LEADER_RESTART_AND_CATCH_UP",
            "NO_QUORUM_FAIL_CLOSED_CHECK",
            "THREE_MEMBER_STATE_EQUIVALENCE_JUDGE",
            "M12_MUTANTS_AND_COUNTEREXAMPLES",
            "M12_EVIDENCE_WRITER"));

    ObjectNode release = report.putObject("releaseTarget");
    release.put("unitTag", "course/m12-complete");
    release.put("productRelease", "matching-0.8.0");
    release.put("verification", "THREE_MEMBER_FAILOVER_PROFILE_AND_CLEAN_TREE_EVIDENCE");
    ObjectNode execution = report.putObject("executionContract");
    execution.put("command", "./gradlew m12Check --no-daemon --max-workers=1");
    execution.put("structuredRedExitCode", 1);
    execution.put("defaultBuildGate", "M11_PASS");

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
    Map<String, String> expected = new LinkedHashMap<>();
    expected.put("case", "high-availability-cex");
    expected.put("profile", "SPOT-CEX-1.0");
    expected.put("planVersion", "0.15");
    expected.put("project", "matching");
    expected.put("unit", "M12");
    expected.put("lifecycle", "READY");
    expected.put("designDepth", "CONTRACT");
    expected.put("startRef", "course/m12-start");
    expected.put("completeRef", "course/m12-complete");
    expected.put("productRelease", "matching-0.8.0");
    expected.put("m12Check.expectedStatus", STATUS);
    expected.put("evidencePath", "build/lab-evidence/M12/manifest.json");
    require(properties.size() == expected.size(), "M12 course declaration field set changed");
    expected.forEach(
        (key, value) ->
            require(value.equals(properties.getProperty(key)), "M12 declaration changed: " + key));
    return Collections.unmodifiableMap(expected);
  }

  private static Workload verifyWorkload(Path root) {
    byte[] bytes = readBytes(root.resolve(WORKLOAD_PATH));
    String digest = Hashing.sha256Hex(bytes);
    require(WORKLOAD_SHA256.equals(digest), "M12 workload SHA-256 changed");
    JsonNode document = JsonSupport.parse(bytes);
    JsonSupport.validate(document, readString(root.resolve(WORKLOAD_SCHEMA_PATH)), false);

    List<String> scenarios = new ArrayList<>();
    Set<String> witnessed = new LinkedHashSet<>();
    for (JsonNode scenario : document.path("fixedScenarios")) {
      scenarios.add(scenario.path("id").stringValue());
      witnessed.addAll(strings(scenario.path("proofObligations")));
    }
    require(SCENARIO_IDS.equals(scenarios), "M12 scenario identity or order changed");
    require(
        new LinkedHashSet<>(COVERAGE_IDS).equals(witnessed),
        "M12 scenario obligations are incomplete");
    require(
        COVERAGE_IDS.equals(strings(document.path("coverageRequirements"))),
        "M12 coverage order changed");
    require(
        MUTANT_IDS.equals(strings(document.path("requiredMutants"))), "M12 mutant order changed");
    require(
        SYSTEM_ERROR_IDS.equals(strings(document.path("systemErrorControls"))),
        "M12 control order changed");
    require(
        TUTORIAL_PERMALINKS.equals(strings(document.path("tutorialPermalinks"))),
        "M12 permalink order changed");
    require(
        PHASE_ORDER.equals(strings(document.path("faultSchedule").path("phaseOrder"))),
        "M12 phase order changed");
    return new Workload(digest, document);
  }

  private static void verifyStartArchitecture(Path root) {
    require(
        BASELINE_COMMIT.equals(git(root, "rev-parse", "course/m11-complete^{}").strip()),
        "M11 completion ref changed");
    require(
        git(root, "rev-parse", "course/m11-complete:matching-core")
            .strip()
            .equals(git(root, "rev-parse", "HEAD:matching-core").strip()),
        "matching-core changed at M12 start");
    require(
        git(root, "rev-parse", "course/m11-complete:matching-cluster-runtime")
            .strip()
            .equals(git(root, "rev-parse", "HEAD:matching-cluster-runtime").strip()),
        "M11 Cluster runtime changed at M12 start");
    require(
        git(
                root,
                "rev-parse",
                "course/m11-complete:matching-testkit/src/test/resources/m11/goldens")
            .strip()
            .equals(
                git(root, "rev-parse", "HEAD:matching-testkit/src/test/resources/m11/goldens")
                    .strip()),
        "M11 application goldens changed at M12 start");
    Path runtime = root.resolve("matching-cluster-runtime/src/main/java");
    try (var paths = Files.walk(runtime)) {
      require(
          paths
              .filter(Files::isRegularFile)
              .noneMatch(path -> path.getFileName().toString().startsWith("M12")),
          "M12 start must not contain the three-member runtime");
    } catch (IOException failure) {
      throw new IllegalStateException("cannot inspect M12 start architecture", failure);
    }
    require(
        !Files.exists(root.resolve("schemas/matching.m12.check.v2.schema.json")),
        "M12 start must not contain the completion schema");
    require(
        !Files.exists(
            root.resolve(
                "matching-testkit/src/main/java/io/github/lchareln/cex/matching/testkit/M12EvidenceWriter.java")),
        "M12 start must not contain the evidence writer");
  }

  private static List<String> strings(JsonNode array) {
    List<String> values = new ArrayList<>();
    for (JsonNode value : array) {
      values.add(value.stringValue());
    }
    return List.copyOf(values);
  }

  private static void writeStrings(
      tools.jackson.databind.node.ArrayNode target, List<String> values) {
    values.forEach(target::add);
  }

  private static void writeInts(
      tools.jackson.databind.node.ArrayNode target, List<Integer> values) {
    values.forEach(target::add);
  }

  private static String git(Path root, String... arguments) {
    List<String> command = new ArrayList<>();
    command.add("git");
    command.addAll(List.of(arguments));
    try {
      Process process =
          new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      int exit = process.waitFor();
      if (exit != 0) {
        throw new IllegalStateException("git command failed: " + output.strip());
      }
      return output;
    } catch (IOException failure) {
      throw new IllegalStateException("cannot run git", failure);
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("git command interrupted", failure);
    }
  }

  private static byte[] readBytes(Path path) {
    try {
      return Files.readAllBytes(path);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot read " + path, failure);
    }
  }

  private static String readString(Path path) {
    return new String(readBytes(path), StandardCharsets.UTF_8);
  }

  private static Path realPath(Path path) {
    try {
      return path.toRealPath();
    } catch (IOException failure) {
      throw new IllegalStateException("cannot resolve trusted output root", failure);
    }
  }

  private static void clear(Path directory) {
    try {
      if (Files.exists(directory)) {
        try (var paths = Files.walk(directory)) {
          for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
            Files.delete(path);
          }
        }
      }
      Files.createDirectories(directory);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot reset M12 report directory", failure);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }

  public record Result(String status, Path reportPath) {}

  private record Workload(String digest, JsonNode document) {}
}
