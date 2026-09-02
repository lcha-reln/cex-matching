package io.github.lchareln.cex.matching.benchmark;

import io.github.lchareln.cex.matching.local.LocalMatchingService;
import io.github.lchareln.cex.matching.local.ServiceConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Runs calibration, every frozen open-loop point, M10Q2 soak promotion, and exact recovery. */
public final class M10QualificationRunner {
  private final Config config;

  public M10QualificationRunner(Config config) {
    this.config = Objects.requireNonNull(config, "config");
  }

  public RunResult run() throws IOException {
    if (Files.exists(config.walRoot())) {
      throw new IOException("WAL root already exists; refusing to mix qualification runs");
    }
    Files.createDirectories(config.walRoot());
    long runOriginNanos = System.nanoTime();
    Instant startedAt = Instant.now();
    long globalOperation = 0;

    try (QualificationArtifactSink sink =
        new QualificationArtifactSink(
            config.output(), config.artifactContext(), config.profile())) {
      CalibrationResult calibration = calibrate();
      List<List<MeasuredPoint>> sweeps = new ArrayList<>();
      for (int sweep = 1; sweep <= config.profile().sweeps(); sweep++) {
        List<MeasuredPoint> points = new ArrayList<>();
        for (int ladderIndex = 0;
            ladderIndex < config.profile().rateLadderPermille().size();
            ladderIndex++) {
          int permille = config.profile().rateLadderPermille().get(ladderIndex);
          long rate =
              config
                  .profile()
                  .offeredRate(calibration.referenceRateOperationsPerSecond(), ladderIndex);
          String stem = "sweep-%d-rate-%04d".formatted(sweep, permille);
          PointExecution execution =
              runMeasuredPoint(sink, runOriginNanos, globalOperation, sweep, permille, rate, stem);
          globalOperation = execution.nextGlobalOperation();
          points.add(new MeasuredPoint(execution.measurement(), execution.recovery()));
        }
        sweeps.add(List.copyOf(points));
      }

      List<List<RateMeasurement>> measured =
          sweeps.stream()
              .map(sweep -> sweep.stream().map(value -> value.load().rateMeasurement()).toList())
              .toList();
      SaturationAnalysis.PublishedEnvelope capacity =
          config.profile().id() == QualificationProfile.Id.CI_SMOKE
              ? SaturationAnalysis.publishSmoke(measured)
              : SaturationAnalysis.publish(measured);

      LongHorizonPromotion promotion =
          new LongHorizonPromotion(capacity.provisionalSoakCandidates());
      List<MeasuredPoint> soakAttempts = new ArrayList<>();
      Map<String, LoadPointResult> expectedPoints = new java.util.LinkedHashMap<>();
      sweeps.forEach(
          sweep ->
              sweep.forEach(
                  point -> expectedPoints.put(point.load().point().pointId(), point.load())));
      long expectedAcceptedTraceRecords =
          sweeps.stream()
              .flatMap(List::stream)
              .mapToLong(point -> point.recovery().durableOperations())
              .sum();
      QualificationArtifactSink.Inventory auditedInventory = null;
      RawArtifactRecomputer.RawRecomputation auditedRawRecomputation = null;
      while (promotion.hasNextCandidate()) {
        long rate = promotion.beginNextAttempt();
        int attemptNumber = promotion.currentAttemptNumber();
        try {
          PointExecution execution =
              runSoakPoint(sink, runOriginNanos, globalOperation, attemptNumber, rate);
          globalOperation = execution.nextGlobalOperation();
          MeasuredPoint measuredAttempt =
              new MeasuredPoint(execution.measurement(), execution.recovery());
          soakAttempts.add(measuredAttempt);
          expectedPoints.put(measuredAttempt.load().point().pointId(), measuredAttempt.load());
          expectedAcceptedTraceRecords =
              Math.addExact(
                  expectedAcceptedTraceRecords, measuredAttempt.recovery().durableOperations());
          auditedInventory = sink.snapshotRawArtifacts();
          auditedRawRecomputation =
              new RawArtifactRecomputer()
                  .recompute(
                      config.output(),
                      config.artifactContext(),
                      config.profile(),
                      auditedInventory,
                      expectedPoints,
                      expectedAcceptedTraceRecords,
                      sink.mapper());
          promotion.recordDecision(
              rate, SaturationAnalysis.classify(execution.measurement().rateMeasurement()));
        } catch (IOException | RuntimeException failure) {
          promotion.recordSystemError(rate);
          throw failure;
        }
      }
      if (!promotion.qualified()) {
        throw new IllegalStateException(
            "every M10Q2 full-duration soak candidate saturated after terminal drain and recovery"
                + " verification");
      }
      Instant finishedAt = Instant.now();
      EnvironmentFingerprint environment =
          EnvironmentFingerprint.capture(
              config.walRoot(),
              config.cpuModel(),
              config.storageDevice(),
              config.filesystem(),
              config.powerPolicy(),
              startedAt,
              finishedAt);
      QualificationArtifactSink.Inventory inventory = sink.finishRawArtifacts();
      if (!inventory.equals(auditedInventory) || auditedRawRecomputation == null) {
        throw new IllegalStateException(
            "final raw inventory differs from the qualified attempt audit");
      }
      Optional<DiagnosticJmh> diagnosticJmh = publishDiagnosticJmh();
      ObjectNode qualification =
          summary(
              sink,
              calibration,
              sweeps,
              capacity,
              promotion,
              soakAttempts,
              environment,
              inventory,
              auditedRawRecomputation,
              diagnosticJmh);
      sink.writeQualification(qualification);
      return new RunResult(
          calibration,
          sweeps,
          capacity,
          soakAttempts,
          promotion.qualifiedOperatingPoint(),
          promotion.qualifiedAttemptNumber(),
          environment,
          inventory,
          config.output().resolve("qualification.json"));
    }
  }

