package io.github.lchareln.cex.matching.testkit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Fail-closed completion judge for M09 snapshot publication and bounded suffix recovery. */
public final class M09CheckRunner {
  public static final String PASS = "PASS";
  public static final String STUDENT_FAILURE = "STUDENT_FAILURE";
  public static final String SYSTEM_ERROR = "SYSTEM_ERROR";
  static final String CHECK_SCHEMA_PATH = "schemas/matching.m09.check.v2.schema.json";
  static final List<String> OUTPUTS =
      List.of(
          "inherited-m08.json",
          "fixed-scenarios.json",
          "fixed-history.canonical.utf8",
          "generated-properties.json",
          "generated-history.canonical.utf8",
          "recovery-ledger.json",
          "storage-inventory.json",
          "coverage.json",
          "crash-windows.json",
          "operation-failures.json",
          "counterexamples-v1.json",
          "counterexamples.json",
          "counterexamples.canonical.utf8",
          "replay.json",
          "mutants.json",
          "architecture.json",
          "check.json");

  public Result run(Path repositoryRoot, Path reportDirectory) {
    return run(repositoryRoot, reportDirectory, repositoryRoot);
  }

  Result run(Path repositoryRoot, Path reportDirectory, Path trustedOutputRoot) {
    Path root = repositoryRoot.toAbsolutePath().normalize();
    Path reports = SafeOutputPaths.resolveTrustedOutput(trustedOutputRoot, reportDirectory);
    clear(reports);
    try {
      Artifacts artifacts = execute(root, reports);
      writePass(root, reports, artifacts);
      return new Result(PASS, reports.resolve("check.json"));
    } catch (RuntimeException failure) {
      clear(reports);
      String classification = classifyFailure(failure);
      String detail =
          STUDENT_FAILURE.equals(classification)
              ? failure.getMessage()
              : stableMessage(failure, root);
      writeFailure(root, reports, classification, detail);
      return new Result(classification, reports.resolve("check.json"));
    }
  }

  static String classifyFailure(RuntimeException failure) {
    return failure instanceof StudentFailure || failure instanceof M09SemanticFailure
        ? STUDENT_FAILURE
        : SYSTEM_ERROR;
  }

