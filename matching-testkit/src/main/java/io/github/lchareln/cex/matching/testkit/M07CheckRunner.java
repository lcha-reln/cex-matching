package io.github.lchareln.cex.matching.testkit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Fail-closed completion judge for M07 opaque participant groups and taker-owned STP. */
public final class M07CheckRunner {
  public static final String PASS = M07PropertyJudge.PASS;
  public static final String STUDENT_FAILURE = M07PropertyJudge.STUDENT_FAILURE;
  public static final String SYSTEM_ERROR = M07PropertyJudge.SYSTEM_ERROR;
  static final String CHECK_SCHEMA_PATH = "schemas/matching.m07.check.v2.schema.json";
  static final List<String> OUTPUTS =
      List.of(
          "m00-m06-regression.json",
          "legacy-entrypoints.json",
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

  private final M07Candidate.Factory production;

  public M07CheckRunner() {
    this(M07ProductionCandidate::new);
  }

  M07CheckRunner(M07Candidate.Factory production) {
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
    M07LegacyRegression.Result legacy =
        new M07LegacyRegression().run(root, reports.resolve(".m06-regression"), trustedOutputRoot);
    M07LegacyEntrypointRegression.Result legacyEntrypoints =
        new M07LegacyEntrypointRegression().run();
    M07FixedScenarioRunner.Result fixed;
    try {
      fixed = new M07FixedScenarioRunner().run(root, production);
    } catch (M07FixedScenarioRunner.CandidateFailure failure) {
      throw new StudentFailure(failure.getMessage());
    }
    studentRequire(
        fixed.scenarios() == 16
            && fixed.commands() == 72
            && fixed.differentialComparisons() == 72
            && fixed.ledgerChecks() == 72,
        "M07 fixed proof counts changed");

    M07Corpus.Fixed corpus = M07Corpus.loadFixed(root);
    M07Corpus.Profile profile = M07Corpus.loadProfile(root);
    M07GeneratedSuite generator = new M07GeneratedSuite();
    List<M07GeneratedSuite.History> histories = generator.generate(profile, corpus);
    M07Canonical.Canonical generated = M07Canonical.generated(histories);
    M07Canonical.Canonical regenerated =
        M07Canonical.generated(generator.generate(profile, corpus));
    systemRequire(
        Arrays.equals(generated.bytes(), regenerated.bytes())
            && generated.digest().equals(regenerated.digest()),
        "two M07 generations produced different canonical bytes");
    systemRequire(generated.commands() == 10_240, "M07 generated command count changed");

    Metrics metrics = verifyProduction(histories);
    M07Coverage.Result coverage = new M07Coverage().analyze(corpus, histories);
    coverage.assertComplete();
    M07CounterexampleSuite.Result counterexamples = new M07CounterexampleSuite().run(root);
    M07ArchitectureGate.Report architecture = new M07ArchitectureGate().verify(root);
    studentRequire(
        architecture.passed(), "M07 architecture boundary failed: " + architecture.violations());
    return new Artifacts(
        legacy,
        legacyEntrypoints,
        fixed,
        profile,
        generated,
        metrics,
        coverage,
        counterexamples,
        architecture);
  }

  private Metrics verifyProduction(List<M07GeneratedSuite.History> histories) {
    int commands = 0;
    int comparisons = 0;
    int ledgers = 0;
    M07PropertyJudge judge = new M07PropertyJudge();
    for (M07GeneratedSuite.History history : histories) {
      M07PropertyJudge.Observation observation = judge.judge(history.commands(), production);
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
        "M07 generated proof counts changed");
    return new Metrics(histories.size(), commands, comparisons, ledgers);
  }

  private static void writePass(Path root, Path reports, Artifacts artifacts) {
    write(reports, "m00-m06-regression.json", legacyReport(artifacts.legacy()));
    write(
        reports, "legacy-entrypoints.json", legacyEntrypointReport(artifacts.legacyEntrypoints()));
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

  private static ObjectNode legacyReport(M07LegacyRegression.Result legacy) {
    ObjectNode report = report("matching.m07.m06-regression-report.v1");
    report.put("checkSchemaVersion", "matching.m06.check.v2");
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

  private static ObjectNode legacyEntrypointReport(M07LegacyEntrypointRegression.Result result) {
    ObjectNode report = report("matching.m07.legacy-entrypoints-report.v1");
    report.put("executedEntrypoints", result.entrypoints());
    report.put("place", true);
    report.put("placeRequest", true);
    report.put("placeGoverned", true);
    report.put("semanticResultsIdentical", result.byteEquivalentSemantics());
    report.put("participantGroupId", result.participantGroupId());
    report.put("stpPolicy", result.stpPolicy());
    return report;
  }

  private static ObjectNode generatedReport(Artifacts artifacts) {
    ObjectNode report = report("matching.m07.generated-properties-report.v1");
    copyGenerator(report, artifacts);
    report.put("differentialComparisons", artifacts.metrics().comparisons());
    report.put("eventLedgerChecks", artifacts.metrics().ledgers());
    return report;
  }

  private static ObjectNode invariantReport(Metrics metrics) {
    ObjectNode report = report("matching.m07.invariants-report.v1");
    report.put("commands", metrics.commands());
    report.put("checks", metrics.ledgers());
    ArrayNode values = report.putArray("properties");
    invariantNames().forEach(values::add);
    return report;
  }

  private static ObjectNode coverageReport(M07Coverage.Result coverage) {
    ObjectNode report = report("matching.m07.coverage-report.v1");
    report.put("requiredObligations", 24);
    report.put("satisfiedObligations", coverage.satisfied());
    ArrayNode values = report.putArray("obligations");
    writeCoverage(values, coverage);
    return report;
  }

  private static ObjectNode boundaryReport() {
    ObjectNode report = report("matching.m07.boundaries-report.v1");
    report.put("participantGroupOwnership", "OPAQUE_CALLER_SUPPLIED");
    report.put("legacyMapping", "GROUP_0_NONE");
    report.put("groupZeroParticipatesInSelfEquality", false);
    report.put("takerOwnsDisposition", true);
    report.put("cancelTakerCancelsFullRemainder", true);
    report.put("cancelMakerContinuesAcrossLevels", true);
    report.put("cancelBothCancelsBothFullRemainders", true);
    report.put("sameGroupTradeForbidden", true);
    report.put("fokPreflight", "READ_ONLY_STP_AWARE");
    report.put("fokFailureAtomic", true);
    report.put("postOnlyPriority", "RAW_BOOK_BEFORE_STP");
    report.put("marketModePriority", "BEFORE_STP_SCAN");
    report.put("ruleSetAttributionPreserved", true);
    report.put("legacyEntrypointsExecuted", 3);
    report.put("finiteCorpus", true);
    report.put("formalProof", false);
    report.put("productionReadinessClaim", false);
    return report;
  }

  private static ObjectNode counterexampleReport(M07CounterexampleSuite.Result suite) {
    ObjectNode report = report("matching.m07.counterexamples-report.v1");
    report.put("required", 8);
    report.put("found", suite.counterexamples().size());
    report.put("replayed", suite.replay().passed());
    report.put(
        "oneMinimal",
        suite.counterexamples().stream()
            .filter(M07CounterexampleSuite.Counterexample::oneMinimal)
            .count());
    report.put(
        "totalCommands",
        suite.counterexamples().stream().mapToInt(value -> value.minimized().size()).sum());
    report.put("canonicalFormat", "M07X1");
    copyCanonical(report, suite.canonical());
    return report;
  }

  private static ObjectNode replayReport(M07CounterexampleSuite.Result suite) {
    ObjectNode report = report("matching.m07.replay-report.v1");
    report.put("requested", suite.replay().scenarios().size());
    report.put("passed", suite.replay().passed());
    report.put("strictClassificationAndFingerprint", true);
    ArrayNode values = report.putArray("scenarios");
    for (M07CounterexampleSuite.ReplayScenario scenario : suite.replay().scenarios()) {
      ObjectNode node = values.addObject();
      node.put("mutantId", scenario.mutantId());
      node.put("passed", scenario.passed());
      node.put("classification", scenario.classification());
      node.put("fingerprint", scenario.fingerprint());
      node.put("commands", scenario.commands());
    }
    return report;
  }

  private static ObjectNode mutantReport(M07CounterexampleSuite.Result suite) {
    ObjectNode report = report("matching.m07.mutants-report.v1");
    report.put("required", 8);
    report.put("killed", suite.counterexamples().size());
    report.put("killClassification", STUDENT_FAILURE);
    report.put("systemErrorControl", suite.systemErrorControl());
    report.put("systemErrorCountsAsKill", false);
    ArrayNode values = report.putArray("mutants");
    for (M07CounterexampleSuite.Counterexample item : suite.counterexamples()) {
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

  private static ObjectNode architectureReport(M07ArchitectureGate.Report architecture) {
    ObjectNode report = report("matching.m07.architecture-report.v1");
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
    ObjectNode inherited = root.putObject("inheritedM06");
    inherited.put("status", PASS);
    inherited.put("checkSchemaVersion", "matching.m06.check.v2");
    inherited.put("fixedScenarios", artifacts.legacy().fixedScenarios());
    inherited.put("fixedCommands", artifacts.legacy().fixedCommands());
    inherited.put("fixedDigest", artifacts.legacy().fixedDigest());
    inherited.put("generatedHistories", artifacts.legacy().generatedHistories());
    inherited.put("generatedCommands", artifacts.legacy().generatedCommands());
    inherited.put("generatedDigest", artifacts.legacy().generatedDigest());
    inherited.put("coverageObligations", artifacts.legacy().coverage());
    inherited.put("mutantsKilled", artifacts.legacy().mutants());

    ObjectNode legacyEntrypoints = root.putObject("legacyEntrypoints");
    legacyEntrypoints.put("executed", artifacts.legacyEntrypoints().entrypoints());
    legacyEntrypoints.put("place", true);
    legacyEntrypoints.put("placeRequest", true);
    legacyEntrypoints.put("placeGoverned", true);
    legacyEntrypoints.put(
        "semanticResultsIdentical", artifacts.legacyEntrypoints().byteEquivalentSemantics());
    legacyEntrypoints.put("participantGroupId", artifacts.legacyEntrypoints().participantGroupId());
    legacyEntrypoints.put("stpPolicy", artifacts.legacyEntrypoints().stpPolicy());

    ObjectNode fixed = root.putObject("fixedCorpus");
    fixed.put("scenarios", artifacts.fixed().scenarios());
    fixed.put("commands", artifacts.fixed().commands());
    ObjectNode counts = fixed.putObject("commandCounts");
    artifacts.fixed().commandCounts().forEach(counts::put);
    fixed.put("canonicalFormat", "M07F1");
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
    coverage.put("requiredObligations", 24);
    coverage.put("satisfiedObligations", artifacts.coverage().satisfied());
    writeCoverage(coverage.putArray("obligations"), artifacts.coverage());

    ObjectNode invariants = root.putObject("invariants");
    invariants.put("passed", true);
    invariants.put("checks", artifacts.metrics().ledgers());
    ArrayNode propertiesArray = invariants.putArray("properties");
    invariantNames().forEach(propertiesArray::add);

    root.set("boundaries", withoutReportEnvelope(boundaryReport()));
    ObjectNode counterexamples = root.putObject("counterexamples");
    M07CounterexampleSuite.Result suite = artifacts.counterexamples();
    counterexamples.put("required", 8);
    counterexamples.put("found", suite.counterexamples().size());
    counterexamples.put("replayed", suite.replay().passed());
    counterexamples.put(
        "oneMinimal",
        suite.counterexamples().stream()
            .filter(M07CounterexampleSuite.Counterexample::oneMinimal)
            .count());
    counterexamples.put(
        "totalCommands",
        suite.counterexamples().stream().mapToInt(value -> value.minimized().size()).sum());
    counterexamples.put("canonicalFormat", "M07X1");
    copyCanonical(counterexamples, suite.canonical());
    ObjectNode mutants = root.putObject("mutants");
    mutants.put("required", 8);
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
    M07StartCheckRunner.TUTORIAL_PERMALINKS.forEach(tutorials::add);
    return root;
  }

  private static ObjectNode withoutReportEnvelope(ObjectNode report) {
    ObjectNode value = report.deepCopy();
    value.remove(List.of("schemaVersion", "status"));
    return value;
  }

  private static void copyGenerator(ObjectNode node, Artifacts artifacts) {
    node.put("algorithm", M07GeneratedSuite.ALGORITHM);
    node.put("baseSeed", Long.toUnsignedString(artifacts.profile().baseSeed()));
    node.put("histories", artifacts.profile().histories());
    node.put("commandsPerHistory", artifacts.profile().commandsPerHistory());
    node.put("commands", artifacts.generated().commands());
    node.put("lanes", artifacts.profile().lanes().size());
    node.put("historiesPerLane", 32);
    node.put("canonicalFormat", "M07H1");
    copyCanonical(node, artifacts.generated());
    node.put("freshGenerations", 2);
  }

  private static void copyCanonical(ObjectNode node, M07Canonical.Canonical canonical) {
    node.put("canonicalDigest", "sha256:" + canonical.digest());
    node.put("canonicalBytes", canonical.bytes().length);
    node.put("canonicalLines", canonical.lines());
  }

  private static void writeCoverage(ArrayNode values, M07Coverage.Result coverage) {
    for (M07Coverage.Witness witness : coverage.witnesses()) {
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
        "RAW_STP_VALIDATION_PRIORITY",
        "LEGACY_GROUP_ZERO_NONE",
        "GROUP_ZERO_NEVER_SELF",
        "SAME_GROUP_NEVER_TRADES",
        "TAKER_OWNED_DISPOSITION",
        "CANCEL_TAKER_FULL_REMAINDER",
        "CANCEL_MAKER_CONTINUES_SCAN",
        "CANCEL_BOTH_FULL_REMAINDERS",
        "FOK_STP_AWARE_PRECHECK",
        "POST_ONLY_RAW_BOOK_PRECEDENCE",
        "RULE_AND_MODE_ATTRIBUTION",
        "PRICE_TIME_PRIORITY",
        "QUANTITY_PARTITION");
  }

  private static ObjectNode base(String status) {
    ObjectNode report = JsonSupport.MAPPER.createObjectNode();
    report.put("schemaVersion", "matching.m07.check.v2");
    report.put("unit", "M07");
    report.put("status", status);
    report.put("contractPlanVersion", "0.9");
    ObjectNode release = report.putObject("releaseTarget");
    release.put("unitTag", "course/m07-complete");
    release.putNull("productRelease");
    release.put("verification", "M07_EVIDENCE_ONLY");
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
    report.put("failure", failure == null || failure.isBlank() ? "M07 check failed" : failure);
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
        throw new IllegalStateException("cannot clear M07 report directory", failure);
      }
    }
    try {
      Files.createDirectories(path);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot create M07 report directory", failure);
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
      M07LegacyRegression.Result legacy,
      M07LegacyEntrypointRegression.Result legacyEntrypoints,
      M07FixedScenarioRunner.Result fixed,
      M07Corpus.Profile profile,
      M07Canonical.Canonical generated,
      Metrics metrics,
      M07Coverage.Result coverage,
      M07CounterexampleSuite.Result counterexamples,
      M07ArchitectureGate.Report architecture) {}

  public record Result(String status, Path reportPath) {}
}
