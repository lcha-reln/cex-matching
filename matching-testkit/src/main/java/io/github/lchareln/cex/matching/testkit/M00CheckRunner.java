package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.PlaceLimitOrderInput;
import io.github.lchareln.cex.matching.PlaceLimitOrderValidator;
import io.github.lchareln.cex.matching.ValidationResult;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Deterministic, fail-closed completion judge for the frozen M00 contract. */
public final class M00CheckRunner {
  public static final String SCHEMA_VERSION = "matching.m00.check.v2";
  public static final String PASS = "PASS";
  public static final String STUDENT_FAILURE = "STUDENT_FAILURE";
  public static final String SYSTEM_ERROR = "SYSTEM_ERROR";

  private static final int REQUIRED_REPLAYS = 100;
  private static final int EXPECTED_RECORDS = 17;
  private static final int EXPECTED_VALID = 2;
  private static final int EXPECTED_INVALID = 15;
  private static final int EXPECTED_LINES = 37;
  private static final int EXPECTED_BYTES = 3199;
  private static final String EXPECTED_DIGEST =
      "sha256:2d287d677d5f200f2b5bd1dd18dabbd40e865779489ce6da36d0411a3b670669";

  private static final List<String> REQUIRED_PRIORITY_CASES =
      List.of("error-priority", "order-id-priority", "side-priority", "price-priority");

  private final Function<PlaceLimitOrderInput, ValidationResult> productionCandidate;
  private final Function<PlaceLimitOrderInput, ValidationResult> requiredMutantCandidate;
  private final Function<PlaceLimitOrderInput, ValidationResult> systemErrorControl;

  public M00CheckRunner() {
    this(
        new PlaceLimitOrderValidator()::validate,
        M00Mutants.quantityZeroAccepted(),
        M00Mutants.throwingControl());
  }

  M00CheckRunner(
      Function<PlaceLimitOrderInput, ValidationResult> productionCandidate,
      Function<PlaceLimitOrderInput, ValidationResult> requiredMutantCandidate,
      Function<PlaceLimitOrderInput, ValidationResult> systemErrorControl) {
    this.productionCandidate = productionCandidate;
    this.requiredMutantCandidate = requiredMutantCandidate;
    this.systemErrorControl = systemErrorControl;
  }

  public Result run(Path repositoryRoot, Path reportDirectory) {
    return run(repositoryRoot, reportDirectory, repositoryRoot);
  }

  Result run(Path repositoryRoot, Path reportDirectory, Path trustedOutputRoot) {
    Path root = repositoryRoot.toAbsolutePath().normalize();
    Path reports = SafeOutputPaths.resolveTrustedOutput(trustedOutputRoot, reportDirectory);
    clearPreviousOutputs(reports);
    try {
      ObjectNode report = execute(root, reports);
      writeAndValidateReport(root, reports, report);
      return new Result(PASS, reports.resolve("check.json"));
    } catch (StudentFailure exception) {
      ObjectNode report = failureReport(STUDENT_FAILURE, exception.getMessage());
      writeAndValidateReport(root, reports, report);
      return new Result(STUDENT_FAILURE, reports.resolve("check.json"));
    } catch (RuntimeException exception) {
      ObjectNode report = failureReport(SYSTEM_ERROR, stableSystemMessage(exception));
      writeAndValidateReport(root, reports, report);
      return new Result(SYSTEM_ERROR, reports.resolve("check.json"));
    }
  }

