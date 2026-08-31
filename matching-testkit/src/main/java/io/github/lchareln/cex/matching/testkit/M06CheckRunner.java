package io.github.lchareln.cex.matching.testkit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Fail-closed completion judge for M06 operating modes and deterministic Mass Cancel. */
public final class M06CheckRunner {
  public static final String PASS = M06PropertyJudge.PASS;
  public static final String STUDENT_FAILURE = M06PropertyJudge.STUDENT_FAILURE;
  public static final String SYSTEM_ERROR = M06PropertyJudge.SYSTEM_ERROR;
  static final String CHECK_SCHEMA_PATH = "schemas/matching.m06.check.v2.schema.json";
  static final List<String> OUTPUTS =
      List.of(
          "m00-m05-regression.json",
          "fixed-scenario-pack.json",
          "fixed-event-batches.json",
          "fixed-history.canonical.utf8",
          "generated-history.canonical.utf8",
          "generated-properties.json",
          "invariants.json",
          "coverage.json",
          "boundaries.json",
          "counterexamples-v1.json",
          "counterexamples.json",
          "counterexamples.canonical.utf8",
          "replay.json",
          "mutants.json",
          "architecture.json",
          "check.json");

  private final M06Candidate.Factory production;

  public M06CheckRunner() {
    this(M06ProductionCandidate::new);
  }

  M06CheckRunner(M06Candidate.Factory production) {
    this.production = java.util.Objects.requireNonNull(production, "production");
  }

  public Result run(Path repositoryRoot, Path reportDirectory) {
    return run(repositoryRoot, reportDirectory, repositoryRoot);
  }

  Result run(Path repositoryRoot, Path reportDirectory, Path trustedOutputRoot) {
    Path root = repositoryRoot.toAbsolutePath().normalize();
    Path reports = SafeOutputPaths.resolveTrustedOutput(trustedOutputRoot, reportDirectory);
    clear(reports);
    try {
      Artifacts artifacts = execute(root, reports, trustedOutputRoot);
      writePass(root, reports, artifacts);
      return new Result(PASS, reports.resolve("check.json"));
    } catch (StudentFailure failure) {
      clear(reports);
      writeFailure(root, reports, STUDENT_FAILURE, failure.getMessage());
      return new Result(STUDENT_FAILURE, reports.resolve("check.json"));
    } catch (RuntimeException failure) {
      clear(reports);
      writeFailure(root, reports, SYSTEM_ERROR, stableMessage(failure, root));
      return new Result(SYSTEM_ERROR, reports.resolve("check.json"));
    }
  }

  private Artifacts execute(Path root, Path reports, Path trustedOutputRoot) {
    M06LegacyRegression.Result legacy =
        new M06LegacyRegression().run(root, reports.resolve(".m05-regression"), trustedOutputRoot);
    M06FixedScenarioRunner.Result fixed;
    try {
      fixed = new M06FixedScenarioRunner().run(root, production);
    } catch (M06FixedScenarioRunner.CandidateFailure failure) {
      throw new StudentFailure(failure.getMessage());
    }
    studentRequire(
        fixed.scenarios() == 15
            && fixed.commands() == 64
            && fixed.differentialComparisons() == 64
            && fixed.ledgerChecks() == 64,
        "M06 fixed proof counts changed");

    M06Corpus.Fixed corpus = M06Corpus.loadFixed(root);
    M06Corpus.Profile profile = M06Corpus.loadProfile(root);
    M06GeneratedSuite generator = new M06GeneratedSuite();
    List<M06GeneratedSuite.History> histories = generator.generate(profile, corpus);
    M06Canonical.Canonical generated = M06Canonical.generated(histories);
    M06Canonical.Canonical regenerated =
        M06Canonical.generated(generator.generate(profile, corpus));
    systemRequire(
        Arrays.equals(generated.bytes(), regenerated.bytes())
            && generated.digest().equals(regenerated.digest()),
        "two M06 generations produced different canonical bytes");
    systemRequire(generated.commands() == 10_240, "M06 generated command count changed");

    Metrics metrics = verifyProduction(histories);
    M06Coverage.Result coverage = new M06Coverage().analyze(corpus, histories);
    coverage.assertComplete();
    M06CounterexampleSuite.Result counterexamples = new M06CounterexampleSuite().run(root);
    M06ArchitectureGate.Report architecture = new M06ArchitectureGate().verify(root);
    studentRequire(
        architecture.passed(), "M06 architecture boundary failed: " + architecture.violations());
    return new Artifacts(
        legacy, fixed, profile, generated, metrics, coverage, counterexamples, architecture);
  }