  private CalibrationResult calibrate() throws IOException {
    Path directory = config.walRoot().resolve("calibration");
    Files.createDirectory(directory);
    try (LocalMatchingService service =
        LocalMatchingService.open(
            config.profile().qualificationWalConfig(directory, 1), ServiceConfig.qualification())) {
      return new UnpacedCalibration().run(service, config.profile().calibration());
    }
  }

  private PointExecution runMeasuredPoint(
      QualificationArtifactSink sink,
      long runOriginNanos,
      long firstGlobalOperation,
      int sweep,
      int permille,
      long rate,
      String stem)
      throws IOException {
    Path directory = config.walRoot().resolve(stem);
    Files.createDirectory(directory);
    RecoveryTrace trace = new RecoveryTrace(directory.resolve("recovery-trace.m10r"), stem);
    LoadPointResult measurement;
    long nextGlobal;
    try (trace;
        LocalMatchingService service =
            LocalMatchingService.open(
                config.profile().qualificationWalConfig(directory, 1),
                ServiceConfig.qualification())) {
      LocalServiceLoadPoint runner =
          new LocalServiceLoadPoint(
              service, sink, config.profile(), runOriginNanos, firstGlobalOperation, trace);
      runner.execute(
          new QualificationArtifactSink.PointIdentity(
              stem + "-warmup", "WARMUP", sweep, permille, rate),
          config.profile().warmupPerRate(),
          false);
      measurement =
          runner.execute(
              new QualificationArtifactSink.PointIdentity(
                  stem + "-measurement", "MEASUREMENT", sweep, permille, rate),
              config.profile().measurementPerRate(),
              false);
      nextGlobal = runner.nextGlobalOperation();
    }
    RecoveryVerification recovery =
        new RecoveryVerifier()
            .verify(
                directory,
                config.walRoot().resolve(stem + "-direct-replay"),
                trace,
                config.profile(),
                measurement.actualSuffixRecords(),
                measurement.actualSuffixBytes());
    recordRecovery(sink, measurement.point(), recovery);
    return new PointExecution(measurement, recovery, nextGlobal);
  }

  private PointExecution runSoakPoint(
      QualificationArtifactSink sink,
      long runOriginNanos,
      long firstGlobalOperation,
      int attemptNumber,
      long provisionalOperatingPoint)
      throws IOException {
    String stem =
        "qop-soak-attempt-%02d-rate-%08d".formatted(attemptNumber, provisionalOperatingPoint);
    Path directory = config.walRoot().resolve(stem);
    Files.createDirectory(directory);
    RecoveryTrace trace = new RecoveryTrace(directory.resolve("recovery-trace.m10r"), stem);
    LoadPointResult soak;
    long nextGlobal;
    try (trace;
        LocalMatchingService service =
            LocalMatchingService.open(
                config.profile().qualificationWalConfig(directory, 1),
                ServiceConfig.qualification())) {
      LocalServiceLoadPoint runner =
          new LocalServiceLoadPoint(
              service, sink, config.profile(), runOriginNanos, firstGlobalOperation, trace);
      soak =
          runner.execute(
              new QualificationArtifactSink.PointIdentity(
                  stem, "SOAK", 0, 0, provisionalOperatingPoint),
              config.profile().soak(),
              false);
      nextGlobal = runner.nextGlobalOperation();
    }
    RecoveryVerification recovery =
        new RecoveryVerifier()
            .verify(
                directory,
                config.walRoot().resolve(stem + "-direct-replay"),
                trace,
                config.profile(),
                soak.actualSuffixRecords(),
                soak.actualSuffixBytes());
    recordRecovery(sink, soak.point(), recovery);
    return new PointExecution(soak, recovery, nextGlobal);
  }

  private static void recordRecovery(
      QualificationArtifactSink sink,
      QualificationArtifactSink.PointIdentity point,
      RecoveryVerification recovery) {
    sink.recordRecovery(point, recovery);
  }

