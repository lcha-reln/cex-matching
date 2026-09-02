package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.benchmark.FrozenPercentiles;
import java.io.IOException;
import java.io.Reader;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Fail-closed M10 bounded-admission and qualification-method completion judge. */
public final class M10CheckRunner {
  public static final String PASS = "PASS";
  public static final String STUDENT_FAILURE = "STUDENT_FAILURE";
  public static final String SYSTEM_ERROR = "SYSTEM_ERROR";
  static final String WORKLOAD_SHA256 =
      "92300fe4580a99f7e8ece911bce2f68a41b945273c923ed484051a011be4fa9b";
  static final String CHECK_SCHEMA_PATH = "schemas/matching.m10.check.v2.schema.json";
  static final String COUNTEREXAMPLE_SCHEMA_PATH =
      "schemas/matching.m10.counterexamples.v1.schema.json";
  static final List<String> OUTPUTS =
      List.of(
          "inherited-m09.json",
          "fixed-scenarios.json",
          "generated-admission.json",
          "generated-actions.canonical.utf8",
          "coverage.json",
          "admission-service.json",
          "method-smoke.json",
          "raw-arrivals.jsonl",
          "raw-completions.jsonl",
          "raw-queue.jsonl",
          "resources.jsonl",
          "reconciliation.json",
          "load-recovery.json",
          "micro-boundary.json",
          "counterexamples-v1.json",
          "counterexamples.json",
          "counterexamples.canonical.utf8",
          "replay.json",
          "mutants.json",
          "environment.json",
          "architecture.json",
          "check.json");

  public Result run(Path repositoryRoot, Path reportDirectory) {
    return run(
        repositoryRoot,
        reportDirectory,
        repositoryRoot,
        repositoryRoot.resolve("build/reports/m10-ci-smoke"));
  }

  Result run(Path repositoryRoot, Path reportDirectory, Path trustedOutputRoot) {
    return run(
        repositoryRoot,
        reportDirectory,
        trustedOutputRoot,
        repositoryRoot.resolve("build/reports/m10-ci-smoke"));
  }

  Result run(
      Path repositoryRoot, Path reportDirectory, Path trustedOutputRoot, Path smokeDirectory) {
    Path root = repositoryRoot.toAbsolutePath().normalize();
    Path reports = SafeOutputPaths.resolveTrustedOutput(trustedOutputRoot, reportDirectory);
    clear(reports);
    try {
      Artifacts artifacts = execute(root, reports, smokeDirectory.toAbsolutePath().normalize());
      writePass(root, reports, artifacts);
      return new Result(PASS, reports.resolve("check.json"));
    } catch (RuntimeException failure) {
      clear(reports);
      String classification = classify(failure);
      writeFailure(root, reports, classification, stableMessage(failure, root));
      return new Result(classification, reports.resolve("check.json"));
    }
  }

  private static Artifacts execute(Path root, Path reports, Path smokeDirectory) {
    Map<String, String> course = verifyCourse(root);
    Workload workload = verifyWorkload(root);
    ObjectNode inheritedSummary =
        new M10InheritedM09Regression().run(root, reports.resolve(".m09-regression"));

    M10GeneratedSuite.Result generated = new M10GeneratedSuite().generate();
    M10GeneratedSuite.Result regenerated = new M10GeneratedSuite().generate();
    systemRequire(
        generated.actions() == 16_384
            && generated.histories().size() == 64
            && generated.executedActions() == 16_384
            && generated.comparisons() >= 16_384
            && generated.ledgerChecks() >= 16_384
            && generated.terminalReconciliations() == 64
            && generated.laneCounts().size() == 4
            && generated.laneCounts().values().stream().allMatch(count -> count == 16)
            && generated.laneWitnesses().values().stream().allMatch(count -> count == 16),
        "M10 generated dimensions changed");
    systemRequire(
        Arrays.equals(generated.canonicalBytes(), regenerated.canonicalBytes())
            && generated.digest().equals(regenerated.digest()),
        "two fresh M10 generations were not byte-exact");

    M10MethodSuite.Result method = new M10MethodSuite().run();
    String sourceCommit = git(root, "rev-parse", "HEAD").strip();
    M10ReleaseBundleVerifier.Result smoke =
        new M10ReleaseBundleVerifier().verifySmoke(root, smokeDirectory, sourceCommit);
    String smokeQualificationSha256 =
        Hashing.sha256Hex(readBytes(smokeDirectory.resolve("qualification.json")));
    ArrayNode smokeArtifactBindings = JsonSupport.MAPPER.createArrayNode();
    for (Path relative : smoke.relativeFiles()) {
      Path file = smokeDirectory.resolve(relative);
      ObjectNode binding = smokeArtifactBindings.addObject();
      binding.put("path", relative.toString());
      binding.put("sha256", Hashing.sha256Hex(readBytes(file)));
      binding.put("bytes", size(file));
    }
    M10FixedSuite.Result fixed = new M10FixedSuite().run(reports.resolve(".fixed-runtime"), method);
    studentRequire(fixed.passed() == 20, "M10 fixed scenario corpus is incomplete");
    M10MutantSuite.Result mutants = new M10MutantSuite().run();
    studentRequire(mutants.killed() == 12, "not every required M10 candidate was killed");
    JsonSupport.validate(
        mutants.counterexamples(), readString(root.resolve(COUNTEREXAMPLE_SCHEMA_PATH)), false);
    M10Coverage.Report coverage =
        new M10Coverage().derive(workload.fixture(), fixed, generated, mutants);
    studentRequire(coverage.observed() == 28, "M10 derived obligation coverage is incomplete");

    ObjectNode environment = environment(root);
    ObjectNode micro = verifyMicroBoundary(root);
    M10ArchitectureGate.Report architecture = new M10ArchitectureGate().verify(root);
    studentRequire(
        architecture.passed(), "M10 architecture boundary failed: " + architecture.violations());
    return new Artifacts(
        course,
        workload,
        inheritedSummary,
        fixed,
        generated,
        regenerated,
        method,
        smoke,
        smokeQualificationSha256,
        smokeArtifactBindings,
        mutants,
        coverage,
        environment,
        micro,
        architecture);
  }

