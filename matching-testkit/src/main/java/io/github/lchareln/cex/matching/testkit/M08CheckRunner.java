package io.github.lchareln.cex.matching.testkit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Fail-closed completion judge for the M08 local WAL, durable identity, recovery, and ACK boundary.
 */
public final class M08CheckRunner {
  public static final String PASS = "PASS";
  public static final String STUDENT_FAILURE = "STUDENT_FAILURE";
  public static final String SYSTEM_ERROR = "SYSTEM_ERROR";
  static final String CHECK_SCHEMA_PATH = "schemas/matching.m08.check.v2.schema.json";
  static final List<String> OUTPUTS =
      List.of(
          "inherited-m07.json",
          "fixed-scenarios.json",
          "fixed-history.canonical.utf8",
          "generated-properties.json",
          "generated-history.canonical.utf8",
          "durability-ledger.json",
          "coverage.json",
          "fault-windows.json",
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
    return failure instanceof StudentFailure || failure instanceof M08SemanticFailure
        ? STUDENT_FAILURE
        : SYSTEM_ERROR;
  }

  private static Artifacts execute(Path root, Path reports) {
    verifyCourse(root);
    verifyFrozenInputs(root);
    Path inheritedPath = reports.resolve(".m07-regression");
    InheritedResult inherited = runM07(root, inheritedPath);
    if (SYSTEM_ERROR.equals(inherited.status())) {
      throw new IllegalStateException("inherited M07 judge returned SYSTEM_ERROR");
    }
    studentRequire(PASS.equals(inherited.status()), "inherited M07 judge failed");
    JsonNode inheritedCheck = JsonSupport.parse(readBytes(inherited.reportPath()));
    ObjectNode inheritedSummary = JsonSupport.MAPPER.createObjectNode();
    inheritedSummary.put("unit", "M07");
    inheritedSummary.put("status", inherited.status());
    inheritedSummary.put(
        "fixedScenarios", inheritedCheck.path("fixedCorpus").path("scenarios").intValue());
    inheritedSummary.put(
        "fixedCommands", inheritedCheck.path("fixedCorpus").path("commands").intValue());
    inheritedSummary.put(
        "generatedHistories", inheritedCheck.path("generator").path("histories").intValue());
    inheritedSummary.put(
        "generatedCommands", inheritedCheck.path("generator").path("commands").intValue());
    inheritedSummary.put(
        "coverageObligations",
        inheritedCheck.path("coverage").path("satisfiedObligations").intValue());
    inheritedSummary.put("mutantsKilled", inheritedCheck.path("mutants").path("killed").intValue());
    deleteTree(inheritedPath);

    M08FixedSuite.Result fixed = new M08FixedSuite().run(root, reports.resolve(".fixed-runtime"));
    M08GeneratedSuite.Result generated =
        new M08GeneratedSuite().run(reports.resolve(".generated-a"));
    M08GeneratedSuite.Result regenerated =
        new M08GeneratedSuite().run(reports.resolve(".generated-b"));
    M08CrashSmoke.Result crashSmoke = new M08CrashSmoke().run(reports.resolve(".child-halt"));
    M08OperationFailureSuite.Result operationFailures =
        new M08OperationFailureSuite().run(reports.resolve(".operation-failures"));
    systemRequire(
        generated.digest().equals(regenerated.digest())
            && Arrays.equals(generated.canonicalBytes(), regenerated.canonicalBytes()),
        "two fresh M08 generations produced different canonical bytes");
    studentRequire(
        generated.histories() == 96
            && generated.operations() == 4_608
            && generated.comparisons() == 4_608
            && generated.ledgerChecks() == 4_608,
        "M08 generated proof counts changed");
    studentRequire(
        generated.invalidEnvelopes() == 192
            && generated.businessRejections() == 576
            && generated.selectedOperations().size() == 6,
        "M08 frozen operationDomain was not fully consumed");
    studentRequire(
        operationFailures.operationFailures() == 7,
        "M08 BEFORE_OPERATION failure suite did not execute all seven targets");

    M08MutantSuite.Result mutants = new M08MutantSuite().run(root);
    studentRequire(mutants.killed() == 10, "not every required M08 mutant was killed");
    systemRequire(
        "SYSTEM_ERROR".equals(mutants.throwingControl()), "M08 throwing control was misclassified");
    M08ArchitectureGate.Report architecture = new M08ArchitectureGate().verify(root);
    studentRequire(
        architecture.passed(), "M08 architecture boundary failed: " + architecture.violations());
    return new Artifacts(
        inheritedSummary,
        fixed,
        generated,
        regenerated,
        crashSmoke,
        operationFailures,
        mutants,
        architecture);
  }