  private ObjectNode execute(Path root, Path reportDirectory) {
    Path fixturePath =
        root.resolve("matching-testkit/src/test/resources/m00/fixtures/history-v1.json");
    Path fixtureSchemaPath = root.resolve("schemas/matching.m00.fixture.v1.schema.json");
    Path goldenPath =
        root.resolve("matching-testkit/src/test/resources/m00/golden/history-v1.canonical.txt");
    Path goldenDigestPath =
        root.resolve("matching-testkit/src/test/resources/m00/golden/history-v1.sha256");

    byte[] fixtureBytes = readBytes(fixturePath);
    String fixtureSchema = readString(fixtureSchemaPath);
    M00FixtureLoader loader = new M00FixtureLoader();
    M00Fixture fixture = loader.load(fixtureBytes, fixtureSchema);
    assertFrozenFixtureShape(fixture);

    JudgeObservation productionObservation = judge(fixture, productionCandidate);
    rejectSystemError(productionObservation, "production validator");
    require(
        PASS.equals(productionObservation.classification()),
        "production validator failed: " + productionObservation.message());

    List<PlaceLimitOrderInput> inputs =
        fixture.records().stream().map(M00Fixture.Record::input).toList();
    CanonicalHistory canonical = new M00Canonicalizer().canonicalize(inputs);
    AtomicFiles.write(reportDirectory.resolve("canonical-history.utf8"), canonical.bytes());
    AtomicFiles.write(
        reportDirectory.resolve("validation-results.json"),
        validationResults(fixture, canonical.validationResults()));

    byte[] goldenBytes = readBytes(goldenPath);
    String goldenDigest = readString(goldenDigestPath).strip();
    require(Arrays.equals(goldenBytes, canonical.bytes()), "canonical history differs from golden");
    require(EXPECTED_DIGEST.equals(goldenDigest), "checked-in golden digest has unexpected value");
    require(
        EXPECTED_DIGEST.equals(canonical.digest()), "canonical digest differs from frozen digest");
    require(EXPECTED_LINES == canonical.lineCount(), "canonical history line count changed");
    require(
        EXPECTED_BYTES == canonical.bytes().length, "canonical history UTF-8 byte count changed");
    require(
        canonical.bytes()[canonical.bytes().length - 1] == '\n',
        "canonical history lacks final LF");
    require(!startsWithBom(canonical.bytes()), "canonical history contains a UTF-8 BOM");

    int schemaProbeCount = verifyFixtureBoundary(loader, fixtureBytes, fixtureSchema);
    verifyCanonicalFramingAndSensitivity();

    Set<String> replayDigests = new LinkedHashSet<>();
    int completedReplays = 0;
    for (int replay = 0; replay < REQUIRED_REPLAYS; replay++) {
      M00Fixture freshFixture = loader.load(fixtureBytes, fixtureSchema);
      CanonicalHistory fresh =
          new M00Canonicalizer()
              .canonicalize(freshFixture.records().stream().map(M00Fixture.Record::input).toList());
      require(
          Arrays.equals(canonical.bytes(), fresh.bytes()), "replay bytes changed at run " + replay);
      require(
          canonical.validationResults().equals(fresh.validationResults()),
          "replay validation changed at run " + replay);
      require(canonical.digest().equals(fresh.digest()), "replay digest changed at run " + replay);
      replayDigests.add(fresh.digest());
      completedReplays++;
    }
    require(replayDigests.size() == 1, "deterministic replay produced multiple digests");

    M00ArchitectureGate.Report architecture = new M00ArchitectureGate().verify(root);
    AtomicFiles.write(reportDirectory.resolve("architecture.json"), architectureJson(architecture));
    require(architecture.passed(), "architecture boundary failed: " + architecture.violations());

    JudgeObservation correctControl = judge(fixture, productionCandidate);
    JudgeObservation requiredMutant = judge(fixture, requiredMutantCandidate);
    JudgeObservation throwingControl = judge(fixture, systemErrorControl);
    rejectSystemError(correctControl, "correct control");
    rejectSystemError(requiredMutant, "required mutant");
    require(PASS.equals(correctControl.classification()), "correct control was rejected");
    require(
        STUDENT_FAILURE.equals(requiredMutant.classification()),
        "required mutant was not killed by a business assertion");
    require(
        "quantity-zero".equals(requiredMutant.caseId()),
        "required mutant failed at an unexpected case");
    if (!SYSTEM_ERROR.equals(throwingControl.classification())) {
      throw new IllegalStateException("throwing control was not classified as SYSTEM_ERROR");
    }
    AtomicFiles.write(
        reportDirectory.resolve("mutants.json"),
        mutantsJson(correctControl, requiredMutant, throwingControl));

    return passReport(
        fixtureBytes,
        fixture,
        canonical,
        schemaProbeCount,
        completedReplays,
        replayDigests.size(),
        architecture,
        requiredMutant);
  }

  private static void assertFrozenFixtureShape(M00Fixture fixture) {
    require(fixture.records().size() == EXPECTED_RECORDS, "frozen fixture record count changed");
    long valid =
        fixture.records().stream()
            .filter(record -> "VALID".equals(record.expected().status()))
            .count();
    require(valid == EXPECTED_VALID, "frozen fixture valid count changed");
    require(EXPECTED_RECORDS - valid == EXPECTED_INVALID, "frozen fixture invalid count changed");
    Set<String> caseIds = new LinkedHashSet<>();
    fixture.records().forEach(record -> caseIds.add(record.caseId()));
    require(caseIds.containsAll(REQUIRED_PRIORITY_CASES), "fixture lost an error-priority case");
  }

