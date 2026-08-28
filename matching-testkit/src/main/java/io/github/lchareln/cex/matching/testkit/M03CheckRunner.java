package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.reference.LinearReferenceModel;
import io.github.lchareln.cex.matching.reference.ReferenceCommand;
import io.github.lchareln.cex.matching.reference.ReferenceMatcher;
import io.github.lchareln.cex.matching.reference.SemanticOutcome;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Deterministic, fail-closed completion judge for the frozen M03 contract. */
public final class M03CheckRunner {
  public static final String SCHEMA_VERSION = "matching.m03.check.v2";
  public static final String PASS = M03PropertyJudge.PASS;
  public static final String STUDENT_FAILURE = M03PropertyJudge.STUDENT_FAILURE;
  public static final String SYSTEM_ERROR = M03PropertyJudge.SYSTEM_ERROR;

  static final String CHECK_SCHEMA_PATH = "schemas/matching.m03.check.v2.schema.json";
  static final String COUNTEREXAMPLE_SCHEMA_PATH =
      "schemas/matching.m03.counterexamples.v1.schema.json";
  static final String EXPECTED_COMMAND_DIGEST =
      "sha256:1920d6b8a480998825c72636d446854d9e795e91b0ab29520f203b12186979ce";
  static final int EXPECTED_COMMAND_BYTES = 1_682_592;
  static final int EXPECTED_COMMAND_LINES = 16_641;

  static final List<String> OUTPUTS =
      List.of(
          "m00-m02-regression.json",
          "reference-model.json",
          "generated-properties.json",
          "invariants.json",
          "counterexamples.json",
          "counterexamples.canonical.utf8",
          "replay.json",
          "mutants.json",
          "architecture.json",
          "counterexamples-v1.json",
          "check.json");
  static final List<String> CHECK_ARTIFACTS =
      List.of(
          "m00-m02-regression.json",
          "reference-model.json",
          "generated-properties.json",
          "invariants.json",
          "counterexamples.json",
          "counterexamples.canonical.utf8",
          "replay.json",
          "mutants.json",
          "architecture.json");

  private final M03Candidate.Factory production;
  private final List<RequiredMutant> requiredMutants;
  private final M03Candidate.Factory systemErrorControl;

  public M03CheckRunner() {
    this(
        M03ProductionCandidate::new,
        List.of(
            new RequiredMutant(
                M03Mutants.BEST_PRICE_LAST_ID,
                "best-price-last",
                M03Mutants.bestPriceLast(M03ProductionCandidate::new),
                new M03Shrinker.Fingerprint("PRICE_TIME_PRIORITY", "WRONG_MAKER_ORDER")),
            new RequiredMutant(
                M03Mutants.SAME_PRICE_LIFO_ID,
                "same-price-lifo",
                M03Mutants.samePriceLifo(M03ProductionCandidate::new),
                new M03Shrinker.Fingerprint("PRICE_TIME_PRIORITY", "WRONG_MAKER_ORDER")),
            new RequiredMutant(
                M03Mutants.TAKER_PRICE_ID,
                "taker-price-trade",
                M03Mutants.takerPrice(M03ProductionCandidate::new),
                new M03Shrinker.Fingerprint("MAKER_PRICE", "TRADE_PRICE")),
            new RequiredMutant(
                M03Mutants.QUANTITY_OVERFLOW_ID,
                "trade-quantity-overflow",
                M03Mutants.tradeQuantityOverflow(M03ProductionCandidate::new),
                new M03Shrinker.Fingerprint("QUANTITY_PARTITION", "TRADE_EXCEEDS_REMAINDER")),
            new RequiredMutant(
                M03Mutants.CANCEL_GHOST_ID,
                "cancel-ghost-book",
                M03Mutants.cancelGhostBook(M03ProductionCandidate::new),
                new M03Shrinker.Fingerprint("BOOK_LIFECYCLE_BIJECTION", "ACTIVE_ID_SET")),
            new RequiredMutant(
                M03Mutants.CANCELED_REUSE_ID,
                "canceled-id-reuse",
                M03Mutants.canceledIdentityReuse(M03ProductionCandidate::new),
                new M03Shrinker.Fingerprint(
                    "LIFECYCLE_IRREVERSIBILITY", "TERMINAL_OR_ACTIVE_ID_REUSED"))),
        M03Mutants.throwingControl());
  }

  M03CheckRunner(
      M03Candidate.Factory production,
      List<RequiredMutant> requiredMutants,
      M03Candidate.Factory systemErrorControl) {
    this.production = Objects.requireNonNull(production, "production");
    this.requiredMutants = List.copyOf(requiredMutants);
    this.systemErrorControl = Objects.requireNonNull(systemErrorControl, "systemErrorControl");
  }