  private ObjectNode summary(
      QualificationArtifactSink sink,
      CalibrationResult calibration,
      List<List<MeasuredPoint>> sweeps,
      SaturationAnalysis.PublishedEnvelope capacity,
      LongHorizonPromotion promotion,
      List<MeasuredPoint> soakAttempts,
      EnvironmentFingerprint environment,
      QualificationArtifactSink.Inventory inventory,
      RawArtifactRecomputer.RawRecomputation rawRecomputation,
      Optional<DiagnosticJmh> diagnosticJmh) {
    ObjectNode root = sink.mapper().createObjectNode();
    root.put("schemaVersion", QualificationArtifactSink.QUALIFICATION_SCHEMA);
    root.put("status", "PASS");
    root.put("runId", config.artifactContext().runId());
    root.put("profileId", config.profile().id().name());
    root.put("resultScope", config.profile().resultScope());
    root.put("eligibleForReleaseEvidence", config.profile().eligibleForReleaseEvidence());
    root.put("qualificationRuntimePolicyId", "M10Q2");
    ObjectNode source = root.putObject("source");
    source.put("commit", config.artifactContext().sourceCommit());
    source.put("workloadSha256", config.artifactContext().workloadSha256());
    writeRuntimeProvenance(root.putObject("runtimeProvenance"));
    writeProfile(root.putObject("profile"));
    writeQualificationRuntime(root.putObject("qualificationRuntime"));
    writeCalibration(root.putObject("calibration"), calibration);
    writeEnvironment(root.putObject("environment"), environment);

    ArrayNode sweepArray = root.putArray("sweeps");
    for (List<MeasuredPoint> sweep : sweeps) {
      ArrayNode points = sweepArray.addArray();
      sweep.forEach(value -> points.add(pointNode(sink, value)));
    }
    ObjectNode capacityNode = root.putObject("capacity");
    capacity.sweepKnees().forEach(capacityNode.putArray("sweepKnees")::add);
    capacityNode.put("publishedKnee", capacity.publishedKnee());
    capacityNode.put("qualifiedOperatingPointCandidate", capacity.qopCandidate());
    capacity
        .provisionalSoakCandidates()
        .forEach(capacityNode.putArray("provisionalSoakCandidates")::add);
    capacityNode.put("qualifiedOperatingPoint", promotion.qualifiedOperatingPoint());
    ObjectNode soakNode = root.putObject("soak");
    soakNode.put("durationSeconds", config.profile().soak().toSeconds());
    soakNode.put("promotionPolicyId", LongHorizonPromotion.POLICY_ID);
    ArrayNode attemptArray = soakNode.putArray("attempts");
    List<LongHorizonPromotion.Attempt> decisions = promotion.attempts();
    if (decisions.size() != soakAttempts.size()) {
      throw new IllegalStateException("promotion decisions and measured attempts differ");
    }
    for (int index = 0; index < decisions.size(); index++) {
      LongHorizonPromotion.Attempt decision = decisions.get(index);
      MeasuredPoint measured = soakAttempts.get(index);
      if (decision.offeredRate() != measured.load().point().offeredRate()) {
        throw new IllegalStateException("promotion decision rate differs from measured attempt");
      }
      ObjectNode attemptNode = attemptArray.addObject();
      attemptNode.put("attemptNumber", decision.attemptNumber());
      attemptNode.put("outcome", decision.outcome().name());
      attemptNode.set("point", pointNode(sink, measured));
    }
    soakNode.put("qualifiedAttemptNumber", promotion.qualifiedAttemptNumber());
    soakNode.put(
        "qualifiedPointId",
        soakAttempts.get(promotion.qualifiedAttemptNumber() - 1).load().point().pointId());
    ObjectNode raw = root.putObject("rawRecomputation");
    raw.put("status", "PASS");
    raw.put("fromDecompressedRaw", true);
    raw.put("arrivalRecords", rawRecomputation.arrivalRecords());
    raw.put("completionRecords", rawRecomputation.completionRecords());
    raw.put("queueRecords", rawRecomputation.queueRecords());
    raw.put("resourceRecords", rawRecomputation.resourceRecords());
    raw.put("maintenanceRecords", rawRecomputation.maintenanceRecords());
    raw.put("phaseCutRecords", rawRecomputation.phaseCutRecords());
    raw.put("acceptedTraceRecords", rawRecomputation.acceptedTraceRecords());
    raw.put("reconstructedRecoveryTraces", rawRecomputation.reconstructedRecoveryTraces());
    raw.put("exactAttemptJoin", rawRecomputation.exactAttemptJoinAndTraceHash());
    raw.put("reconstructedTraceHashesExact", rawRecomputation.exactAttemptJoinAndTraceHash());
    raw.put(
        "recoverySuffixRecordsAndBytesExact",
        rawRecomputation.exactRecoverySuffixRecordsAndBytes());
    raw.put(
        "allNewWalRecordsWithinPlanningCeiling",
        rawRecomputation.allNewWalRecordsWithinPlanningCeiling());
    raw.put("verifiedPublishedPoints", rawRecomputation.points().size());
    writeInventory(root.putObject("artifacts"), inventory, diagnosticJmh);
    return root;
  }