  private static void writePass(Path root, Path reports, Artifacts artifacts) {
    write(reports, "inherited-m07.json", artifacts.inherited());
    ObjectNode fixed = report("matching.m08.fixed-report.v1");
    fixed.put("scenarios", 20);
    fixed.put("digest", artifacts.fixed().digest());
    fixed.put("injectedFaults", artifacts.fixed().injectedFaults());
    fixed.set("results", artifacts.fixed().scenarios());
    write(reports, "fixed-scenarios.json", fixed);
    AtomicFiles.write(
        reports.resolve("fixed-history.canonical.utf8"), artifacts.fixed().canonicalBytes());

    ObjectNode generated = report("matching.m08.generated-report.v1");
    generated.put("algorithm", "splitmix64-v1");
    generated.put("baseSeed", "5808");
    generated.put("histories", artifacts.generated().histories());
    generated.put("operationsPerHistory", 48);
    generated.put("operations", artifacts.generated().operations());
    generated.put("comparisons", artifacts.generated().comparisons());
    generated.put("ledgerChecks", artifacts.generated().ledgerChecks());
    generated.put("restarts", artifacts.generated().restarts());
    generated.put("faultWindows", artifacts.generated().faultWindows());
    generated.put("rollovers", artifacts.generated().rollovers());
    generated.put("invalidEnvelopes", artifacts.generated().invalidEnvelopes());
    generated.put("businessRejections", artifacts.generated().businessRejections());
    ObjectNode selectedOperations = generated.putObject("selectedOperations");
    artifacts.generated().selectedOperations().forEach(selectedOperations::put);
    generated.put("digest", artifacts.generated().digest());
    generated.put("freshRegenerationDigest", artifacts.regenerated().digest());
    generated.put("byteExactRegeneration", true);
    write(reports, "generated-properties.json", generated);
    AtomicFiles.write(
        reports.resolve("generated-history.canonical.utf8"),
        artifacts.generated().canonicalBytes());

    ObjectNode ledger = report("matching.m08.durability-ledger.v1");
    ledger.put("independentIdentityModel", true);
    ledger.put("productionWalParserShared", false);
    ledger.put("thirdLedgerChecks", artifacts.generated().ledgerChecks());
    ledger.put("predictedAppends", artifacts.generated().ledgerAppends());
    ledger.put("predictedRecordForces", artifacts.generated().ledgerRecordForces());
    ledger.put("predictedApplies", artifacts.generated().ledgerApplies());
    ledger.put(
        "predictedSegmentPublicationDirectoryForces",
        artifacts.generated().ledgerDirectoryForces());
    ledger.put("restartLedgerChecks", artifacts.generated().restartLedgerChecks());
    ledger.put("walPositionChecked", true);
    ledger.put("applicationSequenceChecked", true);
    ledger.put("duplicateOriginalResultChecked", true);
    ledger.put("semanticDigestChecked", true);
    ledger.put("businessRejectionConsumesWalAndApplicationSequence", true);
    ledger.put("unknownStaleSlotReachable", false);
    ledger.put("preprovisionedWalDirectory", true);
    ledger.put("ancestorDirectoryDurabilityExternal", true);
    ledger.put(
        "unknownStaleSlotReason",
        "strict continuity and no eviction bind every prior active-epoch slot; exact resolves duplicate and a different identity resolves SLOT_IDENTITY_CONFLICT");
    write(reports, "durability-ledger.json", ledger);

    ObjectNode coverage = report("matching.m08.coverage-report.v1");
    coverage.put("required", 24);
    coverage.put("observed", artifacts.fixed().coverage().size());
    ArrayNode obligations = coverage.putArray("obligations");
    artifacts
        .fixed()
        .coverage()
        .forEach(id -> obligations.addObject().put("id", id).put("hit", true));
    write(reports, "coverage.json", coverage);

    ObjectNode fault = report("matching.m08.fault-windows-report.v1");
    fault.put("model", "CODE_LEVEL_DETERMINISTIC_INJECTION");
    fault.put("fixedInjectedFaults", artifacts.fixed().injectedFaults());
    fault.put("generatedForcedRecordWindows", artifacts.generated().faultWindows());
    fault.put("beforeOperationFailures", artifacts.operationFailures().operationFailures());
    fault.put("childProcessCrashSmokes", artifacts.crashSmoke().processCrashes());
    fault.set("childProcessWindows", artifacts.crashSmoke().windows());
    fault.put("realPowerLoss", false);
    fault.put("realDiskFull", false);
    fault.put("realReadOnlyFilesystem", false);
    fault.put("injectedEnospcWitness", true);
    fault.put("injectedReadOnlyWitness", true);
    fault.put("actualFilesystemForTypedInjections", false);
    fault.put("preprovisionedWalDirectory", true);
    fault.put("ancestorDirectoryDurabilityExternal", true);
    fault.put("childRuntimeHaltSmoke", true);
    fault.put(
        "forceMeaning",
        "FileChannel.force(true) completed at the documented JDK/OS barrier; no physical-media durability claim is made");
    write(reports, "fault-windows.json", fault);

    ObjectNode operationFailures = report("matching.m08.operation-failures-report.v1");
    operationFailures.put("model", "CODE_LEVEL_DETERMINISTIC_INJECTION");
    operationFailures.put("required", 7);
    operationFailures.put("observed", artifacts.operationFailures().operationFailures());
    operationFailures.put("beforeOperation", true);
    operationFailures.put("operationExecuted", false);
    operationFailures.put("actualFilesystem", false);
    operationFailures.put("preprovisionedWalDirectory", true);
    operationFailures.put("ancestorDirectoryDurabilityExternal", true);
    operationFailures.set("failures", artifacts.operationFailures().failures());
    write(reports, "operation-failures.json", operationFailures);

    write(reports, "counterexamples-v1.json", artifacts.mutants().counterexamples());
    ObjectNode counterexamples = report("matching.m08.counterexamples-report.v1");
    counterexamples.put("required", 10);
    counterexamples.put("persisted", artifacts.mutants().killed());
    counterexamples.put("rawOperations", artifacts.mutants().rawOperations());
    counterexamples.put("minimalOperations", artifacts.mutants().minimalOperations());
    counterexamples.put("shrinkTrials", artifacts.mutants().shrinkTrials());
    counterexamples.put("actualMutationActions", artifacts.mutants().actualMutationActions());
    counterexamples.put("candidateDriver", "EXECUTABLE_MUTATED_RUNTIME");
    counterexamples.put("fullRestartGrammar", true);
    counterexamples.put(
        "oneMinimalScope", "HIGH_LEVEL_HISTORY_TOKENS_WITH_REQUIRED_RESTART_GRAMMAR");
    counterexamples.put("digest", artifacts.mutants().digest());
    write(reports, "counterexamples.json", counterexamples);
    AtomicFiles.write(
        reports.resolve("counterexamples.canonical.utf8"), artifacts.mutants().canonicalBytes());
    ObjectNode replay = report("matching.m08.replay-report.v1");
    replay.put("strictReplays", artifacts.mutants().killed());
    replay.put("fingerprintsExact", true);
    replay.put("oneMinimal", true);
    replay.put("freshRuntimePerMutant", true);
    replay.put("fullSubmitCloseRestartRetryGrammar", true);
    replay.put("executableMutationActions", artifacts.mutants().actualMutationActions());
    replay.put("oneMinimalScope", "HIGH_LEVEL_HISTORY_TOKENS_WITH_REQUIRED_RESTART_GRAMMAR");
    write(reports, "replay.json", replay);
    ObjectNode mutants = report("matching.m08.mutants-report.v1");
    mutants.put("required", 10);
    mutants.put("killedAsStudentFailure", artifacts.mutants().killed());
    mutants.put("throwingControl", artifacts.mutants().throwingControl());
    mutants.put("systemErrorCountedAsKill", false);
    mutants.put("candidateDriver", "EXECUTABLE_MUTATED_RUNTIME");
    mutants.put("actualMutationActions", artifacts.mutants().actualMutationActions());
    mutants.set("results", artifacts.mutants().mutants());
    write(reports, "mutants.json", mutants);

    ObjectNode architecture = report("matching.m08.architecture-report.v1");
    architecture.put("coreSources", artifacts.architecture().coreSources());
    architecture.put("localRuntimeSources", artifacts.architecture().localRuntimeSources());
    architecture.put("violations", artifacts.architecture().violations().size());
    ArrayNode violationDetails = architecture.putArray("violationDetails");
    artifacts.architecture().violations().forEach(violationDetails::add);
    architecture.put("coreInfrastructureFree", true);
    architecture.put("localRuntimeJdkAndCoreOnly", true);
    write(reports, "architecture.json", architecture);

    ObjectNode check = passReport(artifacts);
    JsonSupport.validate(check, readString(root.resolve(CHECK_SCHEMA_PATH)), false);
    write(reports, "check.json", check);
  }