  private Metrics verifyProduction(List<M06GeneratedSuite.History> histories) {
    int commands = 0;
    int comparisons = 0;
    int ledgers = 0;
    M06PropertyJudge judge = new M06PropertyJudge();
    for (M06GeneratedSuite.History history : histories) {
      M06PropertyJudge.Observation observation = judge.judge(history.commands(), production);
      if (SYSTEM_ERROR.equals(observation.classification())) {
        throw new IllegalStateException(
            "production candidate raised SYSTEM_ERROR at history "
                + history.index()
                + ": "
                + observation.message());
      }
      studentRequire(
          PASS.equals(observation.classification()),
          "production candidate failed history "
              + history.index()
              + " at "
              + observation.fingerprint()
              + ": "
              + observation.message());
      commands += observation.completedCommands();
      comparisons += observation.differentialComparisons();
      ledgers += observation.ledgerChecks();
    }
    studentRequire(
        histories.size() == 160 && commands == 10_240 && comparisons == 10_240 && ledgers == 10_240,
        "M06 generated proof counts changed");
    return new Metrics(histories.size(), commands, comparisons, ledgers);
  }

  private static void writePass(Path root, Path reports, Artifacts artifacts) {
    write(reports, "m00-m05-regression.json", legacyReport(artifacts.legacy()));
    AtomicFiles.write(
        reports.resolve("fixed-scenario-pack.json"),
        JsonSupport.prettyBytes(artifacts.fixed().scenarioPack()));
    AtomicFiles.write(
        reports.resolve("fixed-event-batches.json"),
        JsonSupport.prettyBytes(artifacts.fixed().eventBatches()));
    AtomicFiles.write(
        reports.resolve("fixed-history.canonical.utf8"), artifacts.fixed().canonical().bytes());
    AtomicFiles.write(
        reports.resolve("generated-history.canonical.utf8"), artifacts.generated().bytes());
    write(reports, "generated-properties.json", generatedReport(artifacts));
    write(reports, "invariants.json", invariantReport(artifacts.metrics()));
    write(reports, "coverage.json", coverageReport(artifacts.coverage()));
    write(reports, "boundaries.json", boundaryReport());
    AtomicFiles.write(
        reports.resolve("counterexamples-v1.json"), artifacts.counterexamples().persistedBytes());
    write(reports, "counterexamples.json", counterexampleReport(artifacts.counterexamples()));
    AtomicFiles.write(
        reports.resolve("counterexamples.canonical.utf8"),
        artifacts.counterexamples().canonical().bytes());
    write(reports, "replay.json", replayReport(artifacts.counterexamples()));
    write(reports, "mutants.json", mutantReport(artifacts.counterexamples()));
    write(reports, "architecture.json", architectureReport(artifacts.architecture()));
    ObjectNode check = passReport(artifacts);
    JsonSupport.validate(check, readString(root.resolve(CHECK_SCHEMA_PATH)), false);
    write(reports, "check.json", check);
  }

  private static ObjectNode legacyReport(M06LegacyRegression.Result legacy) {
    ObjectNode report = report("matching.m06.m05-regression-report.v1");
    report.put("checkSchemaVersion", "matching.m05.check.v2");
    report.put("fixedScenarios", legacy.fixedScenarios());
    report.put("fixedCommands", legacy.fixedCommands());
    report.put("fixedDigest", legacy.fixedDigest());
    report.put("generatedHistories", legacy.generatedHistories());
    report.put("generatedCommands", legacy.generatedCommands());
    report.put("generatedDigest", legacy.generatedDigest());
    report.put("coverageObligations", legacy.coverage());
    report.put("mutantsKilled", legacy.mutants());
    return report;
  }

  private static ObjectNode generatedReport(Artifacts artifacts) {
    ObjectNode report = report("matching.m06.generated-properties-report.v1");
    copyGenerator(report, artifacts);
    report.put("differentialComparisons", artifacts.metrics().comparisons());
    report.put("eventLedgerChecks", artifacts.metrics().ledgers());
    return report;
  }

  private static ObjectNode invariantReport(Metrics metrics) {
    ObjectNode report = report("matching.m06.invariants-report.v1");
    report.put("commands", metrics.commands());
    report.put("checks", metrics.ledgers());
    ArrayNode values = report.putArray("properties");
    invariantNames().forEach(values::add);
    return report;
  }

  private static ObjectNode coverageReport(M06Coverage.Result coverage) {
    ObjectNode report = report("matching.m06.coverage-report.v1");
    report.put("requiredObligations", 26);
    report.put("satisfiedObligations", coverage.satisfied());
    ArrayNode values = report.putArray("obligations");
    writeCoverage(values, coverage);
    return report;
  }