  private Optional<DiagnosticJmh> publishDiagnosticJmh() throws IOException {
    if (config.profile().id() != QualificationProfile.Id.RELEASE_QUALIFICATION) {
      return Optional.empty();
    }
    Path source = config.diagnosticJmh().orElseThrow();
    if (!Files.isRegularFile(source, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("JMH diagnostic is missing or is not a regular file: " + source);
    }
    tools.jackson.databind.JsonNode jmh =
        tools.jackson.databind.json.JsonMapper.builder().build().readTree(source.toFile());
    java.util.Set<String> expectedBenchmarks =
        java.util.Set.of(
            "io.github.lchareln.cex.matching.benchmark.CoreMatchingBenchmark.restingMakerThenMatchingTaker",
            "io.github.lchareln.cex.matching.benchmark.CoreMatchingBenchmark.canonicalEnvelopeDecode");
    java.util.Set<String> actualBenchmarks = new java.util.LinkedHashSet<>();
    if (!jmh.isArray() || jmh.size() != expectedBenchmarks.size()) {
      throw new IOException("JMH diagnostic must contain exactly the two frozen benchmarks");
    }
    for (tools.jackson.databind.JsonNode result : jmh) {
      requireJmhConfiguration(result);
      actualBenchmarks.add(result.path("benchmark").stringValue());
    }
    if (!actualBenchmarks.equals(expectedBenchmarks)) {
      throw new IOException("JMH diagnostic benchmark identities changed: " + actualBenchmarks);
    }
    Path directory = config.output().resolve("diagnostics");
    Files.createDirectory(directory);
    Path destination = directory.resolve("core-sample-time.json");
    Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES);
    long bytes = Files.size(destination);
    if (bytes <= 0) {
      throw new IOException("JMH diagnostic is empty");
    }
    return Optional.of(
        new DiagnosticJmh(
            "diagnostics/core-sample-time.json",
            bytes,
            sha256(destination),
            config.artifactContext().sourceCommit(),
            config.runtimeProvenance().matchingBenchmarkClassesSha256()));
  }

  private static void requireJmhConfiguration(tools.jackson.databind.JsonNode result)
      throws IOException {
    boolean exact =
        "sample".equals(result.path("mode").stringValue())
            && result.path("threads").intValue() == 1
            && result.path("forks").intValue() == 2
            && result.path("warmupIterations").intValue() == 3
            && "2 s".equals(result.path("warmupTime").stringValue())
            && result.path("measurementIterations").intValue() == 5
            && "3 s".equals(result.path("measurementTime").stringValue())
            && "ns/op".equals(result.path("primaryMetric").path("scoreUnit").stringValue())
            && result.path("primaryMetric").path("rawDataHistogram").isArray()
            && result.path("primaryMetric").path("rawDataHistogram").size() == 2;
    if (!exact) {
      throw new IOException(
          "JMH diagnostic must use sample, threads=1, forks=2, warmup=3x2s,"
              + " measurement=5x3s, ns/op, and one histogram per fork");
    }
  }

  private void writeRuntimeProvenance(ObjectNode node) {
    RuntimeProvenance provenance = config.runtimeProvenance();
    node.put("repositoryHead", provenance.repositoryHead());
    node.put("repositoryDirty", provenance.repositoryDirty());
    node.put("matchingBenchmarkClassesSha256", provenance.matchingBenchmarkClassesSha256());
    node.put("matchingLocalRuntimeClassesSha256", provenance.matchingLocalRuntimeClassesSha256());
    node.put("matchingCoreClassesSha256", provenance.matchingCoreClassesSha256());
    node.put("combinedRuntimeClassesSha256", provenance.combinedRuntimeClassesSha256());
  }