  private static Artifacts execute(Path root, Path reports) {
    // M09 is now an inherited semantic regression. Its evidence writer, immutable completion tag,
    // and frozen inputs retain source identity; the current course declaration belongs to M10.
    verifyFrozenInputs(root);
    Path inheritedPath = reports.resolve(".m08-regression");
    M08CheckRunner.Result inheritedResult = new M08CheckRunner().run(root, inheritedPath);
    if (SYSTEM_ERROR.equals(inheritedResult.status())) {
      throw new IllegalStateException("inherited M08 judge returned SYSTEM_ERROR");
    }
    studentRequire(PASS.equals(inheritedResult.status()), "inherited M08 judge failed");
    JsonNode inheritedCheck = JsonSupport.parse(readBytes(inheritedResult.reportPath()));
    ObjectNode inherited = JsonSupport.MAPPER.createObjectNode();
    inherited.put("unit", "M08");
    inherited.put("status", PASS);
    inherited.put("completeRef", "course/m08-complete");
    inherited.put("fixedScenarios", inheritedCheck.path("fixed").path("scenarios").intValue());
    inherited.put(
        "generatedOperations", inheritedCheck.path("generator").path("operations").intValue());
    inherited.put(
        "coverageObligations", inheritedCheck.path("coverage").path("observed").intValue());
    inherited.put("mutantsKilled", inheritedCheck.path("mutants").path("killed").intValue());
    deleteTree(inheritedPath);

    M09Corpus corpus = M09Corpus.load(root);
    M09FixedSuite.Result fixed = new M09FixedSuite().run(corpus, reports.resolve(".fixed-runtime"));
    M09GeneratedSuite.Result generated =
        new M09GeneratedSuite().run(corpus, reports.resolve(".generated-a"));
    M09GeneratedSuite.Result regenerated =
        new M09GeneratedSuite().run(corpus, reports.resolve(".generated-b"));
    M09CrashSmoke.Result crash = new M09CrashSmoke().run(corpus, reports.resolve(".child-halt"));
    M09OperationFailureSuite.Result operationFailures =
        new M09OperationFailureSuite().run(corpus, reports.resolve(".operation-failures"));
    M09MutantSuite.Result mutants = new M09MutantSuite().run(corpus, root);
    M09ArchitectureGate.Report architecture = new M09ArchitectureGate().verify(root);

    systemRequire(fixed.scenarios().size() == 22, "M09 fixed executor count changed");
    studentRequire(fixed.coverage().observed() == 32, "M09 obligation coverage is incomplete");
    systemRequire(
        Arrays.equals(generated.canonicalBytes(), regenerated.canonicalBytes())
            && generated.digest().equals(regenerated.digest()),
        "two fresh M09 generations produced different canonical bytes");
    systemRequire(
        generated.historyPlans().size() == 96
            && generated.operations() == 3_840
            && generated.comparisons() == 3_840
            && generated.ledgerChecks() >= 3_840
            && generated.budgetPreludeOperations() == 65
            && generated.budgetPredictionChecks()
                == generated.budgetPredictedAccepts() + generated.budgetPredictedRejects()
            && generated.budgetPredictedRejects() >= 1
            && generated.checkpointRequiredWitnesses() >= 1,
        "M09 generated dimensions or judge checks changed");
    systemRequire(
        generated.laneCounts().size() == 4
            && generated.laneCounts().values().stream().allMatch(count -> count == 24),
        "M09 generated lane distribution changed");
    systemRequire(
        generated.businessRejections() == 480 && generated.controlCommands() == 480,
        "M09 generated selector evidence changed");
    systemRequire(crash.observed() == 7, "M09 child halt evidence count changed");
    systemRequire(operationFailures.observed() == 8, "M09 operation-failure seam count changed");
    studentRequire(mutants.killed() == 12, "not every required M09 mutant was killed");
    systemRequire(
        SYSTEM_ERROR.equals(mutants.throwingControl()),
        "M09 throwing control was not SYSTEM_ERROR");
    studentRequire(
        architecture.passed(), "M09 architecture boundary failed: " + architecture.violations());
    return new Artifacts(
        inherited, fixed, generated, regenerated, crash, operationFailures, mutants, architecture);
  }