  private static void writePass(Path root, Path reports, Artifacts artifacts) {
    write(reports, "inherited-m09.json", artifacts.inherited());

    ObjectNode fixed = report("matching.m10.fixed-report.v1");
    fixed.put("required", 20);
    fixed.put("passed", artifacts.fixed().passed());
    fixed.set("results", artifacts.fixed().scenarios());
    write(reports, "fixed-scenarios.json", fixed);

    ObjectNode generated = report("matching.m10.generated-report.v1");
    generated.put("algorithm", "splitmix64-v1");
    generated.put("baseSeed", "6010");
    generated.put("histories", artifacts.generated().histories().size());
    generated.put("actionsPerHistory", 256);
    generated.put("actions", artifacts.generated().actions());
    generated.put("executedActions", artifacts.generated().executedActions());
    generated.put("comparisons", artifacts.generated().comparisons());
    generated.put("ledgerChecks", artifacts.generated().ledgerChecks());
    generated.put("terminalReconciliations", artifacts.generated().terminalReconciliations());
    ObjectNode lanes = generated.putObject("lanes");
    artifacts.generated().laneReport().forEach(lanes::put);
    ObjectNode kinds = generated.putObject("actionKinds");
    artifacts.generated().actionReport().forEach(kinds::put);
    ObjectNode laneWitnesses = generated.putObject("laneWitnesses");
    artifacts.generated().laneWitnessReport().forEach(laneWitnesses::put);
    generated.put("digest", artifacts.generated().digest());
    generated.put("freshRegenerationDigest", artifacts.regenerated().digest());
    generated.put("byteExactRegeneration", true);
    generated.put("businessAttemptLedgerSeparate", true);
    generated.put("maintenanceLedgerSeparate", true);
    generated.put("logicalOfferLedgerSeparate", true);
    write(reports, "generated-admission.json", generated);
    AtomicFiles.write(
        reports.resolve("generated-actions.canonical.utf8"),
        artifacts.generated().canonicalBytes());

    ObjectNode coverage = report("matching.m10.coverage-report.v1");
    coverage.put("required", 28);
    coverage.put("observed", artifacts.coverage().observed());
    coverage.set("obligations", artifacts.coverage().obligations());
    write(reports, "coverage.json", coverage);

    ObjectNode admission = report("matching.m10.admission-service-report.v1");
    admission.put("queueCapacityQualification", 64);
    admission.put("trySubmit", "NON_BLOCKING_BOUNDED_ADMISSION");
    admission.put("enqueueIsAck", false);
    admission.put("callerBytesOwned", true);
    admission.put("singleOwnerWorker", true);
    admission.put("submissionResultPassThrough", "EXACT_INSTANCE_AT_RUNTIME_PORT_SEAM");
    admission.put("checkpointResultVisibleBeforeCoordinatorRetry", true);
    admission.put("businessAttemptMaintenanceAndLogicalLedgersSeparate", true);
    admission.put("failureClosesAdmissionAndFailsPending", true);
    admission.put("quiesceRejectsThenDrains", true);
    admission.put("terminalAccountingReconciles", true);
    write(reports, "admission-service.json", admission);

    ObjectNode smokeReport = report("matching.m10.method-smoke.v2");
    JsonNode smokeQualification = artifacts.smoke().qualification();
    smokeReport.put("evidenceMode", "REAL_CI_SMOKE_BUNDLE");
    smokeReport.put("profileId", "CI_SMOKE");
    smokeReport.put("resultScope", "METHOD_SMOKE_ONLY");
    smokeReport.put("eligibleForReleaseEvidence", false);
    smokeReport.put("methodIsomorphic", true);
    smokeReport.put("verifiedRawRecords", artifacts.smoke().rawRecords());
    smokeReport.put("verifiedBundleFiles", artifacts.smoke().relativeFiles().size());
    smokeReport.put("qualificationSha256", artifacts.smokeQualificationSha256());
    smokeReport.set("rawRecomputation", smokeQualification.path("rawRecomputation").deepCopy());
    smokeReport.set("capacity", smokeQualification.path("capacity").deepCopy());
    smokeReport.set("environment", smokeQualification.path("environment").deepCopy());
    smokeReport.set("bundleArtifactBindings", artifacts.smokeArtifactBindings().deepCopy());
    ObjectNode modelOnly = artifacts.method().method().deepCopy();
    modelOnly.put("evidenceMode", "MODEL_ONLY");
    modelOnly.put("methodIsomorphic", false);
    smokeReport.set("deterministicDiagnostic", modelOnly);
    write(reports, "method-smoke.json", smokeReport);
    AtomicFiles.write(reports.resolve("raw-arrivals.jsonl"), artifacts.method().arrivals());
    AtomicFiles.write(reports.resolve("raw-completions.jsonl"), artifacts.method().completions());
    AtomicFiles.write(reports.resolve("raw-queue.jsonl"), artifacts.method().queues());
    AtomicFiles.write(reports.resolve("resources.jsonl"), artifacts.method().resources());
    write(reports, "reconciliation.json", artifacts.method().reconciliation());
    write(reports, "load-recovery.json", artifacts.method().recovery());
    write(reports, "micro-boundary.json", artifacts.micro());

    write(reports, "counterexamples-v1.json", artifacts.mutants().counterexamples());
    ObjectNode counterexampleSummary = report("matching.m10.counterexamples-report.v1");
    counterexampleSummary.put("required", 12);
    counterexampleSummary.put("persisted", artifacts.mutants().killed());
    counterexampleSummary.put("rawActions", artifacts.mutants().rawActions());
    counterexampleSummary.put("minimalActions", artifacts.mutants().minimalActions());
    counterexampleSummary.put("shrinkTrials", artifacts.mutants().shrinkTrials());
    counterexampleSummary.put("digest", artifacts.mutants().digest());
    counterexampleSummary.put(
        "minimalityScope", "ONE_MINIMAL_WITHIN_DECLARED_STEP_DELETION_GRAMMAR");
    counterexampleSummary.put("globalMinimumClaim", false);
    counterexampleSummary.put("invalidHistoryCountedAsKill", false);
    write(reports, "counterexamples.json", counterexampleSummary);
    AtomicFiles.write(
        reports.resolve("counterexamples.canonical.utf8"), artifacts.mutants().canonicalBytes());

    ObjectNode replay = report("matching.m10.replay-report.v1");
    replay.put("strictReplays", artifacts.mutants().killed());
    replay.put("sameFingerprint", true);
    replay.put("freshCandidateStatePerReplay", true);
    replay.put("oneMinimal", true);
    replay.put("minimalityScope", "ONE_MINIMAL_WITHIN_DECLARED_STEP_DELETION_GRAMMAR");
    replay.put("globalMinimumClaim", false);
    replay.put("invalidHistoryCountedAsKill", false);
    write(reports, "replay.json", replay);

    ObjectNode mutants = report("matching.m10.mutants-report.v1");
    mutants.put("required", 12);
    mutants.put("executableCandidates", 12);
    mutants.put("killedAsStudentFailure", artifacts.mutants().killed());
    mutants.put("systemErrorCountedAsKill", false);
    mutants.set("results", artifacts.mutants().candidates());
    mutants.set("controls", artifacts.mutants().controls());
    write(reports, "mutants.json", mutants);
    write(reports, "environment.json", artifacts.environment());

    ObjectNode architecture = report("matching.m10.architecture-report.v1");
    architecture.put("matchingCoreChangePolicy", "M10_HOT_PATH_AUDIT_SPLIT_ONLY");
    architecture.put("matchingCoreBusinessContractsUnchanged", true);
    architecture.put("fullRetainedOrderAuditColdBoundaries", true);
    architecture.put("terminalIdentityRetentionUnchanged", true);
    architecture.put("startCoreTree", artifacts.architecture().startCoreTree());
    architecture.put("headCoreTree", artifacts.architecture().headCoreTree());
    ArrayNode coreDeltaPaths = architecture.putArray("coreDeltaPaths");
    artifacts.architecture().coreDeltaPaths().forEach(coreDeltaPaths::add);
    architecture.put("coreSources", artifacts.architecture().coreSources());
    architecture.put("localRuntimeSources", artifacts.architecture().localRuntimeSources());
    architecture.put("benchmarkSources", artifacts.architecture().benchmarkSources());
    architecture.put("productionModulesDependOnBenchmarks", false);
    architecture.put("localRuntimeDependsOnJmhOrTestkit", false);
    architecture.put("testkitProbeOccurrences", artifacts.architecture().testkitProbeOccurrences());
    architecture.put("runtimePortPublic", false);
    architecture.put("openForTestingPublic", false);
    architecture.put("coreInfrastructureFree", true);
    ArrayNode violations = architecture.putArray("violationDetails");
    artifacts.architecture().violations().forEach(violations::add);
    write(reports, "architecture.json", architecture);

    ObjectNode check = passReport(root, reports, artifacts);
    JsonSupport.validate(check, readString(root.resolve(CHECK_SCHEMA_PATH)), false);
    write(reports, "check.json", check);
  }

