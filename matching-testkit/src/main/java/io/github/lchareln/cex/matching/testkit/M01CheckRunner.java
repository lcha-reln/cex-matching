package io.github.lchareln.cex.matching.testkit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Deterministic, fail-closed completion judge for the frozen M01 contract. */
public final class M01CheckRunner {
  public static final String SCHEMA_VERSION = "matching.m01.check.v2";
  public static final String PASS = M01Assertions.PASS;
  public static final String STUDENT_FAILURE = M01Assertions.STUDENT_FAILURE;
  public static final String SYSTEM_ERROR = M01Assertions.SYSTEM_ERROR;

  static final String FIXTURE_PATH =
      "matching-testkit/src/test/resources/m01/fixtures/price-time-v1.json";
  static final String FIXTURE_SCHEMA_PATH = "schemas/matching.m01.scenario.v1.schema.json";
  static final String CHECK_SCHEMA_PATH = "schemas/matching.m01.check.v2.schema.json";
  static final String GOLDEN_PATH =
      "matching-testkit/src/test/resources/m01/golden/price-time-v1.canonical.txt";
  static final String GOLDEN_DIGEST_PATH =
      "matching-testkit/src/test/resources/m01/golden/price-time-v1.sha256";
  static final String EXPECTED_DIGEST =
      "sha256:74585489c50e81cc3e6a10044263186ce66a7f1b20e1f45015fed68614c3e5a1";
  static final String FROZEN_FIXTURE_SHA256 =
      "d050bc2fc029e3ac0afb5047e3030412412f3a7aecf0938a19a5953618ff9ed7";
  static final int EXPECTED_LINES = 155;
  static final int EXPECTED_BYTES = 14256;

  private static final int REQUIRED_REPLAYS = 100;
  private static final int EXPECTED_SCENARIOS = 8;
  private static final int EXPECTED_CASES = 22;
  private static final List<String> OUTPUTS =
      List.of(
          "m00-regression.json",
          "price-time.json",
          "event-batches.json",
          "invariants.json",
          "canonical-history.utf8",
          "mutants.json",
          "architecture.json",
          "check.json");
  private static final List<String> FROZEN_SCENARIO_IDS =
      List.of(
          "invalid-does-not-consume-sequence",
          "empty-and-noncrossing-rest",
          "exact-touch-maker-price",
          "better-price-before-time",
          "same-price-fifo-three-makers",
          "maker-partially-filled",
          "taker-sweeps-three-levels-and-rests",
          "sell-side-mirror");
  private static final List<String> FROZEN_CASE_IDS =
      List.of(
          "reject-zero-price",
          "first-valid-still-sequence-one",
          "empty-buy-rests",
          "noncrossing-sell-rests",
          "resting-sell",
          "touching-buy-fills",
          "earlier-worse-ask",
          "later-better-ask",
          "buy-takes-better-price-first",
          "fifo-maker-one",
          "fifo-maker-two",
          "fifo-maker-three",
          "fifo-taker",
          "large-resting-maker",
          "small-buy-partial-maker",
          "ask-level-one",
          "ask-level-two",
          "ask-level-three",
          "sweep-and-rest-buy",
          "resting-bid-low",
          "resting-bid-high",
          "sell-takes-high-bid-first-and-rests");

  private final M01Candidate.Factory production;
  private final List<RequiredMutant> requiredMutants;
  private final M01Candidate.Factory systemErrorControl;

  public M01CheckRunner() {
    this(
        M01ProductionCandidate::new,
        List.of(
            new RequiredMutant(
                M01Mutants.LIFO_ID,
                M01Mutants.samePriceLifo(M01ProductionCandidate::new),
                "fifo-taker"),
            new RequiredMutant(
                M01Mutants.TAKER_PRICE_ID,
                M01Mutants.makerUsesTakerPrice(M01ProductionCandidate::new),
                "buy-takes-better-price-first"),
            new RequiredMutant(
                M01Mutants.SKIP_MAKER_ID,
                M01Mutants.skipsFirstMaker(M01ProductionCandidate::new),
                "buy-takes-better-price-first")),
        M01Mutants.throwingControl());
  }

  M01CheckRunner(
      M01Candidate.Factory production,
      List<RequiredMutant> requiredMutants,
      M01Candidate.Factory systemErrorControl) {
    this.production = production;
    this.requiredMutants = List.copyOf(requiredMutants);
    this.systemErrorControl = systemErrorControl;
  }

  public Result run(Path repositoryRoot, Path reportDirectory) {
    return run(repositoryRoot, reportDirectory, repositoryRoot);
  }