  public Result run(Path repositoryRoot, Path reportDirectory) {
    return run(repositoryRoot, reportDirectory, repositoryRoot);
  }

  Result run(Path repositoryRoot, Path reportDirectory, Path trustedOutputRoot) {
    Path root = repositoryRoot.toAbsolutePath().normalize();
    Path reports = SafeOutputPaths.resolveTrustedOutput(trustedOutputRoot, reportDirectory);
    clearOutputs(reports);
    try {
      PassArtifacts artifacts = execute(root, reports);
      writePassReports(root, reports, artifacts);
      return new Result(PASS, reports.resolve("check.json"));
    } catch (StudentFailure exception) {
      clearOutputs(reports);
      writeFailureReport(root, reports, STUDENT_FAILURE, exception.getMessage());
      return new Result(STUDENT_FAILURE, reports.resolve("check.json"));
    } catch (RuntimeException exception) {
      clearOutputs(reports);
      writeFailureReport(root, reports, SYSTEM_ERROR, stableSystemMessage(exception, root));
      return new Result(SYSTEM_ERROR, reports.resolve("check.json"));
    }
  }

  private PassArtifacts execute(Path root, Path reports) {
    byte[] profileBytes = readBytes(root.resolve(M03StartCheckRunner.GENERATOR_PATH));
    String profileSha256 = Hashing.sha256Hex(profileBytes);
    require(
        M03StartCheckRunner.FROZEN_GENERATOR_SHA256.equals(profileSha256),
        "M03 frozen generator profile SHA-256 changed");
    String profileSchema = readString(root.resolve(M03StartCheckRunner.GENERATOR_SCHEMA_PATH));
    M03GeneratorProfile profile =
        M03GeneratorProfile.load(
            root.resolve(M03StartCheckRunner.GENERATOR_PATH),
            root.resolve(M03StartCheckRunner.GENERATOR_SCHEMA_PATH));
    int schemaProbes =
        verifyGeneratorSchemaBoundary(JsonSupport.parse(profileBytes), profileSchema);

    M03HistoryGenerator generator = new M03HistoryGenerator();
    List<M03GeneratedHistory> histories = generator.generate(profile);
    List<M03GeneratedHistory> regenerated = generator.generate(profile);
    M03CommandCanonicalizer canonicalizer = new M03CommandCanonicalizer();
    M03CommandCanonicalizer.CanonicalCommands generated =
        canonicalizer.canonicalize(profile, histories);
    M03CommandCanonicalizer.CanonicalCommands regeneratedCanonical =
        canonicalizer.canonicalize(profile, regenerated);
    require(
        Arrays.equals(generated.bytes(), regeneratedCanonical.bytes()),
        "two fresh M03 generations produced different command bytes");
    require(
        generated.digest().equals(regeneratedCanonical.digest()),
        "two fresh M03 generations produced different command digests");
    require(
        EXPECTED_COMMAND_DIGEST.equals(generated.digest()), "M03 generated command digest changed");
    require(generated.commandCount() == 16_384, "M03 generated command count changed");
    require(
        generated.bytes().length == EXPECTED_COMMAND_BYTES, "M03 generated command bytes changed");
    require(
        countLines(generated.bytes()) == EXPECTED_COMMAND_LINES, "M03 command line count changed");

    PropertyMetrics propertyMetrics = verifyProduction(histories);
    JsonNode m02Regression = runInheritedM02(root, reports);
    M03ArchitectureGate.Report architecture = new M03ArchitectureGate().verify(root);
    require(
        architecture.passed(), "M03 architecture boundary failed: " + architecture.violations());

    List<Counterexample> counterexamples = new ArrayList<>();
    M03Shrinker shrinker = new M03Shrinker();
    for (RequiredMutant mutant : requiredMutants) {
      FailingHistory failure = findFirstFailure(histories, mutant);
      M03Shrinker.Result shrunk =
          shrinker.shrink(
              "mutant-" + mutant.scenarioId(),
              failure.history().seedHex(),
              failure.history().commands(),
              mutant.factory(),
              mutant.fingerprint());
      require(shrunk.oneMinimal(), "counterexample is not one-minimal: " + mutant.id());
      require(
          !shrunk.commands().isEmpty(),
          "counterexample shrank to an empty history: " + mutant.id());
      require(
          shrunk.commands().size() < failure.history().commands().size(),
          "counterexample did not become strictly shorter: " + mutant.id());
      require(
          STUDENT_FAILURE.equals(shrunk.observation().classification()),
          "shrunk counterexample is not STUDENT_FAILURE: " + mutant.id());
      require(
          mutant.fingerprint().value().equals(shrunk.observation().failure().fingerprint()),
          "shrunk counterexample fingerprint changed: " + mutant.id());
      counterexamples.add(new Counterexample(mutant, failure.history(), shrunk));
    }

    M03PropertyJudge.Observation throwing =
        new M03PropertyJudge().judge(histories.getFirst(), systemErrorControl);
    if (!SYSTEM_ERROR.equals(throwing.classification())) {
      throw new IllegalStateException("throwing control was not classified as SYSTEM_ERROR");
    }

    ObjectNode persisted = persistedCounterexamples(profileSha256, counterexamples);
    JsonSupport.validate(persisted, readString(root.resolve(COUNTEREXAMPLE_SCHEMA_PATH)), false);
    byte[] persistedBytes = JsonSupport.prettyBytes(persisted);
    AtomicFiles.write(reports.resolve("counterexamples-v1.json"), persistedBytes);
    JsonNode reparsed = JsonSupport.parse(readBytes(reports.resolve("counterexamples-v1.json")));
    JsonSupport.validate(reparsed, readString(root.resolve(COUNTEREXAMPLE_SCHEMA_PATH)), false);

    M03CounterexampleCanonicalizer.CanonicalCounterexamples canonicalCounterexamples =
        new M03CounterexampleCanonicalizer().canonicalize(reparsed);
    Map<String, M03Candidate.Factory> replayFactories = new LinkedHashMap<>();
    for (RequiredMutant mutant : requiredMutants) {
      replayFactories.put(mutant.id(), mutant.factory());
    }
    M03CounterexampleReplay.ReplayReport replay =
        new M03CounterexampleReplay().replay(reparsed, replayFactories, profile);
    require(replay.allPassed(), "persisted M03 counterexample replay failed");
    require(replay.scenarios().size() == requiredMutants.size(), "M03 replay count changed");

    return new PassArtifacts(
        profileSha256,
        schemaProbes,
        generated,
        propertyMetrics,
        m02Regression,
        architecture,
        counterexamples,
        reparsed,
        canonicalCounterexamples,
        replay,
        throwing);
  }