  private static void writePass(Path root, Path reports, Artifacts artifacts) {
    write(reports, "inherited-m08.json", artifacts.inherited());

    ObjectNode fixed = report("matching.m09.fixed-report.v1");
    fixed.put("scenarios", artifacts.fixed().scenarios().size());
    fixed.put("declaredOperations", 88);
    fixed.put("digest", artifacts.fixed().digest());
    fixed.set("results", artifacts.fixed().scenarios());
    write(reports, "fixed-scenarios.json", fixed);
    AtomicFiles.write(
        reports.resolve("fixed-history.canonical.utf8"), artifacts.fixed().canonicalBytes());

    ObjectNode generated = report("matching.m09.generated-report.v1");
    generated.put("algorithm", "splitmix64-v1");
    generated.put("baseSeed", "5909");
    generated.put("histories", artifacts.generated().historyPlans().size());
    generated.put("operationsPerHistory", 40);
    generated.put("operations", artifacts.generated().operations());
    generated.put("declaredGeneratedOperations", artifacts.generated().operations());
    generated.put("setupBudgetOperations", artifacts.generated().budgetPreludeOperations());
    generated.put("comparisons", artifacts.generated().comparisons());
    generated.put("ledgerChecks", artifacts.generated().ledgerChecks());
    generated.put("inventoryChecks", artifacts.generated().inventoryChecks());
    generated.put("restarts", artifacts.generated().restarts());
    generated.put("snapshots", artifacts.generated().snapshots());
    generated.put("automaticCheckpoints", artifacts.generated().automaticCheckpoints());
    generated.put("businessRejections", artifacts.generated().businessRejections());
    generated.put("controlCommands", artifacts.generated().controlCommands());
    generated.put(
        "budgetPredictionScope",
        "FRESH_APPEND_CANDIDATES_PLUS_CHECKPOINT_RETRIES_AND_65_SETUP_OPERATIONS");
    generated.put("budgetPredictionChecks", artifacts.generated().budgetPredictionChecks());
    generated.put("budgetPredictedAccepts", artifacts.generated().budgetPredictedAccepts());
    generated.put("budgetPredictedRejects", artifacts.generated().budgetPredictedRejects());
    generated.put(
        "checkpointRequiredWitnesses", artifacts.generated().checkpointRequiredWitnesses());
    ObjectNode lanes = generated.putObject("lanes");
    artifacts.generated().laneCounts().forEach(lanes::put);
    ObjectNode operations = generated.putObject("operationCounts");
    artifacts
        .generated()
        .operationCounts()
        .forEach((kind, count) -> operations.put(kind.name(), count));
    generated.put(
        "forcedUnknownReopenOperations",
        artifacts.generated().operationCounts().getOrDefault(M09History.Kind.CRASH, 0));
    generated.put("generatedCrashClaim", false);
    generated.put(
        "generatedCrashBoundary",
        "CONTROLLED_BEFORE_LIVE_APPLY_DURABILITY_UNKNOWN_THEN_FRESH_REOPEN");
    generated.put("digest", artifacts.generated().digest());
    generated.put("freshRegenerationDigest", artifacts.regenerated().digest());
    generated.put("byteExactRegeneration", true);
    write(reports, "generated-properties.json", generated);
    AtomicFiles.write(
        reports.resolve("generated-history.canonical.utf8"),
        artifacts.generated().canonicalBytes());

    ObjectNode ledger = report("matching.m09.recovery-ledger.v1");
    ledger.put("retainedGenesisWalRuntime", true);
    ledger.put("independentStorageLedger", true);
    ledger.put(
        "independentLedgerUsesProductionWalParser",
        !artifacts.architecture().independentLedgerProductionParserFree());
    ledger.put("candidateAndGenesisRuntimeUseProductionWalParser", true);
    ledger.put("genesisRuntimeSharesInheritedCoreSemantics", true);
    ledger.put("thirdCompleteBusinessModelClaim", false);
    ledger.put("semanticComparisons", artifacts.generated().comparisons());
    ledger.put("ledgerChecks", artifacts.generated().ledgerChecks());
    ledger.put("setupBudgetOperations", artifacts.generated().budgetPreludeOperations());
    ledger.put(
        "budgetPredictionScope",
        "FRESH_APPEND_CANDIDATES_PLUS_CHECKPOINT_RETRIES_AND_65_SETUP_OPERATIONS");
    ledger.put("budgetPredictionChecks", artifacts.generated().budgetPredictionChecks());
    ledger.put("budgetPredictedAccepts", artifacts.generated().budgetPredictedAccepts());
    ledger.put("budgetPredictedRejects", artifacts.generated().budgetPredictedRejects());
    ledger.put("checkpointRequiredWitnesses", artifacts.generated().checkpointRequiredWitnesses());
    ledger.put("maxSuffixRecords", 64);
    ledger.put("maxSuffixBytes", 1_048_576);
    ledger.put("cutRecordExactlyOnce", true);
    ledger.put("contiguousSuffixRequired", true);
    ledger.put("wholeSegmentEligibilityComputedIndependently", true);
    ledger.put("externalTerminalSegmentDeletionDetection", false);
    ledger.put(
        "retirementEvidenceScope",
        "RUNTIME_RETIREMENT_NON_TERMINAL_GAP_AND_ACTIVE_OR_CROSSING_RETENTION");
    write(reports, "recovery-ledger.json", ledger);

    ObjectNode inventory = report("matching.m09.storage-inventory.v1");
    inventory.put("checks", artifacts.generated().inventoryChecks());
    inventory.put("snapshotFinalAndTempNamesChecked", true);
    inventory.put("snapshotGenerationAndCutNamesChecked", true);
    inventory.put("walSegmentNamesAndSizesChecked", true);
    inventory.put("unknownDirectoryEntriesRejected", true);
    inventory.put("directoryLockExplicitlyWhitelisted", true);
    inventory.put("exactWholeSegmentRetentionChecked", true);
    inventory.put("activeOrCrossingSegmentRetentionChecked", true);
    inventory.put("externalTerminalSegmentDeletionDetection", false);
    write(reports, "storage-inventory.json", inventory);

    ObjectNode coverage = report("matching.m09.coverage-report.v1");
    coverage.put("required", 32);
    coverage.put("observed", artifacts.fixed().coverage().observed());
    coverage.set("obligations", artifacts.fixed().coverage().report());
    write(reports, "coverage.json", coverage);

    ObjectNode crashes = report("matching.m09.crash-windows-report.v1");
    crashes.put("model", "CHILD_JVM_RUNTIME_HALT");
    crashes.put("required", 7);
    crashes.put("observed", artifacts.crash().observed());
    crashes.put("exitCode", 86);
    crashes.put("markerForced", true);
    crashes.put("windowAndOccurrenceChecked", true);
    crashes.put("freshReopenAndSemanticRetryChecked", true);
    crashes.put("harnessMismatchClassification", SYSTEM_ERROR);
    crashes.put("haltAtDeclaredHookAndNamespaceObserved", true);
    crashes.put("underlyingOperationOrderClaim", false);
    crashes.put("physicalDurabilityClaim", false);
    crashes.put("realPowerLossClaim", false);
    crashes.set("windows", artifacts.crash().windows());
    write(reports, "crash-windows.json", crashes);

    ObjectNode failures = report("matching.m09.operation-failures-report.v1");
    failures.put("model", "CODE_LEVEL_DETERMINISTIC_INJECTION");
    failures.put("required", 8);
    failures.put("observed", artifacts.operationFailures().observed());
    failures.put("faultInjectedAtDeclaredPreOperationHook", true);
    failures.put("underlyingOperationExecutionClaim", false);
    failures.put("actualFilesystemFailure", false);
    failures.put("realPowerLossClaim", false);
    failures.set("failures", artifacts.operationFailures().failures());
    write(reports, "operation-failures.json", failures);

    write(reports, "counterexamples-v1.json", artifacts.mutants().counterexamples());
    ObjectNode counterexamples = report("matching.m09.counterexamples-report.v1");
    counterexamples.put("required", 12);
    counterexamples.put("persisted", artifacts.mutants().killed());
    counterexamples.put("rawOperations", artifacts.mutants().rawOperations());
    counterexamples.put("minimalOperations", artifacts.mutants().minimalOperations());
    counterexamples.put("shrinkTrials", artifacts.mutants().shrinkTrials());
    counterexamples.put("actualMutationActions", artifacts.mutants().actualMutationActions());
    counterexamples.put(
        "candidateDriver", "NINE_STORAGE_STATE_MUTANTS_PLUS_THREE_INVALID_LATEST_CANDIDATES");
    counterexamples.put("freshRuntimePerReplay", true);
    counterexamples.put("operationInterpreter", "SEQUENTIAL_TOKEN_INTERPRETER");
    counterexamples.put(
        "oneMinimalScope",
        "NO_SINGLE_DELETE_REPRODUCES_THE_SAME_FINGERPRINT;TRIALS_MAY_PASS_BE_INVALID_OR_DIFFER");
    counterexamples.put("oneMinimalGlobalMinimumClaim", false);
    counterexamples.put("invalidHistoryCountedAsKill", false);
    counterexamples.put("singleDeletePasses", artifacts.mutants().singleDeletePasses());
    counterexamples.put(
        "singleDeleteInvalidHistories", artifacts.mutants().singleDeleteInvalidHistories());
    counterexamples.put(
        "singleDeleteDifferentStudentFailures",
        artifacts.mutants().singleDeleteDifferentStudentFailures());
    counterexamples.put(
        "singleDeleteSameFingerprintStudentFailures",
        artifacts.mutants().singleDeleteSameFingerprintStudentFailures());
    counterexamples.put("digest", artifacts.mutants().digest());
    write(reports, "counterexamples.json", counterexamples);
    AtomicFiles.write(
        reports.resolve("counterexamples.canonical.utf8"), artifacts.mutants().canonicalBytes());

    ObjectNode replay = report("matching.m09.replay-report.v1");
    replay.put("strictReplays", artifacts.mutants().killed());
    replay.put("freshRuntimePerMutant", true);
    replay.put("fingerprintsExact", true);
    replay.put("oneMinimal", true);
    replay.put(
        "oneMinimalDefinition",
        "NO_SINGLE_DELETE_REPRODUCES_THE_SAME_FINGERPRINT;PASS_INVALID_HISTORY_OR_DIFFERENT_FAILURE_ALLOWED");
    replay.put("globalMinimumClaim", false);
    replay.put("invalidHistoryCountedAsKill", false);
    replay.put("singleDeletePasses", artifacts.mutants().singleDeletePasses());
    replay.put("singleDeleteInvalidHistories", artifacts.mutants().singleDeleteInvalidHistories());
    replay.put(
        "singleDeleteDifferentStudentFailures",
        artifacts.mutants().singleDeleteDifferentStudentFailures());
    replay.put(
        "singleDeleteSameFingerprintStudentFailures",
        artifacts.mutants().singleDeleteSameFingerprintStudentFailures());
    replay.put("actualMutationActions", artifacts.mutants().actualMutationActions());
    replay.put(
        "invalidLatestBaselines",
        "UNKNOWN_VERSION_AND_SHARD_MISMATCH_REWRITE_VALID_INTEGRITY; CORRUPTION_BREAKS_CRC_OR_HASH");
    replay.put(
        "decoderMutationClaim",
        "ONLY_EXECUTABLE_ACCEPTANCE_CANDIDATES; RESTORING_VALID_BYTES_IS_NOT_PRODUCTION_DECODER_PROOF");
    write(reports, "replay.json", replay);

    ObjectNode mutants = report("matching.m09.mutants-report.v1");
    mutants.put("required", 12);
    mutants.put("storageAndStateMutants", 9);
    mutants.put("invalidLatestAcceptanceCandidates", 3);
    mutants.put("executableCandidates", 12);
    mutants.put("killedAsStudentFailure", artifacts.mutants().killed());
    mutants.put("throwingControl", artifacts.mutants().throwingControl());
    mutants.put("systemErrorCountedAsKill", false);
    mutants.put("actualMutationActions", artifacts.mutants().actualMutationActions());
    mutants.put(
        "invalidLatestBaselineIntegrity",
        "UNKNOWN_VERSION_VALID; SHARD_MISMATCH_VALID; CORRUPTION_INVALID");
    mutants.set("results", artifacts.mutants().mutants());
    write(reports, "mutants.json", mutants);

    ObjectNode architecture = report("matching.m09.architecture-report.v1");
    architecture.put("coreSources", artifacts.architecture().coreSources());
    architecture.put("localRuntimeSources", artifacts.architecture().localRuntimeSources());
    architecture.put("testkitProbePresent", artifacts.architecture().testkitProbePresent());
    architecture.put(
        "storageOperationsProductionWiringVerified",
        artifacts.architecture().storageOperationsProductionWiringVerified());
    architecture.put(
        "independentLedgerProductionParserFree",
        artifacts.architecture().independentLedgerProductionParserFree());
    architecture.put("violations", artifacts.architecture().violations().size());
    ArrayNode violationDetails = architecture.putArray("violationDetails");
    artifacts.architecture().violations().forEach(violationDetails::add);
    architecture.put("coreInfrastructureFree", true);
    architecture.put("localRuntimeJdkAndCoreOnly", true);
    architecture.put("testkitProbeAbsentFromProduction", true);
    write(reports, "architecture.json", architecture);

    ObjectNode check = passReport(artifacts);
    JsonSupport.validate(check, readString(root.resolve(CHECK_SCHEMA_PATH)), false);
    write(reports, "check.json", check);
  }