  private static String sha256(Path path) throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (var input = Files.newInputStream(path)) {
        byte[] buffer = new byte[64 * 1024];
        int count;
        while ((count = input.read(buffer)) >= 0) {
          digest.update(buffer, 0, count);
        }
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private void writeProfile(ObjectNode node) {
    node.put("id", config.profile().id().name());
    node.put("resultScope", config.profile().resultScope());
    node.put("eligibleForReleaseEvidence", config.profile().eligibleForReleaseEvidence());
    node.put("calibrationSeconds", config.profile().calibration().toSeconds());
    node.put("sweeps", config.profile().sweeps());
    node.put("warmupSecondsPerRate", config.profile().warmupPerRate().toSeconds());
    node.put("measurementSecondsPerRate", config.profile().measurementPerRate().toSeconds());
    config.profile().rateLadderPermille().forEach(node.putArray("rateLadderPermille")::add);
    FrozenPercentiles.QUANTILES.forEach(node.putArray("percentiles")::add);
    node.put("rankRule", FrozenPercentiles.RANK_RULE);
    node.put("soakSeconds", config.profile().soak().toSeconds());
    node.put("qualificationRuntimePolicyId", "M10Q2");
    node.put("recoveryBudgetMaxSuffixRecords", config.profile().recoveryBudgetMaxSuffixRecords());
    node.put("recoveryBudgetMaxSuffixBytes", config.profile().recoveryBudgetMaxSuffixBytes());
    node.put("proactiveCheckpointOffsetNanos", config.profile().proactiveCheckpointOffsetNanos());
    node.put("plannedWalRecordCeilingBytes", config.profile().plannedWalRecordCeilingBytes());
  }

  private void writeQualificationRuntime(ObjectNode node) {
    node.put("policyId", "M10Q2");
    node.put("scope", "M10_DEDICATED_NOT_M09_DEFAULT");
    ObjectNode m09 = node.putObject("m09Default");
    m09.put(
        "maxSuffixRecords",
        io.github.lchareln.cex.matching.local.RecoveryBudget.M09_DEFAULT.maxSuffixRecords());
    m09.put(
        "maxSuffixBytes",
        io.github.lchareln.cex.matching.local.RecoveryBudget.M09_DEFAULT.maxSuffixBytes());
    ObjectNode finite = node.putObject("finiteRecoveryBudget");
    finite.put("maxSuffixRecords", config.profile().recoveryBudgetMaxSuffixRecords());
    finite.put("maxSuffixBytes", config.profile().recoveryBudgetMaxSuffixBytes());
    node.put("proactiveCheckpointOffsetNanos", config.profile().proactiveCheckpointOffsetNanos());
    node.put(
        "proactiveCheckpointAdmissionLagMaxNanos",
        QualificationProfile.PROACTIVE_CHECKPOINT_ADMISSION_LAG_LIMIT_NANOS);
    node.put("plannedRecordCeilingBytes", config.profile().plannedWalRecordCeilingBytes());
    ObjectNode preflight = node.putObject("phaseBudgetPreflight");
    preflight.put(
        "prefixRecords",
        "START_SUFFIX_PLUS_ARRIVALS_SCHEDULED_BEFORE_CHECKPOINT_ADMISSION_DEADLINE_PLUS_QUEUE_CAPACITY_PLUS_ONE_OWNER");
    preflight.put(
        "postCheckpointSuffixRecords",
        "ALL_PLANNED_DURABLE_ARRIVALS_PLUS_QUEUE_CAPACITY_PLUS_ONE_OWNER");
    preflight.put("validatePrefixAndSuffixSeparately", true);
    ObjectNode scheduler = node.putObject("scheduler");
    scheduler.put("initialArrivalThread", "DEDICATED_NO_COMPLETION_CHECKPOINT_OR_ARTIFACT_IO");
    scheduler.put("coordinator", "ASYNC_COMPLETION_CHECKPOINT_RETRY_AND_ARTIFACT_IO");
    scheduler.put("scheduledObservationCutDoesNotMove", true);
    scheduler.put("producerClosureGraceMaxNanos", config.profile().producerLagMaxLimitNanos());
    scheduler.put("allScheduledArrivalsMaterialized", true);
    scheduler.put("allAdmissionDecisionsWithinLagLimits", true);
    scheduler.put("p99ProducerLagMaxNanos", config.profile().producerLagP99LimitNanos());
    scheduler.put("maxProducerLagMaxNanos", config.profile().producerLagMaxLimitNanos());
    scheduler.put("observationCutLagMaxNanos", config.profile().observationCutLagLimitNanos());
    writeRawTimeContract(node.putObject("rawTimeContract"));
    node.put(
        "observationCut",
        "IMMUTABLE_SCHEDULED_WINDOW_END_RAW_RECONSTRUCTED_BEFORE_PRODUCER_CLOSURE_AND_TERMINAL_DRAIN");
    node.put("terminalDrain", "ZERO_PENDING_BEFORE_RECOVERY");
    ObjectNode resource = node.putObject("resourceSampling");
    resource.put("targetCadenceNanos", config.profile().resourceIntervalNanos());
    resource.put("maximumScheduledGapNanos", 2_000_000_000L);
    resource.put("maximumObservedGapNanos", 2_000_000_000L);
    resource.put("maximumSamplingLagNanos", 2_000_000_000L);
    resource.put("scope", "SCHEDULED_WINDOW_THROUGH_TERMINAL_DRAIN");
    resource.put("cumulativeCounters", "MONOTONIC_NON_DECREASING");
    resource.put("gauges", "NON_NEGATIVE_NOT_CUMULATIVE");
    ObjectNode direct = node.putObject("directReplay");
    direct.put("runtimeConfig", "M08_LEGACY_UNBOUNDED_NO_SNAPSHOT");
    direct.put("purpose", "FRESH_ORDERED_APPLY_DIAGNOSTIC_ONLY");
  }

  static void writeRawTimeContract(ObjectNode node) {
    node.put("admissionTimestamp", "admissionDecisionNanos");
    node.put("admissionObservationKind", "ADMISSION_GATE_DECISION");
    node.put("completionTimestamp", "ownerCompletedNanos");
    node.put("completionTimeOrigin", "OWNER_COMPLETED_UNDER_GATE");
  }

  private static void writeCalibration(ObjectNode node, CalibrationResult value) {
    node.put("mode", "UNPACED");
    node.put("purpose", "RATE_SELECTION_ONLY");
    node.put("elapsedNanos", value.elapsedNanos());
    node.put("logicalOperations", value.logicalOperations());
    node.put("durableCompletions", value.durableCompletions());
    node.put("checkpointCount", value.checkpointCount());
    node.put("referenceRate", value.referenceRateOperationsPerSecond());
  }

  static void writeEnvironment(ObjectNode node, EnvironmentFingerprint value) {
    node.put("javaRuntime", value.javaRuntime());
    node.put("javaVersion", value.javaVersion());
    node.put("javaVendor", value.javaVendor());
    node.put("vmName", value.vmName());
    value.jvmArguments().forEach(node.putArray("jvmArguments")::add);
    node.put("osName", value.osName());
    node.put("osVersion", value.osVersion());
    node.put("osArchitecture", value.osArchitecture());
    node.put("availableProcessors", value.availableProcessors());
    node.put("physicalMemoryBytes", value.physicalMemoryBytes());
    node.put("maximumHeapBytes", value.maximumHeapBytes());
    value.garbageCollectorNames().forEach(node.putArray("garbageCollectorNames")::add);
    node.put("cpuModel", value.cpuModel());
    node.put("storageDevice", value.storageDevice());
    node.put("filesystem", value.filesystem());
    node.put("powerPolicy", value.powerPolicy());
    node.put("walRoot", value.walRoot());
    node.put("walRootUri", value.walRootUri());
    node.put("walFileStoreName", value.walFileStoreName());
    node.put("walFileStoreType", value.walFileStoreType());
    node.put("walFileStoreTotalSpaceBytes", value.walFileStoreTotalSpaceBytes());
    node.put("walFileStoreUsableSpaceBytes", value.walFileStoreUsableSpaceBytes());
    node.put("walFileStoreUnallocatedSpaceBytes", value.walFileStoreUnallocatedSpaceBytes());
    node.put("runStartedAt", value.runStartedAt().toString());
    node.put("runFinishedAt", value.runFinishedAt().toString());
  }

  private static ObjectNode pointNode(QualificationArtifactSink sink, MeasuredPoint value) {
    LoadPointResult load = value.load();
    ObjectNode node = sink.mapper().createObjectNode();
    node.put("pointId", load.point().pointId());
    node.put("phase", load.point().phase());
    node.put("sweep", load.point().sweep());
    node.put("ladderPermille", load.point().ladderPermille());
    node.put("offeredRate", load.point().offeredRate());
    ObjectNode logical = node.putObject("logical");
    logical.put("offers", load.logicalOffers());
    logical.put("initiallyAdmitted", load.logicalInitiallyAdmitted());
    logical.put("overloaded", load.logicalOverloaded());
    logical.put("closedOrInvalid", load.logicalClosedOrInvalid());
    logical.put("terminalCompletions", load.logicalTerminalCompletions());
    ObjectNode attempts = node.putObject("attempts");
    writeAccounting(attempts, load.attemptAccounting());
    ObjectNode latency = node.putObject("latencyPercentilesNanos");
    load.latencyPercentilesNanos().forEach(latency::put);
    node.put("p99QueueDepth", load.rateMeasurement().p99QueueDepth());
    node.put("startingBacklog", load.rateMeasurement().startingBacklog());
    node.put("endingBacklog", load.rateMeasurement().endingBacklog());
    QualificationRecoveryPlan.PhasePlan plan = load.recoveryPlan();
    ObjectNode budgetPlan = node.putObject("phaseBudgetPreflight");
    budgetPlan.put("plannedInitialOffers", plan.plannedInitialOffers());
    budgetPlan.put(
        "plannedBeforeLatestCheckpointAdmission", plan.plannedBeforeLatestCheckpointAdmission());
    budgetPlan.put("actualSuffixRecordsAtPhaseStart", plan.actualSuffixRecordsAtPhaseStart());
    budgetPlan.put("actualSuffixBytesAtPhaseStart", plan.actualSuffixBytesAtPhaseStart());
    budgetPlan.put("queueCapacityPlusOwnerBound", plan.ownerInFlightBound());
    budgetPlan.put("conservativeRetryDurableBound", plan.conservativeRetryDurableBound());
    budgetPlan.put("worstPrefixRecords", plan.worstRecordsBeforeCheckpoint());
    budgetPlan.put("worstPrefixBytes", plan.worstBytesBeforeCheckpoint());
    budgetPlan.put("worstPostCheckpointSuffixRecords", plan.worstSuffixRecordsAfterCheckpoint());
    budgetPlan.put("worstPostCheckpointSuffixBytes", plan.worstSuffixBytesAfterCheckpoint());
    budgetPlan.put("validatedSeparately", true);
    budgetPlan.put("actualSuffixRecordsAtTerminalDrain", load.actualSuffixRecords());
    budgetPlan.put("actualSuffixBytesAtTerminalDrain", load.actualSuffixBytes());
    PhaseEvidence.ObservationCut cut = load.phaseEvidence().observationCut();
    ObjectNode cutNode = node.putObject("observationCut");
    cutNode.put("phaseOriginNanos", cut.phaseOriginNanos());
    cutNode.put("scheduledWindowEndNanos", cut.scheduledWindowEndNanos());
    cutNode.put("observedNanos", cut.observedNanos());
    cutNode.put("observationLagNanos", cut.observationLagNanos());
    cutNode.put("queueCapacity", cut.queueCapacity());
    cutNode.put("startingBacklog", cut.startingBacklog());
    cutNode.put("plannedInitialOffers", cut.plannedInitialOffers());
    cutNode.put("initialDecisionsAtCut", cut.initialDecisionsAtCut());
    cutNode.put("scheduledDecisionBacklogAtCut", cut.scheduledDecisionBacklogAtCut());
    cutNode.put("servicePendingAtCut", cut.servicePendingAtCut());
    cutNode.put("endingBacklog", cut.endingBacklog());
    cutNode.put("p99QueueDepth", cut.p99QueueDepth());
    cutNode.put("postCutOverloaded", cut.postCutOverloaded());
    writeAccounting(cutNode.putObject("attemptAccounting"), cut.attemptAccounting());
    PhaseEvidence.PacingFidelity pacing = cut.pacingFidelity();
    ObjectNode pacingNode = cutNode.putObject("pacingFidelity");
    pacingNode.put("plannedInitialOffers", pacing.plannedInitialOffers());
    pacingNode.put("producedInitialOffers", pacing.producedInitialOffers());
    pacingNode.put("producerLagP99Nanos", pacing.producerLagP99Nanos());
    pacingNode.put("producerLagMaxNanos", pacing.producerLagMaxNanos());
    pacingNode.put("producerLagP99LimitNanos", pacing.producerLagP99LimitNanos());
    pacingNode.put("producerLagMaxLimitNanos", pacing.producerLagMaxLimitNanos());
    pacingNode.put("allScheduledArrivalsMaterialized", pacing.allScheduledArrivalsMaterialized());
    pacingNode.put(
        "allAdmissionDecisionsWithinLagLimits", pacing.allAdmissionDecisionsWithinLagLimits());
    pacingNode.put("passed", pacing.passed());
    PhaseEvidence.TerminalDrain drain = load.phaseEvidence().terminalDrain();
    ObjectNode drainNode = node.putObject("terminalDrain");
    drainNode.put("observedNanos", drain.observedNanos());
    drainNode.put("elapsedAfterObservationCutNanos", drain.elapsedAfterObservationCutNanos());
    drainNode.put("logicalTerminalCompletions", drain.logicalTerminalCompletions());
    drainNode.put("logicalLatencySamples", drain.logicalLatencySamples());
    writeAccounting(drainNode.putObject("attemptAccounting"), drain.attemptAccounting());
    SaturationAnalysis.SaturationDecision saturation =
        SaturationAnalysis.classify(load.rateMeasurement());
    node.put("saturated", saturation.saturated());
    saturation.reasons().forEach(node.putArray("saturationReasons")::add);
    RecoveryVerification recovery = value.recovery();
    ObjectNode recoveryNode = node.putObject("recovery");
    recoveryNode.put("durableOperations", recovery.durableOperations());
    recoveryNode.put("duplicatesReplayed", recovery.duplicatesReplayed());
    recoveryNode.put("liveResultDigest", recovery.liveResultDigest());
    recoveryNode.put("recoveredResultDigest", recovery.recoveredResultDigest());
    recoveryNode.put("directReplayResultDigest", recovery.directReplayResultDigest());
    recoveryNode.put("liveSemanticStateDigest", recovery.liveSemanticStateDigest());
    recoveryNode.put("recoveredSemanticStateDigest", recovery.recoveredSemanticStateDigest());
    recoveryNode.put("directReplaySemanticStateDigest", recovery.directReplaySemanticStateDigest());
    recoveryNode.put("recoveryTraceSha256", recovery.recoveryTraceSha256());
    recoveryNode.put("recoveryTraceId", recovery.recoveryTraceId());
    recoveryNode.put("configuredMaxSuffixRecords", recovery.configuredMaxSuffixRecords());
    recoveryNode.put("configuredMaxSuffixBytes", recovery.configuredMaxSuffixBytes());
    recoveryNode.put("actualSuffixRecords", recovery.actualSuffixRecords());
    recoveryNode.put("actualSuffixBytes", recovery.actualSuffixBytes());
    recoveryNode.put("recoveryElapsedNanos", recovery.recoveryElapsedNanos());
    recoveryNode.put("recoveredWalConfig", "M10_DEDICATED_FINITE_WITH_M09S1");
    recoveryNode.put("directReplayWalConfig", "M08_LEGACY_UNBOUNDED_NO_SNAPSHOT");
    return node;
  }

  private static void writeAccounting(ObjectNode node, RunAccounting value) {
    node.put("offers", value.offers());
    node.put("admitted", value.admitted());
    node.put("overloaded", value.overloaded());
    node.put("closedOrInvalid", value.closedOrInvalid());
    ObjectNode variants = node.putObject("submissionResultVariants");
    value.submissionResultVariants().forEach(variants::put);
    node.put("explicitServiceFailures", value.explicitServiceFailures());
    node.put("pending", value.pendingAtObservationCut());
    node.put("durableAcknowledgements", value.durableAcknowledgements());
  }

  private static void writeInventory(
      ObjectNode node,
      QualificationArtifactSink.Inventory inventory,
      Optional<DiagnosticJmh> diagnosticJmh) {
    ObjectNode streams = node.putObject("streams");
    inventory
        .streams()
        .forEach(
            (name, shards) -> {
              ArrayNode array = streams.putArray(name);
              shards.forEach(
                  shard -> {
                    ObjectNode item = array.addObject();
                    item.put("relativePath", shard.relativePath());
                    item.put("recordCount", shard.recordCount());
                    item.put("compressedBytes", shard.compressedBytes());
                    item.put("sha256", shard.sha256());
                  });
            });
    ObjectNode recovery = node.putObject("recoveryJson");
    recovery.put("relativePath", "recovery.json");
    recovery.put("recordCount", inventory.recoveryRecordCount());
    recovery.put("bytes", inventory.recoveryBytes());
    recovery.put("sha256", inventory.recoverySha256());
    diagnosticJmh.ifPresent(
        diagnostic -> {
          ObjectNode jmh = node.putObject("diagnosticJmh");
          jmh.put("relativePath", diagnostic.relativePath());
          jmh.put("bytes", diagnostic.bytes());
          jmh.put("sha256", diagnostic.sha256());
          jmh.put("jmhVersion", "1.37");
          jmh.put("harness", "JMH");
          // JMH's JSON machine value for Mode.SampleTime is "sample".
          jmh.put("mode", "sample");
          jmh.putArray("benchmarks")
              .add(
                  "io.github.lchareln.cex.matching.benchmark.CoreMatchingBenchmark.restingMakerThenMatchingTaker")
              .add(
                  "io.github.lchareln.cex.matching.benchmark.CoreMatchingBenchmark.canonicalEnvelopeDecode");
          jmh.put("resultScope", "DIAGNOSTIC_ONLY");
          jmh.put("eligibleForCapacityEnvelope", false);
          jmh.put("sourceCommit", diagnostic.sourceCommit());
          jmh.put("benchmarkClassesSha256", diagnostic.benchmarkClassesSha256());
        });
  }

  public record Config(
      QualificationProfile profile,
      ArtifactContext artifactContext,
      Path walRoot,
      Path output,
      String cpuModel,
      String storageDevice,
      String filesystem,
      String powerPolicy,
      Optional<Path> diagnosticJmh,
      RuntimeProvenance runtimeProvenance) {
    public Config {
      Objects.requireNonNull(profile, "profile");
      Objects.requireNonNull(artifactContext, "artifactContext");
      walRoot = Objects.requireNonNull(walRoot, "walRoot").toAbsolutePath().normalize();
      output = Objects.requireNonNull(output, "output").toAbsolutePath().normalize();
      diagnosticJmh = Objects.requireNonNull(diagnosticJmh, "diagnosticJmh");
      runtimeProvenance = Objects.requireNonNull(runtimeProvenance, "runtimeProvenance");
      diagnosticJmh = diagnosticJmh.map(value -> value.toAbsolutePath().normalize());
      if (profile.id() != artifactContext.profileId()
          || !profile.resultScope().equals(artifactContext.resultScope())
          || profile.eligibleForReleaseEvidence() != artifactContext.eligibleForReleaseEvidence()) {
        throw new IllegalArgumentException("profile and raw artifact identity disagree");
      }
      if (walRoot.startsWith(output) || output.startsWith(walRoot)) {
        throw new IllegalArgumentException("WAL and immutable output roots must not overlap");
      }
      if (profile.id() == QualificationProfile.Id.RELEASE_QUALIFICATION
          && diagnosticJmh.isEmpty()) {
        throw new IllegalArgumentException("release qualification requires a JMH diagnostic");
      }
      if (profile.id() == QualificationProfile.Id.CI_SMOKE && diagnosticJmh.isPresent()) {
        throw new IllegalArgumentException("CI smoke must not carry a release JMH diagnostic");
      }
      if (!artifactContext.sourceCommit().equals(runtimeProvenance.repositoryHead())) {
        throw new IllegalArgumentException("runtime provenance HEAD differs from artifact source");
      }
      if (profile.eligibleForReleaseEvidence() && runtimeProvenance.repositoryDirty()) {
        throw new IllegalArgumentException("release runtime provenance cannot be dirty");
      }
    }
  }

  public record MeasuredPoint(LoadPointResult load, RecoveryVerification recovery) {}

  public record RunResult(
      CalibrationResult calibration,
      List<List<MeasuredPoint>> sweeps,
      SaturationAnalysis.PublishedEnvelope capacity,
      List<MeasuredPoint> soakAttempts,
      long qualifiedOperatingPoint,
      int qualifiedAttemptNumber,
      EnvironmentFingerprint environment,
      QualificationArtifactSink.Inventory inventory,
      Path qualificationJson) {
    public RunResult {
      sweeps = sweeps.stream().map(List::copyOf).toList();
      soakAttempts = List.copyOf(soakAttempts);
      if (qualifiedOperatingPoint <= 0
          || qualifiedAttemptNumber <= 0
          || qualifiedAttemptNumber > soakAttempts.size()) {
        throw new IllegalArgumentException("invalid qualified soak promotion result");
      }
    }
  }

  private record PointExecution(
      LoadPointResult measurement, RecoveryVerification recovery, long nextGlobalOperation) {}

  private record DiagnosticJmh(
      String relativePath,
      long bytes,
      String sha256,
      String sourceCommit,
      String benchmarkClassesSha256) {}
}