  private PropertyMetrics verifyProduction(List<M03GeneratedHistory> histories) {
    M03PropertyJudge judge = new M03PropertyJudge();
    int commands = 0;
    int comparisons = 0;
    int ledgerChecks = 0;
    int bookChecks = 0;
    for (M03GeneratedHistory history : histories) {
      M03PropertyJudge.Observation observation = judge.judge(history, production);
      if (SYSTEM_ERROR.equals(observation.classification())) {
        throw new IllegalStateException(
            "production candidate raised SYSTEM_ERROR for history " + history.historyIndex());
      }
      require(
          PASS.equals(observation.classification()),
          "production candidate failed history "
              + history.historyIndex()
              + ": "
              + observation.message());
      commands += observation.completedCommands();
      comparisons += observation.differentialComparisons();
      ledgerChecks += observation.ledgerChecks();
      bookChecks += observation.bookChecks();
    }
    require(histories.size() == 256, "M03 generated history count changed");
    require(commands == 16_384, "M03 production command boundary count changed");
    require(comparisons == commands, "M03 differential comparison count changed");
    require(ledgerChecks == commands, "M03 ledger check count changed");
    require(bookChecks == commands, "M03 book check count changed");
    return new PropertyMetrics(histories.size(), commands, comparisons, ledgerChecks, bookChecks);
  }

  private static FailingHistory findFirstFailure(
      List<M03GeneratedHistory> histories, RequiredMutant mutant) {
    M03PropertyJudge judge = new M03PropertyJudge();
    for (M03GeneratedHistory history : histories) {
      M03PropertyJudge.Observation observation = judge.judge(history, mutant.factory());
      if (SYSTEM_ERROR.equals(observation.classification())) {
        throw new IllegalStateException("required mutant raised SYSTEM_ERROR: " + mutant.id());
      }
      if (STUDENT_FAILURE.equals(observation.classification())
          && observation.failure() != null
          && mutant.fingerprint().value().equals(observation.failure().fingerprint())) {
        return new FailingHistory(history, observation);
      }
    }
    throw new StudentFailure("required semantic mutant survived: " + mutant.id());
  }

  private static ObjectNode persistedCounterexamples(
      String profileSha256, List<Counterexample> counterexamples) {
    ObjectNode root = JsonSupport.MAPPER.createObjectNode();
    root.put("schemaVersion", "matching.m03.counterexamples.v1");
    root.put("profileSha256", profileSha256);
    root.put("generatorAlgorithm", "splitmix64-v1");
    root.put("seedDerivation", M03CommandCanonicalizer.SEED_DERIVATION);
    root.put("modelVersion", "linear-scan-reference-v1");
    ArrayNode scenarios = root.putArray("scenarios");
    for (Counterexample counterexample : counterexamples) {
      scenarios.add(counterexampleJson(counterexample));
    }
    return root;
  }

