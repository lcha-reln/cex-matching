package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

final class M10EvidenceWriterTest {
  @Test
  void cleanDualAnnotatedTagsPublishEveryArtifactExactlyOnce(@TempDir Path temporary) {
    Lab lab = createLab(temporary, true);
    AtomicInteger checks = new AtomicInteger();
    AtomicInteger releaseVerifications = new AtomicInteger();
    M10EvidenceWriter writer =
        new M10EvidenceWriter(
            (root, reports) -> {
              checks.incrementAndGet();
              return new M10CheckRunner.Result(M10CheckRunner.PASS, reports.resolve("check.json"));
            },
            (root, release, sourceCommit) -> {
              releaseVerifications.incrementAndGet();
              return releaseBundle(release);
            });

    M10EvidenceWriter.Result result =
        writer.write(
            lab.root(),
            lab.checks(),
            lab.release(),
            lab.evidence(),
            M10EvidenceWriter.UNIT_TAG,
            M10EvidenceWriter.PRODUCT_RELEASE);

    assertEquals(1, checks.get());
    assertEquals(3, releaseVerifications.get());
    assertEquals(42, result.rawRecords());
    JsonNode manifest = JsonSupport.parse(read(result.manifestPath()));
    assertEquals("M10", manifest.path("unit").stringValue());
    assertEquals("matching-0.5.0", manifest.path("productRelease").stringValue());
    assertEquals(
        M10EvidenceWriter.REQUIRED_CLAIMS,
        manifest.path("claims").valueStream().map(node -> node.path("id").stringValue()).toList());
    assertEquals(
        M10EvidenceWriter.LIMITATIONS,
        manifest.path("limitations").valueStream().map(JsonNode::stringValue).toList());

    List<String> paths = new ArrayList<>();
    for (JsonNode claim : manifest.path("claims")) {
      for (JsonNode artifact : claim.path("artifacts")) {
        String relative = artifact.path("path").stringValue();
        paths.add(relative);
        assertEquals(
            artifact.path("sha256").stringValue(),
            Hashing.sha256Hex(read(lab.evidence().resolve(relative))));
      }
    }
    assertEquals(paths.size(), new LinkedHashSet<>(paths).size());
    assertEquals(
        1 + M10CheckRunner.OUTPUTS.size() + releaseFiles(lab.release()).size(), paths.size());
    assertTrue(paths.contains("reports/release/raw-arrivals/part-00000.jsonl.gz"));
    assertTrue(paths.contains("reports/release/diagnostics/core-sample-time.json"));
    assertEquals(result.manifestSha256(), Hashing.sha256Hex(read(result.manifestPath())));
  }

  @Test
  void lightweightCompleteTagAndNonReleaseProfileFailClosed(@TempDir Path temporary) {
    Lab lightweight = createLab(temporary.resolve("lightweight"), false);
    IllegalStateException tagFailure =
        assertThrows(
            IllegalStateException.class,
            () ->
                writer()
                    .write(
                        lightweight.root(),
                        lightweight.checks(),
                        lightweight.release(),
                        lightweight.evidence(),
                        M10EvidenceWriter.UNIT_TAG,
                        M10EvidenceWriter.PRODUCT_RELEASE));
    assertTrue(tagFailure.getMessage().contains("not annotated"));
    assertFalse(Files.exists(lightweight.evidence().resolve("manifest.json")));

    Lab smoke = createLab(temporary.resolve("smoke"), true);
    ObjectNode qualification =
        (ObjectNode) JsonSupport.parse(read(smoke.release().resolve("qualification.json")));
    qualification.put("profileId", "CI_SMOKE");
    qualification.put("resultScope", "METHOD_SMOKE_ONLY");
    qualification.put("eligibleForReleaseEvidence", false);
    write(smoke.release().resolve("qualification.json"), JsonSupport.prettyBytes(qualification));
    IllegalStateException profileFailure =
        assertThrows(
            IllegalStateException.class,
            () ->
                writer()
                    .write(
                        smoke.root(),
                        smoke.checks(),
                        smoke.release(),
                        smoke.evidence(),
                        M10EvidenceWriter.UNIT_TAG,
                        M10EvidenceWriter.PRODUCT_RELEASE));
    assertTrue(profileFailure.getMessage().contains("not release eligible"));
    assertFalse(Files.exists(smoke.evidence().resolve("manifest.json")));
  }