  private static ObjectNode boundaryReport() {
    ObjectNode report = report("matching.m06.boundaries-report.v1");
    report.put("initialMode", "OPEN");
    report.put("operatorIdIsAuditAttribution", true);
    report.put("operatorIdIsAuthorizationProof", false);
    report.put("placeAllowedOnlyOpen", true);
    report.put("cancelAllowedOpenAndCancelOnly", true);
    report.put("haltedCancelRejectsBeforeLookup", true);
    report.put("ruleControlAllowedAllModes", true);
    report.put("directHaltedToOpenRejected", true);
    report.put("sameModeRejected", true);
    report.put("modeTransitionDoesNotClearBook", true);
    report.put("massCancelRequiredMode", "HALTED");
    report.put("massCancelOrder", "GLOBAL_ASCENDING_ACCEPTANCE_SEQUENCE");
    report.put("massCancelAtomic", true);
    report.put("emptyMassCancelSucceeds", true);
    report.put("terminalIdentityPreserved", true);
    report.put("ruleAttributionPreserved", true);
    report.put("finiteCorpus", true);
    report.put("formalProof", false);
    report.put("productionReadinessClaim", false);
    return report;
  }

  private static ObjectNode counterexampleReport(M06CounterexampleSuite.Result suite) {
    ObjectNode report = report("matching.m06.counterexamples-report.v1");
    report.put("required", 10);
    report.put("found", suite.counterexamples().size());
    report.put("replayed", suite.replay().passed());
    report.put(
        "oneMinimal",
        suite.counterexamples().stream()
            .filter(M06CounterexampleSuite.Counterexample::oneMinimal)
            .count());
    report.put(
        "totalCommands",
        suite.counterexamples().stream().mapToInt(value -> value.minimized().size()).sum());
    report.put("canonicalFormat", "M06X1");
    copyCanonical(report, suite.canonical());
    return report;
  }

  private static ObjectNode replayReport(M06CounterexampleSuite.Result suite) {
    ObjectNode report = report("matching.m06.replay-report.v1");
    report.put("requested", suite.replay().scenarios().size());
    report.put("passed", suite.replay().passed());
    report.put("strictClassificationAndFingerprint", true);
    ArrayNode values = report.putArray("scenarios");
    for (M06CounterexampleSuite.ReplayScenario scenario : suite.replay().scenarios()) {
      ObjectNode node = values.addObject();
      node.put("mutantId", scenario.mutantId());
      node.put("passed", scenario.passed());
      node.put("classification", scenario.classification());
      node.put("fingerprint", scenario.fingerprint());
      node.put("commands", scenario.commands());
    }
    return report;
  }

  private static ObjectNode mutantReport(M06CounterexampleSuite.Result suite) {
    ObjectNode report = report("matching.m06.mutants-report.v1");
    report.put("required", 10);
    report.put("killed", suite.counterexamples().size());
    report.put("killClassification", STUDENT_FAILURE);
    report.put("systemErrorControl", suite.systemErrorControl());
    report.put("systemErrorCountsAsKill", false);
    ArrayNode values = report.putArray("mutants");
    for (M06CounterexampleSuite.Counterexample item : suite.counterexamples()) {
      ObjectNode node = values.addObject();
      node.put("id", item.mutantId());
      node.put("classification", item.observation().classification());
      node.put("fingerprint", item.observation().fingerprint());
      node.put("oneMinimal", item.oneMinimal());
      node.put("replayed", true);
      node.put("minimizedCommands", item.minimized().size());
    }
    return report;
  }

  private static ObjectNode architectureReport(M06ArchitectureGate.Report architecture) {
    ObjectNode report = report("matching.m06.architecture-report.v1");
    report.put("passed", architecture.passed());
    report.put("coreSources", architecture.coreSources().size());
    report.put("referenceSources", architecture.referenceSources().size());
    report.put("forbiddenFindings", architecture.violations().size());
    ArrayNode violations = report.putArray("violations");
    architecture.violations().forEach(violations::add);
    return report;
  }

