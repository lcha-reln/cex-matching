package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

final class M10QualificationSchemaTest {
  private static final String BUNDLE_ENV = "M10_SCHEMA_PROBE_BUNDLE";
  private static final String QUALIFICATION_SCHEMA_PATH =
      "schemas/matching.m10.qualification.v2.schema.json";
  private static final Map<String, String> STREAM_DEFINITIONS =
      Map.of(
          "raw-arrivals/part-00000.jsonl.gz", "rawArrivalRecord",
          "raw-completions/part-00000.jsonl.gz", "rawCompletionRecord",
          "raw-queue/part-00000.jsonl.gz", "rawQueueRecord",
          "resources/part-00000.jsonl.gz", "resourceObservationRecord",
          "raw-phase-cuts/part-00000.jsonl.gz", "rawPhaseCutRecord",
          "accepted-trace/part-00000.jsonl.gz", "acceptedTraceRecord");

  @Test
  void configuredBundleValidatesQualificationEveryRawShapeAndRecovery() throws Exception {
    Path root = Path.of(System.getProperty("matching.repositoryRoot"));
    JsonNode schema =
        JsonSupport.parse(Files.readAllBytes(root.resolve(QUALIFICATION_SCHEMA_PATH)));
    String configured = System.getenv(BUNDLE_ENV);
    assumeTrue(
        configured != null && !configured.isBlank(),
        () -> BUNDLE_ENV + " is only set by the focused artifact-schema probe");
    Path bundle = Path.of(configured).toAbsolutePath().normalize();

    ObjectNode qualification =
        (ObjectNode) JsonSupport.parse(Files.readAllBytes(bundle.resolve("qualification.json")));
    JsonSupport.validate(qualification, schema.toString(), true);
    M10ReleaseBundleVerifier.Result verifiedSmoke =
        new M10ReleaseBundleVerifier()
            .verifySmoke(root, bundle, qualification.path("source").path("commit").stringValue());
    assertTrue(verifiedSmoke.rawRecords() > 0, "independent smoke verifier read no raw rows");
    ObjectNode promotedSmoke = qualification.deepCopy();
    promotedSmoke.put("eligibleForReleaseEvidence", true);
    assertThrows(
        FixtureSchemaException.class,
        () -> JsonSupport.validate(promotedSmoke, schema.toString(), true));

    for (Map.Entry<String, String> stream : STREAM_DEFINITIONS.entrySet()) {
      JsonSupport.validate(
          first(bundle.resolve(stream.getKey())), fragment(schema, stream.getValue()), true);
    }

    Set<String> maintenanceKinds = new LinkedHashSet<>();
    try (BufferedReader reader = gzip(bundle.resolve("raw-maintenance/part-00000.jsonl.gz"))) {
      while (maintenanceKinds.size() < 2) {
        String line = reader.readLine();
        assumeTrue(line != null, "maintenance stream must contain admission and completion rows");
        JsonNode record = JsonSupport.parse(line.getBytes(StandardCharsets.UTF_8));
        JsonSupport.validate(record, fragment(schema, "rawMaintenanceRecord"), true);
        maintenanceKinds.add(record.path("eventKind").stringValue());
      }
    }
    assertEquals(Set.of("ADMISSION", "COMPLETION"), maintenanceKinds);

    JsonSupport.validate(
        JsonSupport.parse(Files.readAllBytes(bundle.resolve("recovery.json"))),
        fragment(schema, "recoveryCollection"),
        true);

    ObjectNode inventedArrival =
        (ObjectNode) first(bundle.resolve("raw-arrivals/part-00000.jsonl.gz"));
    inventedArrival.put("actualOfferNanos", 1);
    inventedArrival.put("queueObservationNanos", 1);
    inventedArrival.put("queueObservationKind", "ADMISSION_GATE_DECISION_NO_SEPARATE_TIMESTAMP");
    assertThrows(
        FixtureSchemaException.class,
        () -> JsonSupport.validate(inventedArrival, fragment(schema, "rawArrivalRecord"), true));

    ObjectNode nonFirstArrival =
        (ObjectNode) nth(bundle.resolve("raw-arrivals/part-00000.jsonl.gz"), 2);
    M10ReleaseBundleVerifier.verifyExactRawFields(nonFirstArrival, "raw-arrivals");
    nonFirstArrival.put("inventedLaterRowField", true);
    IllegalStateException exactFieldFailure =
        assertThrows(
            IllegalStateException.class,
            () -> M10ReleaseBundleVerifier.verifyExactRawFields(nonFirstArrival, "raw-arrivals"));
    assertTrue(
        exactFieldFailure.getMessage().contains("unexpected=[inventedLaterRowField]"),
        exactFieldFailure::getMessage);

    ObjectNode inventedQueue = (ObjectNode) first(bundle.resolve("raw-queue/part-00000.jsonl.gz"));
    inventedQueue.put("offeredNanos", 1);
    inventedQueue.put("observedNanos", 1);
    inventedQueue.put("observationLagFromOfferNanos", 0);
    assertThrows(
        FixtureSchemaException.class,
        () -> JsonSupport.validate(inventedQueue, fragment(schema, "rawQueueRecord"), true));
    ObjectNode inventedCompletion =
        (ObjectNode) first(bundle.resolve("raw-completions/part-00000.jsonl.gz"));
    inventedCompletion.put("terminalNanos", 1);
    assertThrows(
        FixtureSchemaException.class,
        () ->
            JsonSupport.validate(
                inventedCompletion, fragment(schema, "rawCompletionRecord"), true));

    ObjectNode release = syntheticRelease(qualification);
    JsonSupport.validate(release, schema.toString(), true);
    ObjectNode dirtyRelease = release.deepCopy();
    ((ObjectNode) dirtyRelease.path("runtimeProvenance")).put("repositoryDirty", true);
    assertThrows(
        FixtureSchemaException.class,
        () -> JsonSupport.validate(dirtyRelease, schema.toString(), true));
    ObjectNode displayMode = release.deepCopy();
    ((ObjectNode) displayMode.path("artifacts").path("diagnosticJmh")).put("mode", "SampleTime");
    assertThrows(
        FixtureSchemaException.class,
        () -> JsonSupport.validate(displayMode, schema.toString(), true));
    ((ObjectNode) release.path("artifacts")).remove("diagnosticJmh");
    assertThrows(
        FixtureSchemaException.class, () -> JsonSupport.validate(release, schema.toString(), true));
  }