  private static JudgeObservation judge(
      M00Fixture fixture, Function<PlaceLimitOrderInput, ValidationResult> candidate) {
    for (M00Fixture.Record record : fixture.records()) {
      final ValidationResult actual;
      try {
        actual = candidate.apply(record.input());
        if (actual == null) {
          throw new IllegalStateException("candidate returned null");
        }
      } catch (RuntimeException exception) {
        return new JudgeObservation(
            SYSTEM_ERROR,
            record.caseId(),
            expectedText(record.expected()),
            exception.getClass().getSimpleName(),
            "candidate raised " + exception.getClass().getSimpleName());
      }
      if (!matches(record.expected(), actual)) {
        return new JudgeObservation(
            STUDENT_FAILURE,
            record.caseId(),
            expectedText(record.expected()),
            actualText(actual),
            "case "
                + record.caseId()
                + ": expected "
                + expectedText(record.expected())
                + ", actual "
                + actualText(actual));
      }
    }
    return new JudgeObservation(PASS, null, null, null, "all business expectations matched");
  }

  private static boolean matches(M00Fixture.Expected expected, ValidationResult actual) {
    if ("VALID".equals(expected.status())) {
      return actual instanceof ValidationResult.Valid;
    }
    return actual instanceof ValidationResult.Invalid invalid
        && expected.code() == invalid.code()
        && expected.field().equals(invalid.field());
  }

  private static String expectedText(M00Fixture.Expected expected) {
    return "VALID".equals(expected.status())
        ? "VALID"
        : expected.code().name() + "(" + expected.field() + ")";
  }

  private static String actualText(ValidationResult actual) {
    if (actual instanceof ValidationResult.Invalid invalid) {
      return invalid.code().name() + "(" + invalid.field() + ")";
    }
    return "VALID";
  }

  private static int verifyFixtureBoundary(
      M00FixtureLoader loader, byte[] fixtureBytes, String fixtureSchema) {
    String source = new String(fixtureBytes, StandardCharsets.UTF_8);
    List<String> invalidFixtures =
        List.of(
            replaceOnce(source, "\"side\": \"BUY\",", "\"side\": \"BUY\", \"side\": \"BUY\","),
            replaceOnce(source, "\"priceTicks\": 1,", "\"priceTicks\": 1.0,"),
            replaceOnce(source, "\"quantityLots\": 1,", "\"quantityLots\": 1e0,"),
            replaceOnce(source, "\"orderId\": 1,", "\"orderId\": \"1\","),
            replaceOnce(source, "\"priceTicks\": 1,", "\"priceTicks\": null,"),
            replaceOnce(source, "\"quantityLots\": 1,", "\"quantityLots\": true,"),
            replaceOnce(source, "      \"orderId\": 1,\n", ""),
            replaceOnce(
                source,
                "\"caseId\": \"valid-minimum-buy\",",
                "\"caseId\": \"valid-minimum-buy\", \"extra\": 1,"),
            replaceOnce(source, "\"caseId\": \"valid-minimum-buy\",", "\"caseId\": \"BAD ID\","),
            replaceOnce(
                source, "\"instrumentId\": \"BTC-USDT\",", "\"instrumentId\": \"\\uD800\","),
            replaceOnce(
                source,
                "\"instrumentId\": \"BTC-USDT\",",
                "\"instrumentId\": \"BTC\\u0000-USDT\","),
            replaceOnce(
                source, "\"instrumentId\": \"BTC-USDT\",", "\"instrumentId\": \"BTC\\r-USDT\","),
            replaceOnce(source, "\"side\": \"BUY\",", "\"side\": \"BUY\\n\","),
            replaceOnce(
                source,
                "\"instrumentId\": \"BTC-USDT\",",
                "\"instrumentId\": \"" + "x".repeat(65) + "\","),
            replaceOnce(source, "\"side\": \"BUY\",", "\"side\": \"" + "x".repeat(17) + "\","),
            replaceOnce(
                source,
                "\"schemaVersion\": \"matching.m00.fixture.v1\"",
                "\"schemaVersion\": \"wrong\""),
            "{\"schemaVersion\":\"matching.m00.fixture.v1\",\"records\":[]}",
            source + "{}",
            "{broken");
    for (String invalid : invalidFixtures) {
      try {
        loader.load(invalid.getBytes(StandardCharsets.UTF_8), fixtureSchema);
        throw new StudentFailure("fixture boundary accepted an invalid lexical/schema probe");
      } catch (FixtureSchemaException expected) {
        // Expected system-level rejection.
      }
    }
    return invalidFixtures.size();
  }

