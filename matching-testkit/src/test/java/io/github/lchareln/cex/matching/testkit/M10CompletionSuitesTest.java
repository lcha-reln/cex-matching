package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

final class M10CompletionSuitesTest {
  @TempDir Path temporary;

  @Test
  void generatedAdmissionModelIsByteExactAndHasFrozenDimensions() {
    M10GeneratedSuite.Result first = new M10GeneratedSuite().generate();
    M10GeneratedSuite.Result second = new M10GeneratedSuite().generate();
    assertEquals(64, first.histories().size());
    assertEquals(16_384, first.actions());
    assertEquals(16_384, first.executedActions());
    assertTrue(first.comparisons() >= 16_384);
    assertTrue(first.ledgerChecks() >= 16_384);
    assertEquals(64, first.terminalReconciliations());
    assertEquals(4, first.laneCounts().size());
    assertTrue(first.laneCounts().values().stream().allMatch(count -> count == 16));
    assertEquals(first.digest(), second.digest());
    assertArrayEquals(first.canonicalBytes(), second.canonicalBytes());
  }

  @Test
  void methodSmokeRetainsRawScheduledArrivalsButCannotBecomeReleaseEvidence() {
    M10MethodSuite.Result result = new M10MethodSuite().run();
    assertEquals(512, result.scheduledArrivals());
    assertTrue(result.completionSamples() > 0);
    assertEquals(1_150, result.knee());
    assertEquals(805, result.qopCandidate());
    assertEquals(700, result.qop());
    assertEquals("METHOD_SMOKE_ONLY", result.method().path("resultScope").stringValue());
    assertFalse(result.method().path("eligibleForReleaseEvidence").booleanValue());
    assertEquals("MODEL_ONLY", result.method().path("evidenceMode").stringValue());
    assertFalse(result.method().path("methodIsomorphic").booleanValue());
    assertEquals(0, result.method().path("releaseSoakSecondsClaimed").intValue());
  }

  @Test
  void nearestRankAndMissingKneeFailClosed() {
    assertEquals(100, M10MethodSuite.nearestRank(List.of(1L, 2L, 3L, 100L), 0.99));
    assertThrows(IllegalArgumentException.class, () -> M10MethodSuite.nearestRank(List.of(), 0.99));
  }

  @Test
  void allExecutableCandidatesProduceStudentFailureAndControlsStaySystemError() {
    M10MutantSuite.Result result = new M10MutantSuite().run();
    assertEquals(12, result.killed());
    assertEquals(12, result.counterexamples().path("witnesses").size());
    result
        .candidates()
        .forEach(
            candidate ->
                assertEquals("STUDENT_FAILURE", candidate.path("classification").stringValue()));
    result
        .controls()
        .forEach(
            control -> {
              assertEquals("SYSTEM_ERROR", control.path("classification").stringValue());
              assertFalse(control.path("countedAsKill").booleanValue());
            });
  }

  @Test
  void twentyFixedScenariosExerciseRealServiceAndMethodModel() {
    M10MethodSuite.Result method = new M10MethodSuite().run();
    M10FixedSuite.Result fixed = new M10FixedSuite().run(temporary.resolve("fixed"), method);
    assertEquals(20, fixed.passed());
    assertEquals(20, fixed.scenarios().size());
    fixed
        .scenarios()
        .forEach(scenario -> assertEquals("PASS", scenario.path("status").stringValue()));
    assertEquals(
        "REAL_LOCAL_RUNTIME_PLUS_METHOD_MODEL",
        fixed.scenarios().get(19).path("evidenceMode").stringValue());
  }