  @Test
  void v2AcceptsMultipleSoakAttemptsAndLeavesPromotionRelationsToJavaVerifier() throws Exception {
    Path root = Path.of(System.getProperty("matching.repositoryRoot"));
    JsonNode schema =
        JsonSupport.parse(Files.readAllBytes(root.resolve(QUALIFICATION_SCHEMA_PATH)));

    assertEquals(
        "matching.m10.qualification.v2",
        schema.path("properties").path("schemaVersion").path("const").stringValue());
    assertEquals(
        "M10Q2",
        schema.path("properties").path("qualificationRuntimePolicyId").path("const").stringValue());
    assertEquals(
        "M10Q2",
        schema
            .path("$defs")
            .path("profileSharedProperties")
            .path("properties")
            .path("qualificationRuntimePolicyId")
            .path("const")
            .stringValue());
    assertEquals(
        "M10Q2",
        schema
            .path("$defs")
            .path("qualificationRuntime")
            .path("properties")
            .path("policyId")
            .path("const")
            .stringValue());
    assertEquals(
        "#/$defs/point",
        schema
            .path("$defs")
            .path("soakAttempt")
            .path("properties")
            .path("point")
            .path("$ref")
            .stringValue());

    ObjectNode soak = JsonSupport.MAPPER.createObjectNode();
    soak.put("durationSeconds", 1_800);
    soak.put("promotionPolicyId", "M10Q2_DESCENDING_FULL_DURATION_FIRST_PASS");
    ArrayNode attempts = soak.putArray("attempts");
    attempts.add(soakAttempt(1, "SATURATED"));
    attempts.add(soakAttempt(2, "QUALIFIED"));
    soak.put("qualifiedAttemptNumber", 2);
    soak.put("qualifiedPointId", "qop-soak-attempt-02-rate-00000100");

    String structuralSoakSchema = structuralSoakFragment(schema);
    assertDoesNotThrow(
        () -> JsonSupport.validate(soak, structuralSoakSchema, true),
        "the v2 representation must admit retained saturated attempts before qualification");

    ObjectNode relationallyWrong = soak.deepCopy();
    ((ObjectNode) relationallyWrong.path("attempts").path(0)).put("attemptNumber", 2);
    ((ObjectNode) relationallyWrong.path("attempts").path(1)).put("attemptNumber", 1);
    relationallyWrong.put("qualifiedAttemptNumber", 1);
    relationallyWrong.put("qualifiedPointId", "qop-soak-attempt-01-rate-00000200");
    // JSON Schema owns the closed representation. Contiguous ordering, descending rates, the
    // final-attempt QUALIFIED rule, and qualified-point equality remain Java-verifier judgments.
    assertDoesNotThrow(
        () -> JsonSupport.validate(relationallyWrong, structuralSoakSchema, true),
        "cross-field promotion relations must remain the Java verifier's responsibility");

    ObjectNode systemError = soak.deepCopy();
    ((ObjectNode) systemError.path("attempts").path(0)).put("outcome", "SYSTEM_ERROR");
    assertThrows(
        FixtureSchemaException.class,
        () -> JsonSupport.validate(systemError, structuralSoakSchema, true));

    ObjectNode emptyAttempts = soak.deepCopy();
    ((ArrayNode) emptyAttempts.path("attempts")).removeAll();
    assertThrows(
        FixtureSchemaException.class,
        () -> JsonSupport.validate(emptyAttempts, structuralSoakSchema, true));

    ObjectNode extraAttemptField = soak.deepCopy();
    ((ObjectNode) extraAttemptField.path("attempts").path(0)).put("systemError", true);
    assertThrows(
        FixtureSchemaException.class,
        () -> JsonSupport.validate(extraAttemptField, structuralSoakSchema, true));
  }

