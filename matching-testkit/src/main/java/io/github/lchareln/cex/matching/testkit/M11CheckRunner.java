package io.github.lchareln.cex.matching.testkit;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
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

/** Fail-closed completion judge for the bounded M11 single-member Aeron adapter. */
public final class M11CheckRunner {
  public static final String PASS = "PASS";
  public static final String STUDENT_FAILURE = "STUDENT_FAILURE";
  public static final String SYSTEM_ERROR = "SYSTEM_ERROR";
  static final String CHECK_SCHEMA_PATH = "schemas/matching.m11.check.v2.schema.json";
  static final String COUNTEREXAMPLE_SCHEMA_PATH =
      "schemas/matching.m11.counterexamples.v1.schema.json";
  static final String REPLAY_SCHEMA_PATH = "schemas/matching.m11.replay.v1.schema.json";
  static final String COVERAGE_SCHEMA_PATH = "schemas/matching.m11.coverage.v2.schema.json";
  static final String MUTANTS_SCHEMA_PATH = "schemas/matching.m11.mutants.v1.schema.json";
  static final List<String> OUTPUTS =
      List.of(
          "inherited-m10.json",
          "fixed-scenarios.json",
          "generated-differential.json",
          "generated-requests.canonical.bin",
          "cluster-runtime.json",
          "protocol-goldens.json",
          "coverage.json",
          "mutants.json",
          "counterexamples.json",
          "replay.json",
          "architecture.json",
          "environment.json");

  public Result run(Path repositoryRoot, Path reportDirectory) {
    return run(repositoryRoot, reportDirectory, repositoryRoot);
  }

  Result run(Path repositoryRoot, Path reportDirectory, Path trustedOutputRoot) {
    Path root = repositoryRoot.toAbsolutePath().normalize();
    Path reports = SafeOutputPaths.resolveTrustedOutput(trustedOutputRoot, reportDirectory);
    clear(reports);
    try {
      Artifacts artifacts = execute(root, reports);
      writePass(root, reports, artifacts);
      return new Result(PASS, reports.resolve("check.json"));
    } catch (RuntimeException failure) {
      clear(reports);
      String classification = M11FailureClassifier.classify(failure);
      writeFailure(root, reports, classification, stableMessage(failure, root));
      return new Result(classification, reports.resolve("check.json"));
    }
  }

  private static Artifacts execute(Path root, Path reports) {
    Instant started = Instant.now();
    Map<String, String> course = verifyCourse(root);
    Workload workload = verifyWorkload(root);
    ObjectNode inherited =
        new M11InheritedM10Regression().run(root, reports.resolve(".m10-regression"));
    M11ProtocolSuite.Result protocol = new M11ProtocolSuite().run(root);
    ObjectNode architecture = new M11ArchitectureGate().run(root);
    M11GeneratedSuite.Result generated = new M11GeneratedSuite().run(root.resolve("build/tmp/m11"));
    M11FixedSuite.Result fixed = new M11FixedSuite().run(root, protocol, generated, architecture);
    M11MutantSuite.Result mutants = new M11MutantSuite().run(root);
    JsonSupport.validate(
        mutants.counterexamples(), readString(root.resolve(COUNTEREXAMPLE_SCHEMA_PATH)), false);
    JsonSupport.validate(
        mutants.replayReport(), readString(root.resolve(REPLAY_SCHEMA_PATH)), false);
    ObjectNode coverage = new M11Coverage().run(root, fixed, mutants);
    JsonSupport.validate(coverage, readString(root.resolve(COVERAGE_SCHEMA_PATH)), false);
    ObjectNode environment =
        new M11Environment().capture(generated.clusterRoot(), started, Instant.now());
    return new Artifacts(
        course,
        workload,
        inherited,
        protocol,
        architecture,
        generated,
        fixed,
        coverage,
        mutants,
        environment);
  }

