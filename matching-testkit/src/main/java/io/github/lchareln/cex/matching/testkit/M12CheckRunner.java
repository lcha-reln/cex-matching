package io.github.lchareln.cex.matching.testkit;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Fail-closed completion judge for the bounded M12 three-member failover profile. */
public final class M12CheckRunner {
  public static final String PASS = "PASS";
  public static final String STUDENT_FAILURE = "STUDENT_FAILURE";
  public static final String SYSTEM_ERROR = "SYSTEM_ERROR";

  static final String CHECK_SCHEMA_PATH = "schemas/matching.m12.check.v2.schema.json";
  static final String COVERAGE_SCHEMA_PATH = "schemas/matching.m12.coverage.v1.schema.json";
  static final String MUTANTS_SCHEMA_PATH = "schemas/matching.m12.mutants.v1.schema.json";
  static final String COUNTEREXAMPLES_SCHEMA_PATH =
      "schemas/matching.m12.counterexamples.v1.schema.json";
  static final String REPLAY_SCHEMA_PATH = "schemas/matching.m12.replay.v1.schema.json";
  static final List<String> OUTPUTS =
      List.of(
          "inherited-m11.json",
          "corpus.json",
          "commands.canonical.bin",
          "m12-command-history.json",
          "topology.json",
          "leadership.json",
          "quorum.json",
          "catchup.json",
          "state-equivalence.json",
          "coverage.json",
          "mutants.json",
          "counterexamples.json",
          "replay.json",
          "architecture.json",
          "environment.json");

  public M12CheckRunner() {}

  public Result run(Path repositoryRoot, Path reportDirectory) {
    return run(repositoryRoot, reportDirectory, repositoryRoot.resolve("build"));
  }

  Result run(Path repositoryRoot, Path reportDirectory, Path trustedOutputRoot) {
    Path root = repositoryRoot.toAbsolutePath().normalize();
    Path reports =
        SafeOutputPaths.resolveTrustedOutput(
            trustedOutputRoot.toAbsolutePath().normalize(), reportDirectory);
    clear(reports);
    try {
      Artifacts artifacts = execute(root);
      writePass(root, reports, artifacts);
      return new Result(PASS, reports.resolve("check.json"));
    } catch (M12SemanticFailure failure) {
      clear(reports);
      String classification = classifySemanticFailure(failure);
      writeFailure(root, reports, classification, stableMessage(failure, root));
      return new Result(classification, reports.resolve("check.json"));
    } catch (RuntimeException failure) {
      clear(reports);
      writeFailure(root, reports, SYSTEM_ERROR, stableMessage(failure, root));
      return new Result(SYSTEM_ERROR, reports.resolve("check.json"));
    }
  }

  private static Artifacts execute(Path root) {
    Instant started = Instant.now();
    Map<String, String> course = verifyCourse(root);
    M12WorkloadLoader.Workload workload = M12WorkloadLoader.load(root);
    M12DeterministicCorpus.Corpus corpus = M12DeterministicCorpus.generate(workload);
    ObjectNode inherited = new M12InheritedM11Regression().run(root);
    ObjectNode architecture = new M12ArchitectureGate().run(root);
    M12MutantSuite.Result mutants = new M12MutantSuite().run(workload, corpus);
    M12ClusterFaultSuite.Result cluster = new M12ClusterFaultSuite().run(root, workload, corpus);
    M12CoverageLedger.Result coverage =
        new M12CoverageLedger().run(workload, cluster.trace(), mutants.controls());
    require(
        coverage.qualifiesAsRealClusterEvidence(),
        "M12 coverage is not bound to real Aeron child processes");
    M12HistoryJudge.Inspection inspection =
        new M12HistoryJudge().inspect(workload, cluster.trace());
    require(
        inspection.qualifiesAsRealClusterEvidence(), "M12 history is not real Cluster evidence");
    ObjectNode environment =
        new M12Environment().capture(cluster.clusterRoot(), started, Instant.now());
    return new Artifacts(
        course,
        workload,
        corpus,
        inherited,
        architecture,
        mutants,
        cluster,
        coverage,
        inspection,
        environment);
  }