  @Test
  void sourceReleaseMutationAtEitherBoundaryRemovesEvidence(@TempDir Path temporary) {
    Lab before = createLab(temporary.resolve("before"), true);
    Path beforeShard = before.release().resolve("raw-arrivals/part-00000.jsonl.gz");
    M10EvidenceWriter beforeWriter =
        new M10EvidenceWriter(
            checkExecutor(),
            M10EvidenceWriterTest::releaseBundle,
            new M10EvidenceWriter.BoundaryHook() {
              @Override
              public void beforeFinalVerification(Path root) {
                write(beforeShard, "mutated before publication\n".getBytes(StandardCharsets.UTF_8));
              }

              @Override
              public void beforePostPublishVerification(Path root) {}
            });
    IllegalStateException beforeFailure =
        assertThrows(
            IllegalStateException.class,
            () ->
                beforeWriter.write(
                    before.root(),
                    before.checks(),
                    before.release(),
                    before.evidence(),
                    M10EvidenceWriter.UNIT_TAG,
                    M10EvidenceWriter.PRODUCT_RELEASE));
    assertTrue(beforeFailure.getMessage().contains("artifact"));
    assertFalse(Files.exists(before.evidence().resolve("manifest.json")));

    Lab after = createLab(temporary.resolve("after"), true);
    Path afterShard = after.release().resolve("raw-arrivals/part-00000.jsonl.gz");
    M10EvidenceWriter afterWriter =
        new M10EvidenceWriter(
            checkExecutor(),
            M10EvidenceWriterTest::releaseBundle,
            new M10EvidenceWriter.BoundaryHook() {
              @Override
              public void beforeFinalVerification(Path root) {}

              @Override
              public void beforePostPublishVerification(Path root) {
                write(afterShard, "mutated after publication\n".getBytes(StandardCharsets.UTF_8));
              }
            });
    assertThrows(
        IllegalStateException.class,
        () ->
            afterWriter.write(
                after.root(),
                after.checks(),
                after.release(),
                after.evidence(),
                M10EvidenceWriter.UNIT_TAG,
                M10EvidenceWriter.PRODUCT_RELEASE));
    assertFalse(Files.exists(after.evidence().resolve("manifest.json")));
  }

  private static M10EvidenceWriter writer() {
    return new M10EvidenceWriter(checkExecutor(), M10EvidenceWriterTest::releaseBundle);
  }

  private static M10EvidenceWriter.CheckExecutor checkExecutor() {
    return (root, reports) ->
        new M10CheckRunner.Result(M10CheckRunner.PASS, reports.resolve("check.json"));
  }

  private static M10EvidenceWriter.ReleaseBundle releaseBundle(
      Path root, Path release, String sourceCommit) {
    return releaseBundle(release);
  }

  private static M10EvidenceWriter.ReleaseBundle releaseBundle(Path release) {
    return new M10EvidenceWriter.ReleaseBundle(
        JsonSupport.parse(read(release.resolve("qualification.json"))), releaseFiles(release), 42);
  }

  private static List<Path> releaseFiles(Path release) {
    try (var paths = Files.walk(release)) {
      return paths
          .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
          .map(release::relativize)
          .sorted()
          .toList();
    } catch (IOException failure) {
      throw new IllegalStateException("cannot inventory fake M10 release", failure);
    }
  }