  private static ObjectNode passReport(Artifacts artifacts) {
    ObjectNode root = base(PASS);
    ObjectNode inherited = root.putObject("inheritedM05");
    inherited.put("status", PASS);
    inherited.put("checkSchemaVersion", "matching.m05.check.v2");
    inherited.put("fixedScenarios", artifacts.legacy().fixedScenarios());
    inherited.put("fixedCommands", artifacts.legacy().fixedCommands());
    inherited.put("fixedDigest", artifacts.legacy().fixedDigest());
    inherited.put("generatedHistories", artifacts.legacy().generatedHistories());
    inherited.put("generatedCommands", artifacts.legacy().generatedCommands());
    inherited.put("generatedDigest", artifacts.legacy().generatedDigest());
    inherited.put("coverageObligations", artifacts.legacy().coverage());
    inherited.put("mutantsKilled", artifacts.legacy().mutants());

    ObjectNode fixed = root.putObject("fixedCorpus");
    fixed.put("scenarios", artifacts.fixed().scenarios());
    fixed.put("commands", artifacts.fixed().commands());
    ObjectNode counts = fixed.putObject("commandCounts");
    artifacts.fixed().commandCounts().forEach(counts::put);
    fixed.put("canonicalFormat", "M06F1");
    copyCanonical(fixed, artifacts.fixed().canonical());
    fixed.put("differentialComparisons", artifacts.fixed().differentialComparisons());
    fixed.put("eventLedgerChecks", artifacts.fixed().ledgerChecks());

    ObjectNode generator = root.putObject("generator");
    copyGenerator(generator, artifacts);
    ObjectNode properties = root.putObject("properties");
    properties.put("histories", artifacts.metrics().histories());
    properties.put("commands", artifacts.metrics().commands());
    properties.put("differentialComparisons", artifacts.metrics().comparisons());
    properties.put("eventLedgerChecks", artifacts.metrics().ledgers());
    properties.put("referenceLedgerChecks", artifacts.metrics().ledgers());

    ObjectNode coverage = root.putObject("coverage");
    coverage.put("requiredObligations", 26);
    coverage.put("satisfiedObligations", artifacts.coverage().satisfied());
    writeCoverage(coverage.putArray("obligations"), artifacts.coverage());

    ObjectNode invariants = root.putObject("invariants");
    invariants.put("passed", true);
    invariants.put("checks", artifacts.metrics().ledgers());
    ArrayNode propertiesArray = invariants.putArray("properties");
    invariantNames().forEach(propertiesArray::add);

    root.set("boundaries", withoutReportEnvelope(boundaryReport()));
    ObjectNode counterexamples = root.putObject("counterexamples");
    M06CounterexampleSuite.Result suite = artifacts.counterexamples();
    counterexamples.put("required", 10);
    counterexamples.put("found", suite.counterexamples().size());
    counterexamples.put("replayed", suite.replay().passed());
    counterexamples.put(
        "oneMinimal",
        suite.counterexamples().stream()
            .filter(M06CounterexampleSuite.Counterexample::oneMinimal)
            .count());
    counterexamples.put(
        "totalCommands",
        suite.counterexamples().stream().mapToInt(value -> value.minimized().size()).sum());
    counterexamples.put("canonicalFormat", "M06X1");
    copyCanonical(counterexamples, suite.canonical());
    ObjectNode mutants = root.putObject("mutants");
    mutants.put("required", 10);
    mutants.put("killed", suite.counterexamples().size());
    mutants.put("classification", STUDENT_FAILURE);
    mutants.put("systemErrorControl", suite.systemErrorControl());
    mutants.put("systemErrorCountsAsKill", false);
    ObjectNode architecture = root.putObject("architecture");
    architecture.put("passed", artifacts.architecture().passed());
    architecture.put("coreSources", artifacts.architecture().coreSources().size());
    architecture.put("referenceSources", artifacts.architecture().referenceSources().size());
    architecture.put("forbiddenFindings", artifacts.architecture().violations().size());
    ArrayNode violations = architecture.putArray("violations");
    artifacts.architecture().violations().forEach(violations::add);

    ObjectNode scope = root.putObject("evidenceScope");
    scope.put("finiteCorpus", true);
    scope.put("formalProof", false);
    scope.put("productionReadinessClaim", false);
    scope.put("replicationOrFailoverCovered", false);
    ArrayNode tutorials = root.putArray("tutorialPermalinks");
    M06StartCheckRunner.TUTORIAL_PERMALINKS.forEach(tutorials::add);
    return root;
  }

  private static ObjectNode withoutReportEnvelope(ObjectNode report) {
    ObjectNode value = report.deepCopy();
    value.remove(List.of("schemaVersion", "status"));
    return value;
  }

  private static void copyGenerator(ObjectNode node, Artifacts artifacts) {
    node.put("algorithm", M06GeneratedSuite.ALGORITHM);
    node.put("baseSeed", Long.toUnsignedString(artifacts.profile().baseSeed()));
    node.put("histories", artifacts.profile().histories());
    node.put("commandsPerHistory", artifacts.profile().commandsPerHistory());
    node.put("commands", artifacts.generated().commands());
    node.put("lanes", artifacts.profile().lanes().size());
    node.put("historiesPerLane", 32);
    node.put("canonicalFormat", "M06H1");
    copyCanonical(node, artifacts.generated());
    node.put("freshGenerations", 2);
  }