  private static void verifyCanonicalFramingAndSensitivity() {
    PlaceLimitOrderInput framedInput =
        new PlaceLimitOrderInput("交易|对", BigInteger.ONE, "BUY:NOW", BigInteger.ONE, BigInteger.ONE);
    CanonicalHistory framed = new M00Canonicalizer().canonicalize(List.of(framedInput));
    String text = new String(framed.bytes(), StandardCharsets.UTF_8);
    require(text.contains("instrumentId=10:交易|对"), "string framing uses character length");
    require(text.contains("side=7:BUY:NOW"), "delimiter-bearing side was not length framed");

    PlaceLimitOrderInput first =
        new PlaceLimitOrderInput("BTC-USDT", BigInteger.ONE, "BUY", BigInteger.ONE, BigInteger.ONE);
    PlaceLimitOrderInput changed =
        new PlaceLimitOrderInput("BTC-USDT", BigInteger.ONE, "BUY", BigInteger.ONE, BigInteger.TWO);
    String firstDigest = new M00Canonicalizer().canonicalize(List.of(first)).digest();
    String changedDigest = new M00Canonicalizer().canonicalize(List.of(changed)).digest();
    require(!firstDigest.equals(changedDigest), "business input change did not change the digest");
  }

  private static byte[] validationResults(
      M00Fixture fixture, List<ValidationResult> validationResults) {
    ObjectNode root = JsonSupport.MAPPER.createObjectNode();
    root.put("schemaVersion", "matching.m00.validation-results.v1");
    ArrayNode records = root.putArray("records");
    for (int index = 0; index < fixture.records().size(); index++) {
      ObjectNode record = records.addObject();
      record.put("index", index);
      record.put("caseId", fixture.records().get(index).caseId());
      ValidationResult result = validationResults.get(index);
      record.put("status", result.status());
      if (result instanceof ValidationResult.Invalid invalid) {
        record.put("code", invalid.code().name());
        record.put("field", invalid.field());
      } else {
        record.putNull("code");
        record.putNull("field");
      }
    }
    return JsonSupport.prettyBytes(root);
  }

  private static byte[] architectureJson(M00ArchitectureGate.Report report) {
    ObjectNode root = JsonSupport.MAPPER.createObjectNode();
    root.put("schemaVersion", "matching.m00.architecture.v1");
    root.put("status", report.passed() ? PASS : STUDENT_FAILURE);
    root.put("coreSourceFiles", report.sourceFiles());
    ArrayNode violations = root.putArray("violations");
    report.violations().forEach(violations::add);
    return JsonSupport.prettyBytes(root);
  }

  private static byte[] mutantsJson(
      JudgeObservation correct, JudgeObservation mutant, JudgeObservation throwingControl) {
    ObjectNode root = JsonSupport.MAPPER.createObjectNode();
    root.put("schemaVersion", "matching.m00.mutants.v1");
    ArrayNode candidates = root.putArray("candidates");
    addCandidate(candidates, "PRODUCTION-CONTROL", correct, false);
    addCandidate(candidates, M00Mutants.QUANTITY_ZERO_ACCEPTED, mutant, true);
    addCandidate(candidates, "SYSTEM-ERROR-CONTROL", throwingControl, false);
    return JsonSupport.prettyBytes(root);
  }

  private static void addCandidate(
      ArrayNode candidates, String id, JudgeObservation observation, boolean requiredMutant) {
    ObjectNode candidate = candidates.addObject();
    candidate.put("id", id);
    candidate.put("classification", observation.classification());
    candidate.put("killed", requiredMutant && STUDENT_FAILURE.equals(observation.classification()));
    if (observation.caseId() == null) {
      candidate.putNull("caseId");
      candidate.putNull("expected");
      candidate.putNull("actual");
    } else {
      candidate.put("caseId", observation.caseId());
      candidate.put("expected", observation.expected());
      candidate.put("actual", observation.actual());
    }
    candidate.put("message", observation.message());
  }