  private static void writePass(Path root, Path reports, Artifacts artifacts) {
    write(reports, "inherited-m10.json", artifacts.inherited());
    write(reports, "fixed-scenarios.json", artifacts.fixed().report());
    write(reports, "generated-differential.json", artifacts.generated().generatedReport());
    AtomicFiles.write(
        reports.resolve("generated-requests.canonical.bin"),
        artifacts.generated().canonicalBytes());

    ObjectNode cluster = artifacts.generated().clusterReport().deepCopy();
    cluster.set("snapshotRestart", artifacts.generated().snapshotReport().deepCopy());
    write(reports, "cluster-runtime.json", cluster);
    write(reports, "protocol-goldens.json", artifacts.protocol().report());
    write(reports, "coverage.json", artifacts.coverage());

    byte[] counterexamples = artifacts.mutants().persistedBytes();
    AtomicFiles.write(reports.resolve("counterexamples.json"), counterexamples);
    write(reports, "replay.json", artifacts.mutants().replayReport());
    ObjectNode mutantReport = mutantReport(artifacts.mutants());
    JsonSupport.validate(mutantReport, readString(root.resolve(MUTANTS_SCHEMA_PATH)), false);
    write(reports, "mutants.json", mutantReport);
    write(reports, "architecture.json", artifacts.architecture());
    write(reports, "environment.json", artifacts.environment());

    ObjectNode check = passReport(root, reports, artifacts, mutantReport);
    JsonSupport.validate(check, readString(root.resolve(CHECK_SCHEMA_PATH)), false);
    write(reports, "check.json", check);
  }

  static ObjectNode mutantReport(M11MutantSuite.Result mutants) {
    ObjectNode report = JsonSupport.MAPPER.createObjectNode();
    report.put("schemaVersion", "matching.m11.mutants.v1");
    report.put("status", PASS);
    report.put("required", M11StartCheckRunner.MUTANT_IDS.size());
    report.put("killed", mutants.killed());
    report.put("classification", STUDENT_FAILURE);
    report.put("executableCandidates", mutants.candidates().size());
    report.put("systemErrorControls", mutants.controls().size());
    report.put("systemErrorsObserved", mutants.controls().size());
    report.put("systemErrorCountedAsKill", false);
    report.put("counterexampleSha256", Hashing.sha256Hex(mutants.persistedBytes()));
    report.put("counterexampleCanonicalSha256", mutants.digest());
    report.put(
        "productionControlsPassed",
        mutants.replayReport().path("productionControlsPassed").intValue());
    report.put("serializedFreshReplays", mutants.replayReport().path("replayed").intValue());
    report.put(
        "serializedReplayFingerprintsExact",
        mutants.replayReport().path("fingerprintsExact").booleanValue());
    report.put(
        "oneMinimalCounterexamples", mutants.replayReport().path("oneDeleteAudits").intValue());
    report.put("actualMutationActions", mutants.actualMutationActions());
    report.put("rawActions", mutants.rawActions());
    report.put("minimalActions", mutants.minimalActions());
    report.put("shrinkTrials", mutants.shrinkTrials());
    report.set("candidates", mutants.candidates());
    report.set("controls", mutants.controls());
    return report;
  }