  private static void copyCanonical(ObjectNode node, M06Canonical.Canonical canonical) {
    node.put("canonicalDigest", "sha256:" + canonical.digest());
    node.put("canonicalBytes", canonical.bytes().length);
    node.put("canonicalLines", canonical.lines());
  }

  private static void writeCoverage(ArrayNode values, M06Coverage.Result coverage) {
    for (M06Coverage.Witness witness : coverage.witnesses()) {
      ObjectNode node = values.addObject();
      node.put("id", witness.id());
      node.put("satisfied", witness.satisfied());
      node.put("source", witness.source());
      node.put("history", witness.history());
      node.put("commandIndex", witness.commandIndex());
      node.put("count", witness.count());
    }
  }

  private static List<String> invariantNames() {
    return List.of(
        "APPLICATION_SEQUENCE_CONTIGUITY",
        "ACCEPTANCE_SEQUENCE_CONTIGUITY",
        "MODE_REVISION_ONLY_ON_SUCCESS",
        "MODE_TRANSITION_FENCE",
        "MODE_PERMISSION_PRECEDENCE",
        "MODE_CHANGE_FAILURE_ATOMICITY",
        "MODE_TRANSITION_BOOK_PRESERVATION",
        "MASS_CANCEL_HALTED_ONLY",
        "MASS_CANCEL_FAILURE_ATOMICITY",
        "MASS_CANCEL_GLOBAL_ACCEPTANCE_ORDER",
        "MASS_CANCEL_TERMINAL_IDENTITY",
        "MASS_CANCEL_RULE_ATTRIBUTION",
        "RULE_CONTROL_ALL_MODES",
        "PRICE_TIME_PRIORITY",
        "QUANTITY_PARTITION");
  }

  private static ObjectNode base(String status) {
    ObjectNode report = JsonSupport.MAPPER.createObjectNode();
    report.put("schemaVersion", "matching.m06.check.v2");
    report.put("unit", "M06");
    report.put("status", status);
    report.put("contractPlanVersion", "0.8");
    ObjectNode release = report.putObject("releaseTarget");
    release.put("unitTag", "course/m06-complete");
    release.putNull("productRelease");
    release.put("verification", "M06_EVIDENCE_ONLY");
    return report;
  }

  private static ObjectNode report(String schemaVersion) {
    ObjectNode report = JsonSupport.MAPPER.createObjectNode();
    report.put("schemaVersion", schemaVersion);
    report.put("status", PASS);
    return report;
  }

  private static void writeFailure(Path root, Path reports, String status, String failure) {
    ObjectNode report = base(status);
    report.put("failure", failure == null || failure.isBlank() ? "M06 check failed" : failure);
    JsonSupport.validate(report, readString(root.resolve(CHECK_SCHEMA_PATH)), false);
    write(reports, "check.json", report);
  }

  private static void write(Path reports, String name, ObjectNode value) {
    AtomicFiles.write(reports.resolve(name), JsonSupport.prettyBytes(value));
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
          if (!current.equals(path)) {
            Files.deleteIfExists(current);
          }
        }
      } catch (IOException failure) {
        throw new IllegalStateException("cannot clear M06 report directory", failure);
      }
    }
    try {
      Files.createDirectories(path);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot create M06 report directory", failure);
    }
  }

  private static String stableMessage(RuntimeException failure, Path root) {
    String message = failure.getMessage();
    return (failure.getClass().getSimpleName()
            + (message == null || message.isBlank() ? "" : ": " + message))
        .replace(root.toString(), "<repository-root>");
  }

  private static void studentRequire(boolean condition, String message) {
    if (!condition) {
      throw new StudentFailure(message);
    }
  }

  private static void systemRequire(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }

  private static final class StudentFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private StudentFailure(String message) {
      super(message);
    }
  }

  private record Metrics(int histories, int commands, int comparisons, int ledgers) {}

  private record Artifacts(
      M06LegacyRegression.Result legacy,
      M06FixedScenarioRunner.Result fixed,
      M06Corpus.Profile profile,
      M06Canonical.Canonical generated,
      Metrics metrics,
      M06Coverage.Result coverage,
      M06CounterexampleSuite.Result counterexamples,
      M06ArchitectureGate.Report architecture) {}

  public record Result(String status, Path reportPath) {}
}