  private static ObjectNode passReport(Artifacts artifacts) {
    ObjectNode check = JsonSupport.MAPPER.createObjectNode();
    check.put("schemaVersion", "matching.m09.check.v2");
    check.put("unit", "M09");
    check.put("status", PASS);
    check.put("contractPlanVersion", "0.11");
    ObjectNode inherited = check.putObject("inheritedM08");
    inherited.put("unit", "M08");
    inherited.put("status", PASS);
    inherited.put("completeRef", "course/m08-complete");
    ObjectNode inputs = check.putObject("inputs");
    inputs.put("fixedSha256", M09StartCheckRunner.FIXED_CORPUS_SHA256);
    inputs.put("generatorSha256", M09StartCheckRunner.GENERATOR_SHA256);
    ObjectNode fixed = check.putObject("fixed");
    fixed.put("scenarios", artifacts.fixed().scenarios().size());
    fixed.put("declaredOperations", 88);
    fixed.put("digest", artifacts.fixed().digest());
    ObjectNode generator = check.putObject("generator");
    generator.put("algorithm", "splitmix64-v1");
    generator.put("baseSeed", "5909");
    generator.put("histories", artifacts.generated().historyPlans().size());
    generator.put("operationsPerHistory", 40);
    generator.put("operations", artifacts.generated().operations());
    generator.put("declaredGeneratedOperations", artifacts.generated().operations());
    generator.put("setupBudgetOperations", artifacts.generated().budgetPreludeOperations());
    generator.put("lanes", artifacts.generated().laneCounts().size());
    generator.put("historiesPerLane", 24);
    generator.put("comparisons", artifacts.generated().comparisons());
    generator.put("ledgerChecks", artifacts.generated().ledgerChecks());
    generator.put(
        "budgetPredictionScope",
        "FRESH_APPEND_CANDIDATES_PLUS_CHECKPOINT_RETRIES_AND_65_SETUP_OPERATIONS");
    generator.put("budgetPredictionChecks", artifacts.generated().budgetPredictionChecks());
    generator.put("budgetPredictedAccepts", artifacts.generated().budgetPredictedAccepts());
    generator.put("budgetPredictedRejects", artifacts.generated().budgetPredictedRejects());
    generator.put(
        "checkpointRequiredWitnesses", artifacts.generated().checkpointRequiredWitnesses());
    generator.put("digest", artifacts.generated().digest());
    generator.put("byteExactRegeneration", true);
    ObjectNode snapshot = check.putObject("snapshotRecovery");
    snapshot.put("format", "M09S1");
    snapshot.put("genesisWalOracle", true);
    snapshot.put("independentStorageLedger", true);
    snapshot.put(
        "independentLedgerUsesProductionWalParser",
        !artifacts.architecture().independentLedgerProductionParserFree());
    snapshot.put("wholeSegmentExactInventory", true);
    snapshot.put("maxSuffixRecords", 64);
    snapshot.put("maxSuffixBytes", 1_048_576);
    snapshot.put("realPowerLossClaim", false);
    snapshot.put("replication", false);
    snapshot.put("aeron", false);
    snapshot.put("externalTerminalSegmentDeletionDetection", false);
    snapshot.put(
        "retirementEvidenceScope",
        "RUNTIME_RETIREMENT_NON_TERMINAL_GAP_AND_ACTIVE_OR_CROSSING_RETENTION");
    ObjectNode coverage = check.putObject("coverage");
    coverage.put("required", 32);
    coverage.put("observed", artifacts.fixed().coverage().observed());
    ObjectNode faults = check.putObject("faultEvidence");
    faults.put("childRuntimeHalts", artifacts.crash().observed());
    faults.put("operationFailureSeams", artifacts.operationFailures().observed());
    faults.put("systemHarnessFailuresAreSystemError", true);
    faults.put("fixedStorageOperationProgramOrderObserved", true);
    faults.put("childHaltAtDeclaredHookAndNamespaceObserved", true);
    faults.put("childUnderlyingOperationOrderClaim", false);
    faults.put("operationFailureAtDeclaredPreOperationHook", true);
    faults.put("operationFailureUnderlyingOperationExecutionClaim", false);
    faults.put("physicalDurabilityClaim", false);
    faults.put("realPowerLossClaim", false);
    ObjectNode mutants = check.putObject("mutants");
    mutants.put("required", 12);
    mutants.put("storageAndStateMutants", 9);
    mutants.put("invalidLatestAcceptanceCandidates", 3);
    mutants.put("executableCandidates", 12);
    mutants.put("killedAsStudentFailure", artifacts.mutants().killed());
    mutants.put("throwingControl", artifacts.mutants().throwingControl());
    mutants.put("systemErrorCountedAsKill", false);
    mutants.put("actualMutationActions", artifacts.mutants().actualMutationActions());
    ObjectNode architecture = check.putObject("architecture");
    architecture.put("violations", artifacts.architecture().violations().size());
    architecture.put("coreInfrastructureFree", true);
    architecture.put("localRuntimeJdkAndCoreOnly", true);
    architecture.put("testkitProbeAbsentFromProduction", true);
    architecture.put(
        "storageOperationsProductionWiringVerified",
        artifacts.architecture().storageOperationsProductionWiringVerified());
    architecture.put(
        "independentLedgerProductionParserFree",
        artifacts.architecture().independentLedgerProductionParserFree());
    ObjectNode release = check.putObject("releaseTarget");
    release.put("unitTag", "course/m09-complete");
    release.putNull("productRelease");
    release.put("verification", "M09_EVIDENCE_ONLY");
    return check;
  }