  @Test
  void coverageIsDerivedFromFrozenMappingsAndExecutedPassWitnesses() throws Exception {
    Path root = Path.of(System.getProperty("matching.repositoryRoot"));
    JsonNode workload =
        JsonSupport.parse(Files.readAllBytes(root.resolve(M10StartCheckRunner.WORKLOAD_PATH)));
    M10GeneratedSuite.Result generated = new M10GeneratedSuite().generate();
    M10MethodSuite.Result method = new M10MethodSuite().run();
    M10FixedSuite.Result fixed =
        new M10FixedSuite().run(temporary.resolve("coverage-fixed"), method);
    M10MutantSuite.Result mutants = new M10MutantSuite().run();

    M10Coverage.Report coverage = new M10Coverage().derive(workload, fixed, generated, mutants);

    assertEquals(28, coverage.observed());
    assertEquals(28, coverage.obligations().size());
    coverage
        .obligations()
        .forEach(
            obligation -> {
              assertEquals("PASS", obligation.path("status").stringValue());
              assertFalse(obligation.path("witnessIds").isEmpty());
            });
  }

  @Test
  void releaseCapacityRejectsASaturatedQualifiedSoak() {
    ObjectNode qualification = releaseCapacityFixture();
    M10ReleaseBundleVerifier.verifyCapacity(qualification, soakSummaries(qualification));

    ObjectNode qualified =
        (ObjectNode) qualification.path("soak").path("attempts").path(0).path("point");
    markSaturated(qualified);
    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                M10ReleaseBundleVerifier.verifyCapacity(
                    qualification, soakSummaries(qualification)));
    assertTrue(failure.getMessage().contains("qualified soak attempt is saturated"));