  private static ObjectNode passReport(Artifacts artifacts) {
    ObjectNode check = JsonSupport.MAPPER.createObjectNode();
    check.put("schemaVersion", "matching.m08.check.v2");
    check.put("unit", "M08");
    check.put("status", PASS);
    check.put("contractPlanVersion", "0.10");
    check.set("inheritedM07", artifacts.inherited().deepCopy());
    ObjectNode inputs = check.putObject("inputs");
    inputs.put("fixedSha256", M08StartCheckRunner.FIXED_CORPUS_SHA256);
    inputs.put("generatorSha256", M08StartCheckRunner.GENERATOR_SHA256);
    ObjectNode fixed = check.putObject("fixed");
    fixed.put("scenarios", 20);
    fixed.put("digest", artifacts.fixed().digest());
    fixed.put("injectedFaults", artifacts.fixed().injectedFaults());
    ObjectNode generator = check.putObject("generator");
    generator.put("algorithm", "splitmix64-v1");
    generator.put("baseSeed", "5808");
    generator.put("histories", artifacts.generated().histories());
    generator.put("operationsPerHistory", 48);
    generator.put("operations", artifacts.generated().operations());
    generator.put("profileDrivenOperations", 1_152);
    generator.put("invalidEnvelopes", artifacts.generated().invalidEnvelopes());
    generator.put("businessRejections", artifacts.generated().businessRejections());
    ObjectNode operationCounts = generator.putObject("selectedOperations");
    artifacts.generated().selectedOperations().forEach(operationCounts::put);
    generator.put("digest", artifacts.generated().digest());
    ObjectNode durability = check.putObject("durability");
    durability.put("envelope", "M08C1");
    durability.put("wal", "M08W1");
    durability.put("submissionOrder", "VALIDATE_PREFLIGHT_APPEND_FORCE_APPLY_ACK");
    durability.put("genesisRecovery", true);
    durability.put("durableBidirectionalIdentity", true);
    durability.put("allApplicationCommandsJournaled", true);
    durability.put("m07StpPlaceJournaled", true);
    durability.put("m07GovernedStpPlaceJournaled", true);
    durability.put("preprovisionedRealNonSymlinkWalDirectory", true);
    durability.put("ancestorDirectoryDurabilityExternal", true);
    durability.put("runtimeCreatesWalDirectory", false);
    ObjectNode coverage = check.putObject("coverage");
    coverage.put("required", 24);
    coverage.put("observed", artifacts.fixed().coverage().size());
    ObjectNode mutation = check.putObject("mutants");
    mutation.put("required", 10);
    mutation.put("killed", artifacts.mutants().killed());
    mutation.put("throwingControl", artifacts.mutants().throwingControl());
    mutation.put("systemErrorCountedAsKill", false);
    mutation.put("candidateDriver", "EXECUTABLE_MUTATED_RUNTIME");
    mutation.put("actualMutationActions", artifacts.mutants().actualMutationActions());
    mutation.put("fullRestartGrammar", true);
    ObjectNode architecture = check.putObject("architecture");
    architecture.put("coreSources", artifacts.architecture().coreSources());
    architecture.put("localRuntimeSources", artifacts.architecture().localRuntimeSources());
    architecture.put("violations", artifacts.architecture().violations().size());
    ObjectNode evidence = check.putObject("faultEvidence");
    evidence.put("model", "CODE_LEVEL_DETERMINISTIC_INJECTION");
    evidence.put("childProcessCrashSmokes", artifacts.crashSmoke().processCrashes());
    evidence.put("beforeOperationFailures", artifacts.operationFailures().operationFailures());
    evidence.put("injectedEnospcWitness", true);
    evidence.put("injectedReadOnlyWitness", true);
    evidence.put("actualFilesystemForTypedInjections", false);
    evidence.put("preprovisionedWalDirectory", true);
    evidence.put("ancestorDirectoryDurabilityExternal", true);
    evidence.put("realPowerLoss", false);
    evidence.put("realDiskFull", false);
    evidence.put("physicalMediaDurability", false);
    ObjectNode stale = check.putObject("staleClassification");
    stale.put("reservedCode", "PRODUCER_SEQUENCE_STALE");
    stale.put("directWitness", false);
    stale.put("coverage", "STALE_SLOT_RESOLVES_BY_BINDING_PRECEDENCE");
    stale.put("exactPriorSlot", "DUPLICATE_REPLAYED");
    stale.put("differentIdentityPriorSlot", "SLOT_IDENTITY_CONFLICT");
    ObjectNode release = check.putObject("releaseTarget");
    release.put("unitTag", "course/m08-complete");
    release.putNull("productRelease");
    release.put("verification", "M08_EVIDENCE_ONLY");
    return check;
  }