  private static void writePass(Path root, Path reports, Artifacts artifacts) {
    ObjectNode corpus = corpusReport(artifacts.workload(), artifacts.corpus());
    M12StrictReports.validateDocuments(
        root,
        Map.ofEntries(
            Map.entry("inherited-m11.json", artifacts.inherited()),
            Map.entry("m12-command-history.json", artifacts.cluster().historyReport()),
            Map.entry("topology.json", artifacts.cluster().topologyReport()),
            Map.entry("leadership.json", artifacts.cluster().leadershipReport()),
            Map.entry("quorum.json", artifacts.cluster().quorumReport()),
            Map.entry("catchup.json", artifacts.cluster().catchupReport()),
            Map.entry("state-equivalence.json", artifacts.cluster().stateEquivalenceReport()),
            Map.entry("architecture.json", artifacts.architecture()),
            Map.entry("environment.json", artifacts.environment())));
    write(reports, "inherited-m11.json", artifacts.inherited());
    write(reports, "corpus.json", corpus);
    AtomicFiles.write(
        reports.resolve("commands.canonical.bin"), artifacts.cluster().canonicalCommandBytes());
    write(reports, "m12-command-history.json", artifacts.cluster().historyReport());
    write(reports, "topology.json", artifacts.cluster().topologyReport());
    write(reports, "leadership.json", artifacts.cluster().leadershipReport());
    write(reports, "quorum.json", artifacts.cluster().quorumReport());
    write(reports, "catchup.json", artifacts.cluster().catchupReport());
    write(reports, "state-equivalence.json", artifacts.cluster().stateEquivalenceReport());
    write(reports, "coverage.json", artifacts.coverage().report());
    write(reports, "mutants.json", artifacts.mutants().report());
    AtomicFiles.write(
        reports.resolve("counterexamples.json"), artifacts.mutants().counterexampleBytes());
    write(reports, "replay.json", artifacts.mutants().replayReport());
    write(reports, "architecture.json", artifacts.architecture());
    write(reports, "environment.json", artifacts.environment());

    M12StrictReports.validateAll(root, reports);
    validate(root, artifacts.coverage().report(), COVERAGE_SCHEMA_PATH);
    validate(root, artifacts.mutants().report(), MUTANTS_SCHEMA_PATH);
    validate(root, artifacts.mutants().counterexamples(), COUNTEREXAMPLES_SCHEMA_PATH);
    validate(root, artifacts.mutants().replayReport(), REPLAY_SCHEMA_PATH);
    ObjectNode check = passReport(root, reports, artifacts, corpus);
    validate(root, check, CHECK_SCHEMA_PATH);
    M12StrictReports.validateAll(root, reports, check);
    write(reports, "check.json", check);
  }

  private static ObjectNode corpusReport(
      M12WorkloadLoader.Workload workload, M12DeterministicCorpus.Corpus corpus) {
    ObjectNode report = JsonSupport.MAPPER.createObjectNode();
    report.put("schemaVersion", "matching.m12.corpus.v1");
    report.put("status", PASS);
    report.put("algorithm", "splitmix64-v1");
    report.put("seed", Long.toString(M12DeterministicCorpus.SEED));
    report.put("workloadSha256", workload.sha256());
    report.put("fixedScenarios", workload.scenarios().size());
    report.put("coverageObligations", workload.coverageRequirements().size());
    report.put("requiredMutants", workload.requiredMutants().size());
    report.put("systemErrorControls", workload.systemErrorControls().size());
    report.put("distinctBusinessCommands", corpus.identities().size());
    report.put("invocations", corpus.attempts().size());
    report.put("acceptedIngressAttempts", corpus.ingressAttemptCount());
    report.put("expectedFinalNextApplicationSequence", 67);
    report.put("corpusSha256", corpus.corpusSha256());
    report.put("expectedFinalSemanticDigest", corpus.expectedFinalSemanticDigest());
    ArrayNode phases = report.putArray("phaseOrder");
    M12StartCheckRunner.PHASE_ORDER.forEach(phases::add);
    ArrayNode identities = report.putArray("identities");
    corpus
        .identities()
        .forEach(
            identity -> {
              ObjectNode item = identities.addObject();
              item.put("index", identity.index());
              item.put("commandId", identity.commandId().toString());
              item.put("producerId", identity.producerId());
              item.put("producerEpoch", identity.producerEpoch());
              item.put("shardId", identity.shardId());
              item.put("producerSequence", identity.producerSequence());
              item.put("payloadSha256", identity.payloadSha256());
              item.put("canonicalEnvelopeSha256", identity.canonicalSha256());
              item.put("canonicalEnvelopeBytes", identity.canonicalBytes().length);
            });
    return report;
  }