  private static void writeFailure(Path root, Path reports, String status, String detail) {
    ObjectNode failure = JsonSupport.MAPPER.createObjectNode();
    failure.put("schemaVersion", "matching.m09.check.v2");
    failure.put("unit", "M09");
    failure.put("status", status);
    failure.put("contractPlanVersion", "0.11");
    failure.put("detail", detail == null || detail.isBlank() ? "unspecified M09 failure" : detail);
    ObjectNode release = failure.putObject("releaseTarget");
    release.put("unitTag", "course/m09-complete");
    release.putNull("productRelease");
    release.put("verification", "M09_EVIDENCE_ONLY");
    JsonSupport.validate(failure, readString(root.resolve(CHECK_SCHEMA_PATH)), false);
    write(reports, "check.json", failure);
  }

  private static void verifyFrozenInputs(Path root) {
    Path fixed = root.resolve(M09StartCheckRunner.FIXED_CORPUS_PATH);
    Path generator = root.resolve(M09StartCheckRunner.GENERATOR_PATH);
    studentRequire(
        M09StartCheckRunner.FIXED_CORPUS_SHA256.equals(Hashing.sha256Hex(readBytes(fixed))),
        "M09 fixed corpus SHA-256 changed");
    studentRequire(
        M09StartCheckRunner.GENERATOR_SHA256.equals(Hashing.sha256Hex(readBytes(generator))),
        "M09 generator SHA-256 changed");
    JsonSupport.validate(
        JsonSupport.parse(readBytes(fixed)),
        readString(root.resolve(M09StartCheckRunner.FIXED_SCHEMA_PATH)),
        false);
    JsonSupport.validate(
        JsonSupport.parse(readBytes(generator)),
        readString(root.resolve(M09StartCheckRunner.GENERATOR_SCHEMA_PATH)),
        false);
  }