  @Test
  void v2CapacityRequiresPositiveNonEmptyUniqueProvisionalCandidates() throws Exception {
    Path root = Path.of(System.getProperty("matching.repositoryRoot"));
    JsonNode schema =
        JsonSupport.parse(Files.readAllBytes(root.resolve(QUALIFICATION_SCHEMA_PATH)));
    String capacitySchema = fragment(schema, "capacity");
    ObjectNode capacity = JsonSupport.MAPPER.createObjectNode();
    capacity.putArray("sweepKnees").add(300);
    capacity.put("publishedKnee", 300);
    capacity.put("qualifiedOperatingPointCandidate", 200);
    capacity.putArray("provisionalSoakCandidates").add(200).add(100);
    capacity.put("qualifiedOperatingPoint", 100);
    JsonSupport.validate(capacity, capacitySchema, true);

    ObjectNode empty = capacity.deepCopy();
    ((ArrayNode) empty.path("provisionalSoakCandidates")).removeAll();
    assertThrows(
        FixtureSchemaException.class, () -> JsonSupport.validate(empty, capacitySchema, true));

    ObjectNode duplicate = capacity.deepCopy();
    ((ArrayNode) duplicate.path("provisionalSoakCandidates")).removeAll().add(100).add(100);
    assertThrows(
        FixtureSchemaException.class, () -> JsonSupport.validate(duplicate, capacitySchema, true));

    ObjectNode nonPositive = capacity.deepCopy();
    ((ArrayNode) nonPositive.path("provisionalSoakCandidates")).removeAll().add(0);
    assertThrows(
        FixtureSchemaException.class,
        () -> JsonSupport.validate(nonPositive, capacitySchema, true));
  }

  private static ObjectNode soakAttempt(int attemptNumber, String outcome) {
    ObjectNode attempt = JsonSupport.MAPPER.createObjectNode();
    attempt.put("attemptNumber", attemptNumber);
    attempt.put("outcome", outcome);
    attempt.putObject("point");
    return attempt;
  }

  private static String structuralSoakFragment(JsonNode schema) {
    ObjectNode wrapper = JsonSupport.MAPPER.createObjectNode();
    wrapper.put("$schema", "https://json-schema.org/draft/2020-12/schema");
    wrapper.put("$ref", "#/$defs/soak");
    ObjectNode definitions = (ObjectNode) schema.path("$defs").deepCopy();
    definitions.set("point", JsonSupport.MAPPER.createObjectNode());
    wrapper.set("$defs", definitions);
    return wrapper.toString();
  }