  private static void writeFailure(Path root, Path reports, String status, String detail) {
    ObjectNode failure = JsonSupport.MAPPER.createObjectNode();
    failure.put("schemaVersion", "matching.m08.check.v2");
    failure.put("unit", "M08");
    failure.put("status", status);
    failure.put("contractPlanVersion", "0.10");
    failure.put("detail", detail == null || detail.isBlank() ? "unspecified M08 failure" : detail);
    ObjectNode release = failure.putObject("releaseTarget");
    release.put("unitTag", "course/m08-complete");
    release.putNull("productRelease");
    release.put("verification", "M08_EVIDENCE_ONLY");
    JsonSupport.validate(failure, readString(root.resolve(CHECK_SCHEMA_PATH)), false);
    write(reports, "check.json", failure);
  }

  private static void verifyCourse(Path root) {
    Properties properties = new Properties();
    try (var input = Files.newInputStream(root.resolve("course.properties"))) {
      properties.load(input);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot read course.properties", failure);
    }
    studentRequire("M08".equals(properties.getProperty("unit")), "course unit is not M08");
    studentRequire("0.10".equals(properties.getProperty("planVersion")), "course plan is not 0.10");
    studentRequire("COMPLETE".equals(properties.getProperty("lifecycle")), "M08 is not complete");
    studentRequire(
        "IMPLEMENTED".equals(properties.getProperty("designDepth")),
        "M08 design is not implemented");
    studentRequire(
        PASS.equals(properties.getProperty("m08Check.expectedStatus")),
        "course does not require M08 PASS");
  }

