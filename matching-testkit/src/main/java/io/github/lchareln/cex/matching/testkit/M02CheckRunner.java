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

/** Deterministic, fail-closed completion judge for the frozen M02 contract. */
public final class M02CheckRunner {
  public static final String SCHEMA_VERSION = "matching.m02.check.v2";
  public static final String PASS = M02Assertions.PASS;
  public static final String STUDENT_FAILURE = M02Assertions.STUDENT_FAILURE;
  public static final String SYSTEM_ERROR = M02Assertions.SYSTEM_ERROR;

  static final String FIXTURE_PATH =
      "matching-testkit/src/test/resources/m02/fixtures/order-lifecycle-v1.json";
  static final String FIXTURE_SCHEMA_PATH = "schemas/matching.m02.scenario.v1.schema.json";
  static final String CHECK_SCHEMA_PATH = "schemas/matching.m02.check.v2.schema.json";
  static final String GOLDEN_PATH =
      "matching-testkit/src/test/resources/m02/golden/order-lifecycle-v1.canonical.txt";
  static final String GOLDEN_DIGEST_PATH =
      "matching-testkit/src/test/resources/m02/golden/order-lifecycle-v1.sha256";
  static final String EXPECTED_DIGEST =
      "sha256:32054d63accba99b19db823c41f74bda73dc3b8a009b528f2834d2bc70839d16";
  static final int EXPECTED_LINES = 181;
  static final int EXPECTED_BYTES = 17160;
  static final int REQUIRED_REPLAYS = 100;
  static final int EXPECTED_SCENARIOS = 10;
  static final int EXPECTED_COMMANDS = 34;
  static final int EXPECTED_PLACE_COMMANDS = 22;
  static final int EXPECTED_CANCEL_COMMANDS = 12;
  static final int EXPECTED_SCHEMA_PROBES = 8;
  static final String FROZEN_FIXTURE_SHA256 = M02StartCheckRunner.FROZEN_FIXTURE_SHA256;

  static final List<String> OUTPUTS =
      List.of(
          "m00-m01-regression.json",
          "cancel-event-batches.json",
          "lifecycle.json",
          "registry-invariants.json",
          "canonical-history.utf8",
          "mutants.json",
          "architecture.json",
          "check.json");
  private static final List<String> FROZEN_SCENARIO_IDS =
      List.of(
          "invalid-cancel-does-not-mutate-or-consume-sequence",
          "cancel-only-resting-order-removes-level",
          "cancel-middle-preserves-fifo",
          "cancel-partially-filled-remainder",
          "cancel-unknown-order",
          "late-cancel-filled-order",
          "repeat-cancel-stable",
          "duplicate-active-order-id",
          "duplicate-filled-order-id-does-not-resurrect",
          "duplicate-canceled-order-id-does-not-resurrect");
  private static final List<String> FROZEN_CASE_IDS =
      List.of(
          "seed-resting-bid-before-invalid-cancels",
          "reject-cancel-unknown-instrument",
          "reject-cancel-nonpositive-order-id",
          "next-place-still-sequence-two",
          "rest-only-ask-before-cancel",
          "cancel-only-ask-removes-level",
          "fifo-survivor-one-rests",
          "fifo-middle-maker-rests",
          "fifo-survivor-three-rests",
          "cancel-middle-maker-only",
          "taker-observes-survivor-fifo",
          "partial-maker-rests-five",
          "partial-maker-trades-two",
          "cancel-only-partial-remainder",
          "cancel-never-seen-order",
          "place-after-unknown-cancel-uses-sequence-one",
          "filled-maker-rests-before-trade",
          "maker-becomes-filled",
          "late-cancel-reports-filled-terminal",
          "late-cancel-filled-taker-reports-filled-terminal",
          "repeat-target-rests",
          "first-cancel-succeeds",
          "repeat-cancel-reports-canceled-terminal",
          "active-original-rests",
          "duplicate-active-place-rejected",
          "place-after-active-duplicate-uses-sequence-two",
          "filled-identity-maker-rests",
          "filled-identity-maker-completes",
          "duplicate-filled-place-rejected",
          "filled-identity-remains-filled-after-duplicate",
          "canceled-identity-original-rests",
          "canceled-identity-enters-terminal-state",
          "duplicate-canceled-place-rejected",
          "place-after-canceled-duplicate-uses-sequence-two");