  private static ObjectNode passReport(Path root, Path reports, Artifacts artifacts) {
    ObjectNode check = JsonSupport.MAPPER.createObjectNode();
    check.put("schemaVersion", "matching.m10.check.v2");
    check.put("unit", "M10");
    check.put("status", PASS);
    check.put("contractPlanVersion", "0.12");
    check.put(
        "objective",
        "Add bounded local admission and honest open-loop performance qualification without changing durable matching semantics.");
    ObjectNode source = check.putObject("source");
    source.put("commit", git(root, "rev-parse", "HEAD").strip());
    source.put("dirty", !git(root, "status", "--porcelain=v1", "--untracked-files=all").isBlank());
    ObjectNode course = check.putObject("courseDeclaration");
    artifacts.course().forEach(course::put);
    check.set("inheritedM09", artifacts.inherited().deepCopy());
    ObjectNode workload = check.putObject("workloadProfile");
    workload.put("sha256", artifacts.workload().digest());
    workload.put("seed", "6010");
    workload.put("fixedScenarios", 20);
    workload.put("generatedHistories", 64);
    workload.put("actionsPerHistory", 256);
    workload.put("generatedActions", 16_384);
    workload.put("lanes", 4);
    workload.put("queueCapacity", 64);
    workload.put("coverageObligations", 28);
    workload.put("requiredMutants", 12);
    workload.put("schemaProbes", artifacts.workload().schemaProbes());
    check.set(
        "qualificationRuntime",
        artifacts.workload().fixture().path("qualificationRuntime").deepCopy());
    ObjectNode fixed = check.putObject("fixed");
    fixed.put("scenarios", artifacts.fixed().passed());
    fixed.put("status", PASS);
    ObjectNode generator = check.putObject("generator");
    generator.put("algorithm", "splitmix64-v1");
    generator.put("histories", 64);
    generator.put("actionsPerHistory", 256);
    generator.put("actions", artifacts.generated().actions());
    generator.put("executedActions", artifacts.generated().executedActions());
    generator.put("comparisons", artifacts.generated().comparisons());
    generator.put("ledgerChecks", artifacts.generated().ledgerChecks());
    generator.put("terminalReconciliations", artifacts.generated().terminalReconciliations());
    generator.put("lanes", 4);
    generator.put("byteExactRegeneration", true);
    generator.put("freshModelPerHistory", true);
    generator.put("terminalLedgersReconcile", true);
    generator.put("digest", artifacts.generated().digest());
    ObjectNode service = check.putObject("admissionService");
    service.put("bounded", true);
    service.put("singleOwnerWorker", true);
    service.put("enqueueIsAck", false);
    service.put("overloadBeforeWalAndIdentity", true);
    service.put("exactSubmissionResultPassThrough", true);
    service.put("checkpointAttemptMaintenanceLogicalLedgersSeparate", true);
    service.put("terminalAccountingReconciles", true);
    ObjectNode method = check.putObject("methodSmoke");
    JsonNode smoke = artifacts.smoke().qualification();
    method.put("profileId", "CI_SMOKE");
    method.put("resultScope", "METHOD_SMOKE_ONLY");
    method.put("eligibleForReleaseEvidence", false);
    method.put("evidenceMode", "REAL_CI_SMOKE_BUNDLE");
    method.put("methodIsomorphic", true);
    method.put("latencyOrigin", "SCHEDULED_ARRIVAL");
    method.put("rawArrivals", smoke.path("rawRecomputation").path("arrivalRecords").longValue());
    method.put(
        "rawCompletions", smoke.path("rawRecomputation").path("completionRecords").longValue());
    method.put("verifiedRawRecords", artifacts.smoke().rawRecords());
    method.put("verifiedBundleFiles", artifacts.smoke().relativeFiles().size());
    method.put("qualificationSha256", artifacts.smokeQualificationSha256());
    method.put("percentileRankRule", FrozenPercentiles.RANK_RULE);
    method.put("percentilesRecomputed", true);
    method.put("resourceDimensionsPresent", true);
    method.put("aboveKneeRetained", true);
    method.put("sweepKnee", smoke.path("capacity").path("sweepKnees").path(0).longValue());
    method.put(
        "qopCandidate",
        smoke.path("capacity").path("qualifiedOperatingPointCandidate").longValue());
    method.put("qop", smoke.path("capacity").path("qualifiedOperatingPoint").longValue());
    method.put("deterministicDiagnosticEvidenceMode", "MODEL_ONLY");
    method.put("deterministicDiagnosticMethodIsomorphic", false);
    method.put("releaseThroughputClaim", false);
    ObjectNode recovery = check.putObject("loadRecovery");
    recovery.put("realLocalRuntimeExact", true);
    recovery.put("methodModelExact", artifacts.method().recovery().path("exact").booleanValue());
    recovery.put("releaseProfileStillRequired", true);
    ObjectNode coverage = check.putObject("coverage");
    coverage.put("required", 28);
    coverage.put("observed", artifacts.coverage().observed());
    ObjectNode mutants = check.putObject("mutants");
    mutants.put("required", 12);
    mutants.put("killed", artifacts.mutants().killed());
    mutants.put("classification", STUDENT_FAILURE);
    mutants.put("systemErrorCountedAsKill", false);
    mutants.put("systemErrorControls", artifacts.mutants().controls().size());
    ObjectNode boundary = check.putObject("releaseBoundary");
    boundary.put("ordinaryCheckProfile", "CI_SMOKE");
    boundary.put("ordinaryCheckScope", "METHOD_SMOKE_ONLY");
    boundary.put("ordinaryCheckEligibleForReleaseEvidence", false);
    boundary.put("fullReleaseProfileRequired", true);
    boundary.put("fullReleaseSoakSeconds", 1_800);
    boundary.put("fullReleaseExecutedByThisCheck", false);
    boundary.put("noReleaseNumbersFabricated", true);
    ObjectNode target = check.putObject("releaseTarget");
    target.put("unitTag", "course/m10-complete");
    target.put("productRelease", "matching-0.5.0");
    target.put("verification", "FULL_RELEASE_PROFILE_AND_CLEAN_TREE_EVIDENCE");
    check.set("environment", artifacts.environment().deepCopy());
    ObjectNode architecture = check.putObject("architecture");
    architecture.put("matchingCoreChangePolicy", "M10_HOT_PATH_AUDIT_SPLIT_ONLY");
    architecture.put("matchingCoreBusinessContractsUnchanged", true);
    architecture.put("fullRetainedOrderAuditColdBoundaries", true);
    architecture.put("terminalIdentityRetentionUnchanged", true);
    architecture.put("productionModulesDependOnBenchmarks", false);
    architecture.put("localRuntimeDependsOnJmhOrTestkit", false);
    architecture.put("testkitProbeOnly", true);
    architecture.put("runtimeTestSeamsPackagePrivate", true);
    architecture.put("coreInfrastructureFree", true);
    ArrayNode bindings = check.putArray("artifactBindings");
    for (String name : OUTPUTS) {
      if ("check.json".equals(name)) continue;
      Path path = reports.resolve(name);
      systemRequire(
          Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS), "missing M10 artifact " + name);
      ObjectNode binding = bindings.addObject();
      binding.put("path", name);
      binding.put("sha256", Hashing.sha256Hex(readBytes(path)));
      binding.put("bytes", size(path));
    }
    return check;
  }

  private static void writeFailure(Path root, Path reports, String classification, String detail) {
    ObjectNode failure = JsonSupport.MAPPER.createObjectNode();
    failure.put("schemaVersion", "matching.m10.check.v2");
    failure.put("unit", "M10");
    failure.put("status", classification);
    failure.put("contractPlanVersion", "0.12");
    failure.put("failure", detail == null || detail.isBlank() ? "M10 check failed" : detail);
    ObjectNode target = failure.putObject("releaseTarget");
    target.put("unitTag", "course/m10-complete");
    target.put("productRelease", "matching-0.5.0");
    target.put("verification", "FULL_RELEASE_PROFILE_AND_CLEAN_TREE_EVIDENCE");
    JsonSupport.validate(failure, readString(root.resolve(CHECK_SCHEMA_PATH)), false);
    write(reports, "check.json", failure);
  }

  private static Map<String, String> verifyCourse(Path root) {
    Properties properties = new Properties();
    try (Reader reader = Files.newBufferedReader(root.resolve("course.properties"))) {
      properties.load(reader);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot read course.properties", failure);
    }
    Map<String, String> expected =
        Map.ofEntries(
            Map.entry("case", "high-availability-cex"),
            Map.entry("profile", "SPOT-CEX-1.0"),
            Map.entry("planVersion", "0.12"),
            Map.entry("project", "matching"),
            Map.entry("unit", "M10"),
            Map.entry("lifecycle", "COMPLETE"),
            Map.entry("designDepth", "IMPLEMENTED"),
            Map.entry("startRef", "course/m10-start"),
            Map.entry("completeRef", "course/m10-complete"),
            Map.entry("m10Check.expectedStatus", PASS),
            Map.entry("evidencePath", "build/lab-evidence/M10/manifest.json"));
    studentRequire(
        properties.size() == expected.size(), "M10 course declaration field set changed");
    expected.forEach(
        (key, value) ->
            studentRequire(
                value.equals(properties.getProperty(key)), "M10 course changed: " + key));
    return expected;
  }

  private static Workload verifyWorkload(Path root) {
    byte[] bytes = readBytes(root.resolve(M10StartCheckRunner.WORKLOAD_PATH));
    String digest = Hashing.sha256Hex(bytes);
    systemRequire(WORKLOAD_SHA256.equals(digest), "M10 workload SHA changed");
    String schema = readString(root.resolve(M10StartCheckRunner.WORKLOAD_SCHEMA_PATH));
    JsonNode workload = JsonSupport.parse(bytes);
    JsonSupport.validate(workload, schema, false);
    studentRequire("6010".equals(workload.path("seed").stringValue()), "M10 seed changed");
    studentRequire(
        workload.path("fixedAdmissionScenarios").size() == 20, "M10 fixed corpus changed");
    studentRequire(workload.path("coverageRequirements").size() == 28, "M10 coverage changed");
    studentRequire(workload.path("requiredMutants").size() == 12, "M10 mutants changed");
    studentRequire(
        workload.path("generatedAdmissionModel").path("totalActions").intValue() == 16_384,
        "M10 generated action count changed");
    JsonNode runtime = workload.path("qualificationRuntime");
    systemRequire(
        "M10Q1".equals(runtime.path("policyId").stringValue())
            && "M10_DEDICATED_NOT_M09_DEFAULT".equals(runtime.path("scope").stringValue()),
        "M10 qualification runtime policy changed");
    systemRequire(
        runtime.path("m09Default").path("maxSuffixRecords").longValue() == 64
            && runtime.path("m09Default").path("maxSuffixBytes").longValue() == 1_048_576
            && runtime.path("finiteRecoveryBudget").path("maxSuffixRecords").longValue()
                == 1_000_000
            && runtime.path("finiteRecoveryBudget").path("maxSuffixBytes").longValue()
                == 1_073_741_824,
        "M10 dedicated recovery budget boundary changed");
    systemRequire(
        runtime.path("proactiveCheckpointOffsetNanos").longValue() == 100_000_000L
            && runtime.path("proactiveCheckpointAdmissionLagMaxNanos").longValue() == 10_000_000L
            && runtime.path("plannedRecordCeilingBytes").longValue() == 1_024L,
        "M10 checkpoint timing or WAL planning ceiling changed");
    JsonNode preflight = runtime.path("phaseBudgetPreflight");
    systemRequire(
        "START_SUFFIX_PLUS_ARRIVALS_SCHEDULED_BEFORE_CHECKPOINT_ADMISSION_DEADLINE_PLUS_QUEUE_CAPACITY_PLUS_ONE_OWNER"
                .equals(preflight.path("prefixRecords").stringValue())
            && "ALL_PLANNED_DURABLE_ARRIVALS_PLUS_QUEUE_CAPACITY_PLUS_ONE_OWNER"
                .equals(preflight.path("postCheckpointSuffixRecords").stringValue())
            && preflight.path("validatePrefixAndSuffixSeparately").booleanValue(),
        "M10 prefix/suffix phase budget preflight changed");
    JsonNode scheduler = runtime.path("scheduler");
    systemRequire(
        "DEDICATED_NO_COMPLETION_CHECKPOINT_OR_ARTIFACT_IO"
                .equals(scheduler.path("initialArrivalThread").stringValue())
            && "ASYNC_COMPLETION_CHECKPOINT_RETRY_AND_ARTIFACT_IO"
                .equals(scheduler.path("coordinator").stringValue())
            && scheduler.path("scheduledObservationCutDoesNotMove").booleanValue()
            && scheduler.path("producerClosureGraceMaxNanos").longValue() == 250_000_000L
            && scheduler.path("allScheduledArrivalsMaterialized").booleanValue()
            && scheduler.path("allAdmissionDecisionsWithinLagLimits").booleanValue()
            && scheduler.path("p99ProducerLagMaxNanos").longValue() == 50_000_000L
            && scheduler.path("maxProducerLagMaxNanos").longValue() == 250_000_000L
            && scheduler.path("observationCutLagMaxNanos").longValue() == 10_000_000L,
        "M10 scheduled-cut and producer-closure contract changed");
    systemRequire(
        "IMMUTABLE_SCHEDULED_WINDOW_END_RAW_RECONSTRUCTED_BEFORE_PRODUCER_CLOSURE_AND_TERMINAL_DRAIN"
                .equals(runtime.path("observationCut").stringValue())
            && "ZERO_PENDING_BEFORE_RECOVERY".equals(runtime.path("terminalDrain").stringValue()),
        "M10 observation-cut or terminal-drain contract changed");
    JsonNode rawTime = runtime.path("rawTimeContract");
    systemRequire(
        "admissionDecisionNanos".equals(rawTime.path("admissionTimestamp").stringValue())
            && "ADMISSION_GATE_DECISION"
                .equals(rawTime.path("admissionObservationKind").stringValue())
            && "ownerCompletedNanos".equals(rawTime.path("completionTimestamp").stringValue())
            && "OWNER_COMPLETED_UNDER_GATE"
                .equals(rawTime.path("completionTimeOrigin").stringValue()),
        "M10 raw timestamp contract changed");
    List<JsonNode> probes = new ArrayList<>();
    ObjectNode missingGenerated = (ObjectNode) workload.deepCopy();
    missingGenerated.remove("generatedAdmissionModel");
    probes.add(missingGenerated);
    ObjectNode promotedSmoke = (ObjectNode) workload.deepCopy();
    ((ObjectNode) promotedSmoke.path("ciSmoke")).put("eligibleForReleaseEvidence", true);
    probes.add(promotedSmoke);
    ObjectNode changedSeed = (ObjectNode) workload.deepCopy();
    changedSeed.put("seed", "6011");
    probes.add(changedSeed);
    ObjectNode changedCapacity = (ObjectNode) workload.deepCopy();
    ((ObjectNode) changedCapacity.path("admissionContract")).put("queueCapacity", 65);
    probes.add(changedCapacity);
    ObjectNode duplicateScenario = (ObjectNode) workload.deepCopy();
    ((ArrayNode) duplicateScenario.path("fixedAdmissionScenarios"))
        .set(1, duplicateScenario.path("fixedAdmissionScenarios").get(0));
    probes.add(duplicateScenario);
    ObjectNode missingObligation = (ObjectNode) workload.deepCopy();
    ((ArrayNode) missingObligation.path("coverageRequirements")).remove(27);
    probes.add(missingObligation);
    ObjectNode missingPercentile = (ObjectNode) workload.deepCopy();
    ((ArrayNode) missingPercentile.path("releaseOpenLoop").path("percentiles")).remove(3);
    probes.add(missingPercentile);
    ObjectNode absoluteThroughput = (ObjectNode) workload.deepCopy();
    absoluteThroughput.put("minimumOrdersPerSecond", 1_000_000);
    probes.add(absoluteThroughput);
    for (JsonNode probe : probes) {
      try {
        JsonSupport.validate(probe, schema, false);
        throw new IllegalStateException("M10 workload schema accepted a completion negative probe");
      } catch (FixtureSchemaException expected) {
        // strict rejection
      }
    }
    systemRequire(probes.size() == 8, "M10 completion schema probe count changed");
    return new Workload(digest, 8, workload.deepCopy());
  }

  private static ObjectNode verifyMicroBoundary(Path root) {
    String benchmark =
        readString(
            root.resolve(
                "matching-benchmarks/src/main/java/io/github/lchareln/cex/matching/benchmark/CoreMatchingBenchmark.java"));
    String build = readString(root.resolve("matching-benchmarks/build.gradle.kts"));
    studentRequire(
        benchmark.contains("@BenchmarkMode(Mode.SampleTime)"), "JMH mode is not SampleTime");
    studentRequire(build.contains("org.openjdk.jmh:jmh-core:1.37"), "JMH dependency changed");
    ObjectNode micro = report("matching.m10.micro-boundary.v1");
    micro.put("harness", "JMH");
    micro.put("mode", "SampleTime");
    micro.put("resultScope", "DIAGNOSTIC_ONLY");
    micro.put("releaseGate", false);
    micro.put("combinedWithEndToEndScore", false);
    micro.put("numbersPublishedByOrdinaryCheck", false);
    return micro;
  }

  private static ObjectNode environment(Path root) {
    ObjectNode environment = report("matching.m10.environment.v1");
    environment.put("scope", "METHOD_SMOKE_EXECUTION_ENVIRONMENT");
    environment.put("javaRuntime", System.getProperty("java.runtime.name"));
    environment.put("javaVersion", System.getProperty("java.runtime.version"));
    environment.put("javaVendor", System.getProperty("java.vendor"));
    environment.put("vmName", System.getProperty("java.vm.name"));
    ArrayNode flags = environment.putArray("jvmArguments");
    ManagementFactory.getRuntimeMXBean().getInputArguments().forEach(flags::add);
    environment.put("osName", System.getProperty("os.name"));
    environment.put("osVersion", System.getProperty("os.version"));
    environment.put("osArchitecture", System.getProperty("os.arch"));
    environment.put("availableProcessors", Runtime.getRuntime().availableProcessors());
    environment.put("maximumHeapBytes", Runtime.getRuntime().maxMemory());
    try {
      var store = Files.getFileStore(root);
      environment.put("filesystemName", store.name().isBlank() ? "UNNAMED" : store.name());
      environment.put("filesystemType", store.type().isBlank() ? "UNNAMED" : store.type());
    } catch (IOException failure) {
      throw new IllegalStateException("filesystem fingerprint unavailable", failure);
    }
    environment.put("cpuModel", "REQUIRED_FROM_RELEASE_RUNNER_FOR_RELEASE_EVIDENCE");
    environment.put("storageDevice", "REQUIRED_FROM_RELEASE_RUNNER_FOR_RELEASE_EVIDENCE");
    environment.put("powerPolicy", "REQUIRED_FROM_RELEASE_RUNNER_FOR_RELEASE_EVIDENCE");
    environment.put("releaseEnvironmentComplete", false);
    return environment;
  }

  private static String classify(RuntimeException failure) {
    return failure instanceof M10SemanticFailure ? STUDENT_FAILURE : SYSTEM_ERROR;
  }

  private static String stableMessage(RuntimeException failure, Path root) {
    String message = failure.getMessage();
    if (message == null || message.isBlank()) return failure.getClass().getSimpleName();
    return message.replace(root.toString(), "<repository>");
  }

  private static ObjectNode report(String schema) {
    ObjectNode node = JsonSupport.MAPPER.createObjectNode();
    node.put("schemaVersion", schema);
    node.put("status", PASS);
    return node;
  }

  private static void write(Path reports, String name, JsonNode node) {
    AtomicFiles.write(reports.resolve(name), JsonSupport.prettyBytes(node));
  }

  private static long size(Path path) {
    try {
      return Files.size(path);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot size " + path, failure);
    }
  }

  private static String git(Path root, String... arguments) {
    List<String> command = new ArrayList<>();
    command.add("git");
    command.addAll(List.of(arguments));
    try {
      Process process = new ProcessBuilder(command).directory(root.toFile()).start();
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
      int exit = process.waitFor();
      systemRequire(exit == 0, "git command failed: " + error.strip());
      return output;
    } catch (IOException failure) {
      throw new IllegalStateException("cannot execute git", failure);
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("git interrupted", failure);
    }
  }

  private static byte[] readBytes(Path path) {
    try {
      return Files.readAllBytes(path);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot read " + path, failure);
    }
  }

  private static String readString(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot read " + path, failure);
    }
  }

  private static void clear(Path path) {
    deleteTree(path);
    try {
      Files.createDirectories(path);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot create M10 report directory", failure);
    }
  }

  private static void deleteTree(Path path) {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
    try (var paths = Files.walk(path)) {
      for (Path current : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(current);
      }
    } catch (IOException failure) {
      throw new IllegalStateException("cannot clear " + path, failure);
    }
  }

  private static void studentRequire(boolean condition, String message) {
    if (!condition) throw new M10SemanticFailure(message);
  }

  private static void systemRequire(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }

  record Workload(String digest, int schemaProbes, JsonNode fixture) {}

  private record Artifacts(
      Map<String, String> course,
      Workload workload,
      ObjectNode inherited,
      M10FixedSuite.Result fixed,
      M10GeneratedSuite.Result generated,
      M10GeneratedSuite.Result regenerated,
      M10MethodSuite.Result method,
      M10ReleaseBundleVerifier.Result smoke,
      String smokeQualificationSha256,
      JsonNode smokeArtifactBindings,
      M10MutantSuite.Result mutants,
      M10Coverage.Report coverage,
      ObjectNode environment,
      ObjectNode micro,
      M10ArchitectureGate.Report architecture) {}

  public record Result(String status, Path reportPath) {}
}