  private static ObjectNode counterexampleJson(Counterexample counterexample) {
    RequiredMutant mutant = counterexample.mutant();
    M03GeneratedHistory original = counterexample.original();
    M03Shrinker.Result shrunk = counterexample.shrunk();
    M03PropertyJudge.Failure failure = shrunk.observation().failure();
    ObjectNode node = JsonSupport.MAPPER.createObjectNode();
    node.put("scenarioId", mutant.scenarioId());
    node.put("mutantId", mutant.id());
    node.put("classification", shrunk.observation().classification());
    node.put("propertyId", failure.propertyId());
    node.put("divergenceKind", failure.divergenceKind());
    node.put("historyIndex", original.historyIndex());
    node.put("lane", original.laneId());
    node.put("seed", original.seedHex());
    node.put("originalCommandCount", original.commands().size());
    node.put("minimizedCommandCount", shrunk.commands().size());
    node.put("firstFailingCommandIndex", failure.commandIndex());
    node.put("oneMinimal", shrunk.oneMinimal());
    node.put("shrinkTrials", shrunk.trials());
    node.set("originalCommands", M03Json.commands(original.commands()));

    ReferenceMatcher reference = new LinearReferenceModel();
    ArrayNode commands = node.putArray("commands");
    for (int index = 0; index < shrunk.commands().size(); index++) {
      ReferenceCommand command = shrunk.commands().get(index);
      SemanticOutcome expected = reference.apply(command);
      commands.add(M03Json.replayCommand("command-" + (index + 1), command, expected));
    }
    node.set("actualAtFailure", M03Json.outcome(failure.actual()));
    return node;
  }

  private static JsonNode runInheritedM02(Path root, Path reports) {
    Path inheritedDirectory = reports.resolve(".inherited-m02");
    try {
      M02CheckRunner.Result result = new M02CheckRunner().run(root, inheritedDirectory, reports);
      require(M02CheckRunner.PASS.equals(result.status()), "inherited M02 check is not PASS");
      JsonNode report = JsonSupport.parse(readBytes(result.reportPath()));
      JsonSupport.validate(
          report, readString(root.resolve("schemas/matching.m02.check.v2.schema.json")), false);
      require(
          "matching.m02.check.v2".equals(report.path("schemaVersion").stringValue()),
          "inherited M02 schema changed");
      require("PASS".equals(report.path("status").stringValue()), "inherited M02 status changed");
      require(
          report.path("scenarioCorpus").path("scenarios").intValue() == 10,
          "inherited M02 scenario count changed");
      require(
          report.path("scenarioCorpus").path("commands").intValue() == 34,
          "inherited M02 command count changed");
      require(
          "sha256:32054d63accba99b19db823c41f74bda73dc3b8a009b528f2834d2bc70839d16"
              .equals(report.path("canonical").path("digest").stringValue()),
          "inherited M02 canonical digest changed");
      return report.deepCopy();
    } finally {
      deleteTree(inheritedDirectory);
    }
  }

  private static int verifyGeneratorSchemaBoundary(JsonNode valid, String schema) {
    List<JsonNode> invalid = new ArrayList<>();
    ObjectNode missingLanes = (ObjectNode) valid.deepCopy();
    missingLanes.remove("lanes");
    invalid.add(missingLanes);
    ObjectNode extraRoot = (ObjectNode) valid.deepCopy();
    extraRoot.put("clock", "forbidden");
    invalid.add(extraRoot);
    ObjectNode wrongAlgorithm = (ObjectNode) valid.deepCopy();
    wrongAlgorithm.put("algorithm", "java-random");
    invalid.add(wrongAlgorithm);
    ObjectNode tooManyLanes = (ObjectNode) valid.deepCopy();
    ((ArrayNode) tooManyLanes.path("lanes")).add(valid.path("lanes").get(0).deepCopy());
    invalid.add(tooManyLanes);
    ObjectNode badCancel = (ObjectNode) valid.deepCopy();
    ((ObjectNode) badCancel.path("lanes").get(3).path("prefix").get(1).path("input"))
        .put("side", "BUY");
    invalid.add(badCancel);
    ObjectNode badSideSet = (ObjectNode) valid.deepCopy();
    ((ArrayNode) badSideSet.path("randomDomain").path("validSides")).remove(1);
    invalid.add(badSideSet);
    int rejected = 0;
    for (JsonNode probe : invalid) {
      try {
        JsonSupport.validate(probe, schema, false);
      } catch (FixtureSchemaException expected) {
        rejected++;
      }
    }
    require(rejected == 6, "M03 generator schema accepted a negative probe");
    return rejected;
  }