  private final M02Candidate.Factory production;
  private final List<RequiredMutant> requiredMutants;
  private final M02Candidate.Factory systemErrorControl;

  public M02CheckRunner() {
    this(
        M02ProductionCandidate::new,
        List.of(
            new RequiredMutant(
                M02Mutants.WRONG_FIFO_ID,
                M02Mutants.wrongFifoAfterMiddleCancel(M02ProductionCandidate::new),
                "cancel-middle-preserves-fifo",
                "cancel-middle-maker-only"),
            new RequiredMutant(
                M02Mutants.GHOST_RESTING_ID,
                M02Mutants.ghostRestingOrder(M02ProductionCandidate::new),
                "cancel-only-resting-order-removes-level",
                "cancel-only-ask-removes-level"),
            new RequiredMutant(
                M02Mutants.TERMINAL_REUSE_ID,
                M02Mutants.terminalIdentityReuse(M02ProductionCandidate::new),
                "duplicate-canceled-order-id-does-not-resurrect",
                "duplicate-canceled-place-rejected"),
            new RequiredMutant(
                M02Mutants.REPEATED_CANCEL_ID,
                M02Mutants.repeatedCancelSucceeds(M02ProductionCandidate::new),
                "repeat-cancel-stable",
                "repeat-cancel-reports-canceled-terminal")),
        M02Mutants.throwingControl());
  }

  M02CheckRunner(
      M02Candidate.Factory production,
      List<RequiredMutant> requiredMutants,
      M02Candidate.Factory systemErrorControl) {
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
      throw new IllegalStateException("M02 frozen scenario corpus SHA-256 changed");
    }
    String fixtureSchema = readString(root.resolve(FIXTURE_SCHEMA_PATH));
    M02ScenarioLoader loader = new M02ScenarioLoader();
    M02ScenarioPack pack = loader.load(fixtureBytes, fixtureSchema);
    assertFrozenShape(pack);
    int schemaProbes = verifyScenarioBoundary(loader, fixtureBytes, fixtureSchema);

    M02Assertions assertions = new M02Assertions();
    M02Assertions.Observation productionObservation = assertions.judge(pack, production);
    rejectSystemError(productionObservation, "production candidate");
    require(
        PASS.equals(productionObservation.classification()),
        "production candidate failed: " + productionObservation.message());
    if (productionObservation.history() == null) {
      throw new IllegalStateException("passing production candidate has no M02 history");
    }
    M02StatefulPriorityProbes.Result priorityProbes =
        new M02StatefulPriorityProbes().verify(production);
    require(priorityProbes.passed(), priorityProbes.message());
    require(
        priorityProbes.checks() == M02StatefulPriorityProbes.EXPECTED_CHECKS,
        "stateful validation priority probe count changed");

    M02Canonicalizer canonicalizer = new M02Canonicalizer();
    M02CanonicalHistory canonical = canonicalizer.canonicalize(productionObservation.history());
    verifyGolden(root, canonical);

    Set<String> replayDigests = new LinkedHashSet<>();
    int completedReplays = 0;
    for (int replay = 0; replay < REQUIRED_REPLAYS; replay++) {
      M02ScenarioPack freshPack = loader.load(fixtureBytes, fixtureSchema);
      M02Assertions.Observation freshObservation = assertions.judge(freshPack, production);
      rejectSystemError(freshObservation, "fresh replay " + replay);
      require(
          PASS.equals(freshObservation.classification()),
          "fresh replay " + replay + " failed: " + freshObservation.message());
      M02CanonicalHistory fresh = canonicalizer.canonicalize(freshObservation.history());
      require(
          Arrays.equals(canonical.bytes(), fresh.bytes()),
          "fresh parse/engine replay bytes changed at run " + replay);
      require(canonical.digest().equals(fresh.digest()), "fresh replay digest changed");
      replayDigests.add(fresh.digest());
      completedReplays++;
    }
    require(replayDigests.size() == 1, "fresh replays produced multiple semantic digests");