  private static ObjectNode passReport(
      Path root, Path reports, Artifacts artifacts, ObjectNode corpus) {
    ObjectNode check = JsonSupport.MAPPER.createObjectNode();
    check.put("schemaVersion", "matching.m12.check.v2");
    check.put("unit", "M12");
    check.put("status", PASS);
    check.put("contractPlanVersion", "0.15");
    check.put("objective", artifacts.workload().document().path("objective").stringValue());
    ObjectNode source = check.putObject("source");
    source.put("commit", git(root, "rev-parse", "HEAD").strip());
    source.put("dirty", !git(root, "status", "--porcelain=v1", "--untracked-files=all").isBlank());
    ObjectNode course = check.putObject("courseDeclaration");
    artifacts.course().forEach(course::put);
    check.set("inheritedM11", artifacts.inherited());
    check.set("workloadProfile", corpus.deepCopy());
    ObjectNode fixed = check.putObject("fixed");
    fixed.put("schemaVersion", "matching.m12.fixed.v1");
    fixed.put("status", PASS);
    fixed.put("scenarios", M12StartCheckRunner.SCENARIO_IDS.size());
    fixed.put("passed", M12StartCheckRunner.SCENARIO_IDS.size());
    ArrayNode scenarioIds = fixed.putArray("scenarioIds");
    M12StartCheckRunner.SCENARIO_IDS.forEach(scenarioIds::add);
    check.set("commandOutcomes", artifacts.cluster().historyReport());
    check.set("clusterTopology", artifacts.cluster().topologyReport());
    JsonNode contractCorrection = artifacts.cluster().topologyReport().path("contractCorrection");
    require(contractCorrection.isObject(), "M12 topology lacks the automatic-election correction");
    check.set("contractCorrection", contractCorrection.deepCopy());
    check.set("leadership", artifacts.cluster().leadershipReport());
    check.set("quorum", artifacts.cluster().quorumReport());
    check.set("catchup", artifacts.cluster().catchupReport());
    check.set("stateEquivalence", artifacts.cluster().stateEquivalenceReport());
    check.set("coverage", artifacts.coverage().report());
    check.set("mutants", artifacts.mutants().report());
    check.set("replay", artifacts.mutants().replayReport());
    check.set("architecture", artifacts.architecture());
    check.set("environment", artifacts.environment());
    ObjectNode inspection = check.putObject("judgeInspection");
    inspection.put("schemaVersion", "matching.m12.judge-inspection.v1");
    inspection.put("status", PASS);
    inspection.put(
        "realAeronChildProcesses", artifacts.inspection().qualifiesAsRealClusterEvidence());
    inspection.put("assertions", artifacts.inspection().observations().size());
    inspection.put("semanticDigest", artifacts.inspection().semanticDigest());
    inspection.put("acknowledged", artifacts.inspection().retries().acknowledgedCount());
    inspection.put("unknown", artifacts.inspection().retries().acceptedUnknownCount());
    inspection.put("notSubmitted", artifacts.inspection().retries().notSubmittedCount());
    inspection.put("sameIdentityRetries", artifacts.inspection().retries().retryCount());
    inspection.put("duplicateReplays", artifacts.inspection().retries().duplicateReplayCount());
    inspection.put(
        "noQuorumRetryStatus", artifacts.inspection().retries().noQuorumConvergedStatus());

    ArrayNode bindings = check.putArray("artifactBindings");
    for (String name : OUTPUTS) {
      Path file = reports.resolve(name);
      require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS), "missing M12 artifact " + name);
      ObjectNode binding = bindings.addObject();
      binding.put("path", name);
      binding.put("bytes", size(file));
      binding.put("sha256", Hashing.sha256Hex(read(file)));
    }
    check.set("releaseTarget", releaseTarget());
    return check;
  }

  private static void writeFailure(Path root, Path reports, String status, String message) {
    ObjectNode report = JsonSupport.MAPPER.createObjectNode();
    report.put("schemaVersion", "matching.m12.check.v2");
    report.put("unit", "M12");
    report.put("status", status);
    report.put("contractPlanVersion", "0.15");
    report.put("failure", message);
    report.set("releaseTarget", releaseTarget());
    validate(root, report, CHECK_SCHEMA_PATH);
    write(reports, "check.json", report);
  }

  private static ObjectNode releaseTarget() {
    ObjectNode release = JsonSupport.MAPPER.createObjectNode();
    release.put("unitTag", "course/m12-complete");
    release.put("productRelease", "matching-0.8.0");
    release.put("verification", "THREE_MEMBER_FAILOVER_PROFILE_AND_CLEAN_TREE_EVIDENCE");
    release.put("singleShard", true);
    release.put("performanceQualified", false);
    release.put("backupRestoreQualified", false);
    release.put("externalServices", false);
    return release;
  }

  private static Map<String, String> verifyCourse(Path root) {
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
    expected.put("lifecycle", "COMPLETE");
    expected.put("designDepth", "IMPLEMENTED");
    expected.put("startRef", "course/m12-start");
    expected.put("completeRef", "course/m12-complete");
    expected.put("productRelease", "matching-0.8.0");
    expected.put("m12Check.expectedStatus", PASS);
    expected.put("evidencePath", "build/lab-evidence/M12/manifest.json");
    require(properties.size() == expected.size(), "M12 course declaration field set changed");
    expected.forEach(
        (key, value) ->
            require(
                value.equals(properties.getProperty(key)),
                "M12 course declaration changed: " + key));
    return Map.copyOf(expected);
  }

  private static void validate(Path root, JsonNode value, String schemaPath) {
    JsonSupport.validate(
        value, new String(read(root.resolve(schemaPath)), StandardCharsets.UTF_8), false);
  }

  private static void write(Path reports, String name, JsonNode value) {
    AtomicFiles.write(reports.resolve(name), JsonSupport.prettyBytes(value));
  }

  private static byte[] read(Path path) {
    try {
      return Files.readAllBytes(path);
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

  private static String stableMessage(RuntimeException failure, Path root) {
    String message = failure.getMessage();
    String value =
        failure.getClass().getSimpleName()
            + (message == null || message.isBlank() ? "" : ": " + message);
    value = value.replace(root.toString(), "<repository>");
    return value.length() <= 4_096 ? value : value.substring(0, 4_096);
  }

  private static void clear(Path directory) {
    if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    if (Files.isSymbolicLink(directory)) {
      throw new IllegalStateException("M12 report directory must not be a symlink");
    }
    try (var paths = Files.walk(directory)) {
      paths.sorted(Comparator.reverseOrder()).forEach(M12CheckRunner::delete);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot clear M12 reports", failure);
    }
  }

  private static void delete(Path path) {
    try {
      Files.delete(path);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot clear M12 report path " + path, failure);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new M12SemanticFailure(message);
    }
  }

  private static void systemRequire(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }

  static String classifySemanticFailure(M12SemanticFailure failure) {
    Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    return semanticOnly(failure, visited) ? STUDENT_FAILURE : SYSTEM_ERROR;
  }

  private static boolean semanticOnly(Throwable failure, Set<Throwable> visited) {
    if (!visited.add(failure) || !(failure instanceof M12SemanticFailure)) {
      return false;
    }
    Throwable cause = failure.getCause();
    if (cause != null && !semanticOnly(cause, visited)) {
      return false;
    }
    for (Throwable suppressed : failure.getSuppressed()) {
      if (!semanticOnly(suppressed, visited)) {
        return false;
      }
    }
    return true;
  }

  private record Artifacts(
      Map<String, String> course,
      M12WorkloadLoader.Workload workload,
      M12DeterministicCorpus.Corpus corpus,
      ObjectNode inherited,
      ObjectNode architecture,
      M12MutantSuite.Result mutants,
      M12ClusterFaultSuite.Result cluster,
      M12CoverageLedger.Result coverage,
      M12HistoryJudge.Inspection inspection,
      ObjectNode environment) {
    private Artifacts {
      course = Map.copyOf(course);
      inherited = inherited.deepCopy();
      architecture = architecture.deepCopy();
      environment = environment.deepCopy();
    }

    @Override
    public ObjectNode inherited() {
      return inherited.deepCopy();
    }

    @Override
    public ObjectNode architecture() {
      return architecture.deepCopy();
    }

    @Override
    public ObjectNode environment() {
      return environment.deepCopy();
    }
  }

  public record Result(String status, Path reportPath) {}
}