  private static Lab createLab(Path temporary, boolean annotatedCompleteTag) {
    Path root = temporary.resolve("repo");
    Path source = Path.of(System.getProperty("matching.repositoryRoot"));
    copy(
        source.resolve("schemas/cex.lab-evidence.v1.schema.json"),
        root.resolve("schemas/cex.lab-evidence.v1.schema.json"));
    copy(
        source.resolve("schemas/matching.m10.check.v2.schema.json"),
        root.resolve("schemas/matching.m10.check.v2.schema.json"));
    copy(
        source.resolve(M10ReleaseBundleVerifier.SCHEMA_PATH),
        root.resolve(M10ReleaseBundleVerifier.SCHEMA_PATH));
    copy(
        source.resolve(M10StartCheckRunner.WORKLOAD_SCHEMA_PATH),
        root.resolve(M10StartCheckRunner.WORKLOAD_SCHEMA_PATH));
    copy(
        source.resolve(M10StartCheckRunner.WORKLOAD_PATH),
        root.resolve(M10StartCheckRunner.WORKLOAD_PATH));
    write(root.resolve(".gitignore"), "/build/\n".getBytes(StandardCharsets.UTF_8));
    write(root.resolve("course.properties"), course().getBytes(StandardCharsets.UTF_8));
    git(root, "init", "-q");
    git(root, "config", "user.name", "M10 Evidence Test");
    git(root, "config", "user.email", "m10-evidence@example.invalid");
    git(root, "config", "commit.gpgsign", "false");
    git(root, "add", ".");
    git(root, "commit", "-q", "-m", "test: freeze M10 evidence inputs");
    String sourceCommit = git(root, "rev-parse", "HEAD").strip();
    git(root, "tag", "-a", "course/m09-complete", "-m", "test: inherited M09");
    git(root, "tag", "-a", "course/m10-start", "-m", "test: M10 start");
    if (annotatedCompleteTag) {
      git(root, "tag", "-a", M10EvidenceWriter.UNIT_TAG, "-m", "test: M10 complete");
    } else {
      git(root, "tag", M10EvidenceWriter.UNIT_TAG);
    }
    git(root, "tag", "-a", M10EvidenceWriter.PRODUCT_RELEASE, "-m", "test: M10 release");

    Path checks = root.resolve("build/reports/m10");
    for (String name : M10CheckRunner.OUTPUTS) {
      write(
          checks.resolve(name),
          "check.json".equals(name)
              ? JsonSupport.prettyBytes(check(sourceCommit))
              : (name.endsWith(".json")
                  ? "{\"schemaVersion\":\"m10-test-artifact\",\"status\":\"PASS\"}\n"
                      .getBytes(StandardCharsets.UTF_8)
                  : ("M10 test artifact " + name + "\n").getBytes(StandardCharsets.UTF_8)));
    }
    Path release = root.resolve("build/reports/m10-release");
    write(
        release.resolve("qualification.json"),
        JsonSupport.prettyBytes(qualification(sourceCommit)));
    write(
        release.resolve("recovery.json"),
        "{\"schemaVersion\":\"matching.m10.recovery.v1\"}\n".getBytes(StandardCharsets.UTF_8));
    write(
        release.resolve("raw-arrivals/part-00000.jsonl.gz"),
        "fake deterministic gzip shard\n".getBytes(StandardCharsets.UTF_8));
    write(
        release.resolve("diagnostics/core-sample-time.json"),
        "[{\"benchmark\":\"m10-test\",\"mode\":\"sample\",\"rawDataHistogram\":[[[1.0,1]]]}]\n"
            .getBytes(StandardCharsets.UTF_8));
    return new Lab(root, checks, release, root.resolve("build/lab-evidence/M10"));
  }