  private static ObjectNode passReport(
      Path root, Path reports, Artifacts artifacts, ObjectNode mutantReport) {
    ObjectNode check = JsonSupport.MAPPER.createObjectNode();
    check.put("schemaVersion", "matching.m11.check.v2");
    check.put("unit", "M11");
    check.put("status", PASS);
    check.put("contractPlanVersion", "0.14");
    check.put("objective", artifacts.workload().document().path("objective").stringValue());
    ObjectNode source = check.putObject("source");
    source.put("commit", git(root, "rev-parse", "HEAD").strip());
    source.put("dirty", !git(root, "status", "--porcelain=v1", "--untracked-files=all").isBlank());
    ObjectNode course = check.putObject("courseDeclaration");
    artifacts.course().forEach(course::put);

    ObjectNode inherited = check.putObject("inheritedM10");
    copy(
        artifacts.inherited(),
        inherited,
        "unit",
        "completeRef",
        "productRelease",
        "status",
        "fixedScenarios",
        "generatedActions",
        "mutantsKilled",
        "methodSmoke",
        "currentCompiledClasses",
        "baselineCommit");

    ObjectNode workload = check.putObject("workloadProfile");
    workload.put("sha256", artifacts.workload().digest());
    workload.put("seed", "6111");
    workload.put("fixedScenarios", 22);
    workload.put("histories", 32);
    workload.put("actionsPerHistory", 128);
    workload.put("actionsPerPath", 4096);
    workload.put("clusterRuns", 2);
    workload.put("totalActualClusterIngress", 8192);
    workload.put("continuousCorpus", true);
    workload.put("stateResetBetweenSegments", false);
    workload.put("lanes", 4);
    workload.put("snapshotAfterAction", 2048);
    workload.put("coverageObligations", 28);
    workload.put("requiredMutants", 10);
    workload.put("systemErrorControls", 3);
    workload.put("goldens", 6);

    ObjectNode fixed = check.putObject("fixed");
    copy(artifacts.fixed().report(), fixed, "scenarios", "passed", "status");
    ObjectNode generator = check.putObject("generator");
    copy(
        artifacts.generated().generatedReport(),
        generator,
        "algorithm",
        "seed",
        "histories",
        "continuousCorpus",
        "actionsPerHistory",
        "actionsPerPath",
        "clusterRuns",
        "totalActualClusterIngress",
        "freshGenerations",
        "byteExactRegeneration",
        "canonicalSha256",
        "newApplied",
        "duplicateReplayed",
        "identityRejected",
        "commandIdConflicts",
        "slotConflicts",
        "directClusterComparisons",
        "clusterClusterComparisons",
        "threePathFullBusinessEquivalent",
        "finalIdentityBindings",
        "finalNextApplicationSequence");

    ObjectNode protocol = check.putObject("protocol");
    copy(
        artifacts.protocol().report(),
        protocol,
        "goldens",
        "requestV1Readable",
        "requestV1FixesResponseV1",
        "requestV2Current",
        "requestV2ResponseBounds",
        "invalidRequestedResponseStateMutations",
        "fabricatedBusinessResults",
        "responseV1DownEncoded",
        "responseV1OutcomesCovered",
        "payloadHashOuterInvariant",
        "forgedPayloadHashPreApplyRejected",
        "forgedPayloadHashStateMutations",
        "responseV2Current",
        "snapshotS1ReadableAndRestorable",
        "snapshotS2Current",
        "snapshotIdentityBindingsMinimum",
        "snapshotIdentityOrder",
        "snapshotProducerCursorContinuityValidated",
        "nMinusOneIdempotencyPreserved",
        "malformedFailsClosed",
        "unsupportedFailsClosed",
        "boundedResponse",
        "fullEventStreamInResponse");

    ObjectNode cluster = check.putObject("clusterRuntime");
    copy(
        artifacts.generated().clusterReport(),
        cluster,
        "implementation",
        "memberCount",
        "memberId",
        "appointedLeaderId",
        "clusterRuns",
        "actionsPerRun",
        "acceptedIngressOffers",
        "correlatedResponses",
        "serviceObservations",
        "newBusinessApplications",
        "duplicateReplays",
        "rejectedApplications",
        "snapshotAdminAccepted",
        "snapshotsCompleted",
        "restarts",
        "componentErrors",
        "singleMemberOnly",
        "highAvailabilityClaim",
        "performanceClaim",
        "dockerRequired",
        "externalServices");
    check.set("snapshotRestart", artifacts.generated().snapshotReport().deepCopy());

    ObjectNode coverage = check.putObject("coverage");
    copy(
        artifacts.coverage(),
        coverage,
        "required",
        "observed",
        "allWitnessed",
        "source",
        "systemErrorEvaluatedAfterControls",
        "factCount",
        "ledgerSha256");
    ObjectNode mutants = check.putObject("mutants");
    copy(
        mutantReport,
        mutants,
        "required",
        "killed",
        "classification",
        "executableCandidates",
        "systemErrorControls",
        "systemErrorsObserved",
        "systemErrorCountedAsKill",
        "counterexampleSha256",
        "counterexampleCanonicalSha256",
        "productionControlsPassed",
        "serializedFreshReplays",
        "serializedReplayFingerprintsExact",
        "oneMinimalCounterexamples",
        "actualMutationActions",
        "rawActions",
        "minimalActions",
        "shrinkTrials");
    ObjectNode replay = check.putObject("replay");
    copy(
        artifacts.mutants().replayReport(),
        replay,
        "required",
        "replayed",
        "persistedBytesParsed",
        "orderedUniqueIds",
        "stepCountsExact",
        "freshCandidatePerReplay",
        "productionControlsPassed",
        "fingerprintsExact",
        "oneDeleteAudits",
        "invalidHistoryCountedAsKill",
        "systemErrorCountedAsKill",
        "persistedBytesSha256",
        "canonicalSha256");
    ObjectNode architecture = check.putObject("architecture");
    copy(
        artifacts.architecture(),
        architecture,
        "m10CoreTree",
        "headCoreTree",
        "matchingCoreByteIdentical",
        "coreInfrastructureFree",
        "aeronProductionModule",
        "aeronJavaImportViolations",
        "aeronDependencyViolations",
        "clusterServiceLocalWalViolations",
        "clusterServiceExternalIoViolations",
        "standaloneWalWrites",
        "runtimeMetadataInBusinessDigest",
        "violations");
    ObjectNode environment = check.putObject("environment");
    copy(
        artifacts.environment(),
        environment,
        "javaRuntime",
        "javaVersion",
        "javaVendor",
        "vmName",
        "jvmArguments",
        "osName",
        "osVersion",
        "osArchitecture",
        "availableProcessors",
        "maximumHeapBytes",
        "clusterRoot",
        "fileStoreName",
        "fileStoreType",
        "runStartedAt",
        "runFinishedAt");

    ArrayNode bindings = check.putArray("artifactBindings");
    for (String name : OUTPUTS) {
      Path path = reports.resolve(name);
      systemRequire(
          Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS), "missing M11 artifact " + name);
      ObjectNode binding = bindings.addObject();
      binding.put("path", name);
      binding.put("sha256", Hashing.sha256Hex(readBytes(path)));
      binding.put("bytes", size(path));
    }
    ObjectNode target = check.putObject("releaseTarget");
    target.put("unitTag", "course/m11-complete");
    target.putNull("productRelease");
    target.put("verification", "CLEAN_TREE_ANNOTATED_TAG_EVIDENCE");
    return check;
  }

  private static void writeFailure(Path root, Path reports, String classification, String detail) {
    ObjectNode failure = JsonSupport.MAPPER.createObjectNode();
    failure.put("schemaVersion", "matching.m11.check.v2");
    failure.put("unit", "M11");
    failure.put("status", classification);
    failure.put("contractPlanVersion", "0.14");
    failure.put("failure", detail == null || detail.isBlank() ? "M11 check failed" : detail);
    ObjectNode target = failure.putObject("releaseTarget");
    target.put("unitTag", "course/m11-complete");
    target.putNull("productRelease");
    target.put("verification", "CLEAN_TREE_ANNOTATED_TAG_EVIDENCE");
    JsonSupport.validate(failure, readString(root.resolve(CHECK_SCHEMA_PATH)), false);
    write(reports, "check.json", failure);
  }

  private static Map<String, String> verifyCourse(Path root) {
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
            Map.entry("lifecycle", "COMPLETE"),
            Map.entry("designDepth", "IMPLEMENTED"),
            Map.entry("startRef", "course/m11-start"),
            Map.entry("completeRef", "course/m11-complete"),
            Map.entry("m11Check.expectedStatus", PASS),
            Map.entry("evidencePath", "build/lab-evidence/M11/manifest.json"));
    studentRequire(
        properties.size() == expected.size(), "M11 course declaration field set changed");
    expected.forEach(
        (key, value) ->
            studentRequire(
                value.equals(properties.getProperty(key)), "M11 course changed: " + key));
    return expected;
  }

  private static Workload verifyWorkload(Path root) {
    byte[] bytes = readBytes(root.resolve(M11StartCheckRunner.WORKLOAD_PATH));
    String digest = Hashing.sha256Hex(bytes);
    systemRequire(
        M11StartCheckRunner.WORKLOAD_SHA256.equals(digest), "M11 frozen workload SHA changed");
    JsonNode workload = JsonSupport.parse(bytes);
    JsonSupport.validate(
        workload, readString(root.resolve(M11StartCheckRunner.WORKLOAD_SCHEMA_PATH)), false);
    List<String> scenarios = strings(workload.path("fixedScenarios"), "id");
    studentRequire(
        scenarios.equals(M11StartCheckRunner.SCENARIO_IDS), "M11 fixed scenarios changed");
    List<String> obligations = strings(workload.path("coverageRequirements"));
    studentRequire(
        obligations.equals(M11StartCheckRunner.COVERAGE_IDS), "M11 coverage obligations changed");
    studentRequire(
        strings(workload.path("requiredMutants")).equals(M11StartCheckRunner.MUTANT_IDS),
        "M11 mutant identities changed");
    studentRequire(
        strings(workload.path("systemErrorControls")).equals(M11StartCheckRunner.SYSTEM_ERROR_IDS),
        "M11 system controls changed");
    JsonNode generated = workload.path("generatedDifferential");
    systemRequire(
        generated.path("totalActions").intValue() == 4096
            && generated.path("histories").intValue() == 32
            && generated.path("actionsPerHistory").intValue() == 128
            && "ONE_CONTINUOUS_CORPUS_OF_32_ORDERED_SEGMENTS"
                .equals(generated.path("composition").stringValue())
            && !generated.path("stateResetBetweenSegments").booleanValue(),
        "M11 generated corpus dimensions changed");
    JsonNode cluster = workload.path("realClusterProfile");
    systemRequire(
        cluster.path("corpusActionsPerPath").intValue() == 4096
            && cluster.path("clusterRuns").intValue() == 2
            && cluster.path("totalActualClusterIngress").intValue() == 8192
            && cluster.path("completionRequiredBeforeShutdown").booleanValue(),
        "M11 three-path Cluster profile changed");
    Set<String> witnessed = new LinkedHashSet<>();
    for (JsonNode scenario : workload.path("fixedScenarios")) {
      witnessed.addAll(strings(scenario.path("proofObligations")));
    }
    studentRequire(witnessed.containsAll(obligations), "M11 fixed coverage is incomplete");
    return new Workload(digest, workload.deepCopy());
  }

  private static List<String> strings(JsonNode values) {
    List<String> result = new ArrayList<>();
    values.forEach(value -> result.add(value.stringValue()));
    return List.copyOf(result);
  }

  private static List<String> strings(JsonNode values, String field) {
    List<String> result = new ArrayList<>();
    values.forEach(value -> result.add(value.path(field).stringValue()));
    return List.copyOf(result);
  }

  private static void copy(JsonNode source, ObjectNode target, String... fields) {
    for (String field : fields) {
      JsonNode value = source.get(field);
      systemRequire(value != null, "missing report field " + field);
      target.set(field, value.deepCopy());
    }
  }

  private static void write(Path reports, String name, JsonNode node) {
    AtomicFiles.write(reports.resolve(name), JsonSupport.prettyBytes(node));
  }

  private static String stableMessage(RuntimeException failure, Path root) {
    String message = failure.getMessage();
    if (message == null || message.isBlank()) {
      return failure.getClass().getSimpleName();
    }
    return message.replace(root.toString(), "<repository>");
  }

  private static String git(Path root, String... arguments) {
    List<String> command = new ArrayList<>();
    command.add("git");
    command.addAll(List.of(arguments));
    try {
      Process process = new ProcessBuilder(command).directory(root.toFile()).start();
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
      int exit = process.waitFor();
      systemRequire(exit == 0, "git command failed: " + error.strip());
      return output;
    } catch (IOException failure) {
      throw new IllegalStateException("cannot execute git", failure);
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("git interrupted", failure);
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
    try {
      return Files.readString(path);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot read " + path, failure);
    }
  }

  private static long size(Path path) {
    try {
      return Files.size(path);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot size " + path, failure);
    }
  }

  private static void clear(Path path) {
    if (Files.exists(path)) {
      try (var paths = Files.walk(path)) {
        paths.sorted(Comparator.reverseOrder()).forEach(M11CheckRunner::delete);
      } catch (IOException failure) {
        throw new IllegalStateException("cannot clear " + path, failure);
      }
    }
    try {
      Files.createDirectories(path);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot create " + path, failure);
    }
  }

  private static void delete(Path path) {
    try {
      Files.delete(path);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot delete " + path, failure);
    }
  }

  private static void studentRequire(boolean condition, String message) {
    if (!condition) {
      throw new M11SemanticFailure(message);
    }
  }

  private static void systemRequire(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }

  private record Workload(String digest, JsonNode document) {}

  private record Artifacts(
      Map<String, String> course,
      Workload workload,
      ObjectNode inherited,
      M11ProtocolSuite.Result protocol,
      ObjectNode architecture,
      M11GeneratedSuite.Result generated,
      M11FixedSuite.Result fixed,
      ObjectNode coverage,
      M11MutantSuite.Result mutants,
      ObjectNode environment) {}

  public record Result(String status, Path reportPath) {}
}