  private static ObjectNode passReport(
      byte[] fixtureBytes,
      M00Fixture fixture,
      CanonicalHistory canonical,
      int schemaProbeCount,
      int completedReplays,
      int distinctDigests,
      M00ArchitectureGate.Report architecture,
      JudgeObservation mutant) {
    ObjectNode root = baseReport(PASS);

    ObjectNode fixtureNode = root.putObject("fixture");
    fixtureNode.put("path", "matching-testkit/src/test/resources/m00/fixtures/history-v1.json");
    fixtureNode.put("sha256", Hashing.sha256Hex(fixtureBytes));
    fixtureNode.put("records", fixture.records().size());
    fixtureNode.put("valid", EXPECTED_VALID);
    fixtureNode.put("invalid", EXPECTED_INVALID);
    fixtureNode.put("schemaFailureProbes", schemaProbeCount);

    ObjectNode canonicalNode = root.putObject("canonical");
    canonicalNode.put("format", "M00H1");
    canonicalNode.put("lines", canonical.lineCount());
    canonicalNode.put("utf8Bytes", canonical.bytes().length);
    canonicalNode.put("digest", canonical.digest());
    canonicalNode.put(
        "goldenPath", "matching-testkit/src/test/resources/m00/golden/history-v1.canonical.txt");

    ObjectNode replays = root.putObject("replays");
    replays.put("requested", REQUIRED_REPLAYS);
    replays.put("completed", completedReplays);
    replays.put("distinctDigests", distinctDigests);

    ObjectNode mutantNode = root.putObject("requiredMutant");
    mutantNode.put("id", M00Mutants.QUANTITY_ZERO_ACCEPTED);
    mutantNode.put("classification", mutant.classification());
    mutantNode.put("killed", true);
    mutantNode.put("caseId", mutant.caseId());
    mutantNode.put("expected", mutant.expected());
    mutantNode.put("actual", mutant.actual());

    ObjectNode architectureNode = root.putObject("architecture");
    architectureNode.put("status", PASS);
    architectureNode.put("coreSourceFiles", architecture.sourceFiles());
    architectureNode.put("violations", architecture.violations().size());

    ArrayNode assertions = root.putArray("assertions");
    for (String id :
        List.of(
            "fixture-boundary",
            "validation-contract",
            "canonical-golden",
            "deterministic-replay",
            "architecture-boundary",
            "semantic-mutant")) {
      ObjectNode assertion = assertions.addObject();
      assertion.put("id", id);
      assertion.put("status", PASS);
    }
    return root;
  }

  private static ObjectNode failureReport(String status, String message) {
    ObjectNode root = baseReport(status);
    ObjectNode failure = root.putObject("failure");
    failure.put("classification", status);
    failure.put("message", message == null || message.isBlank() ? "unspecified failure" : message);
    return root;
  }

  private static ObjectNode baseReport(String status) {
    ObjectNode root = JsonSupport.MAPPER.createObjectNode();
    root.put("schemaVersion", SCHEMA_VERSION);
    root.put("unit", "M00");
    root.put("status", status);
    root.put("contractPlanVersion", "0.1");
    return root;
  }

  private static void writeAndValidateReport(Path root, Path reportDirectory, ObjectNode report) {
    Path schemaPath = root.resolve("schemas/matching.m00.check.v2.schema.json");
    JsonSupport.validate(report, readString(schemaPath), false);
    AtomicFiles.write(reportDirectory.resolve("check.json"), JsonSupport.prettyBytes(report));
  }

  private static void clearPreviousOutputs(Path reportDirectory) {
    try {
      Files.createDirectories(reportDirectory);
      for (String name :
          List.of(
              "check.json",
              "canonical-history.utf8",
              "validation-results.json",
              "mutants.json",
              "architecture.json")) {
        Files.deleteIfExists(reportDirectory.resolve(name));
      }
    } catch (IOException exception) {
      throw new IllegalStateException("cannot clear stale M00 reports", exception);
    }
  }

  private static boolean startsWithBom(byte[] bytes) {
    return bytes.length >= 3
        && (bytes[0] & 0xff) == 0xef
        && (bytes[1] & 0xff) == 0xbb
        && (bytes[2] & 0xff) == 0xbf;
  }

  private static String replaceOnce(String source, String target, String replacement) {
    int index = source.indexOf(target);
    if (index < 0) {
      throw new IllegalStateException("probe target is absent: " + target);
    }
    return source.substring(0, index) + replacement + source.substring(index + target.length());
  }

  private static byte[] readBytes(Path path) {
    try {
      return Files.readAllBytes(path);
    } catch (IOException exception) {
      throw new IllegalStateException("cannot read " + path, exception);
    }
  }

  private static String readString(Path path) {
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException("cannot read " + path, exception);
    }
  }

  private static String stableSystemMessage(RuntimeException exception) {
    String message = exception.getMessage();
    return exception.getClass().getSimpleName()
        + (message == null || message.isBlank() ? "" : ": " + message);
  }

  private static void rejectSystemError(JudgeObservation observation, String candidateName) {
    if (SYSTEM_ERROR.equals(observation.classification())) {
      throw new IllegalStateException(candidateName + " system error: " + observation.message());
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new StudentFailure(message);
    }
  }

  public record Result(String status, Path reportPath) {
    public boolean passed() {
      return PASS.equals(status);
    }
  }

  private record JudgeObservation(
      String classification, String caseId, String expected, String actual, String message) {}

  private static final class StudentFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private StudentFailure(String message) {
      super(message);
    }
  }
}