  Result run(Path repositoryRoot, Path reportDirectory, Path trustedOutputRoot) {
    Path root = repositoryRoot.toAbsolutePath().normalize();
    Path reports = SafeOutputPaths.resolveTrustedOutput(trustedOutputRoot, reportDirectory);
    clearOutputs(reports);
    try {
      PassArtifacts artifacts = execute(root);
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

  private PassArtifacts execute(Path root) {
    byte[] fixtureBytes = readBytes(root.resolve(FIXTURE_PATH));
    String fixtureSha256 = Hashing.sha256Hex(fixtureBytes);
    if (!FROZEN_FIXTURE_SHA256.equals(fixtureSha256)) {
      throw new IllegalStateException("M01 frozen scenario corpus SHA-256 changed");
    }
    String fixtureSchema = readString(root.resolve(FIXTURE_SCHEMA_PATH));
    M01ScenarioLoader loader = new M01ScenarioLoader();
    M01ScenarioPack pack = loader.load(fixtureBytes, fixtureSchema);
    assertFrozenShape(pack);
    int schemaProbes = verifyScenarioBoundary(loader, fixtureBytes, fixtureSchema);

    M01Assertions assertions = new M01Assertions();
    M01Assertions.Observation productionObservation = assertions.judge(pack, production);
    rejectSystemError(productionObservation, "production candidate");
    require(
        PASS.equals(productionObservation.classification()),
        "production candidate failed: " + productionObservation.message());
    if (productionObservation.history() == null) {
      throw new IllegalStateException("passing production candidate has no execution history");
    }

    M01Canonicalizer canonicalizer = new M01Canonicalizer();
    M01CanonicalHistory canonical = canonicalizer.canonicalize(productionObservation.history());
    verifyGolden(root, canonical);

    Set<String> replayDigests = new LinkedHashSet<>();
    int completedReplays = 0;
    for (int replay = 0; replay < REQUIRED_REPLAYS; replay++) {
      M01ScenarioPack freshPack = loader.load(fixtureBytes, fixtureSchema);
      M01Assertions.Observation freshObservation = assertions.judge(freshPack, production);
      rejectSystemError(freshObservation, "fresh replay " + replay);
      require(
          PASS.equals(freshObservation.classification()),
          "fresh replay " + replay + " failed: " + freshObservation.message());
      M01CanonicalHistory fresh = canonicalizer.canonicalize(freshObservation.history());
      require(
          Arrays.equals(canonical.bytes(), fresh.bytes()),
          "fresh replay bytes changed at run " + replay);
      require(canonical.digest().equals(fresh.digest()), "fresh replay digest changed");
      replayDigests.add(fresh.digest());
      completedReplays++;
    }
    require(replayDigests.size() == 1, "fresh replay produced multiple semantic digests");

    List<M01ReportJson.MutantObservation> mutantObservations = new ArrayList<>();
    for (RequiredMutant mutant : requiredMutants) {
      M01Assertions.Observation observation = assertions.judge(pack, mutant.factory());
      rejectSystemError(observation, "required mutant " + mutant.id());
      require(
          STUDENT_FAILURE.equals(observation.classification()),
          "required mutant survived: " + mutant.id());
      require(
          mutant.expectedCaseId().equals(observation.caseId()),
          "required mutant failed at an unexpected case: " + mutant.id());
      mutantObservations.add(new M01ReportJson.MutantObservation(mutant.id(), observation));
    }
    M01Assertions.Observation throwingControl = assertions.judge(pack, systemErrorControl);
    if (!SYSTEM_ERROR.equals(throwingControl.classification())) {
      throw new IllegalStateException("throwing control was not classified as SYSTEM_ERROR");
    }

    M01ArchitectureGate.Report architecture = new M01ArchitectureGate().verify(root);
    require(
        architecture.passed(), "M01 architecture boundary failed: " + architecture.violations());

    M01M00Regression.Result m00Regression = new M01M00Regression().verify(root, production);
    require(m00Regression.passed(), m00Regression.message());

    return new PassArtifacts(
        fixtureSha256,
        schemaProbes,
        productionObservation,
        canonical,
        completedReplays,
        replayDigests.size(),
        mutantObservations,
        throwingControl,
        architecture,
        m00Regression);
  }

  private static void writePassReports(Path root, Path reports, PassArtifacts artifacts) {
    AtomicFiles.write(
        reports.resolve("m00-regression.json"),
        JsonSupport.prettyBytes(M01ReportJson.m00Regression(artifacts.m00Regression())));
    AtomicFiles.write(
        reports.resolve("price-time.json"),
        JsonSupport.prettyBytes(
            M01ReportJson.priceTime(artifacts.fixtureSha256(), artifacts.production().history())));
    AtomicFiles.write(
        reports.resolve("event-batches.json"),
        JsonSupport.prettyBytes(M01ReportJson.eventBatches(artifacts.production().history())));
    AtomicFiles.write(
        reports.resolve("invariants.json"),
        JsonSupport.prettyBytes(M01ReportJson.invariants(artifacts.production().metrics())));
    AtomicFiles.write(reports.resolve("canonical-history.utf8"), artifacts.canonical().bytes());
    AtomicFiles.write(
        reports.resolve("mutants.json"),
        JsonSupport.prettyBytes(
            M01ReportJson.mutants(
                artifacts.production(), artifacts.mutants(), artifacts.systemErrorControl())));
    AtomicFiles.write(
        reports.resolve("architecture.json"),
        JsonSupport.prettyBytes(M01ReportJson.architecture(artifacts.architecture())));
    ObjectNode check = passReport(artifacts);
    writeAndValidateCheck(root, reports, check);
  }

  private static ObjectNode passReport(PassArtifacts artifacts) {
    ObjectNode report = baseReport(PASS);
    report.put("contractPlanVersion", "0.3");
    ObjectNode corpus = report.putObject("scenarioCorpus");
    corpus.put("path", FIXTURE_PATH);
    corpus.put("schemaPath", FIXTURE_SCHEMA_PATH);
    corpus.put("sha256", artifacts.fixtureSha256());
    corpus.put("scenarios", EXPECTED_SCENARIOS);
    corpus.put("cases", EXPECTED_CASES);
    corpus.put("schemaProbes", artifacts.schemaProbes());

    statusArtifact(report, "m00Regression", "m00-regression.json");
    ObjectNode priceTime = statusArtifact(report, "priceTime", "price-time.json");
    priceTime.put("scenarios", EXPECTED_SCENARIOS);
    priceTime.put("cases", EXPECTED_CASES);
    ObjectNode eventBatches = statusArtifact(report, "eventBatches", "event-batches.json");
    eventBatches.put("cases", EXPECTED_CASES);
    statusArtifact(report, "invariants", "invariants.json");

    ObjectNode canonical = report.putObject("canonical");
    canonical.put("format", "M01H1");
    canonical.put("digest", artifacts.canonical().digest());
    canonical.put("lines", artifacts.canonical().lineCount());
    canonical.put("bytes", artifacts.canonical().bytes().length);
    canonical.put("artifact", "canonical-history.utf8");

    ObjectNode replays = report.putObject("replays");
    replays.put("requested", REQUIRED_REPLAYS);
    replays.put("completed", artifacts.completedReplays());
    replays.put("distinctDigests", artifacts.distinctReplayDigests());

    ArrayNode mutants = report.putArray("requiredMutants");
    for (M01ReportJson.MutantObservation mutant : artifacts.mutants()) {
      ObjectNode node = mutants.addObject();
      node.put("id", mutant.id());
      node.put("classification", mutant.observation().classification());
      node.put("killed", true);
      node.put("scenarioId", mutant.observation().scenarioId());
      node.put("caseId", mutant.observation().caseId());
      node.put("artifact", "mutants.json");
    }
    ObjectNode mutantSummary = statusArtifact(report, "mutants", "mutants.json");
    mutantSummary.put("required", 3);
    mutantSummary.put("killed", 3);
    mutantSummary.put("systemErrorControl", SYSTEM_ERROR);

    ObjectNode architecture = statusArtifact(report, "architecture", "architecture.json");
    architecture.put("sourceFiles", artifacts.architecture().sourceFiles());
    ArrayNode outputArtifacts = report.putArray("artifacts");
    OUTPUTS.stream().filter(name -> !"check.json".equals(name)).forEach(outputArtifacts::add);
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
    ObjectNode failure = report.putObject("failure");
    failure.put("message", message == null ? "unspecified failure" : message);
    writeAndValidateCheck(root, reports, report);
  }

  private static ObjectNode baseReport(String status) {
    ObjectNode report = JsonSupport.MAPPER.createObjectNode();
    report.put("schemaVersion", SCHEMA_VERSION);
    report.put("unit", "M01");
    report.put("status", status);
    return report;
  }

  private static void writeAndValidateCheck(Path root, Path reports, ObjectNode report) {
    JsonSupport.validate(report, readString(root.resolve(CHECK_SCHEMA_PATH)), false);
    AtomicFiles.write(reports.resolve("check.json"), JsonSupport.prettyBytes(report));
  }

  private static void verifyGolden(Path root, M01CanonicalHistory canonical) {
    require(
        Arrays.equals(readBytes(root.resolve(GOLDEN_PATH)), canonical.bytes()),
        "M01 canonical history differs from golden");
    require(
        EXPECTED_DIGEST.equals(readString(root.resolve(GOLDEN_DIGEST_PATH)).strip()),
        "M01 checked-in golden digest changed");
    require(EXPECTED_DIGEST.equals(canonical.digest()), "M01 canonical digest changed");
    require(canonical.lineCount() == EXPECTED_LINES, "M01 canonical line count changed");
    require(canonical.bytes().length == EXPECTED_BYTES, "M01 canonical byte count changed");
    require(
        canonical.bytes()[canonical.bytes().length - 1] == '\n',
        "M01 canonical history lacks final LF");
    require(!startsWithBom(canonical.bytes()), "M01 canonical history contains UTF-8 BOM");
  }

  private static void assertFrozenShape(M01ScenarioPack pack) {
    if (pack.scenarios().size() != EXPECTED_SCENARIOS || pack.caseCount() != EXPECTED_CASES) {
      throw new IllegalStateException("M01 frozen scenario or case count changed");
    }
    List<String> scenarioIds =
        pack.scenarios().stream().map(M01ScenarioPack.Scenario::scenarioId).toList();
    List<String> caseIds =
        pack.scenarios().stream()
            .flatMap(scenario -> scenario.cases().stream())
            .map(M01ScenarioPack.Case::caseId)
            .toList();
    if (!FROZEN_SCENARIO_IDS.equals(scenarioIds) || !FROZEN_CASE_IDS.equals(caseIds)) {
      throw new IllegalStateException("M01 frozen scenario or case identity changed");
    }
  }

  private static int verifyScenarioBoundary(
      M01ScenarioLoader loader, byte[] fixtureBytes, String fixtureSchema) {
    String source = new String(fixtureBytes, StandardCharsets.UTF_8);
    List<String> invalidFixtures =
        List.of(
            replaceOnce(source, "\"priceTicks\": 0,", "\"priceTicks\": 0, \"priceTicks\": 0,"),
            replaceOnce(source, "\"quantityLots\": 2", "\"quantityLots\": 2.0"),
            replaceOnce(
                source, "\"quantityLots\": 2 }", "\"quantityLots\": 2, \"unexpected\": 1 }"),
            replaceOnce(source, "\"side\": \"BUY\", ", ""),
            replaceOnce(
                source,
                "\"caseId\": \"first-valid-still-sequence-one\"",
                "\"caseId\": \"reject-zero-price\""));
    for (String invalid : invalidFixtures) {
      try {
        loader.load(invalid.getBytes(StandardCharsets.UTF_8), fixtureSchema);
        throw new IllegalStateException("M01 loader accepted an invalid boundary probe");
      } catch (FixtureSchemaException expected) {
        // Expected fail-closed boundary rejection.
      }
    }
    return invalidFixtures.size();
  }

  private static String replaceOnce(String source, String target, String replacement) {
    int index = source.indexOf(target);
    if (index < 0) {
      throw new IllegalStateException("fixture probe target missing: " + target);
    }
    return source.substring(0, index) + replacement + source.substring(index + target.length());
  }

  private static void rejectSystemError(
      M01Assertions.Observation observation, String candidateName) {
    if (SYSTEM_ERROR.equals(observation.classification())) {
      throw new IllegalStateException(candidateName + " failed with SYSTEM_ERROR");
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new StudentFailure(message);
    }
  }

  private static boolean startsWithBom(byte[] bytes) {
    return bytes.length >= 3
        && (bytes[0] & 0xff) == 0xef
        && (bytes[1] & 0xff) == 0xbb
        && (bytes[2] & 0xff) == 0xbf;
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

  private static void clearOutputs(Path reports) {
    try {
      Files.createDirectories(reports);
      for (String output : OUTPUTS) {
        Files.deleteIfExists(reports.resolve(output));
      }
    } catch (IOException exception) {
      throw new IllegalStateException("cannot clear stale M01 reports", exception);
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

  public record Result(String status, Path reportPath) {}

  record RequiredMutant(String id, M01Candidate.Factory factory, String expectedCaseId) {}

  private record PassArtifacts(
      String fixtureSha256,
      int schemaProbes,
      M01Assertions.Observation production,
      M01CanonicalHistory canonical,
      int completedReplays,
      int distinctReplayDigests,
      List<M01ReportJson.MutantObservation> mutants,
      M01Assertions.Observation systemErrorControl,
      M01ArchitectureGate.Report architecture,
      M01M00Regression.Result m00Regression) {
    private PassArtifacts {
      mutants = List.copyOf(mutants);
    }
  }

  private static final class StudentFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private StudentFailure(String message) {
      super(message);
    }
  }
}