  private static ObjectNode check(String sourceCommit) {
    ObjectNode root = JsonSupport.MAPPER.createObjectNode();
    root.put("schemaVersion", "matching.m10.check.v2");
    root.put("unit", "M10");
    root.put("status", "PASS");
    root.put("contractPlanVersion", "0.12");
    root.put(
        "objective",
        "Add bounded local admission and honest open-loop performance qualification without"
            + " changing durable matching semantics.");
    root.putObject("source").put("commit", sourceCommit).put("dirty", false);
    ObjectNode course = root.putObject("courseDeclaration");
    course.put("case", "high-availability-cex");
    course.put("profile", "SPOT-CEX-1.0");
    course.put("planVersion", "0.12");
    course.put("project", "matching");
    course.put("unit", "M10");
    course.put("lifecycle", "COMPLETE");
    course.put("designDepth", "IMPLEMENTED");
    course.put("startRef", "course/m10-start");
    course.put("completeRef", "course/m10-complete");
    course.put("m10Check.expectedStatus", "PASS");
    course.put("evidencePath", "build/lab-evidence/M10/manifest.json");
    ObjectNode inherited = root.putObject("inheritedM09");
    inherited.put("unit", "M09");
    inherited.put("completeRef", "course/m09-complete");
    inherited.put("status", "PASS");
    inherited.put("fixedScenarios", 22);
    inherited.put("generatedOperations", 3840);
    inherited.put("mutantsKilled", 12);
    inherited.put("semanticSource", "CURRENT_HEAD_COMPILED_PRODUCTION_CLASSES");
    inherited.put("semanticSourceCommit", sourceCommit);
    inherited.put("sourceOnlyArchitectureBaseline", "course/m09-complete");
    inherited.put("sourceOnlyArchitectureBaselineCommit", sourceCommit);
    inherited.put("sourceOnlyArchitectureSupersededBy", "M10_ARCHITECTURE_GATE");
    ObjectNode workload = root.putObject("workloadProfile");
    workload.put("sha256", M10CheckRunner.WORKLOAD_SHA256);
    workload.put("seed", "6010");
    workload.put("fixedScenarios", 20);
    workload.put("generatedHistories", 64);
    workload.put("actionsPerHistory", 256);
    workload.put("generatedActions", 16384);
    workload.put("lanes", 4);
    workload.put("queueCapacity", 64);
    workload.put("coverageObligations", 28);
    workload.put("requiredMutants", 12);
    workload.put("schemaProbes", 8);
    ObjectNode runtime = root.putObject("qualificationRuntime");
    runtime.put("policyId", "M10Q1");
    runtime.put("scope", "M10_DEDICATED_NOT_M09_DEFAULT");
    runtime.putObject("m09Default").put("maxSuffixRecords", 64).put("maxSuffixBytes", 1_048_576);
    runtime
        .putObject("finiteRecoveryBudget")
        .put("maxSuffixRecords", 1_000_000)
        .put("maxSuffixBytes", 1_073_741_824);
    runtime.put("proactiveCheckpointOffsetNanos", 100_000_000);
    runtime.put("proactiveCheckpointAdmissionLagMaxNanos", 10_000_000);
    runtime.put("plannedRecordCeilingBytes", 1_024);
    runtime
        .putObject("phaseBudgetPreflight")
        .put(
            "prefixRecords",
            "START_SUFFIX_PLUS_ARRIVALS_SCHEDULED_BEFORE_CHECKPOINT_ADMISSION_DEADLINE_PLUS_QUEUE_CAPACITY_PLUS_ONE_OWNER")
        .put(
            "postCheckpointSuffixRecords",
            "ALL_PLANNED_DURABLE_ARRIVALS_PLUS_QUEUE_CAPACITY_PLUS_ONE_OWNER")
        .put("validatePrefixAndSuffixSeparately", true);
    runtime
        .putObject("scheduler")
        .put("initialArrivalThread", "DEDICATED_NO_COMPLETION_CHECKPOINT_OR_ARTIFACT_IO")
        .put("coordinator", "ASYNC_COMPLETION_CHECKPOINT_RETRY_AND_ARTIFACT_IO")
        .put("scheduledObservationCutDoesNotMove", true)
        .put("producerClosureGraceMaxNanos", 250_000_000)
        .put("allScheduledArrivalsMaterialized", true)
        .put("allAdmissionDecisionsWithinLagLimits", true)
        .put("p99ProducerLagMaxNanos", 50_000_000)
        .put("maxProducerLagMaxNanos", 250_000_000)
        .put("observationCutLagMaxNanos", 10_000_000);
    runtime
        .putObject("rawTimeContract")
        .put("admissionTimestamp", "admissionDecisionNanos")
        .put("admissionObservationKind", "ADMISSION_GATE_DECISION")
        .put("completionTimestamp", "ownerCompletedNanos")
        .put("completionTimeOrigin", "OWNER_COMPLETED_UNDER_GATE");
    runtime.put(
        "observationCut",
        "IMMUTABLE_SCHEDULED_WINDOW_END_RAW_RECONSTRUCTED_BEFORE_PRODUCER_CLOSURE_AND_TERMINAL_DRAIN");
    runtime.put("terminalDrain", "ZERO_PENDING_BEFORE_RECOVERY");
    runtime
        .putObject("resourceSampling")
        .put("targetCadenceNanos", 1_000_000_000)
        .put("maximumScheduledGapNanos", 2_000_000_000)
        .put("scope", "SCHEDULED_WINDOW_THROUGH_TERMINAL_DRAIN")
        .put("cumulativeCounters", "MONOTONIC_NON_DECREASING")
        .put("gauges", "NON_NEGATIVE_NOT_CUMULATIVE");
    runtime
        .putObject("directReplay")
        .put("runtimeConfig", "M08_LEGACY_UNBOUNDED_NO_SNAPSHOT")
        .put("purpose", "FRESH_ORDERED_APPLY_DIAGNOSTIC_ONLY");
    root.putObject("fixed").put("scenarios", 20).put("status", "PASS");
    ObjectNode generator = root.putObject("generator");
    generator.put("algorithm", "splitmix64-v1");
    generator.put("histories", 64);
    generator.put("actionsPerHistory", 256);
    generator.put("actions", 16384);
    generator.put("executedActions", 16384);
    generator.put("comparisons", 16384);
    generator.put("ledgerChecks", 16384);
    generator.put("terminalReconciliations", 64);
    generator.put("lanes", 4);
    generator.put("byteExactRegeneration", true);
    generator.put("freshModelPerHistory", true);
    generator.put("terminalLedgersReconcile", true);
    generator.put("digest", "1".repeat(64));
    ObjectNode admission = root.putObject("admissionService");
    admission.put("bounded", true);
    admission.put("singleOwnerWorker", true);
    admission.put("enqueueIsAck", false);
    admission.put("overloadBeforeWalAndIdentity", true);
    admission.put("exactSubmissionResultPassThrough", true);
    admission.put("checkpointAttemptMaintenanceLogicalLedgersSeparate", true);
    admission.put("terminalAccountingReconciles", true);
    ObjectNode smoke = root.putObject("methodSmoke");
    smoke.put("profileId", "CI_SMOKE");
    smoke.put("resultScope", "METHOD_SMOKE_ONLY");
    smoke.put("eligibleForReleaseEvidence", false);
    smoke.put("evidenceMode", "REAL_CI_SMOKE_BUNDLE");
    smoke.put("methodIsomorphic", true);
    smoke.put("latencyOrigin", "SCHEDULED_ARRIVAL");
    smoke.put("rawArrivals", 512);
    smoke.put("rawCompletions", 512);
    smoke.put("verifiedRawRecords", 2_048);
    smoke.put("verifiedBundleFiles", 10);
    smoke.put("qualificationSha256", "a".repeat(64));
    smoke.put("percentileRankRule", "NEAREST_RANK_CEIL_Q_TIMES_N");
    smoke.put("percentilesRecomputed", true);
    smoke.put("resourceDimensionsPresent", true);
    smoke.put("aboveKneeRetained", true);
    smoke.put("sweepKnee", 10);
    smoke.put("qopCandidate", 7);
    smoke.put("qop", 7);
    smoke.put("deterministicDiagnosticEvidenceMode", "MODEL_ONLY");
    smoke.put("deterministicDiagnosticMethodIsomorphic", false);
    smoke.put("releaseThroughputClaim", false);
    root.putObject("loadRecovery")
        .put("realLocalRuntimeExact", true)
        .put("methodModelExact", true)
        .put("releaseProfileStillRequired", true);
    root.putObject("coverage").put("required", 28).put("observed", 28);
    root.putObject("mutants")
        .put("required", 12)
        .put("killed", 12)
        .put("classification", "STUDENT_FAILURE")
        .put("systemErrorCountedAsKill", false)
        .put("systemErrorControls", 3);
    root.putObject("releaseBoundary")
        .put("ordinaryCheckProfile", "CI_SMOKE")
        .put("ordinaryCheckScope", "METHOD_SMOKE_ONLY")
        .put("ordinaryCheckEligibleForReleaseEvidence", false)
        .put("fullReleaseProfileRequired", true)
        .put("fullReleaseSoakSeconds", 1800)
        .put("fullReleaseExecutedByThisCheck", false)
        .put("noReleaseNumbersFabricated", true);
    root.putObject("releaseTarget")
        .put("unitTag", "course/m10-complete")
        .put("productRelease", "matching-0.5.0")
        .put("verification", "FULL_RELEASE_PROFILE_AND_CLEAN_TREE_EVIDENCE");
    ObjectNode environment = root.putObject("environment");
    environment.put("schemaVersion", "matching.m10.environment.v1");
    environment.put("status", "PASS");
    environment.put("scope", "METHOD_SMOKE_EXECUTION_ENVIRONMENT");
    environment.put("javaRuntime", "test-runtime");
    environment.put("javaVersion", "25-test");
    environment.put("javaVendor", "test-vendor");
    environment.put("vmName", "test-vm");
    environment.putArray("jvmArguments");
    environment.put("osName", "test-os");
    environment.put("osVersion", "1");
    environment.put("osArchitecture", "test-arch");
    environment.put("availableProcessors", 1);
    environment.put("maximumHeapBytes", 1);
    environment.put("filesystemName", "test-fs");
    environment.put("filesystemType", "test-type");
    environment.put("cpuModel", "REQUIRED_FROM_RELEASE_RUNNER_FOR_RELEASE_EVIDENCE");
    environment.put("storageDevice", "REQUIRED_FROM_RELEASE_RUNNER_FOR_RELEASE_EVIDENCE");
    environment.put("powerPolicy", "REQUIRED_FROM_RELEASE_RUNNER_FOR_RELEASE_EVIDENCE");
    environment.put("releaseEnvironmentComplete", false);
    root.putObject("architecture")
        .put("matchingCoreChangePolicy", "M10_HOT_PATH_AUDIT_SPLIT_ONLY")
        .put("matchingCoreBusinessContractsUnchanged", true)
        .put("fullRetainedOrderAuditColdBoundaries", true)
        .put("terminalIdentityRetentionUnchanged", true)
        .put("productionModulesDependOnBenchmarks", false)
        .put("localRuntimeDependsOnJmhOrTestkit", false)
        .put("testkitProbeOnly", true)
        .put("runtimeTestSeamsPackagePrivate", true)
        .put("coreInfrastructureFree", true);
    ArrayNode bindings = root.putArray("artifactBindings");
    for (int index = 0; index < 21; index++) {
      bindings
          .addObject()
          .put("path", "artifact/" + index)
          .put("sha256", "%064x".formatted(index + 1))
          .put("bytes", 1);
    }
    return root;
  }