  private static void writePassReports(Path root, Path reports, PassArtifacts artifacts) {
    AtomicFiles.write(
        reports.resolve("m00-m02-regression.json"),
        JsonSupport.prettyBytes(regressionReport(artifacts.m02Regression())));
    AtomicFiles.write(
        reports.resolve("reference-model.json"),
        JsonSupport.prettyBytes(referenceReport(artifacts.architecture())));
    AtomicFiles.write(
        reports.resolve("generated-properties.json"),
        JsonSupport.prettyBytes(generatedPropertiesReport(artifacts)));
    AtomicFiles.write(
        reports.resolve("invariants.json"),
        JsonSupport.prettyBytes(invariantsReport(artifacts.propertyMetrics())));
    AtomicFiles.write(
        reports.resolve("counterexamples.json"),
        JsonSupport.prettyBytes(
            counterexampleReport(artifacts.persisted(), artifacts.counterexamples())));
    AtomicFiles.write(
        reports.resolve("counterexamples.canonical.utf8"),
        artifacts.canonicalCounterexamples().bytes());
    AtomicFiles.write(
        reports.resolve("replay.json"), JsonSupport.prettyBytes(replayReport(artifacts.replay())));
    AtomicFiles.write(
        reports.resolve("mutants.json"),
        JsonSupport.prettyBytes(mutantReport(artifacts.counterexamples(), artifacts.throwing())));
    AtomicFiles.write(
        reports.resolve("architecture.json"),
        JsonSupport.prettyBytes(architectureReport(artifacts.architecture())));
    writeAndValidateCheck(root, reports, passReport(artifacts));
  }

  private static ObjectNode regressionReport(JsonNode inherited) {
    ObjectNode report = JsonSupport.MAPPER.createObjectNode();
    report.put("schemaVersion", "matching.m03.regression-report.v1");
    report.put("status", PASS);
    report.put("m00Status", inherited.path("m01Regression").path("m00Status").stringValue());
    report.put("m01Status", inherited.path("m01Regression").path("status").stringValue());
    report.put("m02Status", inherited.path("status").stringValue());
    report.put("m02Scenarios", inherited.path("scenarioCorpus").path("scenarios").intValue());
    report.put("m02Commands", inherited.path("scenarioCorpus").path("commands").intValue());
    report.put("m02Digest", inherited.path("canonical").path("digest").stringValue());
    return report;
  }

  private static ObjectNode referenceReport(M03ArchitectureGate.Report architecture) {
    ObjectNode report = JsonSupport.MAPPER.createObjectNode();
    report.put("schemaVersion", "matching.m03.reference-report.v1");
    report.put("status", PASS);
    report.put("module", "matching-reference");
    report.put("representation", "FLAT_LIST_LINEAR_SCAN");
    report.put("productionImports", 0);
    report.put("coreDependency", false);
    report.put("testkitDependency", false);
    report.put("sourceFiles", architecture.referenceSourceFiles());
    return report;
  }

  private static ObjectNode generatedPropertiesReport(PassArtifacts artifacts) {
    PropertyMetrics metrics = artifacts.propertyMetrics();
    ObjectNode report = JsonSupport.MAPPER.createObjectNode();
    report.put("schemaVersion", "matching.m03.generated-properties-report.v1");
    report.put("status", PASS);
    report.put("histories", metrics.histories());
    report.put("commands", metrics.commands());
    report.put("differentialComparisons", metrics.differentialComparisons());
    report.put("commandDigest", artifacts.generated().digest());
    report.put("commandBytes", artifacts.generated().bytes().length);
    report.put("commandLines", countLines(artifacts.generated().bytes()));
    ObjectNode lanes = report.putObject("lanes");
    lanes.put("BEST_PRICE", 64);
    lanes.put("SAME_PRICE_FIFO", 64);
    lanes.put("MAKER_PRICE", 64);
    lanes.put("CANCELED_IDENTITY", 64);
    return report;
  }

  private static ObjectNode invariantsReport(PropertyMetrics metrics) {
    ObjectNode report = JsonSupport.MAPPER.createObjectNode();
    report.put("schemaVersion", "matching.m03.invariants-report.v1");
    report.put("status", PASS);
    report.put("commands", metrics.commands());
    report.put("eventLedgerChecks", metrics.ledgerChecks());
    report.put("bookLifecycleChecks", metrics.bookChecks());
    ArrayNode properties = report.putArray("properties");
    properties.add("EVENT_GRAMMAR");
    properties.add("VALIDATION_PRIORITY_AND_NO_MUTATION");
    properties.add("ACCEPTANCE_SEQUENCE_CONTIGUITY");
    properties.add("PRICE_TIME_PRIORITY");
    properties.add("MAKER_PRICE");
    properties.add("QUANTITY_PARTITION");
    properties.add("BOOK_ORDER_FIFO_AND_NON_CROSSED");
    properties.add("BOOK_LIFECYCLE_BIJECTION");
    properties.add("LIFECYCLE_IRREVERSIBILITY");
    properties.add("EXACT_BATCH_DIFFERENTIAL");
    return report;
  }