    List<M02ReportJson.MutantObservation> mutants = new ArrayList<>();
    for (RequiredMutant mutant : requiredMutants) {
      M02Assertions.Observation observation = assertions.judge(pack, mutant.factory());
      rejectSystemError(observation, "required mutant " + mutant.id());
      require(
          STUDENT_FAILURE.equals(observation.classification()),
          "required mutant survived: " + mutant.id());
      require(
          mutant.expectedScenarioId().equals(observation.scenarioId())
              && mutant.expectedCaseId().equals(observation.caseId()),
          "required mutant failed at an unexpected command: " + mutant.id());
      mutants.add(new M02ReportJson.MutantObservation(mutant.id(), observation));
    }
    M02Assertions.Observation throwingControl = assertions.judge(pack, systemErrorControl);
    if (!SYSTEM_ERROR.equals(throwingControl.classification())) {
      throw new IllegalStateException("throwing control was not classified as SYSTEM_ERROR");
    }

    M02ArchitectureGate.Report architecture = new M02ArchitectureGate().verify(root);
    require(
        architecture.passed(), "M02 architecture boundary failed: " + architecture.violations());
    M02M01Regression.Result regression = new M02M01Regression().verify(root, production);
    require(regression.passed(), regression.message());

    return new PassArtifacts(
        fixtureSha256,
        schemaProbes,
        priorityProbes,
        productionObservation,
        canonical,
        completedReplays,
        replayDigests.size(),
        mutants,
        throwingControl,
        architecture,
        regression);
  }

  private static void writePassReports(Path root, Path reports, PassArtifacts artifacts) {
    AtomicFiles.write(
        reports.resolve("m00-m01-regression.json"),
        JsonSupport.prettyBytes(M02ReportJson.regression(artifacts.regression())));
    AtomicFiles.write(
        reports.resolve("cancel-event-batches.json"),
        JsonSupport.prettyBytes(
            M02ReportJson.eventBatches(
                artifacts.fixtureSha256(), artifacts.production().history())));
    AtomicFiles.write(
        reports.resolve("lifecycle.json"),
        JsonSupport.prettyBytes(
            M02ReportJson.lifecycle(artifacts.production().metrics(), artifacts.priorityProbes())));
    AtomicFiles.write(
        reports.resolve("registry-invariants.json"),
        JsonSupport.prettyBytes(
            M02ReportJson.registryInvariants(artifacts.production().metrics())));
    AtomicFiles.write(reports.resolve("canonical-history.utf8"), artifacts.canonical().bytes());
    AtomicFiles.write(
        reports.resolve("mutants.json"),
        JsonSupport.prettyBytes(
            M02ReportJson.mutants(
                artifacts.production(), artifacts.mutants(), artifacts.systemErrorControl())));
    AtomicFiles.write(
        reports.resolve("architecture.json"),
        JsonSupport.prettyBytes(M02ReportJson.architecture(artifacts.architecture())));
    writeAndValidateCheck(root, reports, passReport(artifacts));
  }

  private static ObjectNode passReport(PassArtifacts artifacts) {
    ObjectNode report = baseReport(PASS);
    report.put("contractPlanVersion", "0.4");
    ObjectNode corpus = report.putObject("scenarioCorpus");
    corpus.put("path", FIXTURE_PATH);
    corpus.put("schemaPath", FIXTURE_SCHEMA_PATH);
    corpus.put("sha256", artifacts.fixtureSha256());
    corpus.put("scenarios", EXPECTED_SCENARIOS);
    corpus.put("commands", EXPECTED_COMMANDS);
    corpus.put("placeCommands", EXPECTED_PLACE_COMMANDS);
    corpus.put("cancelCommands", EXPECTED_CANCEL_COMMANDS);
    corpus.put("schemaProbes", artifacts.schemaProbes());

    ObjectNode regression = statusArtifact(report, "m01Regression", "m00-m01-regression.json");
    regression.put("m01Scenarios", artifacts.regression().m01Scenarios());
    regression.put("m01Commands", artifacts.regression().m01Commands());
    regression.put("m00Status", artifacts.regression().m00().passed() ? PASS : STUDENT_FAILURE);
    ObjectNode batches = statusArtifact(report, "eventBatches", "cancel-event-batches.json");
    batches.put("commands", EXPECTED_COMMANDS);
    ObjectNode lifecycle = statusArtifact(report, "lifecycle", "lifecycle.json");
    lifecycle.put("commands", artifacts.production().metrics().commands());
    lifecycle.put("canceled", artifacts.production().metrics().canceled());
    lifecycle.put("cancelRejected", artifacts.production().metrics().cancelRejected());
    lifecycle.put("validationPriorityProbes", artifacts.priorityProbes().checks());
    ObjectNode registry = statusArtifact(report, "registryInvariants", "registry-invariants.json");
    registry.put("checks", artifacts.production().metrics().registryBookChecks());

    ObjectNode canonical = report.putObject("canonical");
    canonical.put("format", "M02H1");
    canonical.put("digest", artifacts.canonical().digest());
    canonical.put("lines", artifacts.canonical().lineCount());
    canonical.put("bytes", artifacts.canonical().bytes().length);
    canonical.put("artifact", "canonical-history.utf8");

    ObjectNode replays = report.putObject("replays");
    replays.put("requested", REQUIRED_REPLAYS);
    replays.put("completed", artifacts.completedReplays());
    replays.put("distinctDigests", artifacts.distinctReplayDigests());

    ObjectNode mutantSummary = statusArtifact(report, "mutants", "mutants.json");
    mutantSummary.put("required", 4);
    mutantSummary.put("killed", 4);
    mutantSummary.put("systemErrorControl", SYSTEM_ERROR);
    ArrayNode required = mutantSummary.putArray("requiredMutants");
    for (M02ReportJson.MutantObservation mutant : artifacts.mutants()) {
      ObjectNode node = required.addObject();
      node.put("id", mutant.id());
      node.put("classification", mutant.observation().classification());
      node.put("killed", true);
      node.put("scenarioId", mutant.observation().scenarioId());
      node.put("caseId", mutant.observation().caseId());
    }

    ObjectNode architecture = statusArtifact(report, "architecture", "architecture.json");
    architecture.put("sourceFiles", artifacts.architecture().sourceFiles());
    ArrayNode outputs = report.putArray("artifacts");
    OUTPUTS.stream().filter(name -> !"check.json".equals(name)).forEach(outputs::add);
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
    report.put("unit", "M02");
    report.put("status", status);
    return report;
  }

  private static void writeAndValidateCheck(Path root, Path reports, ObjectNode report) {
    JsonSupport.validate(report, readString(root.resolve(CHECK_SCHEMA_PATH)), false);
    AtomicFiles.write(reports.resolve("check.json"), JsonSupport.prettyBytes(report));
  }

  private static void verifyGolden(Path root, M02CanonicalHistory canonical) {
    require(
        Arrays.equals(readBytes(root.resolve(GOLDEN_PATH)), canonical.bytes()),
        "M02 canonical history differs from golden");
    require(
        EXPECTED_DIGEST.equals(readString(root.resolve(GOLDEN_DIGEST_PATH)).strip()),
        "M02 checked-in golden digest changed");
    require(EXPECTED_DIGEST.equals(canonical.digest()), "M02 canonical digest changed");
    require(canonical.lineCount() == EXPECTED_LINES, "M02 canonical line count changed");
    require(canonical.bytes().length == EXPECTED_BYTES, "M02 canonical byte count changed");
    require(
        canonical.bytes().length > 0 && canonical.bytes()[canonical.bytes().length - 1] == '\n',
        "M02 canonical history lacks final LF");
    require(!startsWithBom(canonical.bytes()), "M02 canonical history contains UTF-8 BOM");
  }

  private static void assertFrozenShape(M02ScenarioPack pack) {
    List<String> scenarios =
        pack.scenarios().stream().map(M02ScenarioPack.Scenario::scenarioId).toList();
    List<String> cases =
        pack.scenarios().stream()
            .flatMap(scenario -> scenario.commands().stream())
            .map(M02ScenarioPack.Command::caseId)
            .toList();
    if (pack.scenarios().size() != EXPECTED_SCENARIOS
        || pack.commandCount() != EXPECTED_COMMANDS
        || pack.placeCommandCount() != EXPECTED_PLACE_COMMANDS
        || pack.cancelCommandCount() != EXPECTED_CANCEL_COMMANDS
        || !FROZEN_SCENARIO_IDS.equals(scenarios)
        || !FROZEN_CASE_IDS.equals(cases)) {
      throw new IllegalStateException("M02 frozen scenario shape or identity changed");
    }
  }

  static int verifyScenarioBoundary(
      M02ScenarioLoader loader, byte[] fixtureBytes, String fixtureSchema) {
    String source = new String(fixtureBytes, StandardCharsets.UTF_8);
    List<String> invalidFixtures =
        List.of(
            replaceOnce(
                source,
                "\"schemaVersion\": \"matching.m02.scenario.v1\",",
                "\"schemaVersion\": \"matching.m02.scenario.v1\", \"schemaVersion\": \"matching.m02.scenario.v1\","),
            replaceOnce(source, "\"type\": \"PLACE\",", "\"type\": \"REPLACE\","),
            replaceOnce(
                source,
                "\"input\": { \"instrumentId\": \"ETH-USDT\", \"orderId\": 0 }",
                "\"input\": { \"instrumentId\": \"ETH-USDT\", \"orderId\": 0, \"side\": \"BUY\" }"),
            replaceOnce(
                source,
                "\"input\": { \"instrumentId\": \"BTC-USDT\", \"orderId\": 500 }",
                "\"input\": { \"instrumentId\": \"BTC-USDT\" }"),
            replaceOnce(
                source,
                "\"caseId\": \"seed-resting-bid-before-invalid-cancels\",",
                "\"caseId\": \"seed-resting-bid-before-invalid-cancels\", \"unexpected\": true,"),
            replaceOnce(source, "\"type\": \"CANCELED\",", "\"type\": \"CANCELLED\","),
            replaceOnce(
                source,
                "\"events\": [\n              { \"type\": \"CANCEL_REJECTED\", \"orderId\": 500, \"code\": \"ORDER_NOT_FOUND\" }\n            ]",
                "\"events\": []"),
            replaceOnce(source, "\"quantityLots\": 4", "\"quantityLots\": 4.0"));
    for (String invalid : invalidFixtures) {
      try {
        loader.load(invalid.getBytes(StandardCharsets.UTF_8), fixtureSchema);
        throw new IllegalStateException("M02 loader accepted an invalid boundary probe");
      } catch (FixtureSchemaException expected) {
        // Expected fail-closed rejection.
      }
    }
    if (invalidFixtures.size() != EXPECTED_SCHEMA_PROBES) {
      throw new IllegalStateException("M02 schema probe count changed");
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

  private static void rejectSystemError(M02Assertions.Observation observation, String name) {
    if (SYSTEM_ERROR.equals(observation.classification())) {
      throw new IllegalStateException(name + " failed with SYSTEM_ERROR");
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
      throw new IllegalStateException("cannot clear stale M02 reports", exception);
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

  record RequiredMutant(
      String id, M02Candidate.Factory factory, String expectedScenarioId, String expectedCaseId) {}

  private record PassArtifacts(
      String fixtureSha256,
      int schemaProbes,
      M02StatefulPriorityProbes.Result priorityProbes,
      M02Assertions.Observation production,
      M02CanonicalHistory canonical,
      int completedReplays,
      int distinctReplayDigests,
      List<M02ReportJson.MutantObservation> mutants,
      M02Assertions.Observation systemErrorControl,
      M02ArchitectureGate.Report architecture,
      M02M01Regression.Result regression) {
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