  private static ObjectNode report(String schemaVersion) {
    ObjectNode report = JsonSupport.MAPPER.createObjectNode();
    report.put("schemaVersion", schemaVersion);
    report.put("unit", "M09");
    report.put("status", PASS);
    return report;
  }

  private static void write(Path reports, String name, JsonNode value) {
    AtomicFiles.write(reports.resolve(name), JsonSupport.prettyBytes(value));
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

  private static String stableMessage(Throwable failure, Path root) {
    String message = failure.getMessage();
    return (failure.getClass().getSimpleName() + ": " + (message == null ? "" : message))
        .replace(root.toString(), "<repository>");
  }

  private static void clear(Path path) {
    deleteTree(path);
    try {
      Files.createDirectories(path);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot create M09 report directory", failure);
    }
  }

  private static void deleteTree(Path path) {
    if (!Files.exists(path)) {
      return;
    }
    try (var paths = Files.walk(path)) {
      for (Path current : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(current);
      }
    } catch (IOException failure) {
      throw new IllegalStateException("cannot clear M09 path", failure);
    }
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

  private record Artifacts(
      ObjectNode inherited,
      M09FixedSuite.Result fixed,
      M09GeneratedSuite.Result generated,
      M09GeneratedSuite.Result regenerated,
      M09CrashSmoke.Result crash,
      M09OperationFailureSuite.Result operationFailures,
      M09MutantSuite.Result mutants,
      M09ArchitectureGate.Report architecture) {}

  private static final class StudentFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private StudentFailure(String message) {
      super(message);
    }
  }

  public record Result(String status, Path reportPath) {}
}
