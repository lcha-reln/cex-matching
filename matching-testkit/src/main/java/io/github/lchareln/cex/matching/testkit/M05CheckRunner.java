package io.github.lchareln.cex.matching.testkit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Fail-closed completion judge for the narrow M05 versioned order-entry price-band axis. */
public final class M05CheckRunner {
  public static final String PASS = M05PropertyJudge.PASS;
  public static final String STUDENT_FAILURE = M05PropertyJudge.STUDENT_FAILURE;
  public static final String SYSTEM_ERROR = M05PropertyJudge.SYSTEM_ERROR;
  static final String CHECK_SCHEMA_PATH = "schemas/matching.m05.check.v2.schema.json";
  static final List<String> OUTPUTS =
      List.of(
          "m00-m04-regression.json",
          "ruleset-hash-vectors.json",
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
  private final M05Candidate.Factory production;

  public M05CheckRunner() {
    this(M05ProductionCandidate::new);
  }

  M05CheckRunner(M05Candidate.Factory production) {
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
      PassArtifacts artifacts = execute(root, reports, trustedOutputRoot);
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

  private PassArtifacts execute(Path root, Path reports, Path trustedOutputRoot) {
    M05LegacyRegression.Result legacy =
        new M05LegacyRegression().run(root, reports.resolve(".m04-regression"), trustedOutputRoot);
    M05BoundaryFacts.Result boundaries = new M05BoundaryFacts().verify(root);
    M05FixedScenarioRunner.Result fixed;
    try {
      fixed = new M05FixedScenarioRunner().run(root, production);
    } catch (M05FixedScenarioRunner.CandidateFailure failure) {
      throw new StudentFailure(failure.getMessage());
    }
    studentRequire(
        fixed.scenarioCount() == 12 && fixed.commandCount() == 54,
        "M05 fixed corpus execution count changed");
    studentRequire(
        fixed.differentialComparisons() == 54 && fixed.ledgerChecks() == 54,
        "M05 fixed corpus differential or ledger count changed");

    M05ScenarioCorpus.Corpus corpus = M05ScenarioCorpus.load(root);
    M05GeneratorProfile profile = M05GeneratorProfile.load(root);
    M05HistoryGenerator generator = new M05HistoryGenerator();
    List<M05GeneratedHistory> histories = generator.generate(profile, corpus);
    M05CommandCanonicalizer canonicalizer = new M05CommandCanonicalizer();
    M05CommandCanonicalizer.CanonicalCommands commands =
        canonicalizer.generated(profile, histories);
    M05CommandCanonicalizer.CanonicalCommands regenerated =
        canonicalizer.generated(profile, generator.generate(profile, corpus));
    systemRequire(
        Arrays.equals(commands.bytes(), regenerated.bytes()),
        "two M05 generations produced different canonical bytes");
    systemRequire(
        commands.digest().equals(regenerated.digest()),
        "two M05 generations produced different canonical digests");
    systemRequire(commands.commandCount() == 10_240, "M05 generated command count changed");

    PropertyMetrics metrics = verifyProduction(histories, production);
    M05GeneratedCoverage.Result coverage = new M05GeneratedCoverage().analyze(profile, histories);
    coverage.assertRequired();
    M05CounterexampleSuite.Result counterexamples = new M05CounterexampleSuite().run(root);
    M05ArchitectureGate.Report architecture = new M05ArchitectureGate().verify(root);
    studentRequire(
        architecture.passed(), "M05 architecture boundary failed: " + architecture.violations());
    return new PassArtifacts(
        legacy,
        boundaries,
        fixed,
        profile,
        commands,
        metrics,
        coverage,
        counterexamples,
        architecture);
  }

  private static PropertyMetrics verifyProduction(
      List<M05GeneratedHistory> histories, M05Candidate.Factory production) {
    int commands = 0;
    int comparisons = 0;
    int ledgers = 0;
    int books = 0;
    int controls = 0;
    M05PropertyJudge judge = new M05PropertyJudge();
    for (M05GeneratedHistory history : histories) {
      M05PropertyJudge.Observation observation = judge.judge(history, production);
      if (SYSTEM_ERROR.equals(observation.classification())) {
        throw new IllegalStateException(
            "production candidate raised SYSTEM_ERROR at history "
                + history.historyIndex()
                + ": "
                + observation.message());
      }
      studentRequire(
          PASS.equals(observation.classification()),
          "production candidate failed history "
              + history.historyIndex()
              + ": "
              + observation.message());
      commands += observation.completedCommands();
      comparisons += observation.differentialComparisons();
      ledgers += observation.ledgerChecks();
      books += observation.bookChecks();
      controls += observation.marketControlChecks();
    }
    studentRequire(
        histories.size() == 160 && commands == 10_240, "M05 generated suite size changed");
    studentRequire(
        comparisons == commands && ledgers == commands && books == commands && controls == commands,
        "M05 generated proof counts changed");
    return new PropertyMetrics(histories.size(), commands, comparisons, ledgers, books, controls);
  }

  private static void writePass(Path root, Path reports, PassArtifacts artifacts) {
    write(reports, "m00-m04-regression.json", legacyReport(artifacts.legacy()));
    write(reports, "ruleset-hash-vectors.json", hashVectorReport());
    AtomicFiles.write(
        reports.resolve("fixed-scenario-pack.json"),
        JsonSupport.prettyBytes(artifacts.fixed().scenarioPack()));
    AtomicFiles.write(
        reports.resolve("fixed-event-batches.json"),
        JsonSupport.prettyBytes(artifacts.fixed().eventBatches()));
    AtomicFiles.write(
        reports.resolve("fixed-history.canonical.utf8"), artifacts.fixed().canonicalBytes());
    AtomicFiles.write(
        reports.resolve("generated-history.canonical.utf8"), artifacts.commands().bytes());
    write(reports, "generated-properties.json", generatedReport(artifacts));
    write(reports, "invariants.json", invariantReport(artifacts.metrics()));
    write(reports, "coverage.json", coverageReport(artifacts.coverage()));
    write(reports, "boundaries.json", boundaryReport(artifacts.boundaries()));
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

  private static ObjectNode legacyReport(M05LegacyRegression.Result legacy) {
    ObjectNode report = report("matching.m05.m04-regression-report.v1");
    report.put("historicalArchitectureGateExecuted", false);
    report.put("fixedScenarios", legacy.fixedScenarios());
    report.put("fixedCommands", legacy.fixedCommands());
    report.put("fixedDigest", legacy.fixedDigest());
    report.put("generatedHistories", legacy.generatedHistories());
    report.put("generatedCommands", legacy.generatedCommands());
    report.put("generatedDigest", legacy.generatedDigest());
    report.put("coverageObligations", legacy.coverageObligations());
    report.put("counterexamples", legacy.counterexamples());
    report.put("mutantsKilled", legacy.mutantsKilled());
    report.putNull("productRelease");
    return report;
  }

  private static ObjectNode hashVectorReport() {
    ObjectNode report = report("matching.m05.ruleset-hash-vectors-report.v1");
    report.put("canonicalFormat", "M05RS1");
    ArrayNode vectors = report.putArray("vectors");
    for (M05BoundaryFacts.HashVector vector : M05BoundaryFacts.frozenHashVectors()) {
      ObjectNode node = vectors.addObject();
      node.put("id", vector.id());
      node.put("version", vector.version());
      node.put("lowerInclusive", vector.lowerInclusive());
      node.put("upperInclusive", vector.upperInclusive());
      node.put("contentHash", vector.contentHash());
    }
    return report;
  }

  private static ObjectNode generatedReport(PassArtifacts artifacts) {
    ObjectNode report = report("matching.m05.generated-properties-report.v1");
    report.put("algorithm", M05GeneratorProfile.ALGORITHM);
    report.put("baseSeed", Long.toUnsignedString(artifacts.profile().baseSeed()));
    report.put("histories", artifacts.metrics().histories());
    report.put("commandsPerHistory", artifacts.profile().commandsPerHistory());
    report.put("commands", artifacts.metrics().commands());
    report.put("differentialComparisons", artifacts.metrics().comparisons());
    report.put("ledgerChecks", artifacts.metrics().ledgers());
    report.put("bookChecks", artifacts.metrics().books());
    report.put("marketControlChecks", artifacts.metrics().controls());
    report.put("canonicalFormat", "M05H1");
    report.put("canonicalDigest", artifacts.commands().digest());
    report.put("canonicalBytes", artifacts.commands().bytes().length);
    report.put("canonicalLines", countLines(artifacts.commands().bytes()));
    report.put("freshGenerations", 2);
    ObjectNode lanes = report.putObject("lanes");
    M05StartCheckRunner.LANE_IDS.forEach(lane -> lanes.put(lane, 32));
    return report;
  }

  private static ObjectNode invariantReport(PropertyMetrics metrics) {
    ObjectNode report = report("matching.m05.invariants-report.v1");
    report.put("commands", metrics.commands());
    report.put("checks", metrics.ledgers());
    report.put("eventLedgerChecks", metrics.ledgers());
    report.put("bookChecks", metrics.books());
    report.put("marketControlChecks", metrics.controls());
    ArrayNode properties = report.putArray("properties");
    List.of(
            "APPLICATION_SEQUENCE_CONTIGUITY",
            "ACCEPTANCE_SEQUENCE_CONTIGUITY",
            "RULE_SET_PREPARE_ATOMICITY",
            "RULE_SET_VERSION_MONOTONICITY",
            "RULE_SET_ACTIVATION_ATOMICITY",
            "ACTIVATION_SEQUENCE_FENCE",
            "GOVERNED_PLACE_FENCE",
            "INCLUSIVE_ORDER_ENTRY_PRICE_BAND",
            "GRANDFATHER_RESTING_ORDERS",
            "RULE_SET_ATTRIBUTION",
            "PRICE_TIME_PRIORITY",
            "QUANTITY_PARTITION")
        .forEach(properties::add);
    return report;
  }

  private static ObjectNode coverageReport(M05GeneratedCoverage.Result coverage) {
    ObjectNode report = report("matching.m05.coverage-report.v1");
    report.put("requiredObligations", 20);
    report.put("satisfiedObligations", coverage.satisfiedObligations());
    ArrayNode obligations = report.putArray("obligations");
    for (M05GeneratedCoverage.Obligation obligation : coverage.obligations()) {
      ObjectNode node = obligations.addObject();
      node.put("id", obligation.id());
      node.put("satisfied", obligation.satisfied());
      node.put("historyIndex", obligation.historyIndex());
      node.put("commandIndex", obligation.commandIndex());
      node.put("count", coverage.counts().getOrDefault(obligation.id(), 0));
    }
    return report;
  }

  private static ObjectNode boundaryReport(M05BoundaryFacts.Result facts) {
    ObjectNode report = report("matching.m05.boundaries-report.v1");
    report.put("canonicalFormat", facts.canonicalFormat());
    report.put("hashVectors", facts.hashVectors());
    report.put("hashMismatchFailsClosed", facts.hashMismatchFailsClosed());
    report.put("sameVersionDifferentHashFailsClosed", facts.sameVersionDifferentHashFailsClosed());
    report.put("lowerInclusive", facts.lowerInclusive());
    report.put("upperInclusive", facts.upperInclusive());
    report.put("longMaximumInclusive", facts.longMaximumInclusive());
    report.put("staleActivationFenceFailsClosed", facts.staleActivationFenceFailsClosed());
    report.put("stalePlaceFenceFailsClosed", facts.stalePlaceFenceFailsClosed());
    report.put("grandfatherExistingOrders", facts.grandfatherExistingOrders());
    return report;
  }

  private static ObjectNode counterexampleReport(M05CounterexampleSuite.Result suite) {
    ObjectNode report = report("matching.m05.counterexamples-report.v1");
    report.put("required", 8);
    report.put("found", suite.counterexamples().size());
    report.put("replayed", suite.replay().scenarios().size());
    report.put(
        "oneMinimal",
        suite.counterexamples().stream().filter(item -> item.shrunk().oneMinimal()).count());
    report.put(
        "totalCommands",
        suite.counterexamples().stream().mapToInt(item -> item.shrunk().commands().size()).sum());
    report.put("canonicalFormat", "M05X1");
    report.put("canonicalDigest", suite.canonical().digest());
    report.put("canonicalBytes", suite.canonical().bytes().length);
    report.put("canonicalLines", suite.canonical().lines());
    ArrayNode scenarios = report.putArray("scenarios");
    for (M05CounterexampleSuite.Counterexample item : suite.counterexamples()) {
      ObjectNode node = scenarios.addObject();
      node.put("mutantId", item.mutant().id());
      node.put("historyIndex", item.source().historyIndex());
      node.put("lane", item.source().laneId());
      node.put("seed", item.source().seedHex());
      node.put("originalCommands", item.source().commands().size());
      node.put("minimizedCommands", item.shrunk().commands().size());
      node.put("oneMinimal", item.shrunk().oneMinimal());
      node.put("fingerprint", item.mutant().fingerprint().value());
    }
    return report;
  }

  private static ObjectNode replayReport(M05CounterexampleSuite.Result suite) {
    ObjectNode report = report("matching.m05.replay-report.v1");
    report.put("requested", suite.replay().scenarios().size());
    report.put("passed", suite.replay().allPassed());
    ArrayNode scenarios = report.putArray("scenarios");
    for (M05CounterexampleReplay.ReplayScenario scenario : suite.replay().scenarios()) {
      ObjectNode node = scenarios.addObject();
      node.put("mutantId", scenario.mutantId());
      node.put("passed", scenario.passed());
      node.put("classification", scenario.classification());
      node.put("fingerprint", scenario.fingerprint());
    }
    return report;
  }

  private static ObjectNode mutantReport(M05CounterexampleSuite.Result suite) {
    ObjectNode report = report("matching.m05.mutants-report.v1");
    report.put("required", 8);
    report.put("killed", suite.counterexamples().size());
    report.put("classification", STUDENT_FAILURE);
    report.put("systemErrorControl", suite.systemErrorControl());
    ArrayNode mutants = report.putArray("mutants");
    for (M05CounterexampleSuite.Counterexample item : suite.counterexamples()) {
      ObjectNode node = mutants.addObject();
      node.put("id", item.mutant().id());
      node.put("classification", item.shrunk().observation().classification());
      node.put("killed", true);
      node.put("fingerprint", item.mutant().fingerprint().value());
      node.put("coverageKey", item.mutant().generatedCoverageKey());
      node.put("oneMinimal", item.shrunk().oneMinimal());
      node.put("replayed", true);
    }
    return report;
  }

  private static ObjectNode architectureReport(M05ArchitectureGate.Report architecture) {
    ObjectNode report = report("matching.m05.architecture-report.v1");
    report.put("passed", architecture.passed());
    report.put("coreSources", architecture.coreSources().size());
    report.put("referenceSources", architecture.referenceSources().size());
    report.put("forbiddenFindings", architecture.violations().size());
    ArrayNode violations = report.putArray("violations");
    architecture.violations().forEach(violations::add);
    return report;
  }

  private static ObjectNode passReport(PassArtifacts artifacts) {
    ObjectNode report = base(PASS);
    ObjectNode inherited = report.putObject("inheritedM04");
    inherited.put("status", PASS);
    inherited.put("checkSchemaVersion", "matching.m04.check.v2");
    inherited.put("fixedScenarios", artifacts.legacy().fixedScenarios());
    inherited.put("fixedCommands", artifacts.legacy().fixedCommands());
    inherited.put("fixedDigest", artifacts.legacy().fixedDigest());
    inherited.put("generatedHistories", artifacts.legacy().generatedHistories());
    inherited.put("generatedCommands", artifacts.legacy().generatedCommands());
    inherited.put("generatedDigest", artifacts.legacy().generatedDigest());
    inherited.put("coverageObligations", artifacts.legacy().coverageObligations());
    inherited.put("counterexamples", artifacts.legacy().counterexamples());
    inherited.put("mutantsKilled", artifacts.legacy().mutantsKilled());
    inherited.putNull("productRelease");

    ObjectNode fixed = report.putObject("fixedCorpus");
    fixed.put("scenarios", artifacts.fixed().scenarioCount());
    fixed.put("commands", artifacts.fixed().commandCount());
    ObjectNode commandCounts = fixed.putObject("commandCounts");
    artifacts.fixed().commandCounts().forEach(commandCounts::put);
    fixed.put("canonicalFormat", "M05F1");
    fixed.put("canonicalDigest", artifacts.fixed().canonicalDigest());
    fixed.put("canonicalBytes", artifacts.fixed().canonicalBytes().length);
    fixed.put("canonicalLines", artifacts.fixed().canonicalLines());
    fixed.put("differentialComparisons", artifacts.fixed().differentialComparisons());
    fixed.put("ledgerChecks", artifacts.fixed().ledgerChecks());

    ObjectNode generator = report.putObject("generator");
    generator.put("algorithm", M05GeneratorProfile.ALGORITHM);
    generator.put("baseSeed", Long.toUnsignedString(artifacts.profile().baseSeed()));
    generator.put("histories", artifacts.profile().histories());
    generator.put("commandsPerHistory", artifacts.profile().commandsPerHistory());
    generator.put("commands", artifacts.commands().commandCount());
    generator.put("lanes", artifacts.profile().lanes().size());
    generator.put("historiesPerLane", 32);
    generator.put("canonicalFormat", "M05H1");
    generator.put("canonicalDigest", artifacts.commands().digest());
    generator.put("canonicalBytes", artifacts.commands().bytes().length);
    generator.put("canonicalLines", countLines(artifacts.commands().bytes()));
    generator.put("freshGenerations", 2);

    ObjectNode properties = report.putObject("properties");
    properties.put("histories", artifacts.metrics().histories());
    properties.put("commands", artifacts.metrics().commands());
    properties.put("differentialComparisons", artifacts.metrics().comparisons());
    properties.put("ledgerChecks", artifacts.metrics().ledgers());
    properties.put("bookChecks", artifacts.metrics().books());
    properties.put("marketControlChecks", artifacts.metrics().controls());

    ObjectNode coverage = report.putObject("coverage");
    coverage.put("requiredObligations", 20);
    coverage.put("satisfiedObligations", artifacts.coverage().satisfiedObligations());
    ArrayNode obligations = coverage.putArray("obligations");
    for (M05GeneratedCoverage.Obligation obligation : artifacts.coverage().obligations()) {
      ObjectNode node = obligations.addObject();
      node.put("id", obligation.id());
      node.put("satisfied", obligation.satisfied());
      node.put("historyIndex", obligation.historyIndex());
      node.put("commandIndex", obligation.commandIndex());
    }

    ObjectNode invariants = report.putObject("invariants");
    invariants.put("passed", true);
    invariants.put("checks", artifacts.metrics().ledgers());
    ArrayNode invariantProperties = invariants.putArray("properties");
    List.of(
            "APPLICATION_SEQUENCE_CONTIGUITY",
            "RULE_SET_PREPARE_ATOMICITY",
            "RULE_SET_ACTIVATION_ATOMICITY",
            "ACTIVATION_SEQUENCE_FENCE",
            "GOVERNED_PLACE_FENCE",
            "INCLUSIVE_ORDER_ENTRY_PRICE_BAND",
            "GRANDFATHER_RESTING_ORDERS",
            "RULE_SET_ATTRIBUTION")
        .forEach(invariantProperties::add);

    copyBoundaryFields(report.putObject("boundaries"), artifacts.boundaries());
    ObjectNode counterexamples = report.putObject("counterexamples");
    M05CounterexampleSuite.Result suite = artifacts.counterexamples();
    counterexamples.put("required", 8);
    counterexamples.put("found", suite.counterexamples().size());
    counterexamples.put("replayed", suite.replay().scenarios().size());
    counterexamples.put(
        "oneMinimal",
        suite.counterexamples().stream().filter(item -> item.shrunk().oneMinimal()).count());
    counterexamples.put(
        "totalCommands",
        suite.counterexamples().stream().mapToInt(item -> item.shrunk().commands().size()).sum());
    counterexamples.put("canonicalFormat", "M05X1");
    counterexamples.put("canonicalDigest", suite.canonical().digest());
    counterexamples.put("canonicalBytes", suite.canonical().bytes().length);
    counterexamples.put("canonicalLines", suite.canonical().lines());
    ObjectNode mutants = report.putObject("mutants");
    mutants.put("required", 8);
    mutants.put("killed", suite.counterexamples().size());
    mutants.put("classification", STUDENT_FAILURE);
    mutants.put("systemErrorControl", suite.systemErrorControl());
    ObjectNode architecture = report.putObject("architecture");
    architecture.put("passed", artifacts.architecture().passed());
    architecture.put("coreSources", artifacts.architecture().coreSources().size());
    architecture.put("referenceSources", artifacts.architecture().referenceSources().size());
    architecture.put("forbiddenFindings", artifacts.architecture().violations().size());
    ArrayNode architectureViolations = architecture.putArray("violations");
    artifacts.architecture().violations().forEach(architectureViolations::add);
    return report;
  }

  private static void copyBoundaryFields(ObjectNode node, M05BoundaryFacts.Result facts) {
    node.put("canonicalFormat", facts.canonicalFormat());
    node.put("hashVectors", facts.hashVectors());
    node.put("hashMismatchFailsClosed", facts.hashMismatchFailsClosed());
    node.put("sameVersionDifferentHashFailsClosed", facts.sameVersionDifferentHashFailsClosed());
    node.put("lowerInclusive", facts.lowerInclusive());
    node.put("upperInclusive", facts.upperInclusive());
    node.put("longMaximumInclusive", facts.longMaximumInclusive());
    node.put("staleActivationFenceFailsClosed", facts.staleActivationFenceFailsClosed());
    node.put("stalePlaceFenceFailsClosed", facts.stalePlaceFenceFailsClosed());
    node.put("grandfatherExistingOrders", facts.grandfatherExistingOrders());
  }

  private static ObjectNode base(String status) {
    ObjectNode report = JsonSupport.MAPPER.createObjectNode();
    report.put("schemaVersion", "matching.m05.check.v2");
    report.put("unit", "M05");
    report.put("status", status);
    report.put("contractPlanVersion", "0.7");
    ObjectNode release = report.putObject("releaseTarget");
    release.put("unitTag", "course/m05-complete");
    release.putNull("productRelease");
    release.put("verification", "M05_EVIDENCE_ONLY");
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
    report.put("failure", failure == null || failure.isBlank() ? "M05 check failed" : failure);
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

  private static int countLines(byte[] bytes) {
    int lines = 0;
    for (byte value : bytes) {
      if (value == '\n') {
        lines++;
      }
    }
    return lines;
  }

  private static void clear(Path path) {
    if (!Files.exists(path)) {
      try {
        Files.createDirectories(path);
      } catch (IOException failure) {
        throw new IllegalStateException("cannot create M05 report directory", failure);
      }
      return;
    }
    try (var paths = Files.walk(path)) {
      for (Path current : paths.sorted(Comparator.reverseOrder()).toList()) {
        if (!current.equals(path)) {
          Files.deleteIfExists(current);
        }
      }
    } catch (IOException failure) {
      throw new IllegalStateException("cannot clear M05 report directory", failure);
    }
  }

  private static String stableMessage(RuntimeException failure, Path root) {
    String message = failure.getMessage();
    String stable =
        failure.getClass().getSimpleName()
            + (message == null || message.isBlank() ? "" : ": " + message);
    return stable.replace(root.toString(), "<repository-root>");
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

    StudentFailure(String message) {
      super(message);
    }
  }

  private record PropertyMetrics(
      int histories, int commands, int comparisons, int ledgers, int books, int controls) {}

  private record PassArtifacts(
      M05LegacyRegression.Result legacy,
      M05BoundaryFacts.Result boundaries,
      M05FixedScenarioRunner.Result fixed,
      M05GeneratorProfile profile,
      M05CommandCanonicalizer.CanonicalCommands commands,
      PropertyMetrics metrics,
      M05GeneratedCoverage.Result coverage,
      M05CounterexampleSuite.Result counterexamples,
      M05ArchitectureGate.Report architecture) {}

  public record Result(String status, Path reportPath) {}
}