  private static ObjectNode qualification(String sourceCommit) {
    ObjectNode root = JsonSupport.MAPPER.createObjectNode();
    root.put("schemaVersion", "matching.m10.qualification.v1");
    root.put("status", "PASS");
    root.put("runId", "m10-test-release");
    root.put("profileId", "RELEASE_QUALIFICATION");
    root.put("resultScope", "RELEASE_QUALIFICATION");
    root.put("eligibleForReleaseEvidence", true);
    root.putObject("source")
        .put("commit", sourceCommit)
        .put("workloadSha256", M10CheckRunner.WORKLOAD_SHA256);
    root.putObject("environment")
        .put("javaVersion", "25-test")
        .put("osName", "test-os")
        .put("osVersion", "1")
        .put("osArchitecture", "test-arch");
    root.putObject("rawRecomputation")
        .put("status", "PASS")
        .put("fromDecompressedRaw", true)
        .put("rawRecords", 42)
        .put("rawPoints", 49)
        .put("percentilesRecomputed", true)
        .put("accountingReconciled", true)
        .put("capacityEnvelopeRecomputed", true);
    return root;
  }

  private static String course() {
    return """
    case=high-availability-cex
    profile=SPOT-CEX-1.0
    planVersion=0.12
    project=matching
    unit=M10
    lifecycle=COMPLETE
    designDepth=IMPLEMENTED
    startRef=course/m10-start
    completeRef=course/m10-complete
    m10Check.expectedStatus=PASS
    evidencePath=build/lab-evidence/M10/manifest.json
    """;
  }

  private static byte[] read(Path path) {
    try {
      return Files.readAllBytes(path);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot read test artifact", failure);
    }
  }

  private static void copy(Path source, Path target) {
    try {
      Files.createDirectories(target.getParent());
      Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot copy M10 evidence test input", failure);
    }
  }

  private static void write(Path path, byte[] bytes) {
    try {
      Files.createDirectories(path.getParent());
      Files.write(path, bytes);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot write M10 evidence test artifact", failure);
    }
  }

  private static String git(Path root, String... arguments) {
    try {
      Files.createDirectories(root);
      List<String> command = new ArrayList<>();
      command.add("git");
      command.addAll(List.of(arguments));
      Process process = new ProcessBuilder(command).directory(root.toFile()).start();
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
      int exit = process.waitFor();
      if (exit != 0) throw new IllegalStateException("git failed: " + error);
      return output;
    } catch (IOException | InterruptedException failure) {
      if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
      throw new IllegalStateException("cannot run git in M10 evidence test", failure);
    }
  }

  private record Lab(Path root, Path checks, Path release, Path evidence) {}
}