  private static ObjectNode counterexampleReport(
      JsonNode persisted, List<Counterexample> counterexamples) {
    ObjectNode report = JsonSupport.MAPPER.createObjectNode();
    report.put("schemaVersion", "matching.m03.counterexample-report.v1");
    report.put("status", PASS);
    report.put("required", counterexamples.size());
    report.put("originalCommands", counterexamples.size() * 64);
    report.put(
        "minimizedCommands",
        counterexamples.stream().mapToInt(item -> item.shrunk().commands().size()).sum());
    ArrayNode scenarios = report.putArray("scenarios");
    for (JsonNode source : persisted.path("scenarios")) {
      ObjectNode scenario = scenarios.addObject();
      scenario.put("scenarioId", source.path("scenarioId").stringValue());
      ArrayNode cases = scenario.putArray("cases");
      for (JsonNode command : source.path("commands")) {
        ObjectNode replayCase = cases.addObject();
        replayCase.put("caseId", command.path("caseId").stringValue());
        replayCase.put("type", command.path("type").stringValue());
        replayCase.set("input", command.path("input").deepCopy());
        replayCase.set("events", command.path("expected").path("events").deepCopy());
        replayCase.set("bookAfter", command.path("expected").path("bookAfter").deepCopy());
      }
    }
    return report;
  }

  private static ObjectNode replayReport(M03CounterexampleReplay.ReplayReport replay) {
    ObjectNode report = JsonSupport.MAPPER.createObjectNode();
    report.put("schemaVersion", "matching.m03.replay-report.v1");
    report.put("status", replay.allPassed() ? PASS : STUDENT_FAILURE);
    report.put("requested", replay.scenarios().size());
    report.put(
        "completed",
        replay.scenarios().stream().filter(M03CounterexampleReplay.ScenarioReplay::passed).count());
    ArrayNode scenarios = report.putArray("scenarios");
    for (M03CounterexampleReplay.ScenarioReplay replayed : replay.scenarios()) {
      ObjectNode item = scenarios.addObject();
      item.put("scenarioId", replayed.scenarioId());
      item.put("mutantId", replayed.mutantId());
      item.put("commands", replayed.commandCount());
      item.put("expectedFingerprint", replayed.expectedFingerprint());
      item.put("actualFingerprint", replayed.actualFingerprint());
      item.put("classification", replayed.classification());
      item.put("referenceOutcomesExact", replayed.referenceOutcomesExact());
      item.put("actualOutcomeExact", replayed.actualOutcomeExact());
      item.put("provenanceExact", replayed.provenanceExact());
      item.put("oneMinimalReverified", replayed.oneMinimalReverified());
      item.put("passed", replayed.passed());
    }
    return report;
  }

  private static ObjectNode mutantReport(
      List<Counterexample> counterexamples, M03PropertyJudge.Observation throwing) {
    ObjectNode report = JsonSupport.MAPPER.createObjectNode();
    report.put("schemaVersion", "matching.m03.mutant-report.v1");
    report.put("status", PASS);
    report.put("required", counterexamples.size());
    report.put("killed", counterexamples.size());
    report.put("systemErrorControl", throwing.classification());
    ArrayNode mutants = report.putArray("mutants");
    for (Counterexample counterexample : counterexamples) {
      M03PropertyJudge.Failure failure = counterexample.shrunk().observation().failure();
      ObjectNode item = mutants.addObject();
      item.put("id", counterexample.mutant().id());
      item.put("classification", counterexample.shrunk().observation().classification());
      item.put("killed", true);
      item.put("propertyId", failure.propertyId());
      item.put("divergenceKind", failure.divergenceKind());
      item.put("historyIndex", counterexample.original().historyIndex());
      item.put("seed", counterexample.original().seedHex());
      item.put("originalCommands", counterexample.original().commands().size());
      item.put("minimizedCommands", counterexample.shrunk().commands().size());
      item.put("shrinkTrials", counterexample.shrunk().trials());
      item.put("oneMinimal", counterexample.shrunk().oneMinimal());
      item.put("replayed", true);
    }
    return report;
  }