  private static void verifyFrozenInputs(Path root) {
    Path fixed = root.resolve(M08StartCheckRunner.FIXED_CORPUS_PATH);
    Path generator = root.resolve(M08StartCheckRunner.GENERATOR_PATH);
    studentRequire(
        M08StartCheckRunner.FIXED_CORPUS_SHA256.equals(Hashing.sha256Hex(readBytes(fixed))),
        "M08 fixed corpus SHA-256 changed");
    studentRequire(
        M08StartCheckRunner.GENERATOR_SHA256.equals(Hashing.sha256Hex(readBytes(generator))),
        "M08 generator SHA-256 changed");
    JsonSupport.validate(
        JsonSupport.parse(readBytes(fixed)),
        readString(root.resolve(M08StartCheckRunner.FIXED_SCHEMA_PATH)),
        false);
    JsonSupport.validate(
        JsonSupport.parse(readBytes(generator)),
        readString(root.resolve(M08StartCheckRunner.GENERATOR_SCHEMA_PATH)),
        false);
  }

  private static InheritedResult runM07(Path root, Path reports) {
    try {
      Class<?> runnerType = Class.forName("io.github.lchareln.cex.matching.testkit.M07CheckRunner");
      Object runner = runnerType.getConstructor().newInstance();
      Object result =
          runnerType.getMethod("run", Path.class, Path.class).invoke(runner, root, reports);
      String status = (String) result.getClass().getMethod("status").invoke(result);
      Path reportPath = (Path) result.getClass().getMethod("reportPath").invoke(result);
      return new InheritedResult(status, reportPath);
    } catch (ReflectiveOperationException failure) {
      throw new IllegalStateException(
          "M08 requires the completed M07 judge on its integration baseline", failure);
    }
  }

  private static ObjectNode report(String schemaVersion) {
    ObjectNode report = JsonSupport.MAPPER.createObjectNode();
    report.put("schemaVersion", schemaVersion);
    report.put("unit", "M08");
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
      throw new IllegalStateException("cannot create M08 report directory", failure);
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
      throw new IllegalStateException("cannot clear M08 path", failure);
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
      M08FixedSuite.Result fixed,
      M08GeneratedSuite.Result generated,
      M08GeneratedSuite.Result regenerated,
      M08CrashSmoke.Result crashSmoke,
      M08OperationFailureSuite.Result operationFailures,
      M08MutantSuite.Result mutants,
      M08ArchitectureGate.Report architecture) {}

  private record InheritedResult(String status, Path reportPath) {}

  private static final class StudentFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private StudentFailure(String message) {
      super(message);
    }
  }

  public record Result(String status, Path reportPath) {}
}
