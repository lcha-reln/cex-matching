package io.github.lchareln.cex.matching.testkit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Deterministic fail-closed completion judge for the frozen M04 execution-policy contract. */
public final class M04CheckRunner {
  public static final String SCHEMA_VERSION = "matching.m04.check.v2";
  public static final String PASS = M04PropertyJudge.PASS;
  public static final String STUDENT_FAILURE = M04PropertyJudge.STUDENT_FAILURE;
  public static final String SYSTEM_ERROR = M04PropertyJudge.SYSTEM_ERROR;

  static final String CHECK_SCHEMA_PATH = "schemas/matching.m04.check.v2.schema.json";
  static final String EXPECTED_FIXED_DIGEST =
      "sha256:68de35e41358ea72c9852fdf3fd652db116774964360f0b526f43612576bfa77";
  static final int EXPECTED_FIXED_BYTES = 47_104;
  static final int EXPECTED_FIXED_LINES = 63;
  static final String EXPECTED_COMMAND_DIGEST =
      "sha256:6005c674d0c42927989f1c8c4d1ddce224d06ceff0b95bf58615d23c4496ba51";
  static final int EXPECTED_COMMAND_BYTES = 1_496_773;
  static final int EXPECTED_COMMAND_LINES = 12_481;
  static final List<String> OUTPUTS =
      List.of(
          "m00-m03-regression.json",
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
  static final List<String> CHECK_ARTIFACTS =
      OUTPUTS.stream().filter(name -> !"check.json".equals(name)).toList();

  private final M04Candidate.Factory production;

  public M04CheckRunner() {
    this(M04ProductionCandidate::new);
  }

  M04CheckRunner(M04Candidate.Factory production) {
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
      PassArtifacts artifacts = execute(root);
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

  private PassArtifacts execute(Path root) {
    M04FrozenInputs.Result frozen = new M04FrozenInputs().verify(root);
    M04BoundaryFacts.Result boundaries = new M04BoundaryFacts().verify(root);
    M04FixedScenarioRunner.Result fixed;
    try {
      fixed = new M04FixedScenarioRunner().run(root, production);
    } catch (M04FixedScenarioRunner.CandidateFailure failure) {
      throw new StudentFailure(failure.getMessage());
    }
    systemRequire(EXPECTED_FIXED_DIGEST.equals(fixed.canonicalDigest()), "M04F1 digest changed");
    systemRequire(
        fixed.canonicalBytes().length == EXPECTED_FIXED_BYTES, "M04F1 byte count changed");
    systemRequire(fixed.canonicalLines() == EXPECTED_FIXED_LINES, "M04F1 line count changed");

    M04GeneratorProfile profile =
        M04GeneratorProfile.load(
            root.resolve(M04StartCheckRunner.GENERATOR_PATH),
            root.resolve(M04StartCheckRunner.GENERATOR_SCHEMA_PATH));
    M04HistoryGenerator generator = new M04HistoryGenerator();
    List<M04GeneratedHistory> histories = generator.generate(profile);
    M04CommandCanonicalizer canonicalizer = new M04CommandCanonicalizer();
    M04CommandCanonicalizer.CanonicalCommands commands =
        canonicalizer.canonicalize(profile, histories);
    M04CommandCanonicalizer.CanonicalCommands regenerated =
        canonicalizer.canonicalize(profile, generator.generate(profile));
    systemRequire(
        Arrays.equals(commands.bytes(), regenerated.bytes()),
        "two M04 generations produced different canonical bytes");
    systemRequire(
        commands.digest().equals(regenerated.digest()),
        "two M04 generations produced different canonical digests");
    systemRequire(EXPECTED_COMMAND_DIGEST.equals(commands.digest()), "M04H1 digest changed");
    systemRequire(commands.commandCount() == 12_288, "M04 generated command count changed");
    systemRequire(commands.bytes().length == EXPECTED_COMMAND_BYTES, "M04H1 byte count changed");
    systemRequire(
        countLines(commands.bytes()) == EXPECTED_COMMAND_LINES, "M04H1 line count changed");

    PropertyMetrics metrics = verifyProduction(histories, production);
    M04GeneratedCoverage.Result coverage = new M04GeneratedCoverage().analyze(profile, histories);
    coverage.assertRequired();
    M04CounterexampleSuite.Result counterexamples = new M04CounterexampleSuite().run(root);
    M04LegacyRegression.Result legacy = new M04LegacyRegression().run(root);
    M04ArchitectureGate.Report architecture = new M04ArchitectureGate().verify(root);
    studentRequire(
        architecture.passed(), "M04 architecture boundary failed: " + architecture.violations());
    return new PassArtifacts(
        frozen,
        boundaries,
        fixed,
        profile,
        commands,
        metrics,
        coverage,
        counterexamples,
        legacy,
        architecture);
  }

  private static PropertyMetrics verifyProduction(
      List<M04GeneratedHistory> histories, M04Candidate.Factory production) {
    int commands = 0;
    int comparisons = 0;
    int ledgers = 0;
    int books = 0;
    M04PropertyJudge judge = new M04PropertyJudge();
    for (M04GeneratedHistory history : histories) {
      M04PropertyJudge.Observation observation = judge.judge(history, production);
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
    }
    studentRequire(histories.size() == 192, "M04 generated history count changed");
    studentRequire(commands == 12_288, "M04 property command count changed");
    studentRequire(comparisons == commands, "M04 differential count changed");
    studentRequire(ledgers == commands, "M04 ledger count changed");
    studentRequire(books == commands, "M04 book count changed");
    return new PropertyMetrics(histories.size(), commands, comparisons, ledgers, books);
  }

  private static void writePass(Path root, Path reports, PassArtifacts artifacts) {
    write(reports, "m00-m03-regression.json", legacyReport(artifacts.legacy()));
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
    write(reports, "coverage.json", coverageReport(artifacts));
    write(reports, "boundaries.json", boundaryReport(artifacts.boundaries()));
    AtomicFiles.write(
        reports.resolve("counterexamples-v1.json"), artifacts.counterexamples().persistedBytes());
    write(reports, "counterexamples.json", counterexampleReport(artifacts.counterexamples()));
    AtomicFiles.write(
        reports.resolve("counterexamples.canonical.utf8"),
        artifacts.counterexamples().canonical().bytes());
    write(reports, "replay.json", replayReport(artifacts.counterexamples().replay()));
    write(reports, "mutants.json", mutantReport(artifacts));
    write(reports, "architecture.json", architectureReport(artifacts.architecture()));
    writeCheck(root, reports, passReport(artifacts));
  }

  private static ObjectNode legacyReport(M04LegacyRegression.Result legacy) {
    ObjectNode report = report("matching.m04.m03-regression-report.v1");
    report.put("historicalArchitectureGateExecuted", false);
    report.put("histories", legacy.histories());
    report.put("commands", legacy.commands());
    report.put("bytes", legacy.bytes());
    report.put("lines", legacy.lines());
    report.put("digest", legacy.digest());
    report.put("differentialComparisons", legacy.differentialComparisons());
    report.put("eventLedgerChecks", legacy.ledgerChecks());
    report.put("bookChecks", legacy.bookChecks());
    report.put("mutantsKilled", legacy.mutants().size());
    report.put("counterexamplesOneMinimal", legacy.mutants().size());
    report.put("systemErrorControl", legacy.systemErrorControl());
    ArrayNode mutants = report.putArray("mutants");
    for (M04LegacyRegression.MutantFact fact : legacy.mutants()) {
      ObjectNode node = mutants.addObject();
      node.put("id", fact.id());
      node.put("fingerprint", fact.fingerprint());
      node.put("historyIndex", fact.historyIndex());
      node.put("lane", fact.lane());
      node.put("seed", fact.seed());
      node.put("originalCommands", fact.originalCommands());
      node.put("minimizedCommands", fact.minimizedCommands());
      node.put("shrinkTrials", fact.shrinkTrials());
      node.put("oneMinimal", fact.oneMinimal());
    }
    return report;
  }

  private static ObjectNode generatedReport(PassArtifacts artifacts) {
    ObjectNode report = report("matching.m04.generated-properties-report.v1");
    report.put("histories", artifacts.metrics().histories());
    report.put("commands", artifacts.metrics().commands());
    report.put("differentialComparisons", artifacts.metrics().differentialComparisons());
    report.put("commandDigest", artifacts.commands().digest());
    report.put("commandBytes", artifacts.commands().bytes().length);
    report.put("commandLines", countLines(artifacts.commands().bytes()));
    report.put("freshGenerations", 2);
    ObjectNode lanes = report.putObject("lanes");
    M04StartCheckRunner.LANE_IDS.forEach(lane -> lanes.put(lane, 32));
    return report;
  }

  private static ObjectNode invariantReport(PropertyMetrics metrics) {
    ObjectNode report = report("matching.m04.invariants-report.v1");
    report.put("commands", metrics.commands());
    report.put("eventLedgerChecks", metrics.ledgerChecks());
    report.put("bookLifecycleChecks", metrics.bookChecks());
    ArrayNode properties = report.putArray("properties");
    List.of(
            "VALIDATION_PRIORITY_AND_NO_MUTATION",
            "EXECUTION_POLICY_GRAMMAR",
            "ACCEPTANCE_SEQUENCE_CONTIGUITY",
            "PRICE_TIME_PRIORITY",
            "MAKER_PRICE",
            "PRICE_PROTECTION",
            "QUANTITY_PARTITION",
            "IOC_IMMEDIATE_EXECUTION",
            "IOC_REMAINDER_DISPOSITION",
            "FOK_FILLABILITY",
            "POLICY_REJECTION_ATOMICITY",
            "POST_ONLY_ADMISSION",
            "BOOK_LIFECYCLE_BIJECTION",
            "LIFECYCLE_IRREVERSIBILITY",
            "EXACT_BATCH_DIFFERENTIAL")
        .forEach(properties::add);
    return report;
  }

  private static ObjectNode coverageReport(PassArtifacts artifacts) {
    ObjectNode report = report("matching.m04.coverage-report.v1");
    ObjectNode declared = report.putObject("declared");
    declared.set(
        "ioc", JsonSupport.MAPPER.valueToTree(artifacts.profile().coverageRequirements().ioc()));
    declared.set(
        "fok", JsonSupport.MAPPER.valueToTree(artifacts.profile().coverageRequirements().fok()));
    declared.set(
        "postOnly",
        JsonSupport.MAPPER.valueToTree(artifacts.profile().coverageRequirements().postOnly()));
    declared.put(
        "rejectionIdentityAndSequence",
        artifacts.profile().coverageRequirements().rejectionIdentityAndSequence());
    declared.put("buyAndSell", artifacts.profile().coverageRequirements().buyAndSell());
    ObjectNode counts = report.putObject("counts");
    artifacts.coverage().counts().forEach(counts::put);
    ObjectNode first = report.putObject("firstWitnesses");
    for (Map.Entry<String, M04GeneratedCoverage.Witness> entry :
        artifacts.coverage().firstWitnesses().entrySet()) {
      M04GeneratedCoverage.Witness witness = entry.getValue();
      ObjectNode node = first.putObject(entry.getKey());
      node.put("historyIndex", witness.historyIndex());
      node.put("commandIndex", witness.commandIndex());
      node.put("seed", witness.seedHex());
      node.put("lane", witness.laneId());
    }
    return report;
  }

  private static ObjectNode boundaryReport(M04BoundaryFacts.Result boundaries) {
    ObjectNode report = report("matching.m04.boundaries-report.v1");
    report.put("exactRawPolicyVariants", boundaries.exactRawPolicyVariants());
    report.put("exactRawPolicyPaths", boundaries.exactRawPolicyPaths());
    report.put("longMaxFokDeductionPaths", boundaries.longMaxFokDeductionPaths());
    report.set(
        "rejectedRawPolicies", JsonSupport.MAPPER.valueToTree(boundaries.rejectedRawPolicies()));
    report.set("paths", JsonSupport.MAPPER.valueToTree(boundaries.paths()));
    report.put("generatedQuantityMaximum", 5);
    report.put("longMaxCoveredByGeneratedSuite", false);
    return report;
  }

  private static ObjectNode counterexampleReport(M04CounterexampleSuite.Result result) {
    ObjectNode report = report("matching.m04.counterexample-report.v1");
    report.put("required", result.counterexamples().size());
    report.put("originalCommands", result.counterexamples().size() * 64);
    report.put(
        "minimizedCommands",
        result.counterexamples().stream().mapToInt(item -> item.shrunk().commands().size()).sum());
    ArrayNode scenarios = report.putArray("scenarios");
    for (M04CounterexampleSuite.Counterexample item : result.counterexamples()) {
      ObjectNode node = scenarios.addObject();
      node.put("scenarioId", item.mutant().scenarioId());
      node.put("mutantId", item.mutant().id());
      node.put("coverageKey", item.mutant().generatedCoverageKey());
      node.put("historyIndex", item.source().historyIndex());
      node.put("sourceFailingCommandIndex", item.sourceObservation().failure().commandIndex());
      node.put("lane", item.source().laneId());
      node.put("seed", item.source().seedHex());
      node.put("originalCommands", item.source().commands().size());
      node.put("minimizedCommands", item.shrunk().commands().size());
      node.put("shrinkTrials", item.shrunk().trials());
      node.put("oneMinimal", item.shrunk().oneMinimal());
    }
    return report;
  }

  private static ObjectNode replayReport(M04CounterexampleReplay.ReplayReport replay) {
    ObjectNode report = report("matching.m04.replay-report.v1");
    report.put("requested", replay.scenarios().size());
    report.put(
        "completed",
        replay.scenarios().stream().filter(M04CounterexampleReplay.ScenarioReplay::passed).count());
    ArrayNode scenarios = report.putArray("scenarios");
    for (M04CounterexampleReplay.ScenarioReplay replayed : replay.scenarios()) {
      ObjectNode node = scenarios.addObject();
      node.put("scenarioId", replayed.scenarioId());
      node.put("mutantId", replayed.mutantId());
      node.put("commands", replayed.commandCount());
      node.put("expectedFingerprint", replayed.expectedFingerprint());
      node.put("actualFingerprint", replayed.actualFingerprint());
      node.put("classification", replayed.classification());
      node.put("referenceOutcomesExact", replayed.referenceOutcomesExact());
      node.put("actualOutcomeExact", replayed.actualOutcomeExact());
      node.put("provenanceExact", replayed.provenanceExact());
      node.put("oneMinimalReverified", replayed.oneMinimalReverified());
      node.put("passed", replayed.passed());
    }
    return report;
  }

  private static ObjectNode mutantReport(PassArtifacts artifacts) {
    ObjectNode report = report("matching.m04.mutant-report.v1");
    report.put("required", artifacts.counterexamples().counterexamples().size());
    report.put("killed", artifacts.counterexamples().counterexamples().size());
    report.put("systemErrorControl", artifacts.counterexamples().systemErrorControl());
    ArrayNode mutants = report.putArray("mutants");
    for (M04CounterexampleSuite.Counterexample item :
        artifacts.counterexamples().counterexamples()) {
      ObjectNode node = mutants.addObject();
      node.put("id", item.mutant().id());
      node.put("classification", item.shrunk().observation().classification());
      node.put("killed", true);
      node.put("fingerprint", item.mutant().fingerprint().value());
      node.put("coverageKey", item.mutant().generatedCoverageKey());
      node.put("historyIndex", item.source().historyIndex());
      node.put("sourceFailingCommandIndex", item.sourceObservation().failure().commandIndex());
      node.put("lane", item.source().laneId());
      node.put("seed", item.source().seedHex());
      node.put("originalCommands", item.source().commands().size());
      node.put("minimizedCommands", item.shrunk().commands().size());
      node.put("shrinkTrials", item.shrunk().trials());
      node.put("oneMinimal", item.shrunk().oneMinimal());
      node.put("replayed", true);
    }
    return report;
  }

  private static ObjectNode architectureReport(M04ArchitectureGate.Report architecture) {
    ObjectNode report = report("matching.m04.architecture-report.v1");
    report.put("coreSourceFiles", architecture.coreSourceFiles());
    report.put("referenceSourceFiles", architecture.referenceSourceFiles());
    report.put("referenceRepresentation", "FLAT_LIST_LINEAR_SCAN");
    report.put("productionImports", 0);
    report.put("coreRuntimeDependencies", 0);
    ArrayNode violations = report.putArray("violations");
    architecture.violations().forEach(violations::add);
    return report;
  }

  private static ObjectNode passReport(PassArtifacts artifacts) {
    ObjectNode report = base(PASS);
    report.put("contractPlanVersion", "0.6");
    M04LegacyRegression.Result legacy = artifacts.legacy();
    ObjectNode inherited = artifact(report, "inheritedM03", "m00-m03-regression.json");
    inherited.put("histories", legacy.histories());
    inherited.put("commands", legacy.commands());
    inherited.put("bytes", legacy.bytes());
    inherited.put("lines", legacy.lines());
    inherited.put("commandDigest", legacy.digest());
    inherited.put("differentialComparisons", legacy.differentialComparisons());
    inherited.put("ledgerChecks", legacy.ledgerChecks());
    inherited.put("bookChecks", legacy.bookChecks());
    inherited.put("mutantsKilled", legacy.mutants().size());
    inherited.put("counterexamples", legacy.mutants().size());
    inherited.put("oneMinimal", legacy.mutants().size());
    inherited.put("historicalArchitectureGateExecuted", false);

    ObjectNode fixed = artifact(report, "fixedCorpus", "fixed-scenario-pack.json");
    fixed.put("path", M04StartCheckRunner.FIXED_CORPUS_PATH);
    fixed.put("schemaPath", M04StartCheckRunner.FIXED_CORPUS_SCHEMA_PATH);
    fixed.put("sha256", M04StartCheckRunner.FIXED_CORPUS_SHA256);
    fixed.put("scenarios", 14);
    fixed.put("commands", 48);
    fixed.put("placeCommands", 44);
    fixed.put("cancelCommands", 4);
    fixed.put("schemaProbes", artifacts.frozen().fixedSchemaProbes());
    ObjectNode policies = fixed.putObject("policyCounts");
    List.of("GTC", "IOC", "FOK", "POST_ONLY", "UNKNOWN")
        .forEach(policy -> policies.put(policy, artifacts.frozen().policyCounts().get(policy)));
    fixed.put("eventBatchesArtifact", "fixed-event-batches.json");
    fixed.put("canonicalArtifact", "fixed-history.canonical.utf8");
    fixed.put("canonicalFormat", "M04F1");
    fixed.put("canonicalDigest", artifacts.fixed().canonicalDigest());
    fixed.put("canonicalBytes", artifacts.fixed().canonicalBytes().length);
    fixed.put("canonicalLines", artifacts.fixed().canonicalLines());

    ObjectNode generator = report.putObject("generator");
    generator.put("path", M04StartCheckRunner.GENERATOR_PATH);
    generator.put("schemaPath", M04StartCheckRunner.GENERATOR_SCHEMA_PATH);
    generator.put("sha256", M04StartCheckRunner.GENERATOR_SHA256);
    generator.put("algorithm", M04GeneratorProfile.ALGORITHM);
    generator.put("seedDerivation", M04CommandCanonicalizer.SEED_DERIVATION);
    generator.put("baseSeed", "4404");
    generator.put("histories", 192);
    generator.put("commandsPerHistory", 64);
    generator.put("totalCommands", 12_288);
    generator.put("lanes", 6);
    generator.put("historiesPerLane", 32);
    generator.put("schemaProbes", artifacts.frozen().generatorSchemaProbes());
    generator.put("canonicalFormat", "M04H1");
    generator.put("canonicalArtifact", "generated-history.canonical.utf8");
    generator.put("canonicalDigest", artifacts.commands().digest());
    generator.put("canonicalBytes", artifacts.commands().bytes().length);
    generator.put("canonicalLines", countLines(artifacts.commands().bytes()));
    generator.put("freshGenerations", 2);

    PropertyMetrics metrics = artifacts.metrics();
    ObjectNode properties = artifact(report, "properties", "generated-properties.json");
    properties.put("histories", metrics.histories());
    properties.put("commands", metrics.commands());
    properties.put("differentialComparisons", metrics.differentialComparisons());
    properties.put("ledgerChecks", metrics.ledgerChecks());
    properties.put("bookChecks", metrics.bookChecks());

    ObjectNode coverage = artifact(report, "coverage", "coverage.json");
    coverage.put("requiredObligations", artifacts.coverage().counts().size());
    coverage.put("satisfiedObligations", artifacts.coverage().counts().size());
    coverage.put(
        "fokOutsideLimit",
        artifacts.coverage().counts().get(M04GeneratedCoverage.FOK_OUTSIDE_LIMIT_EXCLUDED));
    coverage.put(
        "baseValidUnknown",
        artifacts.coverage().counts().get(M04GeneratedCoverage.BASE_VALID_UNKNOWN));
    coverage.put(
        "baseValidUnusedUnknown",
        artifacts.coverage().counts().get(M04GeneratedCoverage.BASE_VALID_UNUSED_ID_UNKNOWN));

    M04BoundaryFacts.Result boundary = artifacts.boundaries();
    ObjectNode boundaries = artifact(report, "boundaries", "boundaries.json");
    boundaries.put("exactRawPolicyVariants", boundary.exactRawPolicyVariants());
    boundaries.put("exactRawPolicyPaths", boundary.exactRawPolicyPaths());
    boundaries.put("longMaxFokDeductionPaths", boundary.longMaxFokDeductionPaths());

    M04CounterexampleSuite.Result suite = artifacts.counterexamples();
    int minimized =
        suite.counterexamples().stream().mapToInt(item -> item.shrunk().commands().size()).sum();
    ObjectNode counterexamples = artifact(report, "counterexamples", "counterexamples.json");
    counterexamples.put("required", 8);
    counterexamples.put("shrunk", 8);
    counterexamples.put("persisted", 8);
    counterexamples.put("schemaParsed", 8);
    counterexamples.put("replayed", 8);
    counterexamples.put("oneMinimal", 8);
    counterexamples.put("minimizedCommands", minimized);
    counterexamples.put("persistedArtifact", "counterexamples-v1.json");
    ObjectNode canonical = counterexamples.putObject("canonical");
    canonical.put("format", "M04X1");
    canonical.put("artifact", "counterexamples.canonical.utf8");
    canonical.put("digest", suite.canonical().digest());
    canonical.put("bytes", suite.canonical().bytes().length);
    canonical.put("lines", suite.canonical().lines());

    ObjectNode mutants = artifact(report, "mutants", "mutants.json");
    mutants.put("required", 8);
    mutants.put("killed", 8);
    mutants.put("systemErrorControl", suite.systemErrorControl());
    ArrayNode required = mutants.putArray("requiredMutants");
    for (M04CounterexampleSuite.Counterexample item : suite.counterexamples()) {
      ObjectNode mutant = required.addObject();
      mutant.put("id", item.mutant().id());
      mutant.put("fingerprint", item.mutant().fingerprint().value());
      mutant.put("coverageKey", item.mutant().generatedCoverageKey());
      mutant.put("classification", item.shrunk().observation().classification());
      mutant.put("killed", true);
      mutant.put("historyIndex", item.source().historyIndex());
      mutant.put("sourceFailingCommandIndex", item.sourceObservation().failure().commandIndex());
      mutant.put("lane", item.source().laneId());
      mutant.put("seed", item.source().seedHex());
      mutant.put("originalCommands", 64);
      mutant.put("minimizedCommands", item.shrunk().commands().size());
      mutant.put("shrinkTrials", item.shrunk().trials());
      mutant.put("oneMinimal", item.shrunk().oneMinimal());
      mutant.put("replayed", true);
    }

    ObjectNode architecture = artifact(report, "architecture", "architecture.json");
    architecture.put("coreSourceFiles", artifacts.architecture().coreSourceFiles());
    architecture.put("referenceSourceFiles", artifacts.architecture().referenceSourceFiles());
    architecture.put("violations", artifacts.architecture().violations().size());
    ObjectNode release = report.putObject("releaseTarget");
    release.put("unitTag", "course/m04-complete");
    release.putNull("productRelease");
    release.put("verification", "M04_EVIDENCE_ONLY");
    ArrayNode outputs = report.putArray("artifacts");
    CHECK_ARTIFACTS.forEach(outputs::add);
    return report;
  }

  private static ObjectNode report(String schemaVersion) {
    ObjectNode report = JsonSupport.MAPPER.createObjectNode();
    report.put("schemaVersion", schemaVersion);
    report.put("status", PASS);
    return report;
  }

  private static ObjectNode artifact(ObjectNode report, String field, String name) {
    ObjectNode node = report.putObject(field);
    node.put("status", PASS);
    node.put("artifact", name);
    return node;
  }

  private static void write(Path reports, String name, ObjectNode document) {
    AtomicFiles.write(reports.resolve(name), JsonSupport.prettyBytes(document));
  }

  private static void writeFailure(Path root, Path reports, String status, String message) {
    ObjectNode report = base(status);
    report.putObject("failure").put("message", message == null ? "unspecified failure" : message);
    writeCheck(root, reports, report);
  }

  private static ObjectNode base(String status) {
    ObjectNode report = JsonSupport.MAPPER.createObjectNode();
    report.put("schemaVersion", SCHEMA_VERSION);
    report.put("unit", "M04");
    report.put("status", status);
    return report;
  }

  private static void writeCheck(Path root, Path reports, ObjectNode report) {
    JsonSupport.validate(report, readString(root.resolve(CHECK_SCHEMA_PATH)), false);
    AtomicFiles.write(reports.resolve("check.json"), JsonSupport.prettyBytes(report));
  }

  private static int countLines(byte[] bytes) {
    int result = 0;
    for (byte value : bytes) {
      if (value == '\n') {
        result++;
      }
    }
    return result;
  }

  private static String readString(Path path) {
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException("cannot read " + path, exception);
    }
  }

  private static void clear(Path reports) {
    try {
      Files.createDirectories(reports);
      for (String name : OUTPUTS) {
        Files.deleteIfExists(reports.resolve(name));
      }
    } catch (IOException exception) {
      throw new IllegalStateException("cannot clear stale M04 reports", exception);
    }
  }

  private static String stableMessage(RuntimeException failure, Path root) {
    String message = failure.getMessage();
    if (message == null || message.isBlank()) {
      return failure.getClass().getSimpleName();
    }
    return message.replace('\\', '/').replace(root.toString().replace('\\', '/'), "<repository>");
  }

  private static void systemRequire(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }

  private static void studentRequire(boolean condition, String message) {
    if (!condition) {
      throw new StudentFailure(message);
    }
  }

  public record Result(String status, Path reportPath) {}

  private record PropertyMetrics(
      int histories, int commands, int differentialComparisons, int ledgerChecks, int bookChecks) {}

  private record PassArtifacts(
      M04FrozenInputs.Result frozen,
      M04BoundaryFacts.Result boundaries,
      M04FixedScenarioRunner.Result fixed,
      M04GeneratorProfile profile,
      M04CommandCanonicalizer.CanonicalCommands commands,
      PropertyMetrics metrics,
      M04GeneratedCoverage.Result coverage,
      M04CounterexampleSuite.Result counterexamples,
      M04LegacyRegression.Result legacy,
      M04ArchitectureGate.Report architecture) {}

  private static final class StudentFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private StudentFailure(String message) {
      super(message);
    }
  }
}