  private static ObjectNode architectureReport(M03ArchitectureGate.Report architecture) {
    ObjectNode report = JsonSupport.MAPPER.createObjectNode();
    report.put("schemaVersion", "matching.m03.architecture-report.v1");
    report.put("status", architecture.passed() ? PASS : STUDENT_FAILURE);
    report.put("coreSourceFiles", architecture.coreSourceFiles());
    report.put("referenceSourceFiles", architecture.referenceSourceFiles());
    ArrayNode violations = report.putArray("violations");
    architecture.violations().forEach(violations::add);
    return report;
  }

  private static ObjectNode passReport(PassArtifacts artifacts) {
    ObjectNode report = baseReport(PASS);
    report.put("contractPlanVersion", "0.5");
    ObjectNode generator = report.putObject("generator");
    generator.put("path", M03StartCheckRunner.GENERATOR_PATH);
    generator.put("schemaPath", M03StartCheckRunner.GENERATOR_SCHEMA_PATH);
    generator.put("sha256", artifacts.profileSha256());
    generator.put("algorithm", "splitmix64-v1");
    generator.put("seedDerivation", M03CommandCanonicalizer.SEED_DERIVATION);
    generator.put("baseSeed", "6824");
    generator.put("histories", 256);
    generator.put("commandsPerHistory", 64);
    generator.put("totalCommands", 16_384);
    generator.put("lanes", 4);
    generator.put("historiesPerLane", 64);
    generator.put("schemaProbes", artifacts.schemaProbes());

    ObjectNode regression = statusArtifact(report, "m02Regression", "m00-m02-regression.json");
    regression.put("checkSchemaVersion", "matching.m02.check.v2");
    regression.put("scenarios", 10);
    regression.put("commands", 34);
    regression.put(
        "digest", "sha256:32054d63accba99b19db823c41f74bda73dc3b8a009b528f2834d2bc70839d16");
    ObjectNode independence = statusArtifact(report, "independence", "reference-model.json");
    independence.put("module", "matching-reference");
    independence.put("coreDependency", false);
    independence.put("testkitDependency", false);
    independence.put("representation", "FLAT_LIST_LINEAR_SCAN");
    independence.put("sourceFiles", artifacts.architecture().referenceSourceFiles());
    PropertyMetrics metrics = artifacts.propertyMetrics();
    ObjectNode properties = statusArtifact(report, "properties", "generated-properties.json");
    properties.put("histories", metrics.histories());
    properties.put("commands", metrics.commands());
    properties.put("differentialComparisons", metrics.differentialComparisons());
    properties.put("ledgerChecks", metrics.ledgerChecks());
    properties.put("bookChecks", metrics.bookChecks());
    ObjectNode determinism = report.putObject("determinism");
    determinism.put("generations", 2);
    determinism.put("distinctCommandDigests", 1);
    determinism.put("commandDigest", artifacts.generated().digest());
    determinism.put("format", "M03G1");

    int minimized =
        artifacts.counterexamples().stream()
            .mapToInt(item -> item.shrunk().commands().size())
            .sum();
    ObjectNode counterexamples = statusArtifact(report, "counterexamples", "counterexamples.json");
    counterexamples.put("required", 6);
    counterexamples.put("shrunk", 6);
    counterexamples.put("persisted", 6);
    counterexamples.put("replayed", 6);
    counterexamples.put("oneMinimal", 6);
    counterexamples.put("minimizedCommands", minimized);
    ObjectNode canonical = counterexamples.putObject("canonical");
    canonical.put("format", "M03X1");
    canonical.put("digest", artifacts.canonicalCounterexamples().digest());
    canonical.put("lines", countLines(artifacts.canonicalCounterexamples().bytes()));
    canonical.put("bytes", artifacts.canonicalCounterexamples().bytes().length);
    canonical.put("artifact", "counterexamples.canonical.utf8");

    ObjectNode mutants = statusArtifact(report, "mutants", "mutants.json");
    mutants.put("required", 6);
    mutants.put("killed", 6);
    mutants.put("systemErrorControl", artifacts.throwing().classification());
    ArrayNode required = mutants.putArray("requiredMutants");
    for (Counterexample counterexample : artifacts.counterexamples()) {
      ObjectNode mutant = required.addObject();
      mutant.put("id", counterexample.mutant().id());
      mutant.put("classification", counterexample.shrunk().observation().classification());
      mutant.put("killed", true);
      mutant.put("propertyId", counterexample.shrunk().observation().failure().propertyId());
      mutant.put("originalCommands", counterexample.original().commands().size());
      mutant.put("minimizedCommands", counterexample.shrunk().commands().size());
      mutant.put("oneMinimal", counterexample.shrunk().oneMinimal());
      mutant.put("replayed", true);
    }
    ObjectNode architecture = statusArtifact(report, "architecture", "architecture.json");
    architecture.put("coreSourceFiles", artifacts.architecture().coreSourceFiles());
    architecture.put("referenceSourceFiles", artifacts.architecture().referenceSourceFiles());
    architecture.put("violations", artifacts.architecture().violations().size());
    ObjectNode releaseTarget = report.putObject("releaseTarget");
    releaseTarget.put("unitTag", "course/m03-complete");
    releaseTarget.put("productRelease", "matching-0.1.0");
    releaseTarget.put("verification", "M03_EVIDENCE_ONLY");
    ArrayNode outputs = report.putArray("artifacts");
    CHECK_ARTIFACTS.forEach(outputs::add);
    return report;
  }