    ObjectNode postCutOverload = releaseCapacityFixture();
    ObjectNode postCutSoak =
        (ObjectNode) postCutOverload.path("soak").path("attempts").path(0).path("point");
    ((ObjectNode) postCutSoak.path("observationCut")).put("postCutOverloaded", 1);
    postCutSoak.put("saturated", true);
    ((ArrayNode) postCutSoak.path("saturationReasons")).add("POST_CUT_PLANNED_OVERLOAD_REJECTION");
    assertTrue(
        assertThrows(
                IllegalStateException.class,
                () ->
                    M10ReleaseBundleVerifier.verifyCapacity(
                        postCutOverload, soakSummaries(postCutOverload)))
            .getMessage()
            .contains("qualified soak attempt is saturated"));
  }

  @Test
  void releaseCapacityRejectsSelectingARateSaturatedInAnySweep() {
    ObjectNode qualification = releaseCapacityFixture();
    ObjectNode wrongCandidate = qualification.deepCopy();
    ((ObjectNode) wrongCandidate.path("capacity")).put("qualifiedOperatingPointCandidate", 351);
    assertTrue(
        assertThrows(
                IllegalStateException.class,
                () ->
                    M10ReleaseBundleVerifier.verifyCapacity(
                        wrongCandidate, soakSummaries(wrongCandidate)))
            .getMessage()
            .contains("QOP candidate changed"));

    ObjectNode selectedInSecondSweep = (ObjectNode) qualification.path("sweeps").path(1).path(2);
    ObjectNode attempts =
        (ObjectNode) selectedInSecondSweep.path("observationCut").path("attemptAccounting");
    attempts.put("offers", 11);
    attempts.put("overloaded", 1);
    selectedInSecondSweep.put("saturated", true);
    ((ArrayNode) selectedInSecondSweep.path("saturationReasons")).add("OVERLOAD_REJECTION");

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                M10ReleaseBundleVerifier.verifyCapacity(
                    qualification, soakSummaries(qualification)));
    assertTrue(failure.getMessage().contains("provisional soak candidates changed"));
  }

  @Test
  void releaseCapacityPromotesTheFirstQualifiedFullDurationAttempt() {
    ObjectNode qualification = releaseCapacityFallbackFixture();

    M10ReleaseBundleVerifier.verifyCapacity(qualification, soakSummaries(qualification));

    assertEquals(200, qualification.path("capacity").path("qualifiedOperatingPoint").longValue());
    assertEquals(2, qualification.path("soak").path("qualifiedAttemptNumber").intValue());
  }

  @Test
  void releaseCapacityRejectsSkippedReorderedOrMissingPrecedingAttempts() {
    ObjectNode skipped = releaseCapacityFixture();
    ObjectNode skippedPoint =
        (ObjectNode) skipped.path("soak").path("attempts").path(0).path("point");
    skippedPoint.put("offeredRate", 200);
    skippedPoint.put("pointId", soakPointId(1, 200));
    ((ObjectNode) skipped.path("capacity")).put("qualifiedOperatingPoint", 200);
    ((ObjectNode) skipped.path("soak")).put("qualifiedPointId", soakPointId(1, 200));
    assertTrue(
        assertThrows(
                IllegalStateException.class,
                () -> M10ReleaseBundleVerifier.verifyCapacity(skipped, soakSummaries(skipped)))
            .getMessage()
            .contains("continuous provisional-candidate prefix"));

    ObjectNode reordered = releaseCapacityFallbackFixture();
    ArrayNode reorderedAttempts = (ArrayNode) reordered.path("soak").path("attempts");
    JsonNode first = reorderedAttempts.path(0).deepCopy();
    JsonNode second = reorderedAttempts.path(1).deepCopy();
    reorderedAttempts.set(0, second);
    reorderedAttempts.set(1, first);
    assertTrue(
        assertThrows(
                IllegalStateException.class,
                () -> M10ReleaseBundleVerifier.verifyCapacity(reordered, soakSummaries(reordered)))
            .getMessage()
            .contains("attempt numbers are not contiguous"));

    ObjectNode missingPredecessor = releaseCapacityFallbackFixture();
    ((ArrayNode) missingPredecessor.path("soak").path("attempts")).remove(0);
    ((ObjectNode) missingPredecessor.path("soak")).put("qualifiedAttemptNumber", 1);
    assertTrue(
        assertThrows(
                IllegalStateException.class,
                () ->
                    M10ReleaseBundleVerifier.verifyCapacity(
                        missingPredecessor, soakSummaries(missingPredecessor)))
            .getMessage()
            .contains("attempt numbers are not contiguous"));
  }

  @Test
  void releaseCapacityRejectsWrongOutcomesAndQualifiedPointers() {
    ObjectNode precedingQualified = releaseCapacityFallbackFixture();
    ((ObjectNode) precedingQualified.path("soak").path("attempts").path(0))
        .put("outcome", "QUALIFIED");
    assertTrue(
        assertThrows(
                IllegalStateException.class,
                () ->
                    M10ReleaseBundleVerifier.verifyCapacity(
                        precedingQualified, soakSummaries(precedingQualified)))
            .getMessage()
            .contains("first-pass promotion"));

    ObjectNode finalSaturated = releaseCapacityFallbackFixture();
    ((ObjectNode) finalSaturated.path("soak").path("attempts").path(1)).put("outcome", "SATURATED");
    assertTrue(
        assertThrows(
                IllegalStateException.class,
                () ->
                    M10ReleaseBundleVerifier.verifyCapacity(
                        finalSaturated, soakSummaries(finalSaturated)))
            .getMessage()
            .contains("first-pass promotion"));

    ObjectNode wrongAttemptPointer = releaseCapacityFallbackFixture();
    ((ObjectNode) wrongAttemptPointer.path("soak")).put("qualifiedAttemptNumber", 1);
    assertTrue(
        assertThrows(
                IllegalStateException.class,
                () ->
                    M10ReleaseBundleVerifier.verifyCapacity(
                        wrongAttemptPointer, soakSummaries(wrongAttemptPointer)))
            .getMessage()
            .contains("qualified attempt pointer"));

    ObjectNode wrongPointPointer = releaseCapacityFallbackFixture();
    ((ObjectNode) wrongPointPointer.path("soak")).put("qualifiedPointId", soakPointId(1, 300));
    assertTrue(
        assertThrows(
                IllegalStateException.class,
                () ->
                    M10ReleaseBundleVerifier.verifyCapacity(
                        wrongPointPointer, soakSummaries(wrongPointPointer)))
            .getMessage()
            .contains("qualified point pointer"));

    ObjectNode wrongQop = releaseCapacityFallbackFixture();
    ((ObjectNode) wrongQop.path("capacity")).put("qualifiedOperatingPoint", 300);
    assertTrue(
        assertThrows(
                IllegalStateException.class,
                () -> M10ReleaseBundleVerifier.verifyCapacity(wrongQop, soakSummaries(wrongQop)))
            .getMessage()
            .contains("published QOP changed"));
  }

  @Test
  void releaseCapacityNeverDowngradesSystemErrorsIntoSaturation() {
    ObjectNode explicitFailure = releaseCapacityFallbackFixture();
    ObjectNode failedAccounting =
        (ObjectNode)
            explicitFailure.path("soak").path("attempts").path(0).path("point").path("attempts");
    failedAccounting.put("explicitServiceFailures", 1);
    assertTrue(
        assertThrows(
                IllegalStateException.class,
                () ->
                    M10ReleaseBundleVerifier.verifyCapacity(
                        explicitFailure, soakSummaries(explicitFailure)))
            .getMessage()
            .contains("explicit service failures"));

    ObjectNode durabilityUnknown = releaseCapacityFallbackFixture();
    ObjectNode variants =
        (ObjectNode)
            durabilityUnknown
                .path("soak")
                .path("attempts")
                .path(0)
                .path("point")
                .path("attempts")
                .path("submissionResultVariants");
    variants.put("DURABILITY_UNKNOWN", 1);
    assertTrue(
        assertThrows(
                IllegalStateException.class,
                () ->
                    M10ReleaseBundleVerifier.verifyCapacity(
                        durabilityUnknown, soakSummaries(durabilityUnknown)))
            .getMessage()
            .contains("non-saturation system failure"));
  }

  @Test
  void releaseJmhDiagnosticRequiresRealSampleTimeHistogramData() throws Exception {
    String sourceCommit = "a".repeat(40);
    Path release = temporary.resolve("jmh-repo/build/lab-evidence/staging/reports/release");
    Path file = release.resolve("diagnostics/core-sample-time.json");
    ObjectNode result = jmhResult("restingMakerThenMatchingTaker");
    ArrayNode results =
        JsonSupport.MAPPER.createArrayNode().add(result).add(jmhResult("canonicalEnvelopeDecode"));
    Files.createDirectories(file.getParent());
    Files.write(file, JsonSupport.prettyBytes(results));
    ObjectNode qualification = jmhInventory(file, sourceCommit);
    List<Path> files = new ArrayList<>();

    M10ReleaseBundleVerifier.verifyJmhDiagnostic(
        temporary.resolve("jmh-repo"), release, sourceCommit, qualification, files);

    assertEquals(List.of(Path.of("diagnostics/core-sample-time.json")), files);
    result.put("mode", "avgt");
    Files.write(file, JsonSupport.prettyBytes(results));
    qualification = jmhInventory(file, sourceCommit);
    ObjectNode invalid = qualification;
    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                M10ReleaseBundleVerifier.verifyJmhDiagnostic(
                    temporary.resolve("jmh-repo"),
                    release,
                    sourceCommit,
                    invalid,
                    new ArrayList<>()));
    assertTrue(failure.getMessage().contains("not SampleTime"));

    result.put("mode", "sample");
    result.put("forks", 1);
    Files.write(file, JsonSupport.prettyBytes(results));
    qualification = jmhInventory(file, sourceCommit);
    ObjectNode wrongForks = qualification;
    IllegalStateException forkFailure =
        assertThrows(
            IllegalStateException.class,
            () ->
                M10ReleaseBundleVerifier.verifyJmhDiagnostic(
                    temporary.resolve("jmh-repo"),
                    release,
                    sourceCommit,
                    wrongForks,
                    new ArrayList<>()));
    assertTrue(forkFailure.getMessage().contains("configuration changed"));

    result.put("forks", 2);
    Files.write(file, JsonSupport.prettyBytes(results));
    ObjectNode wrongClassBinding = jmhInventory(file, sourceCommit);
    ((ObjectNode) wrongClassBinding.path("runtimeProvenance"))
        .put("matchingBenchmarkClassesSha256", "c".repeat(64));
    assertTrue(
        assertThrows(
                IllegalStateException.class,
                () ->
                    M10ReleaseBundleVerifier.verifyJmhDiagnostic(
                        temporary.resolve("jmh-repo"),
                        release,
                        sourceCommit,
                        wrongClassBinding,
                        new ArrayList<>()))
            .getMessage()
            .contains("runtime provenance"));
  }

  @Test
  void calibrationAndEverySweepRateAreIndependentlyRecomputed() {
    ObjectNode qualification = calibrationFixture();
    M10ReleaseBundleVerifier.verifyCalibrationAndRateLadder(qualification, 3);

    ObjectNode wrongReference = qualification.deepCopy();
    ((ObjectNode) wrongReference.path("calibration")).put("referenceRate", 999);
    assertTrue(
        assertThrows(
                IllegalStateException.class,
                () -> M10ReleaseBundleVerifier.verifyCalibrationAndRateLadder(wrongReference, 3))
            .getMessage()
            .contains("reference rate formula"));

    ObjectNode wrongLadderOrder = qualification.deepCopy();
    ((ObjectNode) wrongLadderOrder.path("sweeps").path(0).path(1)).put("ladderPermille", 700);
    assertTrue(
        assertThrows(
                IllegalStateException.class,
                () -> M10ReleaseBundleVerifier.verifyCalibrationAndRateLadder(wrongLadderOrder, 3))
            .getMessage()
            .contains("calibration-to-ladder"));

    ObjectNode inconsistentSweepRate = qualification.deepCopy();
    ObjectNode point = (ObjectNode) inconsistentSweepRate.path("sweeps").path(2).path(4);
    point.put("offeredRate", point.path("offeredRate").longValue() + 1);
    assertTrue(
        assertThrows(
                IllegalStateException.class,
                () ->
                    M10ReleaseBundleVerifier.verifyCalibrationAndRateLadder(
                        inconsistentSweepRate, 3))
            .getMessage()
            .contains("calibration-to-ladder"));
  }

  @Test
  void architectureRestrictsCoreToTheHotPathAmendmentAndKeepsBenchmarksOutOfProduction() {
    Path root = Path.of(System.getProperty("matching.repositoryRoot"));
    M10ArchitectureGate.Report report = new M10ArchitectureGate().verify(root);
    assertTrue(report.passed(), () -> String.join("; ", report.violations()));
    assertEquals(
        List.of(
            "matching-core/src/main/java/io/github/lchareln/cex/matching/SingleInstrumentMatchingEngine.java",
            "matching-core/src/test/java/io/github/lchareln/cex/matching/SingleInstrumentTerminalHistoryGrowthTest.java"),
        report.coreDeltaPaths());
    assertEquals(1, report.testkitProbeOccurrences());
  }

  @Test
  void inheritedM09RunsCurrentClassesAgainstItsAnnotatedSourceBaseline() {
    Path root = Path.of(System.getProperty("matching.repositoryRoot"));
    Path workspace =
        root.resolve("build/m10-test").resolve(temporary.getFileName().toString()).normalize();

    ObjectNode summary = new M10InheritedM09Regression().run(root, workspace);

    assertEquals("PASS", summary.path("status").stringValue());
    assertEquals(22, summary.path("fixedScenarios").intValue());
    assertEquals(3_840, summary.path("generatedOperations").intValue());
    assertEquals(12, summary.path("mutantsKilled").intValue());
    assertEquals(
        "CURRENT_HEAD_COMPILED_PRODUCTION_CLASSES", summary.path("semanticSource").stringValue());
    assertEquals(
        "course/m09-complete", summary.path("sourceOnlyArchitectureBaseline").stringValue());
    assertFalse(Files.exists(workspace));
  }

  private static ObjectNode releaseCapacityFixture() {
    return releaseCapacityFixture(false);
  }

  private static ObjectNode releaseCapacityFallbackFixture() {
    return releaseCapacityFixture(true);
  }

  private static ObjectNode releaseCapacityFixture(boolean firstAttemptSaturates) {
    ObjectNode qualification = JsonSupport.MAPPER.createObjectNode();
    ArrayNode sweeps = qualification.putArray("sweeps");
    for (int sweep = 1; sweep <= 3; sweep++) {
      ArrayNode points = sweeps.addArray();
      for (int index = 1; index <= 8; index++) {
        long rate = index * 100L;
        ObjectNode point = ratePoint("sweep-" + sweep + '-' + index, rate, index >= 5);
        point.put("sweep", sweep);
        points.add(point);
      }
    }
    ObjectNode capacity = qualification.putObject("capacity");
    capacity.putArray("sweepKnees").add(500).add(500).add(500);
    capacity.put("publishedKnee", 500);
    capacity.put("qualifiedOperatingPointCandidate", 350);
    capacity.putArray("provisionalSoakCandidates").add(300).add(200).add(100);
    capacity.put("qualifiedOperatingPoint", firstAttemptSaturates ? 200 : 300);

    ObjectNode soak = qualification.putObject("soak");
    soak.put("durationSeconds", 1_800);
    soak.put("promotionPolicyId", "M10Q2_DESCENDING_FULL_DURATION_FIRST_PASS");
    ArrayNode attempts = soak.putArray("attempts");
    addSoakAttempt(
        attempts, 1, 300, firstAttemptSaturates, firstAttemptSaturates ? "SATURATED" : "QUALIFIED");
    if (firstAttemptSaturates) {
      addSoakAttempt(attempts, 2, 200, false, "QUALIFIED");
      soak.put("qualifiedAttemptNumber", 2);
      soak.put("qualifiedPointId", soakPointId(2, 200));
    } else {
      soak.put("qualifiedAttemptNumber", 1);
      soak.put("qualifiedPointId", soakPointId(1, 300));
    }
    return qualification;
  }

  private static void addSoakAttempt(
      ArrayNode attempts, int attemptNumber, long rate, boolean saturated, String outcome) {
    ObjectNode point = ratePoint(soakPointId(attemptNumber, rate), rate, saturated);
    point.put("phase", "SOAK");
    point.put("sweep", 0);
    point.put("ladderPermille", 0);
    attempts
        .addObject()
        .put("attemptNumber", attemptNumber)
        .put("outcome", outcome)
        .set("point", point);
  }

  private static String soakPointId(int attemptNumber, long rate) {
    return "qop-soak-attempt-%02d-rate-%08d".formatted(attemptNumber, rate);
  }

  private static Map<String, JsonNode> soakSummaries(JsonNode qualification) {
    Map<String, JsonNode> summaries = new java.util.LinkedHashMap<>();
    for (JsonNode attempt : qualification.path("soak").path("attempts")) {
      JsonNode point = attempt.path("point");
      summaries.put(point.path("pointId").stringValue(), point);
    }
    return Map.copyOf(summaries);
  }

  private static void markSaturated(ObjectNode point) {
    ObjectNode cutAttempts = (ObjectNode) point.path("observationCut").path("attemptAccounting");
    cutAttempts.put("offers", 11);
    cutAttempts.put("overloaded", 1);
    ObjectNode terminalAttempts = (ObjectNode) point.path("attempts");
    terminalAttempts.put("offers", 11);
    terminalAttempts.put("overloaded", 1);
    ((ObjectNode) point.path("logical")).put("overloaded", 1);
    point.put("saturated", true);
    ((ArrayNode) point.path("saturationReasons")).add("OVERLOAD_REJECTION");
  }

  private static ObjectNode calibrationFixture() {
    ObjectNode qualification = JsonSupport.MAPPER.createObjectNode();
    ObjectNode calibration = qualification.putObject("calibration");
    calibration.put("mode", "UNPACED");
    calibration.put("purpose", "RATE_SELECTION_ONLY");
    calibration.put("elapsedNanos", 2_000_000_000L);
    calibration.put("logicalOperations", 2_100L);
    calibration.put("durableCompletions", 2_000L);
    calibration.put("checkpointCount", 0);
    calibration.put("referenceRate", 1_000L);
    ArrayNode sweeps = qualification.putArray("sweeps");
    List<Integer> ladder = List.of(250, 500, 700, 850, 1000, 1150, 1350, 1600);
    for (int sweep = 1; sweep <= 3; sweep++) {
      ArrayNode points = sweeps.addArray();
      for (int permille : ladder) {
        points
            .addObject()
            .put("sweep", sweep)
            .put("ladderPermille", permille)
            .put("offeredRate", permille);
      }
    }
    return qualification;
  }

  private static ObjectNode ratePoint(String id, long rate, boolean saturated) {
    ObjectNode point = JsonSupport.MAPPER.createObjectNode();
    point.put("pointId", id);
    point.put("phase", "MEASUREMENT");
    point.put("ladderPermille", 0);
    point.put("offeredRate", rate);
    ObjectNode logical = point.putObject("logical");
    logical.put("initiallyAdmitted", 10);
    logical.put("terminalCompletions", 10);
    logical.put("overloaded", saturated ? 1 : 0);
    logical.put("closedOrInvalid", 0);
    point.put("startingBacklog", 0);
    point.put("endingBacklog", 0);
    point.put("p99QueueDepth", 0);
    ObjectNode observation = point.putObject("observationCut");
    ObjectNode attempts = observation.putObject("attemptAccounting");
    attempts.put("offers", saturated ? 11 : 10);
    attempts.put("admitted", 10);
    attempts.put("overloaded", saturated ? 1 : 0);
    attempts.put("closedOrInvalid", 0);
    ObjectNode variants = attempts.putObject("submissionResultVariants");
    for (String variant :
        List.of(
            "NEW_DURABLY_APPLIED",
            "DUPLICATE_REPLAYED",
            "STRUCTURAL_REJECTED",
            "PREFLIGHT_REJECTED",
            "CHECKPOINT_REQUIRED",
            "DURABILITY_UNKNOWN",
            "FAILED_CLOSED")) {
      variants.put(variant, "NEW_DURABLY_APPLIED".equals(variant) ? 10 : 0);
    }
    attempts.put("explicitServiceFailures", 0);
    attempts.put("pending", 0);
    point.set("attempts", attempts.deepCopy());
    observation.put("startingBacklog", 0);
    observation.put("endingBacklog", 0);
    observation.put("p99QueueDepth", 0);
    observation.put("postCutOverloaded", 0);
    point.put("saturated", saturated);
    ArrayNode reasons = point.putArray("saturationReasons");
    if (saturated) reasons.add("OVERLOAD_REJECTION");
    return point;
  }

  private static ObjectNode jmhInventory(Path file, String sourceCommit) throws Exception {
    ObjectNode qualification = JsonSupport.MAPPER.createObjectNode();
    ObjectNode inventory = qualification.putObject("artifacts").putObject("diagnosticJmh");
    inventory.put("relativePath", "diagnostics/core-sample-time.json");
    inventory.put("bytes", Files.size(file));
    inventory.put("sha256", Hashing.sha256Hex(Files.readAllBytes(file)));
    inventory.put("jmhVersion", "1.37");
    inventory.put("mode", "sample");
    inventory.put("resultScope", "DIAGNOSTIC_ONLY");
    inventory.put("eligibleForCapacityEnvelope", false);
    inventory.put("sourceCommit", sourceCommit);
    inventory.put("benchmarkClassesSha256", "b".repeat(64));
    qualification
        .putObject("runtimeProvenance")
        .put("matchingBenchmarkClassesSha256", "b".repeat(64));
    return qualification;
  }

  private static ObjectNode jmhResult(String method) {
    ObjectNode result = JsonSupport.MAPPER.createObjectNode();
    result.put("jmhVersion", "1.37");
    result.put(
        "benchmark", "io.github.lchareln.cex.matching.benchmark.CoreMatchingBenchmark." + method);
    result.put("mode", "sample");
    result.put("threads", 1);
    result.put("forks", 2);
    result.put("warmupIterations", 3);
    result.put("warmupTime", "2 s");
    result.put("warmupBatchSize", 1);
    result.put("measurementIterations", 5);
    result.put("measurementTime", "3 s");
    result.put("measurementBatchSize", 1);
    ObjectNode metric = result.putObject("primaryMetric");
    metric.put("scoreUnit", "ns/op");
    ArrayNode histogram = metric.putArray("rawDataHistogram");
    for (int fork = 0; fork < 2; fork++) {
      ArrayNode iterations = histogram.addArray();
      for (int iteration = 0; iteration < 5; iteration++) {
        iterations.addArray().addArray().add(42.0).add(3);
      }
    }
    return result;
  }
}