  private static ObjectNode syntheticRelease(ObjectNode smoke) {
    ObjectNode release = smoke.deepCopy();
    release.put("profileId", "RELEASE_QUALIFICATION");
    release.put("resultScope", "RELEASE_QUALIFICATION");
    release.put("eligibleForReleaseEvidence", true);
    ((ObjectNode) release.path("runtimeProvenance")).put("repositoryDirty", false);
    ObjectNode profile = (ObjectNode) release.path("profile");
    profile.put("id", "RELEASE_QUALIFICATION");
    profile.put("resultScope", "RELEASE_QUALIFICATION");
    profile.put("eligibleForReleaseEvidence", true);
    profile.put("calibrationSeconds", 20);
    profile.put("sweeps", 3);
    profile.put("warmupSecondsPerRate", 10);
    profile.put("measurementSecondsPerRate", 30);
    profile.put("soakSeconds", 1800);
    ((ObjectNode) release.path("calibration")).put("elapsedNanos", 20_000_000_000L);

    ArrayNode sweeps = (ArrayNode) release.path("sweeps");
    ArrayNode firstSweep = (ArrayNode) sweeps.path(0);
    sweeps.add(rewriteSweep(firstSweep, 2));
    sweeps.add(rewriteSweep(firstSweep, 3));
    ArrayNode knees = (ArrayNode) release.path("capacity").path("sweepKnees");
    knees.add(knees.path(0).longValue()).add(knees.path(0).longValue());
    ((ObjectNode) release.path("soak")).put("durationSeconds", 1800);
    ObjectNode raw = (ObjectNode) release.path("rawRecomputation");
    raw.put("phaseCutRecords", 49);
    raw.put("reconstructedRecoveryTraces", 25);
    raw.put("verifiedPublishedPoints", 25);

    ObjectNode artifacts = (ObjectNode) release.path("artifacts");
    ((ObjectNode) artifacts.path("recoveryJson")).put("recordCount", 25);
    ObjectNode jmh = artifacts.putObject("diagnosticJmh");
    jmh.put("relativePath", "diagnostics/core-sample-time.json");
    jmh.put("bytes", 1);
    jmh.put("sha256", "0".repeat(64));
    jmh.put("jmhVersion", "1.37");
    jmh.put("harness", "JMH");
    jmh.put("mode", "sample");
    jmh.putArray("benchmarks")
        .add(
            "io.github.lchareln.cex.matching.benchmark.CoreMatchingBenchmark."
                + "restingMakerThenMatchingTaker")
        .add(
            "io.github.lchareln.cex.matching.benchmark.CoreMatchingBenchmark."
                + "canonicalEnvelopeDecode");
    jmh.put("resultScope", "DIAGNOSTIC_ONLY");
    jmh.put("eligibleForCapacityEnvelope", false);
    jmh.put("sourceCommit", release.path("source").path("commit").stringValue());
    jmh.put(
        "benchmarkClassesSha256",
        release.path("runtimeProvenance").path("matchingBenchmarkClassesSha256").stringValue());
    return release;
  }

  private static ArrayNode rewriteSweep(ArrayNode original, int sweep) {
    ArrayNode result = JsonSupport.MAPPER.createArrayNode();
    for (JsonNode value : original) {
      ObjectNode point = (ObjectNode) value.deepCopy();
      point.put(
          "pointId",
          point.path("pointId").stringValue().replace("sweep-1-", "sweep-" + sweep + "-"));
      point.put("sweep", sweep);
      ObjectNode recovery = (ObjectNode) point.path("recovery");
      recovery.put(
          "recoveryTraceId",
          recovery
              .path("recoveryTraceId")
              .stringValue()
              .replace("sweep-1-", "sweep-" + sweep + "-"));
      result.add(point);
    }
    return result;
  }

  private static JsonNode first(Path path) throws IOException {
    return nth(path, 1);
  }

  private static JsonNode nth(Path path, int oneBasedLine) throws IOException {
    try (BufferedReader reader = gzip(path)) {
      String line = null;
      for (int index = 0; index < oneBasedLine; index++) {
        line = reader.readLine();
        if (line == null) {
          throw new IllegalStateException(
              "gzip stream has fewer than " + oneBasedLine + " rows: " + path);
        }
      }
      return JsonSupport.parse(line.getBytes(StandardCharsets.UTF_8));
    }
  }

  private static BufferedReader gzip(Path path) throws IOException {
    return new BufferedReader(
        new InputStreamReader(
            new GZIPInputStream(Files.newInputStream(path)), StandardCharsets.UTF_8));
  }

  private static String fragment(JsonNode schema, String definition) {
    ObjectNode wrapper = JsonSupport.MAPPER.createObjectNode();
    wrapper.put("$schema", "https://json-schema.org/draft/2020-12/schema");
    wrapper.put("$ref", "#/$defs/" + definition);
    wrapper.set("$defs", schema.path("$defs").deepCopy());
    return wrapper.toString();
  }
}