  private static ObjectNode statusArtifact(ObjectNode report, String field, String artifact) {
    ObjectNode node = report.putObject(field);
    node.put("status", PASS);
    node.put("artifact", artifact);
    return node;
  }

  private static void writeFailureReport(Path root, Path reports, String status, String message) {
    ObjectNode report = baseReport(status);
    report.putObject("failure").put("message", message == null ? "unspecified failure" : message);
    writeAndValidateCheck(root, reports, report);
  }

  private static ObjectNode baseReport(String status) {
    ObjectNode report = JsonSupport.MAPPER.createObjectNode();
    report.put("schemaVersion", SCHEMA_VERSION);
    report.put("unit", "M03");
    report.put("status", status);
    return report;
  }

  private static void writeAndValidateCheck(Path root, Path reports, ObjectNode report) {
    JsonSupport.validate(report, readString(root.resolve(CHECK_SCHEMA_PATH)), false);
    AtomicFiles.write(reports.resolve("check.json"), JsonSupport.prettyBytes(report));
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

  private static void clearOutputs(Path reports) {
    try {
      Files.createDirectories(reports);
      for (String output : OUTPUTS) {
        Files.deleteIfExists(reports.resolve(output));
      }
    } catch (IOException exception) {
      throw new IllegalStateException("cannot clear stale M03 reports", exception);
    }
  }

  private static byte[] readBytes(Path path) {
    try {
      return Files.readAllBytes(path);
    } catch (IOException exception) {
      throw new IllegalStateException("cannot read " + path, exception);
    }
  }

  private static String readString(Path path) {
    return new String(readBytes(path), StandardCharsets.UTF_8);
  }

  private static void deleteTree(Path path) {
    if (!Files.exists(path)) {
      return;
    }
    try (var paths = Files.walk(path)) {
      paths.sorted((left, right) -> right.compareTo(left)).forEach(M03CheckRunner::deleteOne);
    } catch (IOException exception) {
      throw new IllegalStateException("cannot clear inherited M02 reports", exception);
    }
  }

  private static void deleteOne(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException exception) {
      throw new IllegalStateException("cannot delete inherited M02 report", exception);
    }
  }

  private static String stableSystemMessage(RuntimeException exception, Path root) {
    String message = exception.getMessage();
    if (message == null || message.isBlank()) {
      return exception.getClass().getSimpleName();
    }
    String normalizedRoot = root.toString().replace('\\', '/');
    String stable = message.replace('\\', '/').replace(normalizedRoot, "<repository>");
    return exception.getClass().getSimpleName() + ": " + stable;
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new StudentFailure(message);
    }
  }

  public record Result(String status, Path reportPath) {}

  record RequiredMutant(
      String id,
      String scenarioId,
      M03Candidate.Factory factory,
      M03Shrinker.Fingerprint fingerprint) {}

  private record PropertyMetrics(
      int histories, int commands, int differentialComparisons, int ledgerChecks, int bookChecks) {}

  private record FailingHistory(
      M03GeneratedHistory history, M03PropertyJudge.Observation observation) {}

  private record Counterexample(
      RequiredMutant mutant, M03GeneratedHistory original, M03Shrinker.Result shrunk) {}

  private record PassArtifacts(
      String profileSha256,
      int schemaProbes,
      M03CommandCanonicalizer.CanonicalCommands generated,
      PropertyMetrics propertyMetrics,
      JsonNode m02Regression,
      M03ArchitectureGate.Report architecture,
      List<Counterexample> counterexamples,
      JsonNode persisted,
      M03CounterexampleCanonicalizer.CanonicalCounterexamples canonicalCounterexamples,
      M03CounterexampleReplay.ReplayReport replay,
      M03PropertyJudge.Observation throwing) {
    private PassArtifacts {
      counterexamples = List.copyOf(counterexamples);
    }
  }

  private static final class StudentFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private StudentFailure(String message) {
      super(message);
    }
  }
}
