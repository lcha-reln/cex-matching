package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.SingleInstrumentMatchingEngine;
import io.github.lchareln.cex.matching.benchmark.FrozenPercentiles;
import io.github.lchareln.cex.matching.benchmark.M10QualificationRunner;
import io.github.lchareln.cex.matching.benchmark.RateMeasurement;
import io.github.lchareln.cex.matching.benchmark.RunAccounting;
import io.github.lchareln.cex.matching.benchmark.ScheduledArrival;
import io.github.lchareln.cex.matching.local.LocalMatchingService;
import io.github.lchareln.cex.matching.local.M08Envelope;
import io.github.lchareln.cex.matching.local.M08EnvelopeCodec;
import io.github.lchareln.cex.matching.local.StructuralRejectionException;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Independently hashes, decompresses, reconciles, and re-derives a full M10 release bundle. */
final class M10ReleaseBundleVerifier {
  static final String SCHEMA_PATH = "schemas/matching.m10.qualification.v1.schema.json";
  private static final String WORKLOAD_SHA256 = M10CheckRunner.WORKLOAD_SHA256;
  private static final List<String> STREAMS =
      List.of(
          "raw-phase-cuts",
          "raw-arrivals",
          "raw-maintenance",
          "raw-completions",
          "raw-queue",
          "resources",
          "accepted-trace");
  private static final Map<String, String> RECORD_SCHEMAS =
      Map.of(
          "raw-arrivals", "matching.m10.raw-arrival.v2",
          "raw-completions", "matching.m10.raw-completion.v2",
          "raw-queue", "matching.m10.raw-queue.v2",
          "resources", "matching.m10.resource-observation.v1",
          "raw-maintenance", "matching.m10.raw-maintenance.v1",
          "raw-phase-cuts", "matching.m10.raw-phase-cut.v2",
          "accepted-trace", "matching.m10.accepted-trace.v2");
  private static final Set<String> SUBMISSION_VARIANTS =
      Set.of(
          "NEW_DURABLY_APPLIED",
          "DUPLICATE_REPLAYED",
          "STRUCTURAL_REJECTED",
          "PREFLIGHT_REJECTED",
          "CHECKPOINT_REQUIRED",
          "DURABILITY_UNKNOWN",
          "FAILED_CLOSED");
  private static final Set<String> COMMON_RAW_FIELDS =
      Set.of(
          "schemaVersion",
          "runId",
          "profileId",
          "resultScope",
          "eligibleForReleaseEvidence",
          "sourceCommit",
          "workloadSha256",
          "qualificationRuntimePolicyId",
          "qualificationRecoveryBudgetPolicy",
          "qualificationMaxSuffixRecords",
          "qualificationMaxSuffixBytes",
          "m09DefaultMaxSuffixRecords",
          "m09DefaultMaxSuffixBytes",
          "plannedWalRecordCeilingBytes",
          "proactiveCheckpointOffsetNanos",
          "recordType",
          "pointId",
          "phase",
          "sweep",
          "ladderPermille",
          "offeredRate");
  private static final Map<String, Set<String>> RAW_FIELDS =
      Map.of(
          "raw-arrivals",
          withCommon(
              "logicalOperationId",
              "attempt",
              "retryOriginAttempt",
              "retryOfferOrdinal",
              "canonicalEnvelopeSha256",
              "attemptKind",
              "timeDomain",
              "latencyOrigin",
              "scheduledArrivalNanos",
              "admissionDecisionNanos",
              "producerLagNanos",
              "admissionOutcome",
              "rejectionCode",
              "observationKind",
              "decisionQueueDepth"),
          "raw-completions",
          withCommon(
              "logicalOperationId",
              "attempt",
              "canonicalEnvelopeSha256",
              "timeDomain",
              "latencyOrigin",
              "timeOrigin",
              "scheduledArrivalNanos",
              "ownerCompletedNanos",
              "latencyFromScheduledNanos",
              "completionKind",
              "submissionResultVariant",
              "serviceFailureCode",
              "logicalTerminal",
              "canonicalResultDigest",
              "semanticStateDigest",
              "walRecordLength"),
          "raw-queue",
          withCommon(
              "logicalOperationId",
              "attempt",
              "attemptKind",
              "admissionDecisionNanos",
              "observationKind",
              "decisionQueueDepth"),
          "resources",
          withCommon(
              "sampleSequence",
              "sampleKind",
              "scheduledSampleNanos",
              "observedNanos",
              "samplingLagNanos",
              "allocationUnit",
              "totalThreadAllocatedBytes",
              "gcCountUnit",
              "garbageCollectionCount",
              "gcTimeUnit",
              "garbageCollectionMillis",
              "cpuUnit",
              "processCpuNanos",
              "memoryUnit",
              "heapUsedBytes",
              "committedVirtualMemoryBytes",
              "systemMemoryUsedBytes",
              "queueDepth"),
          "raw-phase-cuts",
          withCommon("observationCut", "terminalDrain"),
          "accepted-trace",
          withCommon(
              "recoveryTraceId",
              "traceOrdinal",
              "logicalOperationId",
              "attempt",
              "canonicalEnvelopeEncoding",
              "canonicalEnvelopeSha256",
              "canonicalEnvelopeBase64",
              "canonicalResultDigest",
              "semanticStateDigest"));
  private static final Set<String> MAINTENANCE_ADMISSION_FIELDS =
      withCommon(
          "eventKind",
          "maintenanceType",
          "maintenanceAttempt",
          "reason",
          "scheduledPhaseOffsetNanos",
          "scheduledNanos",
          "offeredNanos",
          "offerLagNanos",
          "admissionOutcome",
          "rejectionCode");
  private static final Set<String> MAINTENANCE_COMPLETION_FIELDS =
      withCommon(
          "eventKind",
          "maintenanceType",
          "maintenanceAttempt",
          "reason",
          "scheduledPhaseOffsetNanos",
          "scheduledNanos",
          "offeredNanos",
          "terminalNanos",
          "pauseFromScheduledNanos",
          "completionKind",
          "failureCode",
          "suffixRecordsBeforeReset",
          "suffixBytesBeforeReset",
          "suffixRecordsAfterReset",
          "suffixBytesAfterReset");
  private static final Set<String> REQUIRED_JMH_BENCHMARKS =
      Set.of(
          "io.github.lchareln.cex.matching.benchmark.CoreMatchingBenchmark.restingMakerThenMatchingTaker",
          "io.github.lchareln.cex.matching.benchmark.CoreMatchingBenchmark.canonicalEnvelopeDecode");

  private enum BundleProfile {
    RELEASE(
        "RELEASE_QUALIFICATION", "RELEASE_QUALIFICATION", true, 20, 3, 10, 30, 1_800, 24, 25, 49),
    SMOKE("CI_SMOKE", "METHOD_SMOKE_ONLY", false, 1, 1, 1, 2, 3, 8, 9, 17);

    final String id;
    final String scope;
    final boolean eligible;
    final int calibrationSeconds;
    final int sweeps;
    final int warmupSeconds;
    final int measurementSeconds;
    final int soakSeconds;
    final int warmupPoints;
    final int publishedPoints;
    final int rawPoints;

    BundleProfile(
        String id,
        String scope,
        boolean eligible,
        int calibrationSeconds,
        int sweeps,
        int warmupSeconds,
        int measurementSeconds,
        int soakSeconds,
        int warmupPoints,
        int publishedPoints,
        int rawPoints) {
      this.id = id;
      this.scope = scope;
      this.eligible = eligible;
      this.calibrationSeconds = calibrationSeconds;
      this.sweeps = sweeps;
      this.warmupSeconds = warmupSeconds;
      this.measurementSeconds = measurementSeconds;
      this.soakSeconds = soakSeconds;
      this.warmupPoints = warmupPoints;
      this.publishedPoints = publishedPoints;
      this.rawPoints = rawPoints;
    }
  }

  Result verify(Path repositoryRoot, Path releaseDirectory, String sourceCommit) {
    Path root = repositoryRoot.toAbsolutePath().normalize();
    Path release = releaseDirectory.toAbsolutePath().normalize();
    require(
        release.equals(root.resolve("build/reports/m10-release")),
        "invalid M10 release report directory");
    return verifyAt(root, release, sourceCommit, BundleProfile.RELEASE);
  }

  Result verifySmoke(Path repositoryRoot, Path smokeDirectory, String sourceCommit) {
    Path root = repositoryRoot.toAbsolutePath().normalize();
    Path smoke = smokeDirectory.toAbsolutePath().normalize();
    require(
        smoke.equals(root.resolve("build/reports/m10-ci-smoke")),
        "invalid M10 CI smoke report directory");
    return verifyAt(root, smoke, sourceCommit, BundleProfile.SMOKE);
  }

  /** Re-verifies a staged or published copy below the same repository root. */
  Result verifyCopy(Path repositoryRoot, Path releaseDirectory, String sourceCommit) {
    Path root = repositoryRoot.toAbsolutePath().normalize();
    Path release = releaseDirectory.toAbsolutePath().normalize();
    require(release.startsWith(root), "copied M10 release bundle escapes repository root");
    require(
        !release.equals(root.resolve("build/reports/m10-release")),
        "source M10 release bundle must use verify");
    return verifyAt(root, release, sourceCommit, BundleProfile.RELEASE);
  }

  private Result verifyAt(
      Path root, Path release, String sourceCommit, BundleProfile expectedProfile) {
    SafeOutputPaths.requireNoSymlinkComponents(root, release);
    require(Files.isDirectory(release, LinkOption.NOFOLLOW_LINKS), "M10 release bundle is missing");
    Path qualificationPath = release.resolve("qualification.json");
    JsonNode qualification = JsonSupport.parse(readBytes(qualificationPath));
    JsonSupport.validate(qualification, readString(root.resolve(SCHEMA_PATH)), true);
    verifyFrozenIdentity(root, qualification, sourceCommit, expectedProfile);
    String runId = text(qualification, "runId");

    Map<String, JsonNode> summaries = summaryPoints(qualification);
    Path temporary;
    try {
      temporary = Files.createTempDirectory("m10-release-recompute-");
    } catch (IOException failure) {
      throw new IllegalStateException("cannot create raw recomputation directory", failure);
    }
    Map<String, PointRaw> points = new LinkedHashMap<>();
    Map<String, TraceAccumulator> traces = new LinkedHashMap<>();
    List<Path> files = new ArrayList<>();
    files.add(Path.of("qualification.json"));
    Map<String, Long> streamRecordCounts = new LinkedHashMap<>();
    long rawRecords = 0;
    try {
      JsonNode streamInventory = qualification.path("artifacts").path("streams");
      require(
          streamInventory.isObject() && streamInventory.size() == STREAMS.size(),
          "release stream inventory changed");
      for (String stream : STREAMS) {
        JsonNode shards = streamInventory.path(stream);
        require(shards.isArray() && !shards.isEmpty(), "release stream has no shards: " + stream);
        long streamRecords = 0;
        int expectedPart = 0;
        for (JsonNode shard : shards) {
          String expectedSuffix = "part-%05d.jsonl.gz".formatted(expectedPart++);
          String relative = text(shard, "relativePath");
          require(
              relative.equals(stream + '/' + expectedSuffix),
              "non-contiguous shard path " + relative);
          Path relativePath = safeRelative(relative);
          Path file = resolveFile(root, release, relativePath);
          long count = positiveLong(shard, "recordCount");
          require(count <= 25_000, "shard exceeds 25,000 records");
          require(
              Files.size(file) == positiveLong(shard, "compressedBytes"),
              "compressed byte count changed: " + relative);
          require(
              Files.size(file) < 100L * 1024L * 1024L,
              "compressed shard would exceed the Git object limit: " + relative);
          require(
              streamSha256(file).equals(text(shard, "sha256")),
              "compressed shard hash changed: " + relative);
          long observed =
              readShard(
                  file, stream, runId, sourceCommit, expectedProfile, temporary, points, traces);
          require(observed == count, "decompressed record count changed: " + relative);
          streamRecords = Math.addExact(streamRecords, observed);
          files.add(relativePath);
        }
        require(streamRecords > 0, "release stream is empty: " + stream);
        streamRecordCounts.put(stream, streamRecords);
        rawRecords = Math.addExact(rawRecords, streamRecords);
      }
      closeSpools(points);
      verifyRawPoints(points, summaries, expectedProfile);
      verifyRecovery(
          root,
          release,
          runId,
          sourceCommit,
          qualification,
          summaries,
          points,
          traces,
          expectedProfile,
          files);
      if (expectedProfile == BundleProfile.RELEASE) {
        verifyJmhDiagnostic(root, release, sourceCommit, qualification, files);
      } else {
        require(
            !qualification.path("artifacts").has("diagnosticJmh"),
            "CI smoke must not carry release JMH diagnostics");
      }
      verifyCapacity(qualification, summaries, expectedProfile);
      verifyRawRecomputation(qualification, streamRecordCounts, summaries.size(), traces.size());
      require(
          points.size() == expectedProfile.rawPoints,
          "raw point count differs from frozen profile");

      ObjectNode verified = (ObjectNode) qualification.deepCopy();
      ObjectNode recomputation = (ObjectNode) verified.path("rawRecomputation");
      recomputation.put("status", "PASS");
      recomputation.put("fromDecompressedRaw", true);
      recomputation.put("rawRecords", rawRecords);
      recomputation.put("rawPoints", points.size());
      recomputation.put("percentilesRecomputed", true);
      recomputation.put("accountingReconciled", true);
      recomputation.put("capacityEnvelopeRecomputed", true);
      files.sort(Comparator.comparing(Path::toString));
      require(new LinkedHashSet<>(files).size() == files.size(), "release file listed twice");
      return new Result(verified, List.copyOf(files), rawRecords);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot verify M10 release bundle", failure);
    } finally {
      closeSpools(points);
      deleteTree(temporary);
    }
  }

  private static long readShard(
      Path file,
      String stream,
      String runId,
      String sourceCommit,
      BundleProfile expectedProfile,
      Path temporary,
      Map<String, PointRaw> points,
      Map<String, TraceAccumulator> traces)
      throws IOException {
    long records = 0;
    PointRaw previous = null;
    try (var input =
            new GZIPInputStream(
                new BufferedInputStream(Files.newInputStream(file), 64 * 1024), 64 * 1024);
        var reader =
            new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8), 64 * 1024)) {
      String line;
      while ((line = reader.readLine()) != null) {
        require(!line.isBlank(), "raw shard contains a blank line");
        JsonNode record = JsonSupport.parse(line.getBytes(StandardCharsets.UTF_8));
        verifyCommonRecord(record, stream, runId, sourceCommit, expectedProfile);
        verifyExactRawFields(record, stream);
        String pointId = text(record, "pointId");
        PointRaw point =
            points.computeIfAbsent(
                pointId,
                ignored ->
                    new PointRaw(
                        pointId, temporary.resolve("point-" + points.size()), expectedProfile));
        if (previous != null && previous != point) previous.closeQuietly();
        previous = point;
        point.accept(stream, record);
        if ("accepted-trace".equals(stream)) {
          traces
              .computeIfAbsent(text(record, "recoveryTraceId"), TraceAccumulator::new)
              .accept(record);
        }
        records = Math.incrementExact(records);
      }
      if (previous != null) previous.closeQuietly();
    }
    return records;
  }

  private static void verifyCommonRecord(
      JsonNode record,
      String stream,
      String runId,
      String sourceCommit,
      BundleProfile expectedProfile) {
    require(
        RECORD_SCHEMAS.get(stream).equals(text(record, "schemaVersion")),
        "raw record schema changed in " + stream);
    require("DATA".equals(text(record, "recordType")), "raw record is not DATA");
    require(expectedProfile.id.equals(text(record, "profileId")), "raw profile changed");
    require(expectedProfile.scope.equals(text(record, "resultScope")), "raw scope changed");
    require(
        record.path("eligibleForReleaseEvidence").booleanValue() == expectedProfile.eligible,
        "raw record eligibility changed");
    require(sourceCommit.equals(text(record, "sourceCommit")), "raw source commit changed");
    require(WORKLOAD_SHA256.equals(text(record, "workloadSha256")), "raw workload hash changed");
    require(runId.equals(text(record, "runId")), "raw run ID changed");
    require(
        "M10Q1".equals(text(record, "qualificationRuntimePolicyId")), "raw runtime policy changed");
    require(
        "M10_DEDICATED_NOT_M09_DEFAULT".equals(text(record, "qualificationRecoveryBudgetPolicy")),
        "raw recovery budget policy changed");
    require(
        record.path("qualificationMaxSuffixRecords").longValue() == 1_000_000L
            && record.path("qualificationMaxSuffixBytes").longValue() == 1_073_741_824L
            && record.path("m09DefaultMaxSuffixRecords").longValue() == 64L
            && record.path("m09DefaultMaxSuffixBytes").longValue() == 1_048_576L
            && record.path("plannedWalRecordCeilingBytes").intValue() == 1_024
            && record.path("proactiveCheckpointOffsetNanos").longValue() == 100_000_000L,
        "raw M10Q1 finite runtime boundary changed");
  }

  static void verifyExactRawFields(JsonNode record, String stream) {
    require(record instanceof ObjectNode, "raw record is not an object in " + stream);
    Set<String> expected;
    if ("raw-maintenance".equals(stream)) {
      expected =
          switch (text(record, "eventKind")) {
            case "ADMISSION" -> MAINTENANCE_ADMISSION_FIELDS;
            case "COMPLETION" -> MAINTENANCE_COMPLETION_FIELDS;
            default -> throw new IllegalStateException("unknown maintenance event kind");
          };
    } else {
      expected = RAW_FIELDS.get(stream);
      require(expected != null, "unknown raw stream for exact field gate: " + stream);
    }
    Set<String> actual = new LinkedHashSet<>(((ObjectNode) record).propertyNames());
    if (!actual.equals(expected)) {
      Set<String> unexpected = new LinkedHashSet<>(actual);
      unexpected.removeAll(expected);
      Set<String> missing = new LinkedHashSet<>(expected);
      missing.removeAll(actual);
      throw new IllegalStateException(
          "raw record field set changed in "
              + stream
              + ": unexpected="
              + unexpected
              + ", missing="
              + missing);
    }
  }

  private static Set<String> withCommon(String... fields) {
    Set<String> result = new LinkedHashSet<>(COMMON_RAW_FIELDS);
    result.addAll(Arrays.asList(fields));
    return Set.copyOf(result);
  }

  private static void verifyRawPoints(
      Map<String, PointRaw> points, Map<String, JsonNode> summaries, BundleProfile expectedProfile)
      throws IOException {
    int warmups = 0;
    for (PointRaw raw : points.values()) {
      raw.finish();
      raw.verifyTerminalLedgers();
      raw.verifyScheduledWindow();
      RunAccounting terminalAccounting = raw.terminalAccounting();
      RunAccounting cutAccounting = raw.cutAccounting();
      require(
          terminalAccounting.submissionResultVariants().get("CHECKPOINT_REQUIRED") == 0
              && cutAccounting.submissionResultVariants().get("CHECKPOINT_REQUIRED") == 0,
          "qualification load encountered checkpoint-required logical retry backlog");
      JsonNode rawCut = raw.phaseCut.path("observationCut");
      JsonNode rawDrain = raw.phaseCut.path("terminalDrain");
      long plannedDemand = raw.logicalOffers;
      long undecidedDemand = Math.subtractExact(plannedDemand, raw.cutInitialDecisions);
      long servicePending = cutAccounting.pendingAtObservationCut();
      long endingDemandBacklog = Math.addExact(undecidedDemand, servicePending);
      long postCutOverloaded =
          Math.subtractExact(terminalAccounting.overloaded(), cutAccounting.overloaded());
      require(
          cutAccounting.equals(accounting(rawCut.path("attemptAccounting"))),
          "raw observation-cut accounting changed: " + raw.pointId);
      require(
          nonNegative(rawCut, "plannedInitialOffers") == plannedDemand
              && nonNegative(rawCut, "initialDecisionsAtCut") == raw.cutInitialDecisions
              && nonNegative(rawCut, "scheduledDecisionBacklogAtCut") == undecidedDemand
              && nonNegative(rawCut, "servicePendingAtCut") == servicePending
              && nonNegative(rawCut, "endingBacklog") == endingDemandBacklog
              && nonNegative(rawCut, "postCutOverloaded") == postCutOverloaded
              && raw.cutInitialDecisions == cutAccounting.offers()
              && raw.cutInitialDecisions
                  == Math.addExact(
                      cutAccounting.admitted(),
                      Math.addExact(cutAccounting.overloaded(), cutAccounting.closedOrInvalid())),
          "scheduled-cut demand/admission/backlog equations changed: " + raw.pointId);
      require(
          terminalAccounting.equals(accounting(rawDrain.path("attemptAccounting"))),
          "raw terminal-drain accounting changed: " + raw.pointId);
      require(
          raw.logicalTerminal == nonNegative(rawDrain, "logicalTerminalCompletions")
              && raw.latencies.count() == nonNegative(rawDrain, "logicalLatencySamples"),
          "terminal logical denominator changed: " + raw.pointId);
      long p99Queue = raw.initialQueues.quantile(0.99);
      long producerLagP99 = raw.producerLags.quantile(0.99);
      long producerLagMax = raw.producerLags.max();
      require(
          p99Queue == nonNegative(rawCut, "p99QueueDepth"),
          "phase-cut queue p99 changed: " + raw.pointId);
      JsonNode pacing = rawCut.path("pacingFidelity");
      require(
          nonNegative(pacing, "plannedInitialOffers") == raw.logicalOffers
              && nonNegative(pacing, "producedInitialOffers") == raw.logicalOffers
              && nonNegative(pacing, "producerLagP99Nanos") == producerLagP99
              && nonNegative(pacing, "producerLagMaxNanos") == producerLagMax
              && pacing.path("allScheduledArrivalsMaterialized").booleanValue()
              && pacing.path("allAdmissionDecisionsWithinLagLimits").booleanValue()
              && pacing.path("passed").booleanValue()
              && producerLagP99 <= 50_000_000L
              && producerLagMax <= 250_000_000L,
          "producer pacing fidelity changed: " + raw.pointId);

      JsonNode summary = summaries.get(raw.pointId);
      if (summary == null) {
        require("WARMUP".equals(raw.phase), "unsummarized raw point is not warmup: " + raw.pointId);
        require(raw.pointId.endsWith("-warmup"), "warmup point identity changed");
        warmups++;
        continue;
      }
      require(raw.phase.equals(text(summary, "phase")), "point phase differs from raw");
      require(raw.sweep == summary.path("sweep").intValue(), "point sweep differs from raw");
      require(
          raw.ladderPermille == summary.path("ladderPermille").intValue(),
          "point ladder differs from raw");
      require(
          raw.offeredRate == summary.path("offeredRate").longValue(),
          "point rate differs from raw");
      JsonNode logical = summary.path("logical");
      require(raw.logicalOffers == logical.path("offers").longValue(), "logical offers changed");
      require(
          raw.logicalInitiallyAdmitted == logical.path("initiallyAdmitted").longValue(),
          "logical admissions changed");
      require(
          raw.logicalOverloaded == logical.path("overloaded").longValue(),
          "logical overload changed");
      require(
          raw.logicalClosed == logical.path("closedOrInvalid").longValue(),
          "logical closed count changed");
      require(
          raw.logicalTerminal == logical.path("terminalCompletions").longValue(),
          "logical terminal count changed");
      require(
          terminalAccounting.equals(accounting(summary.path("attempts"))),
          "terminal attempt accounting changed: " + raw.pointId);
      Map<String, Long> latency = raw.latencies.quantiles(FrozenPercentiles.QUANTILES);
      JsonNode published = summary.path("latencyPercentilesNanos");
      for (Map.Entry<String, Long> value : latency.entrySet()) {
        require(
            value.getValue() == published.path(value.getKey()).longValue(),
            "published latency percentile changed: " + raw.pointId + ' ' + value.getKey());
      }
      require(p99Queue == summary.path("p99QueueDepth").longValue(), "queue p99 changed");
      require(
          nonNegative(summary, "startingBacklog") == nonNegative(rawCut, "startingBacklog")
              && nonNegative(summary, "endingBacklog") == endingDemandBacklog,
          "published demand backlog differs from scheduled cut: " + raw.pointId);
      require(
          rawCut.equals(summary.path("observationCut")),
          "summary observation cut differs from raw: " + raw.pointId);
      require(
          rawDrain.equals(summary.path("terminalDrain")),
          "summary terminal drain differs from raw: " + raw.pointId);
      RateMeasurement measurement =
          new RateMeasurement(
              raw.offeredRate,
              64,
              cutAccounting.admitted(),
              cutAccounting.terminalCompletions(),
              cutAccounting.overloaded(),
              nonNegative(rawCut, "startingBacklog"),
              nonNegative(rawCut, "endingBacklog"),
              p99Queue,
              postCutOverloaded);
      List<String> saturationReasons = independentSaturationReasons(measurement);
      require(
          !saturationReasons.isEmpty() == summary.path("saturated").booleanValue(),
          "saturation classification changed");
      require(
          saturationReasons.equals(strings(summary.path("saturationReasons"))),
          "saturation reasons changed");
      verifyPhaseBudget(raw, summary, points);
    }
    require(warmups == expectedProfile.warmupPoints, "raw warmup point count changed");
    require(summaries.size() == expectedProfile.publishedPoints, "published point count changed");
  }

  private static void verifyPhaseBudget(
      PointRaw raw, JsonNode summary, Map<String, PointRaw> points) {
    JsonNode plan = summary.path("phaseBudgetPreflight");
    long operations =
        ScheduledArrival.operationsFor(
            raw.offeredRate, Math.multiplyExact(raw.durationSeconds(), 1_000_000_000L));
    long strictlyBefore =
        Math.min(
            operations,
            BigInteger.valueOf(raw.offeredRate)
                .multiply(BigInteger.valueOf(110_000_000L))
                .add(BigInteger.valueOf(999_999_999L))
                .divide(BigInteger.valueOf(1_000_000_000L))
                .longValueExact());
    long expectedStartRecords = 0;
    long expectedStartBytes = 0;
    if ("MEASUREMENT".equals(raw.phase)) {
      PointRaw warmup = points.get(raw.pointId.replace("-measurement", "-warmup"));
      require(warmup != null, "measurement has no preceding warmup raw point");
      expectedStartRecords = warmup.actualSuffixRecords;
      expectedStartBytes = warmup.actualSuffixBytes;
    }
    long ownerBound = 65;
    long worstPrefixRecords =
        Math.addExact(Math.addExact(expectedStartRecords, strictlyBefore), ownerBound);
    long worstPrefixBytes =
        Math.addExact(
            expectedStartBytes,
            Math.multiplyExact(Math.addExact(strictlyBefore, ownerBound), 1_024L));
    long worstPostRecords = Math.addExact(operations, ownerBound);
    long worstPostBytes = Math.multiplyExact(worstPostRecords, 1_024L);
    require(
        nonNegative(plan, "plannedInitialOffers") == operations, "phase planned offers changed");
    require(
        nonNegative(plan, "plannedBeforeLatestCheckpointAdmission") == strictlyBefore,
        "phase pre-checkpoint offer bound changed");
    require(
        nonNegative(plan, "actualSuffixRecordsAtPhaseStart") == expectedStartRecords
            && nonNegative(plan, "actualSuffixBytesAtPhaseStart") == expectedStartBytes,
        "phase starting suffix changed");
    require(
        nonNegative(plan, "queueCapacityPlusOwnerBound") == ownerBound
            && nonNegative(plan, "conservativeRetryDurableBound") == ownerBound,
        "phase owner/retry bound changed");
    require(
        nonNegative(plan, "worstPrefixRecords") == worstPrefixRecords
            && nonNegative(plan, "worstPrefixBytes") == worstPrefixBytes
            && nonNegative(plan, "worstPostCheckpointSuffixRecords") == worstPostRecords
            && nonNegative(plan, "worstPostCheckpointSuffixBytes") == worstPostBytes
            && plan.path("validatedSeparately").booleanValue(),
        "phase recovery preflight formula changed");
    require(
        worstPrefixRecords <= 1_000_000L
            && worstPrefixBytes <= 1_073_741_824L
            && worstPostRecords <= 1_000_000L
            && worstPostBytes <= 1_073_741_824L,
        "phase preflight exceeds M10Q1 finite recovery budget");
    require(
        nonNegative(plan, "actualSuffixRecordsAtTerminalDrain") == raw.actualSuffixRecords
            && nonNegative(plan, "actualSuffixBytesAtTerminalDrain") == raw.actualSuffixBytes
            && raw.actualSuffixRecords <= worstPostRecords
            && raw.actualSuffixBytes <= worstPostBytes,
        "raw terminal suffix exceeds or differs from post-checkpoint plan");
    for (PointRaw.MaintenanceAttempt attempt : raw.maintenance.values()) {
      if (attempt.completed && "PROACTIVE_PHASE_CHECKPOINT".equals(attempt.reason)) {
        require(
            attempt.suffixRecordsBeforeReset <= worstPrefixRecords
                && attempt.suffixBytesBeforeReset <= worstPrefixBytes,
            "actual proactive-checkpoint prefix exceeds preflight bound");
      }
    }
  }

  static void verifyCapacity(JsonNode qualification, Map<String, JsonNode> summaries) {
    verifyCapacity(qualification, summaries, BundleProfile.RELEASE);
  }

  private static void verifyCapacity(
      JsonNode qualification, Map<String, JsonNode> summaries, BundleProfile expectedProfile) {
    List<List<RateMeasurement>> sweeps = new ArrayList<>();
    for (JsonNode sweep : qualification.path("sweeps")) {
      List<RateMeasurement> rates = new ArrayList<>();
      for (JsonNode point : sweep) rates.add(rateMeasurement(point));
      sweeps.add(List.copyOf(rates));
    }
    IndependentCapacity recomputed =
        independentCapacity(sweeps, expectedProfile == BundleProfile.RELEASE ? 3 : 1);
    JsonNode capacity = qualification.path("capacity");
    require(
        recomputed.sweepKnees().equals(longs(capacity.path("sweepKnees"))),
        "published sweep knees changed");
    require(
        recomputed.publishedKnee() == capacity.path("publishedKnee").longValue(),
        "published knee changed");
    require(
        recomputed.qopCandidate() == capacity.path("qualifiedOperatingPointCandidate").longValue(),
        "published QOP candidate changed");
    require(
        recomputed.qop() == capacity.path("qualifiedOperatingPoint").longValue(),
        "published QOP changed");
    JsonNode soak = qualification.path("soak").path("point");
    require(soak.path("offeredRate").longValue() == recomputed.qop(), "soak is not at QOP");
    require(summaries.containsKey(text(soak, "pointId")), "soak raw point is missing");
    require(independentSaturationReasons(rateMeasurement(soak)).isEmpty(), "QOP soak is saturated");
    require(!soak.path("saturated").booleanValue(), "QOP soak is published as saturated");
    require(soak.path("saturationReasons").isEmpty(), "QOP soak has saturation reasons");
    JsonNode logical = soak.path("logical");
    require(logical.path("overloaded").longValue() == 0, "QOP soak overloaded");
    require(logical.path("closedOrInvalid").longValue() == 0, "QOP soak rejected closed/invalid");
    require(
        logical.path("initiallyAdmitted").longValue()
            == logical.path("terminalCompletions").longValue(),
        "QOP soak did not terminally reconcile");
    JsonNode attempts = soak.path("attempts");
    require(attempts.path("pending").longValue() == 0, "QOP soak retains pending attempts");
    require(
        attempts.path("explicitServiceFailures").longValue() == 0,
        "QOP soak has explicit service failures");
    require(attempts.path("closedOrInvalid").longValue() == 0, "QOP soak has rejected attempts");
  }

  private static IndependentCapacity independentCapacity(
      List<List<RateMeasurement>> sweeps, int requiredSweeps) {
    require(sweeps.size() == requiredSweeps, "capacity sweep count changed");
    List<Long> knees = new ArrayList<>();
    List<RateMeasurement> reference = sweeps.getFirst();
    require(reference.size() == 8, "capacity ladder size changed");
    for (List<RateMeasurement> sweep : sweeps) {
      require(sweep.size() == reference.size(), "capacity sweep ladders differ");
      long previous = 0;
      long knee = 0;
      for (int index = 0; index < sweep.size(); index++) {
        RateMeasurement point = sweep.get(index);
        require(point.offeredRate() > previous, "capacity sweep rates are not increasing");
        require(
            point.offeredRate() == reference.get(index).offeredRate(),
            "capacity sweep offered rates differ");
        previous = point.offeredRate();
        if (knee == 0
            && index + 1 < sweep.size()
            && !independentSaturationReasons(point).isEmpty()
            && !independentSaturationReasons(sweep.get(index + 1)).isEmpty()) {
          knee = point.offeredRate();
        }
      }
      require(knee > 0, "capacity sweep has no consecutive saturated knee");
      knees.add(knee);
    }
    long publishedKnee = knees.stream().mapToLong(Long::longValue).min().orElseThrow();
    long candidate = Math.floorDiv(Math.multiplyExact(publishedKnee, 70L), 100L);
    require(candidate > 0, "QOP candidate is not positive");
    long selected = 0;
    for (int index = 0; index < reference.size(); index++) {
      long rate = reference.get(index).offeredRate();
      if (rate <= candidate) {
        boolean allUnsaturated = true;
        for (List<RateMeasurement> sweep : sweeps) {
          if (!independentSaturationReasons(sweep.get(index)).isEmpty()) {
            allUnsaturated = false;
            break;
          }
        }
        if (allUnsaturated) selected = Math.max(selected, rate);
      }
    }
    require(selected > 0, "no all-sweep unsaturated measured QOP at or below candidate");
    for (List<RateMeasurement> sweep : sweeps) {
      boolean aboveKneeRetained =
          sweep.stream()
              .anyMatch(
                  point ->
                      point.offeredRate() > publishedKnee
                          && !independentSaturationReasons(point).isEmpty());
      require(aboveKneeRetained, "capacity sweep lost above-knee saturation evidence");
    }
    return new IndependentCapacity(List.copyOf(knees), publishedKnee, candidate, selected);
  }

  private static List<String> independentSaturationReasons(RateMeasurement measurement) {
    List<String> reasons = new ArrayList<>();
    if (measurement.overloaded() > 0) reasons.add("OVERLOAD_REJECTION");
    if (measurement.postCutOverloaded() > 0) {
      reasons.add("POST_CUT_PLANNED_OVERLOAD_REJECTION");
    }
    if (Math.multiplyExact(measurement.p99QueueDepth(), 1_000L)
        >= Math.multiplyExact(measurement.queueCapacity(), 800L)) {
      reasons.add("P99_QUEUE_DEPTH_AT_LEAST_80_PERCENT");
    }
    if (measurement.admitted() > 0
        && Math.multiplyExact(measurement.completed(), 1_000L)
            < Math.multiplyExact(measurement.admitted(), 995L)) {
      reasons.add("COMPLETED_PER_ADMITTED_BELOW_99_5_PERCENT");
    }
    if (Math.multiplyExact(measurement.backlogGrowth(), 1_000L)
        > Math.multiplyExact(measurement.queueCapacity(), 100L)) {
      reasons.add("END_BACKLOG_GROWTH_ABOVE_10_PERCENT");
    }
    return List.copyOf(reasons);
  }

  private record IndependentCapacity(
      List<Long> sweepKnees, long publishedKnee, long qopCandidate, long qop) {}

  private static RateMeasurement rateMeasurement(JsonNode point) {
    JsonNode observation = point.path("observationCut");
    RunAccounting attempts = accounting(observation.path("attemptAccounting"));
    return new RateMeasurement(
        point.path("offeredRate").longValue(),
        64,
        attempts.admitted(),
        attempts.terminalCompletions(),
        attempts.overloaded(),
        nonNegative(observation, "startingBacklog"),
        nonNegative(observation, "endingBacklog"),
        nonNegative(observation, "p99QueueDepth"),
        nonNegative(observation, "postCutOverloaded"));
  }

  private static RunAccounting accounting(JsonNode node) {
    return new RunAccounting(
        nonNegative(node, "offers"),
        nonNegative(node, "admitted"),
        nonNegative(node, "overloaded"),
        nonNegative(node, "closedOrInvalid"),
        completeVariants(node.path("submissionResultVariants")),
        nonNegative(node, "explicitServiceFailures"),
        nonNegative(node, "pending"));
  }

  private static Map<String, Long> completeVariants(JsonNode node) {
    Map<String, Long> values = new LinkedHashMap<>();
    for (String variant : SUBMISSION_VARIANTS.stream().sorted().toList()) {
      values.put(variant, nonNegative(node, variant));
    }
    return Map.copyOf(values);
  }

  private static Map<String, Long> completeVariants(Map<String, Long> observed) {
    Map<String, Long> values = new LinkedHashMap<>();
    for (String variant : SUBMISSION_VARIANTS.stream().sorted().toList()) {
      values.put(variant, observed.getOrDefault(variant, 0L));
    }
    return Map.copyOf(values);
  }

  private static void verifyRecovery(
      Path root,
      Path release,
      String runId,
      String sourceCommit,
      JsonNode qualification,
      Map<String, JsonNode> summaries,
      Map<String, PointRaw> points,
      Map<String, TraceAccumulator> traces,
      BundleProfile expectedProfile,
      List<Path> files)
      throws IOException {
    JsonNode inventory = qualification.path("artifacts").path("recoveryJson");
    Path relative = safeRelative(text(inventory, "relativePath"));
    require(relative.equals(Path.of("recovery.json")), "recovery artifact path changed");
    Path file = resolveFile(root, release, relative);
    require(Files.size(file) == positiveLong(inventory, "bytes"), "recovery byte count changed");
    require(streamSha256(file).equals(text(inventory, "sha256")), "recovery hash changed");
    JsonNode recovery = JsonSupport.parse(readBytes(file));
    require(
        "matching.m10.recovery.v1".equals(text(recovery, "schemaVersion")),
        "recovery schema changed");
    require(
        "RECOVERY_COLLECTION".equals(text(recovery, "recordType")),
        "recovery collection type changed");
    require(runId.equals(text(recovery, "runId")), "recovery run ID changed");
    require(expectedProfile.id.equals(text(recovery, "profileId")), "recovery profile changed");
    require(expectedProfile.scope.equals(text(recovery, "resultScope")), "recovery scope changed");
    require(
        recovery.path("eligibleForReleaseEvidence").booleanValue() == expectedProfile.eligible,
        "recovery eligibility changed");
    require(sourceCommit.equals(text(recovery, "sourceCommit")), "recovery source changed");
    require(WORKLOAD_SHA256.equals(text(recovery, "workloadSha256")), "recovery workload changed");
    JsonNode records = recovery.path("records");
    require(
        records.size() == positiveLong(inventory, "recordCount"), "recovery record count changed");
    require(records.size() == summaries.size(), "every published point needs recovery evidence");
    Set<String> observed = new LinkedHashSet<>();
    Set<String> observedTraces = new LinkedHashSet<>();
    for (JsonNode record : records) {
      require(runId.equals(text(record, "runId")), "recovery record run ID changed");
      require(
          expectedProfile.id.equals(text(record, "profileId")), "recovery record profile changed");
      require(
          expectedProfile.scope.equals(text(record, "resultScope")),
          "recovery record scope changed");
      require(
          record.path("eligibleForReleaseEvidence").booleanValue() == expectedProfile.eligible,
          "recovery record eligibility changed");
      require(sourceCommit.equals(text(record, "sourceCommit")), "recovery record source changed");
      require(
          WORKLOAD_SHA256.equals(text(record, "workloadSha256")),
          "recovery record workload changed");
      require(
          "M10Q1".equals(text(record, "qualificationRuntimePolicyId"))
              && "M10_DEDICATED_NOT_M09_DEFAULT"
                  .equals(text(record, "qualificationRecoveryBudgetPolicy"))
              && record.path("qualificationMaxSuffixRecords").longValue() == 1_000_000L
              && record.path("qualificationMaxSuffixBytes").longValue() == 1_073_741_824L
              && record.path("m09DefaultMaxSuffixRecords").longValue() == 64L
              && record.path("m09DefaultMaxSuffixBytes").longValue() == 1_048_576L
              && record.path("plannedWalRecordCeilingBytes").intValue() == 1_024
              && record.path("proactiveCheckpointOffsetNanos").longValue() == 100_000_000L,
          "recovery M10Q1 boundary changed");
      String pointId = text(record, "pointId");
      require(
          summaries.containsKey(pointId) && observed.add(pointId),
          "recovery point is missing or duplicated: " + pointId);
      require(
          record.path("durableOperations").longValue() > 0, "recovery has no durable operations");
      require(
          record.path("durableOperations").longValue()
              == record.path("duplicatesReplayed").longValue(),
          "recovery duplicate count changed");
      require(
          text(record, "liveResultDigest").equals(text(record, "recoveredResultDigest")),
          "recovered result digest changed");
      require(
          text(record, "liveResultDigest").equals(text(record, "directReplayResultDigest")),
          "direct replay result digest changed");
      require(
          text(record, "liveSemanticStateDigest")
              .equals(text(record, "recoveredSemanticStateDigest")),
          "recovered semantic state changed");
      require(
          text(record, "liveSemanticStateDigest")
              .equals(text(record, "directReplaySemanticStateDigest")),
          "direct replay semantic state changed");
      require(
          record.path("exactResultDigest").booleanValue()
              && record.path("exactSemanticStateDigest").booleanValue(),
          "recovery exactness flags are not PASS");
      require(
          record.path("configuredMaxSuffixRecords").longValue() == 1_000_000L
              && record.path("configuredMaxSuffixBytes").longValue() == 1_073_741_824L,
          "recovery configured suffix budget changed");
      require(
          "M10_DEDICATED_FINITE_WITH_M09S1".equals(text(record, "recoveredWalConfig"))
              && "M08_LEGACY_UNBOUNDED_NO_SNAPSHOT".equals(text(record, "directReplayWalConfig")),
          "recovery/direct replay runtime config changed");
      require(positiveLong(record, "recoveryElapsedNanos") > 0, "recovery time is missing");
      PointRaw rawPoint = points.get(pointId);
      require(rawPoint != null, "recovery has no decompressed raw point");
      require(
          nonNegative(record, "actualSuffixRecords") == rawPoint.actualSuffixRecords
              && nonNegative(record, "actualSuffixBytes") == rawPoint.actualSuffixBytes,
          "recovery suffix differs from decompressed WAL completions");
      String traceId = text(record, "recoveryTraceId");
      require(observedTraces.add(traceId), "recovery trace is duplicated");
      TraceAccumulator trace = traces.get(traceId);
      require(trace != null, "recovery has no accepted-trace stream");
      TraceEvidence traceEvidence = trace.finish();
      require(
          positiveLong(record, "durableOperations") == traceEvidence.records
              && text(record, "recoveryTraceSha256").equals(traceEvidence.traceSha256)
              && text(record, "liveResultDigest").equals(traceEvidence.resultSha256)
              && text(record, "liveSemanticStateDigest").equals(traceEvidence.semanticStateDigest),
          "accepted JSONL does not reconstruct recovery trace/result/state");
      JsonNode summaryRecovery = summaries.get(pointId).path("recovery");
      for (String field :
          List.of(
              "durableOperations",
              "duplicatesReplayed",
              "liveResultDigest",
              "recoveredResultDigest",
              "directReplayResultDigest",
              "liveSemanticStateDigest",
              "recoveredSemanticStateDigest",
              "directReplaySemanticStateDigest",
              "recoveryTraceSha256",
              "recoveryTraceId",
              "configuredMaxSuffixRecords",
              "configuredMaxSuffixBytes",
              "actualSuffixRecords",
              "actualSuffixBytes",
              "recoveryElapsedNanos",
              "recoveredWalConfig",
              "directReplayWalConfig")) {
        require(
            record.path(field).equals(summaryRecovery.path(field)),
            "summary recovery field changed: " + field);
      }
    }
    require(observedTraces.equals(traces.keySet()), "recovery/accepted trace inventories differ");
    files.add(relative);
  }

  private static void verifyFrozenIdentity(
      Path repositoryRoot, JsonNode root, String sourceCommit, BundleProfile expectedProfile) {
    require(
        "matching.m10.qualification.v1".equals(text(root, "schemaVersion")),
        "qualification schema changed");
    require("PASS".equals(text(root, "status")), "qualification is not PASS");
    require(expectedProfile.id.equals(text(root, "profileId")), "qualification profile changed");
    require(expectedProfile.scope.equals(text(root, "resultScope")), "qualification scope changed");
    require(
        root.path("eligibleForReleaseEvidence").booleanValue() == expectedProfile.eligible,
        "qualification eligibility changed");
    require(
        sourceCommit.equals(text(root.path("source"), "commit")), "qualification source changed");
    require(
        WORKLOAD_SHA256.equals(text(root.path("source"), "workloadSha256")),
        "qualification workload changed");
    require(
        "M10Q1".equals(text(root, "qualificationRuntimePolicyId")),
        "qualification runtime policy changed");
    JsonNode runtime = root.path("qualificationRuntime");
    require(
        "M10Q1".equals(text(runtime, "policyId"))
            && "M10_DEDICATED_NOT_M09_DEFAULT".equals(text(runtime, "scope"))
            && runtime.path("m09Default").path("maxSuffixRecords").longValue() == 64L
            && runtime.path("m09Default").path("maxSuffixBytes").longValue() == 1_048_576L
            && runtime.path("finiteRecoveryBudget").path("maxSuffixRecords").longValue()
                == 1_000_000L
            && runtime.path("finiteRecoveryBudget").path("maxSuffixBytes").longValue()
                == 1_073_741_824L
            && runtime.path("proactiveCheckpointOffsetNanos").longValue() == 100_000_000L
            && runtime.path("proactiveCheckpointAdmissionLagMaxNanos").longValue() == 10_000_000L
            && runtime.path("plannedRecordCeilingBytes").intValue() == 1_024,
        "qualification M10Q1 recovery boundary changed");
    verifyRuntimeProvenance(repositoryRoot, root, sourceCommit, expectedProfile);
    require(
        "START_SUFFIX_PLUS_ARRIVALS_SCHEDULED_BEFORE_CHECKPOINT_ADMISSION_DEADLINE_PLUS_QUEUE_CAPACITY_PLUS_ONE_OWNER"
                .equals(text(runtime.path("phaseBudgetPreflight"), "prefixRecords"))
            && "ALL_PLANNED_DURABLE_ARRIVALS_PLUS_QUEUE_CAPACITY_PLUS_ONE_OWNER"
                .equals(text(runtime.path("phaseBudgetPreflight"), "postCheckpointSuffixRecords"))
            && runtime
                .path("phaseBudgetPreflight")
                .path("validatePrefixAndSuffixSeparately")
                .booleanValue(),
        "qualification phase budget formula changed");
    require(
        "DEDICATED_NO_COMPLETION_CHECKPOINT_OR_ARTIFACT_IO"
                .equals(text(runtime.path("scheduler"), "initialArrivalThread"))
            && "ASYNC_COMPLETION_CHECKPOINT_RETRY_AND_ARTIFACT_IO"
                .equals(text(runtime.path("scheduler"), "coordinator"))
            && runtime.path("scheduler").path("scheduledObservationCutDoesNotMove").booleanValue()
            && runtime.path("scheduler").path("producerClosureGraceMaxNanos").longValue()
                == 250_000_000L
            && runtime.path("scheduler").path("allScheduledArrivalsMaterialized").booleanValue()
            && runtime.path("scheduler").path("allAdmissionDecisionsWithinLagLimits").booleanValue()
            && runtime.path("scheduler").path("p99ProducerLagMaxNanos").longValue() == 50_000_000L
            && runtime.path("scheduler").path("maxProducerLagMaxNanos").longValue() == 250_000_000L
            && runtime.path("scheduler").path("observationCutLagMaxNanos").longValue()
                == 10_000_000L,
        "qualification scheduler/pacing boundary changed");
    JsonNode rawTime = runtime.path("rawTimeContract");
    require(
        "admissionDecisionNanos".equals(text(rawTime, "admissionTimestamp"))
            && "ADMISSION_GATE_DECISION".equals(text(rawTime, "admissionObservationKind"))
            && "ownerCompletedNanos".equals(text(rawTime, "completionTimestamp"))
            && "OWNER_COMPLETED_UNDER_GATE".equals(text(rawTime, "completionTimeOrigin")),
        "qualification raw timestamp contract changed");
    require(
        "IMMUTABLE_SCHEDULED_WINDOW_END_RAW_RECONSTRUCTED_BEFORE_PRODUCER_CLOSURE_AND_TERMINAL_DRAIN"
                .equals(text(runtime, "observationCut"))
            && "ZERO_PENDING_BEFORE_RECOVERY".equals(text(runtime, "terminalDrain"))
            && runtime.path("resourceSampling").path("targetCadenceNanos").longValue()
                == 1_000_000_000L
            && runtime.path("resourceSampling").path("maximumScheduledGapNanos").longValue()
                == 2_000_000_000L
            && runtime.path("resourceSampling").path("maximumObservedGapNanos").longValue()
                == 2_000_000_000L
            && runtime.path("resourceSampling").path("maximumSamplingLagNanos").longValue()
                == 2_000_000_000L
            && "SCHEDULED_WINDOW_THROUGH_TERMINAL_DRAIN"
                .equals(text(runtime.path("resourceSampling"), "scope"))
            && "M08_LEGACY_UNBOUNDED_NO_SNAPSHOT"
                .equals(text(runtime.path("directReplay"), "runtimeConfig")),
        "qualification observation/resource/direct-replay boundary changed");
    JsonNode profile = root.path("profile");
    require(
        expectedProfile.id.equals(text(profile, "id"))
            && expectedProfile.scope.equals(text(profile, "resultScope"))
            && profile.path("eligibleForReleaseEvidence").booleanValue()
                == expectedProfile.eligible,
        "nested qualification profile changed");
    require(
        profile.path("calibrationSeconds").intValue() == expectedProfile.calibrationSeconds
            && profile.path("sweeps").intValue() == expectedProfile.sweeps
            && profile.path("warmupSecondsPerRate").intValue() == expectedProfile.warmupSeconds
            && profile.path("measurementSecondsPerRate").intValue()
                == expectedProfile.measurementSeconds
            && profile.path("soakSeconds").intValue() == expectedProfile.soakSeconds
            && "M10Q1".equals(text(profile, "qualificationRuntimePolicyId"))
            && profile.path("recoveryBudgetMaxSuffixRecords").longValue() == 1_000_000L
            && profile.path("recoveryBudgetMaxSuffixBytes").longValue() == 1_073_741_824L
            && profile.path("proactiveCheckpointOffsetNanos").longValue() == 100_000_000L
            && profile.path("plannedWalRecordCeilingBytes").intValue() == 1_024,
        "qualification durations changed");
    require(
        ints(profile.path("rateLadderPermille"))
            .equals(List.of(250, 500, 700, 850, 1000, 1150, 1350, 1600)),
        "release rate ladder changed");
    require(
        doubles(profile.path("percentiles")).equals(List.of(0.5, 0.95, 0.99, 0.999)),
        "release percentiles changed");
    require(FrozenPercentiles.RANK_RULE.equals(text(profile, "rankRule")), "rank rule changed");
    require(root.path("sweeps").size() == expectedProfile.sweeps, "sweep count changed");
    for (JsonNode sweep : root.path("sweeps"))
      require(sweep.size() == 8, "sweep does not have eight rates");
    require(
        root.path("soak").path("durationSeconds").intValue() == expectedProfile.soakSeconds,
        "qualification soak duration changed");
    require(
        root.path("calibration").path("elapsedNanos").longValue()
            >= Math.multiplyExact((long) expectedProfile.calibrationSeconds, 1_000_000_000L),
        "qualification calibration duration changed");
    verifyCalibrationAndRateLadder(root, expectedProfile.sweeps);
    Instant started = Instant.parse(text(root.path("environment"), "runStartedAt"));
    Instant finished = Instant.parse(text(root.path("environment"), "runFinishedAt"));
    long minimumRunSeconds =
        expectedProfile.calibrationSeconds
            + (long) expectedProfile.sweeps
                * 8L
                * (expectedProfile.warmupSeconds + expectedProfile.measurementSeconds)
            + expectedProfile.soakSeconds;
    require(!finished.isBefore(started), "release finish precedes start");
    require(
        Duration.between(started, finished).toSeconds() >= minimumRunSeconds,
        "qualification wall-clock interval is shorter than the frozen profile");
    JsonNode raw = root.path("rawRecomputation");
    require(
        "PASS".equals(text(raw, "status")) && raw.path("fromDecompressedRaw").booleanValue(),
        "runner raw recomputation is not PASS");
  }

  static void verifyCalibrationAndRateLadder(JsonNode root, int expectedSweeps) {
    JsonNode calibration = root.path("calibration");
    require(
        "UNPACED".equals(text(calibration, "mode"))
            && "RATE_SELECTION_ONLY".equals(text(calibration, "purpose")),
        "calibration scope changed");
    long elapsed = positiveLong(calibration, "elapsedNanos");
    long logicalOperations = positiveLong(calibration, "logicalOperations");
    long durableCompletions = positiveLong(calibration, "durableCompletions");
    require(durableCompletions <= logicalOperations, "calibration durable count exceeds offers");
    nonNegative(calibration, "checkpointCount");
    long referenceRate =
        BigInteger.valueOf(durableCompletions)
            .multiply(BigInteger.valueOf(1_000_000_000L))
            .divide(BigInteger.valueOf(elapsed))
            .longValueExact();
    require(
        referenceRate > 0 && referenceRate == positiveLong(calibration, "referenceRate"),
        "calibration reference rate formula changed");
    List<Integer> ladder = List.of(250, 500, 700, 850, 1000, 1150, 1350, 1600);
    require(root.path("sweeps").size() == expectedSweeps, "sweep count changed");
    for (int sweepIndex = 0; sweepIndex < expectedSweeps; sweepIndex++) {
      JsonNode sweep = root.path("sweeps").path(sweepIndex);
      require(sweep.size() == ladder.size(), "sweep does not have the frozen rate ladder");
      for (int ladderIndex = 0; ladderIndex < ladder.size(); ladderIndex++) {
        JsonNode point = sweep.path(ladderIndex);
        int permille = ladder.get(ladderIndex);
        long expectedRate =
            Math.max(1L, Math.floorDiv(Math.multiplyExact(referenceRate, permille), 1_000L));
        require(
            point.path("sweep").intValue() == sweepIndex + 1
                && point.path("ladderPermille").intValue() == permille
                && point.path("offeredRate").longValue() == expectedRate,
            "calibration-to-ladder rate mapping changed");
      }
    }
  }

  private static void verifyRawRecomputation(
      JsonNode qualification,
      Map<String, Long> streamCounts,
      int publishedPoints,
      int reconstructedTraces) {
    JsonNode raw = qualification.path("rawRecomputation");
    require(
        raw.path("arrivalRecords").longValue() == streamCounts.get("raw-arrivals"),
        "runner arrival recomputation count changed");
    require(
        raw.path("completionRecords").longValue() == streamCounts.get("raw-completions"),
        "runner completion recomputation count changed");
    require(
        raw.path("queueRecords").longValue() == streamCounts.get("raw-queue"),
        "runner queue recomputation count changed");
    require(
        raw.path("resourceRecords").longValue() == streamCounts.get("resources"),
        "runner resource recomputation count changed");
    require(
        raw.path("maintenanceRecords").longValue() == streamCounts.get("raw-maintenance"),
        "runner maintenance recomputation count changed");
    require(
        raw.path("phaseCutRecords").longValue() == streamCounts.get("raw-phase-cuts"),
        "runner phase-cut recomputation count changed");
    require(
        raw.path("acceptedTraceRecords").longValue() == streamCounts.get("accepted-trace"),
        "runner accepted-trace recomputation count changed");
    require(
        raw.path("verifiedPublishedPoints").intValue() == publishedPoints,
        "runner published-point recomputation count changed");
    require(
        raw.path("reconstructedRecoveryTraces").intValue() == reconstructedTraces,
        "runner reconstructed trace count changed");
    require(raw.path("exactAttemptJoin").booleanValue(), "runner exact attempt join is not PASS");
    require(
        raw.path("reconstructedTraceHashesExact").booleanValue(),
        "runner reconstructed trace hash check is not PASS");
    require(
        raw.path("recoverySuffixRecordsAndBytesExact").booleanValue(),
        "runner recovery suffix recomputation is not PASS");
    require(
        raw.path("allNewWalRecordsWithinPlanningCeiling").booleanValue(),
        "runner WAL planning-ceiling recomputation is not PASS");
  }

  private static void verifyRuntimeProvenance(
      Path repositoryRoot,
      JsonNode qualification,
      String sourceCommit,
      BundleProfile expectedProfile) {
    JsonNode provenance = qualification.path("runtimeProvenance");
    require(
        sourceCommit.equals(text(provenance, "repositoryHead")),
        "runtime provenance repository HEAD changed");
    if (expectedProfile == BundleProfile.RELEASE) {
      require(!provenance.path("repositoryDirty").booleanValue(), "release runtime was dirty");
    }
    String benchmark = runtimeClassesSha256(M10QualificationRunner.class);
    String local = runtimeClassesSha256(LocalMatchingService.class);
    String core = runtimeClassesSha256(SingleInstrumentMatchingEngine.class);
    require(
        benchmark.equals(sha256Text(provenance, "matchingBenchmarkClassesSha256"))
            && local.equals(sha256Text(provenance, "matchingLocalRuntimeClassesSha256"))
            && core.equals(sha256Text(provenance, "matchingCoreClassesSha256")),
        "runtime class provenance differs from executing verifier classpath");
    Map<String, String> components =
        Map.of(
            "matchingBenchmarkClassesSha256", benchmark,
            "matchingLocalRuntimeClassesSha256", local,
            "matchingCoreClassesSha256", core);
    MessageDigest combined = sha256Digest();
    components.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(
            entry -> {
              combined.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
              combined.update((byte) 0);
              combined.update(entry.getValue().getBytes(StandardCharsets.UTF_8));
              combined.update((byte) 0);
            });
    require(
        HexFormat.of()
            .formatHex(combined.digest())
            .equals(sha256Text(provenance, "combinedRuntimeClassesSha256")),
        "combined runtime class provenance changed");
    require(
        repositoryRoot.toAbsolutePath().normalize().equals(repositoryRoot),
        "repository root is not normalized");
  }

  private static String runtimeClassesSha256(Class<?> marker) {
    final Path codeSource;
    try {
      codeSource =
          Path.of(marker.getProtectionDomain().getCodeSource().getLocation().toURI())
              .toAbsolutePath()
              .normalize();
    } catch (URISyntaxException failure) {
      throw new IllegalStateException("runtime code source is not a filesystem path", failure);
    }
    MessageDigest digest = sha256Digest();
    try {
      if (Files.isDirectory(codeSource, LinkOption.NOFOLLOW_LINKS)) {
        try (var paths = Files.walk(codeSource)) {
          for (Path file :
              paths
                  .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                  .filter(path -> path.getFileName().toString().endsWith(".class"))
                  .sorted(Comparator.comparing(path -> codeSource.relativize(path).toString()))
                  .toList()) {
            updateClassDigest(
                digest,
                codeSource
                    .relativize(file)
                    .toString()
                    .replace(file.getFileSystem().getSeparator(), "/"),
                Files.readAllBytes(file));
          }
        }
      } else {
        require(
            Files.isRegularFile(codeSource, LinkOption.NOFOLLOW_LINKS), "code source is missing");
        try (ZipFile archive = new ZipFile(codeSource.toFile())) {
          for (var entry :
              archive.stream()
                  .filter(value -> !value.isDirectory() && value.getName().endsWith(".class"))
                  .sorted(Comparator.comparing(java.util.zip.ZipEntry::getName))
                  .toList()) {
            try (var input = archive.getInputStream(entry)) {
              updateClassDigest(digest, entry.getName(), input.readAllBytes());
            }
          }
        }
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (IOException failure) {
      throw new IllegalStateException(
          "cannot hash runtime class code source " + codeSource, failure);
    }
  }

  private static void updateClassDigest(MessageDigest digest, String relative, byte[] bytes) {
    digest.update(relative.getBytes(StandardCharsets.UTF_8));
    digest.update((byte) 0);
    digest.update(bytes);
    digest.update((byte) 0);
  }

  static void verifyJmhDiagnostic(
      Path root, Path release, String sourceCommit, JsonNode qualification, List<Path> files)
      throws IOException {
    JsonNode inventory = qualification.path("artifacts").path("diagnosticJmh");
    Path relative = safeRelative(text(inventory, "relativePath"));
    require(
        relative.equals(Path.of("diagnostics/core-sample-time.json")),
        "JMH diagnostic path changed");
    require("1.37".equals(text(inventory, "jmhVersion")), "JMH diagnostic version changed");
    require("sample".equals(text(inventory, "mode")), "JMH diagnostic mode changed");
    require(
        "DIAGNOSTIC_ONLY".equals(text(inventory, "resultScope")), "JMH diagnostic scope changed");
    require(
        !inventory.path("eligibleForCapacityEnvelope").booleanValue(),
        "JMH diagnostic was promoted into the capacity envelope");
    require(
        sourceCommit.equals(text(inventory, "sourceCommit")),
        "JMH diagnostic source commit changed");
    require(
        sha256Text(inventory, "benchmarkClassesSha256")
            .equals(
                sha256Text(
                    qualification.path("runtimeProvenance"), "matchingBenchmarkClassesSha256")),
        "JMH diagnostic benchmark classes differ from runtime provenance");
    Path file = resolveFile(root, release, relative);
    require(
        Files.size(file) == positiveLong(inventory, "bytes"), "JMH diagnostic byte count changed");
    require(streamSha256(file).equals(text(inventory, "sha256")), "JMH diagnostic hash changed");
    JsonNode results = JsonSupport.parse(readBytes(file));
    require(
        results.isArray() && results.size() == REQUIRED_JMH_BENCHMARKS.size(),
        "JMH diagnostic must contain exactly the frozen core and codec benchmarks");
    Set<String> benchmarks = new LinkedHashSet<>();
    for (JsonNode result : results) {
      require("1.37".equals(text(result, "jmhVersion")), "JMH result version changed");
      require("sample".equals(text(result, "mode")), "JMH result is not SampleTime");
      require(
          result.path("threads").intValue() == 1
              && result.path("forks").intValue() == 2
              && result.path("warmupIterations").intValue() == 3
              && "2 s".equals(text(result, "warmupTime"))
              && result.path("warmupBatchSize").intValue() == 1
              && result.path("measurementIterations").intValue() == 5
              && "3 s".equals(text(result, "measurementTime"))
              && result.path("measurementBatchSize").intValue() == 1,
          "JMH production fork/warmup/measurement configuration changed");
      require(benchmarks.add(text(result, "benchmark")), "JMH benchmark result is duplicated");
      JsonNode metric = result.path("primaryMetric");
      require(metric.isObject(), "JMH primary metric is missing");
      require("ns/op".equals(text(metric, "scoreUnit")), "JMH output time unit changed");
      JsonNode histogram = metric.path("rawDataHistogram");
      require(
          histogram.isArray() && histogram.size() == result.path("forks").intValue(),
          "JMH rawDataHistogram does not cover every fork");
      for (int fork = 0; fork < histogram.size(); fork++) {
        JsonNode iterations = histogram.path(fork);
        require(
            iterations.isArray()
                && iterations.size() == result.path("measurementIterations").intValue(),
            "JMH fork histogram does not cover every measurement iteration");
        long observations = 0;
        for (JsonNode buckets : iterations) {
          require(buckets.isArray() && !buckets.isEmpty(), "JMH measurement histogram is empty");
          for (JsonNode bucket : buckets) {
            require(bucket.isArray() && bucket.size() == 2, "JMH histogram bucket shape changed");
            require(
                bucket.path(0).isNumber() && bucket.path(0).doubleValue() >= 0.0,
                "JMH histogram bucket value is invalid");
            require(
                bucket.path(1).isIntegralNumber() && bucket.path(1).longValue() > 0,
                "JMH histogram bucket count is invalid");
            observations = Math.addExact(observations, bucket.path(1).longValue());
          }
        }
        require(observations > 0, "JMH fork histogram has no observations");
      }
    }
    require(
        benchmarks.equals(REQUIRED_JMH_BENCHMARKS),
        "JMH diagnostic benchmark set changed: " + benchmarks);
    files.add(relative);
  }

  private static Map<String, JsonNode> summaryPoints(JsonNode qualification) {
    Map<String, JsonNode> result = new LinkedHashMap<>();
    for (JsonNode sweep : qualification.path("sweeps")) {
      for (JsonNode point : sweep) {
        require(result.put(text(point, "pointId"), point) == null, "duplicate summary point");
      }
    }
    JsonNode soak = qualification.path("soak").path("point");
    require(result.put(text(soak, "pointId"), soak) == null, "duplicate soak point");
    return Map.copyOf(result);
  }

  private static Path resolveFile(Path root, Path release, Path relative) {
    Path file = release.resolve(relative).normalize();
    require(file.startsWith(release), "release artifact escapes bundle");
    SafeOutputPaths.requireNoSymlinkComponents(root, file);
    require(
        Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS),
        "release artifact missing: " + relative);
    return file;
  }

  private static Path safeRelative(String value) {
    Path path = Path.of(value).normalize();
    require(
        !path.isAbsolute() && !path.startsWith("..") && path.toString().equals(value),
        "release artifact path is not canonical: " + value);
    return path;
  }

  private static String streamSha256(Path path) {
    try {
      MessageDigest digest = sha256Digest();
      try (var input = new BufferedInputStream(Files.newInputStream(path), 64 * 1024)) {
        byte[] buffer = new byte[64 * 1024];
        int count;
        while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
      }
      return java.util.HexFormat.of().formatHex(digest.digest());
    } catch (IOException failure) {
      throw new IllegalStateException("cannot hash " + path, failure);
    }
  }

  private static String sha256(byte[] value) {
    return HexFormat.of().formatHex(sha256Digest().digest(value));
  }

  private static String sha256Text(JsonNode node, String field) {
    String value = text(node, field);
    require(value.matches("[0-9a-f]{64}"), "invalid SHA-256 field " + field);
    return value;
  }

  private static MessageDigest sha256Digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  private static void closeSpools(Map<String, PointRaw> points) {
    points.values().forEach(PointRaw::closeQuietly);
  }

  private static String text(JsonNode node, String field) {
    String value = node.path(field).stringValue();
    require(value != null && !value.isBlank(), "missing text field " + field);
    return value;
  }

  private static long positiveLong(JsonNode node, String field) {
    long value = node.path(field).longValue();
    require(value > 0, "field must be positive: " + field);
    return value;
  }

  private static long nonNegative(JsonNode node, String field) {
    long value = node.path(field).longValue();
    require(value >= 0, "field must be non-negative: " + field);
    return value;
  }

  private static List<String> strings(JsonNode array) {
    List<String> values = new ArrayList<>();
    array.forEach(value -> values.add(value.stringValue()));
    return List.copyOf(values);
  }

  private static List<Integer> ints(JsonNode array) {
    List<Integer> values = new ArrayList<>();
    array.forEach(value -> values.add(value.intValue()));
    return List.copyOf(values);
  }

  private static List<Long> longs(JsonNode array) {
    List<Long> values = new ArrayList<>();
    array.forEach(value -> values.add(value.longValue()));
    return List.copyOf(values);
  }

  private static List<Double> doubles(JsonNode array) {
    List<Double> values = new ArrayList<>();
    array.forEach(value -> values.add(value.doubleValue()));
    return List.copyOf(values);
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

  private static void deleteTree(Path path) {
    if (path == null || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
    try (var paths = Files.walk(path)) {
      for (Path current : paths.sorted(Comparator.reverseOrder()).toList())
        Files.deleteIfExists(current);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot clear " + path, failure);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }

  record Result(JsonNode qualification, List<Path> relativeFiles, long rawRecords) {
    Result {
      qualification = qualification.deepCopy();
      relativeFiles = List.copyOf(relativeFiles);
      if (rawRecords <= 0) throw new IllegalArgumentException("rawRecords must be positive");
    }
  }

  private static final class PointRaw {
    private final String pointId;
    private final BundleProfile expectedProfile;
    private final LongSpool latencies;
    private final LongSpool initialQueues;
    private final LongSpool producerLags;
    private final LongSpool arrivalKeys;
    private final LongSpool queueKeys;
    private final AttemptTupleSpool arrivalTuples;
    private final AttemptTupleSpool queueTuples;
    private final LongSpool admittedKeys;
    private final LongSpool completionKeys;
    private final LongSpool durableCompletionKeys;
    private final LongSpool acceptedKeys;
    private final LongSpool retryExpectedKeys;
    private final LongSpool retryFirstArrivalKeys;
    private final LongSpool initiallyAdmittedLogicalKeys;
    private final LongSpool terminalLogicalKeys;
    private final EnvelopeLedger envelopes;
    private String phase;
    private int sweep = -1;
    private int ladderPermille = -1;
    private long offeredRate = -1;
    private long logicalOffers;
    private long logicalInitiallyAdmitted;
    private long logicalOverloaded;
    private long logicalClosed;
    private long logicalTerminal;
    private long attemptOffers;
    private long attemptAdmitted;
    private long attemptOverloaded;
    private long attemptClosed;
    private long completionRecords;
    private long explicitFailures;
    private long queueRecords;
    private long resourceRecords;
    private long maintenanceRecords;
    private long acceptedTraceRecords;
    private long nextInitialOperation;
    private long scheduledOrigin = -1;
    private long finalInitialActualOffer = -1;
    private long cutOffers;
    private long cutInitialDecisions;
    private long cutAdmitted;
    private long cutOverloaded;
    private long cutClosed;
    private long cutExplicitFailures;
    private long cutObservedNanos = -1;
    private long scheduledWindowEndNanos = -1;
    private long phaseOriginNanos = -1;
    private long latestCompletedCheckpointTerminalNanos = -1;
    private long actualSuffixRecords;
    private long actualSuffixBytes;
    private long resourceFirstPeriodicObservedNanos = -1;
    private long resourceLastScheduledNanos = -1;
    private long resourceLastObservedNanos = -1;
    private long resourceLastAllocated;
    private long resourceLastGcCount;
    private long resourceLastGcMillis;
    private long resourceLastCpuNanos;
    private boolean resourceTerminal;
    private JsonNode phaseCut;
    private final Map<String, Long> variants = new HashMap<>();
    private final Map<String, Long> cutVariants = new HashMap<>();
    private final Map<Long, MaintenanceAttempt> maintenance = new LinkedHashMap<>();

    PointRaw(String pointId, Path prefix, BundleProfile expectedProfile) {
      this.pointId = pointId;
      this.expectedProfile = expectedProfile;
      latencies = new LongSpool(prefix.resolveSibling(prefix.getFileName() + "-latency.bin"));
      initialQueues = new LongSpool(prefix.resolveSibling(prefix.getFileName() + "-queue.bin"));
      producerLags = new LongSpool(prefix.resolveSibling(prefix.getFileName() + "-lag.bin"));
      arrivalKeys = new LongSpool(prefix.resolveSibling(prefix.getFileName() + "-arrivals.bin"));
      queueKeys = new LongSpool(prefix.resolveSibling(prefix.getFileName() + "-queue-keys.bin"));
      arrivalTuples =
          new AttemptTupleSpool(
              prefix.resolveSibling(prefix.getFileName() + "-arrival-tuples.bin"));
      queueTuples =
          new AttemptTupleSpool(prefix.resolveSibling(prefix.getFileName() + "-queue-tuples.bin"));
      admittedKeys = new LongSpool(prefix.resolveSibling(prefix.getFileName() + "-admitted.bin"));
      completionKeys =
          new LongSpool(prefix.resolveSibling(prefix.getFileName() + "-completions.bin"));
      durableCompletionKeys =
          new LongSpool(prefix.resolveSibling(prefix.getFileName() + "-durable.bin"));
      acceptedKeys = new LongSpool(prefix.resolveSibling(prefix.getFileName() + "-accepted.bin"));
      retryExpectedKeys =
          new LongSpool(prefix.resolveSibling(prefix.getFileName() + "-retry-expected.bin"));
      retryFirstArrivalKeys =
          new LongSpool(prefix.resolveSibling(prefix.getFileName() + "-retry-first.bin"));
      initiallyAdmittedLogicalKeys =
          new LongSpool(prefix.resolveSibling(prefix.getFileName() + "-logical-admitted.bin"));
      terminalLogicalKeys =
          new LongSpool(prefix.resolveSibling(prefix.getFileName() + "-logical-terminal.bin"));
      envelopes =
          new EnvelopeLedger(prefix.resolveSibling(prefix.getFileName() + "-envelopes.bin"));
    }

    void accept(String stream, JsonNode record) {
      bindIdentity(record);
      switch (stream) {
        case "raw-arrivals" -> arrival(record);
        case "raw-completions" -> completion(record);
        case "raw-queue" -> queue(record);
        case "resources" -> resource(record);
        case "raw-maintenance" -> maintenance(record);
        case "raw-phase-cuts" -> phaseCut(record);
        case "accepted-trace" -> accepted(record);
        default -> throw new IllegalArgumentException("unknown stream " + stream);
      }
    }

    void bindIdentity(JsonNode record) {
      String observedPhase = text(record, "phase");
      int observedSweep = record.path("sweep").intValue();
      int observedPermille = record.path("ladderPermille").intValue();
      long observedRate = record.path("offeredRate").longValue();
      if (phase == null) {
        phase = observedPhase;
        sweep = observedSweep;
        ladderPermille = observedPermille;
        offeredRate = observedRate;
      } else {
        require(
            phase.equals(observedPhase)
                && sweep == observedSweep
                && ladderPermille == observedPermille
                && offeredRate == observedRate,
            "raw point identity mixed across shards: " + pointId);
      }
    }

    void arrival(JsonNode record) {
      require(phaseCut != null, "phase cut must be loaded before arrivals");
      attemptOffers++;
      int attempt = record.path("attempt").intValue();
      require(attempt >= 0, "attempt is negative");
      long operationIndex = operationIndex(record);
      long key = attemptKey(operationIndex, attempt);
      arrivalKeys.add(key);
      String envelopeHash = sha256Text(record, "canonicalEnvelopeSha256");
      if (attempt == 0) envelopes.append(operationIndex, envelopeHash);
      else envelopes.requireMatches(operationIndex, envelopeHash);
      String outcome = text(record, "admissionOutcome");
      boolean admitted = "ENQUEUED_NOT_ACK".equals(outcome);
      if (admitted) {
        attemptAdmitted++;
        admittedKeys.add(key);
        require(record.path("rejectionCode").isNull(), "admitted attempt has a rejection code");
      } else {
        require("REJECTED".equals(outcome), "unknown admission outcome");
        String code = text(record, "rejectionCode");
        if ("OVERLOADED_BEFORE_WAL".equals(code)) {
          attemptOverloaded++;
          require(
              record.path("decisionQueueDepth").intValue() == 64,
              "overload decision was not taken at full queue depth");
        } else {
          attemptClosed++;
        }
      }
      if (attempt == 0) {
        require(
            "INITIAL_SCHEDULED".equals(text(record, "attemptKind")),
            "initial logical offer is not scheduled");
        require(record.path("retryOriginAttempt").isNull(), "initial attempt has retry origin");
        require(record.path("retryOfferOrdinal").longValue() == 0, "initial retry ordinal changed");
        require(
            operationIndex == nextInitialOperation,
            "initial scheduled operations are not contiguous");
        nextInitialOperation = Math.incrementExact(nextInitialOperation);
        logicalOffers++;
        if (admitted) {
          logicalInitiallyAdmitted++;
          initiallyAdmittedLogicalKeys.add(operationIndex);
        } else if ("OVERLOADED_BEFORE_WAL".equals(text(record, "rejectionCode")))
          logicalOverloaded++;
        else logicalClosed++;
      } else {
        require(
            "CHECKPOINT_RETRY".equals(text(record, "attemptKind")), "retry attempt kind changed");
        require(operationIndex < nextInitialOperation, "retry precedes its initial logical offer");
        int retryOrigin = record.path("retryOriginAttempt").intValue();
        long retryOrdinal = positiveLong(record, "retryOfferOrdinal");
        require(
            retryOrigin >= 0
                && attempt == Math.addExact(retryOrigin, Math.toIntExact(retryOrdinal)),
            "checkpoint retry attempt/origin/ordinal changed");
        if (retryOrdinal == 1) retryFirstArrivalKeys.add(key);
      }
      long scheduled = nonNegative(record, "scheduledArrivalNanos");
      long decision = nonNegative(record, "admissionDecisionNanos");
      require(
          "RUN_RELATIVE_MONOTONIC_NANOS".equals(text(record, "timeDomain"))
              && "SCHEDULED_ARRIVAL".equals(text(record, "latencyOrigin")),
          "arrival time domain changed");
      require(decision >= scheduled, "admission decision precedes scheduled arrival");
      require(
          nonNegative(record, "producerLagNanos") == decision - scheduled,
          "producer lag no longer uses scheduled arrival");
      require(
          !record.has("actualOfferNanos")
              && !record.has("queueObservationNanos")
              && !record.has("queueObservationKind")
              && "ADMISSION_GATE_DECISION".equals(text(record, "observationKind")),
          "arrival queue observation is not the admission-gate decision");
      long decisionDepth = nonNegative(record, "decisionQueueDepth");
      require(decisionDepth <= 64, "admission decision queue depth exceeds capacity");
      arrivalTuples.add(
          operationIndex,
          attempt,
          text(record, "attemptKind"),
          decision,
          Math.toIntExact(decisionDepth));
      if (scheduledOrigin < 0) {
        require(
            operationIndex == 0 && attempt == 0,
            "point does not begin with logical operation zero");
        scheduledOrigin = scheduled;
        require(scheduledOrigin == phaseOriginNanos, "first schedule differs from phase origin");
      }
      long expectedScheduled =
          Math.addExact(scheduledOrigin, ScheduledArrival.at(0, operationIndex, offeredRate));
      require(scheduled == expectedScheduled, "scheduled-arrival sequence changed");
      if (attempt == 0) {
        require(
            decision - scheduled <= 250_000_000L,
            "initial admission decision exceeded closure grace");
        finalInitialActualOffer = decision;
        producerLags.add(decision - scheduled);
        if (decision < scheduledWindowEndNanos) cutInitialDecisions++;
      }
      if (decision < scheduledWindowEndNanos) {
        cutOffers++;
        if (admitted) cutAdmitted++;
        else if ("OVERLOADED_BEFORE_WAL".equals(text(record, "rejectionCode"))) cutOverloaded++;
        else cutClosed++;
      }
    }

    void completion(JsonNode record) {
      completionRecords++;
      long operationIndex = operationIndex(record);
      int attempt = record.path("attempt").intValue();
      require(attempt >= 0, "completion attempt is negative");
      long key = attemptKey(operationIndex, attempt);
      completionKeys.add(key);
      envelopes.requireMatches(operationIndex, sha256Text(record, "canonicalEnvelopeSha256"));
      long scheduled = nonNegative(record, "scheduledArrivalNanos");
      long terminal = nonNegative(record, "ownerCompletedNanos");
      long latency = nonNegative(record, "latencyFromScheduledNanos");
      require(
          "RUN_RELATIVE_MONOTONIC_NANOS".equals(text(record, "timeDomain"))
              && "SCHEDULED_ARRIVAL".equals(text(record, "latencyOrigin"))
              && "OWNER_COMPLETED_UNDER_GATE".equals(text(record, "timeOrigin"))
              && !record.has("terminalNanos"),
          "completion time domain changed");
      require(
          terminal >= scheduled && latency == terminal - scheduled,
          "raw completion latency origin changed");
      require(
          scheduled
              == Math.addExact(
                  scheduledOrigin, ScheduledArrival.at(0, operationIndex, offeredRate)),
          "completion scheduled-arrival identity changed");
      String kind = text(record, "completionKind");
      String variant = null;
      if ("SUBMISSION_RESULT".equals(kind)) {
        variant = text(record, "submissionResultVariant");
        require(SUBMISSION_VARIANTS.contains(variant), "unknown submission result variant");
        variants.merge(variant, 1L, Math::addExact);
        require(record.path("serviceFailureCode").isNull(), "result completion has failure code");
        if ("NEW_DURABLY_APPLIED".equals(variant) || "DUPLICATE_REPLAYED".equals(variant)) {
          durableCompletionKeys.add(key);
        }
        if ("CHECKPOINT_REQUIRED".equals(variant)) {
          require(
              !record.path("logicalTerminal").booleanValue(), "checkpoint-required is terminal");
          retryExpectedKeys.add(attemptKey(operationIndex, Math.incrementExact(attempt)));
        } else {
          require(record.path("logicalTerminal").booleanValue(), "terminal result is non-terminal");
        }
        if ("NEW_DURABLY_APPLIED".equals(variant)) {
          int walRecordLength = record.path("walRecordLength").intValue();
          require(
              walRecordLength > 0 && walRecordLength <= 1_024,
              "new durable result has invalid WAL record length");
          if (terminal > latestCompletedCheckpointTerminalNanos) {
            actualSuffixRecords = Math.incrementExact(actualSuffixRecords);
            actualSuffixBytes = Math.addExact(actualSuffixBytes, walRecordLength);
          }
        } else {
          require(record.path("walRecordLength").isNull(), "non-new result carries WAL length");
        }
      } else {
        require("EXPLICIT_SERVICE_FAILURE".equals(kind), "unknown completion kind");
        explicitFailures++;
        text(record, "serviceFailureCode");
        require(record.path("submissionResultVariant").isNull(), "failure has result variant");
        require(record.path("walRecordLength").isNull(), "failure carries WAL length");
        require(record.path("logicalTerminal").booleanValue(), "explicit failure is non-terminal");
      }
      if (terminal < scheduledWindowEndNanos) {
        if (variant == null) cutExplicitFailures++;
        else cutVariants.merge(variant, 1L, Math::addExact);
      }
      if (record.path("logicalTerminal").booleanValue()) {
        logicalTerminal++;
        latencies.add(latency);
        terminalLogicalKeys.add(operationIndex);
      }
    }

    void queue(JsonNode record) {
      long operationIndex = operationIndex(record);
      int attempt = record.path("attempt").intValue();
      require(attempt >= 0, "queue attempt is negative");
      queueKeys.add(attemptKey(operationIndex, attempt));
      long offered = nonNegative(record, "admissionDecisionNanos");
      require(
          offered >= 0
              && !record.has("offeredNanos")
              && !record.has("observedNanos")
              && !record.has("observationLagFromOfferNanos")
              && "ADMISSION_GATE_DECISION".equals(text(record, "observationKind")),
          "queue row is not the synchronous admission-gate decision");
      long depth = nonNegative(record, "decisionQueueDepth");
      require(depth <= 64, "raw queue depth exceeds capacity");
      queueTuples.add(
          operationIndex, attempt, text(record, "attemptKind"), offered, Math.toIntExact(depth));
      if (attempt == 0) {
        require(
            "INITIAL_SCHEDULED".equals(text(record, "attemptKind")),
            "initial queue row kind changed");
        if (offered < scheduledWindowEndNanos) initialQueues.add(depth);
      } else {
        require(
            "CHECKPOINT_RETRY".equals(text(record, "attemptKind")), "retry queue row kind changed");
      }
      queueRecords++;
    }

    void resource(JsonNode record) {
      require(
          "CUMULATIVE_BYTES_ALL_THREADS".equals(text(record, "allocationUnit")),
          "allocation unit changed");
      require(
          "CUMULATIVE_COLLECTIONS".equals(text(record, "gcCountUnit")), "GC count unit changed");
      require("CUMULATIVE_MILLISECONDS".equals(text(record, "gcTimeUnit")), "GC time unit changed");
      require("CUMULATIVE_PROCESS_NANOSECONDS".equals(text(record, "cpuUnit")), "CPU unit changed");
      require("BYTES".equals(text(record, "memoryUnit")), "memory unit changed");
      for (String field :
          List.of(
              "totalThreadAllocatedBytes",
              "garbageCollectionCount",
              "garbageCollectionMillis",
              "processCpuNanos",
              "heapUsedBytes",
              "committedVirtualMemoryBytes",
              "systemMemoryUsedBytes",
              "queueDepth")) {
        nonNegative(record, field);
      }
      long sequence = positiveLong(record, "sampleSequence");
      require(sequence == Math.incrementExact(resourceRecords), "resource sequence changed");
      String sampleKind = text(record, "sampleKind");
      require(
          "PERIODIC".equals(sampleKind) || "TERMINAL".equals(sampleKind),
          "resource sample kind changed");
      require(!resourceTerminal, "resource samples continue after terminal sample");
      long scheduled = nonNegative(record, "scheduledSampleNanos");
      long observed = nonNegative(record, "observedNanos");
      require(observed >= scheduled, "resource sample precedes schedule");
      require(
          nonNegative(record, "samplingLagNanos") == observed - scheduled
              && observed - scheduled <= 2_000_000_000L,
          "resource sampling lag changed");
      long allocated = nonNegative(record, "totalThreadAllocatedBytes");
      long gcCount = nonNegative(record, "garbageCollectionCount");
      long gcMillis = nonNegative(record, "garbageCollectionMillis");
      long cpuNanos = nonNegative(record, "processCpuNanos");
      require(nonNegative(record, "queueDepth") <= 64, "resource queue gauge exceeds capacity");
      if (resourceLastObservedNanos >= 0) {
        require(observed > resourceLastObservedNanos, "resource time regressed");
        require(
            scheduled - resourceLastScheduledNanos <= 2_000_000_000L,
            "resource scheduled gap exceeds two seconds");
        require(
            observed - resourceLastObservedNanos <= 2_000_000_000L,
            "resource observed gap exceeds two seconds");
        require(
            allocated >= resourceLastAllocated
                && gcCount >= resourceLastGcCount
                && gcMillis >= resourceLastGcMillis
                && cpuNanos >= resourceLastCpuNanos,
            "resource cumulative counter regressed");
      } else {
        require("PERIODIC".equals(sampleKind), "resource series does not begin periodically");
        require(scheduled == phaseOriginNanos, "first resource schedule differs from phase origin");
        resourceFirstPeriodicObservedNanos = observed;
      }
      resourceLastScheduledNanos = scheduled;
      resourceLastObservedNanos = observed;
      resourceLastAllocated = allocated;
      resourceLastGcCount = gcCount;
      resourceLastGcMillis = gcMillis;
      resourceLastCpuNanos = cpuNanos;
      resourceTerminal = "TERMINAL".equals(sampleKind);
      resourceRecords = sequence;
    }

    void maintenance(JsonNode record) {
      maintenanceRecords++;
      require(
          "M09S1_CHECKPOINT".equals(text(record, "maintenanceType")), "maintenance type changed");
      long id = positiveLong(record, "maintenanceAttempt");
      String reason = text(record, "reason");
      require(
          "PROACTIVE_PHASE_CHECKPOINT".equals(reason) || "CHECKPOINT_REQUIRED_RETRY".equals(reason),
          "maintenance reason changed");
      long scheduledOffset = nonNegative(record, "scheduledPhaseOffsetNanos");
      if ("PROACTIVE_PHASE_CHECKPOINT".equals(reason)) {
        require(scheduledOffset == 100_000_000L, "proactive checkpoint offset changed");
      }
      long scheduled = nonNegative(record, "scheduledNanos");
      long offered = nonNegative(record, "offeredNanos");
      require(
          scheduled == Math.addExact(phaseOriginNanos, scheduledOffset) && offered >= scheduled,
          "maintenance schedule changed");
      if ("PROACTIVE_PHASE_CHECKPOINT".equals(reason)) {
        require(
            offered - scheduled <= 10_000_000L,
            "proactive checkpoint admission exceeded its frozen deadline");
      }
      String eventKind = text(record, "eventKind");
      if ("ADMISSION".equals(eventKind)) {
        require(
            nonNegative(record, "offerLagNanos") == offered - scheduled,
            "maintenance offer lag changed");
        String outcome = text(record, "admissionOutcome");
        boolean enqueued = "ENQUEUED".equals(outcome);
        if (enqueued) require(record.path("rejectionCode").isNull(), "checkpoint has rejection");
        else {
          require("REJECTED".equals(outcome), "maintenance admission outcome changed");
          text(record, "rejectionCode");
        }
        require(
            maintenance.put(
                    id,
                    new MaintenanceAttempt(
                        reason, scheduledOffset, scheduled, offered, enqueued, false))
                == null,
            "maintenance admission identity duplicated");
        return;
      }
      require("COMPLETION".equals(eventKind), "maintenance event kind changed");
      MaintenanceAttempt admission = maintenance.get(id);
      require(admission != null && admission.enqueued, "checkpoint completion lacks admission");
      require(!admission.completed, "checkpoint completed twice");
      require(
          admission.reason.equals(reason)
              && admission.scheduledOffset == scheduledOffset
              && admission.scheduled == scheduled
              && admission.offered == offered,
          "checkpoint completion identity changed");
      long terminal = nonNegative(record, "terminalNanos");
      require(terminal >= offered, "checkpoint completion precedes admission");
      require(
          nonNegative(record, "pauseFromScheduledNanos") == terminal - scheduled,
          "checkpoint pause changed");
      require("COMPLETED".equals(text(record, "completionKind")), "checkpoint did not complete");
      require(record.path("failureCode").isNull(), "completed checkpoint has failure code");
      long suffixRecordsBeforeReset = nonNegative(record, "suffixRecordsBeforeReset");
      long suffixBytesBeforeReset = nonNegative(record, "suffixBytesBeforeReset");
      require(
          nonNegative(record, "suffixRecordsAfterReset") == 0
              && nonNegative(record, "suffixBytesAfterReset") == 0,
          "checkpoint did not reset recovery suffix");
      admission.completed = true;
      admission.terminal = terminal;
      admission.suffixRecordsBeforeReset = suffixRecordsBeforeReset;
      admission.suffixBytesBeforeReset = suffixBytesBeforeReset;
      latestCompletedCheckpointTerminalNanos =
          Math.max(latestCompletedCheckpointTerminalNanos, terminal);
    }

    void accepted(JsonNode record) {
      long operationIndex = operationIndex(record);
      int attempt = record.path("attempt").intValue();
      require(attempt >= 0, "accepted trace attempt is negative");
      acceptedKeys.add(attemptKey(operationIndex, attempt));
      envelopes.requireMatches(operationIndex, sha256Text(record, "canonicalEnvelopeSha256"));
      require(
          "BASE64".equals(text(record, "canonicalEnvelopeEncoding")),
          "accepted trace encoding changed");
      byte[] envelope = Base64.getDecoder().decode(text(record, "canonicalEnvelopeBase64"));
      require(
          sha256(envelope).equals(text(record, "canonicalEnvelopeSha256")),
          "accepted trace envelope bytes changed");
      text(record, "canonicalResultDigest");
      text(record, "semanticStateDigest");
      acceptedTraceRecords++;
    }

    void phaseCut(JsonNode record) {
      require(phaseCut == null, "point has duplicate phase-cut records: " + pointId);
      phaseCut = record.deepCopy();
      JsonNode cut = record.path("observationCut");
      phaseOriginNanos = nonNegative(cut, "phaseOriginNanos");
      scheduledWindowEndNanos = positiveLong(cut, "scheduledWindowEndNanos");
      cutObservedNanos = positiveLong(cut, "observedNanos");
      require(
          scheduledWindowEndNanos > phaseOriginNanos
              && cutObservedNanos >= scheduledWindowEndNanos
              && nonNegative(cut, "observationLagNanos")
                  == cutObservedNanos - scheduledWindowEndNanos
              && cutObservedNanos - scheduledWindowEndNanos <= 10_000_000L,
          "scheduled-window observation cut changed");
      require(cut.path("queueCapacity").intValue() == 64, "phase-cut capacity changed");
      require(
          nonNegative(cut, "startingBacklog") <= 65, "starting backlog exceeds queue plus owner");
      long planned = positiveLong(cut, "plannedInitialOffers");
      long decisions = nonNegative(cut, "initialDecisionsAtCut");
      long undecided = nonNegative(cut, "scheduledDecisionBacklogAtCut");
      long servicePending = nonNegative(cut, "servicePendingAtCut");
      require(
          decisions <= planned
              && undecided == planned - decisions
              && servicePending <= 65
              && nonNegative(cut, "endingBacklog") == Math.addExact(undecided, servicePending)
              && servicePending == nonNegative(cut.path("attemptAccounting"), "pending"),
          "scheduled-cut demand backlog changed");
      require(nonNegative(cut, "p99QueueDepth") <= 64, "phase-cut queue p99 exceeds capacity");
      nonNegative(cut, "postCutOverloaded");
      JsonNode pacing = cut.path("pacingFidelity");
      require(
          positiveLong(pacing, "producerLagP99LimitNanos") == 50_000_000L
              && positiveLong(pacing, "producerLagMaxLimitNanos") == 250_000_000L
              && pacing.path("allScheduledArrivalsMaterialized").booleanValue()
              && pacing.path("allAdmissionDecisionsWithinLagLimits").booleanValue(),
          "producer lag limits changed");
      JsonNode drain = record.path("terminalDrain");
      long drainObserved = positiveLong(drain, "observedNanos");
      require(
          drainObserved >= cutObservedNanos
              && nonNegative(drain, "elapsedAfterObservationCutNanos")
                  == drainObserved - cutObservedNanos,
          "terminal drain timing changed");
      require(
          nonNegative(drain.path("attemptAccounting"), "pending") == 0,
          "terminal drain retains pending work");
    }

    void finish() throws IOException {
      latencies.close();
      initialQueues.close();
      producerLags.close();
      arrivalKeys.close();
      queueKeys.close();
      arrivalTuples.close();
      queueTuples.close();
      admittedKeys.close();
      completionKeys.close();
      durableCompletionKeys.close();
      acceptedKeys.close();
      retryExpectedKeys.close();
      retryFirstArrivalKeys.close();
      initiallyAdmittedLogicalKeys.close();
      terminalLogicalKeys.close();
      envelopes.close();
    }

    void verifyTerminalLedgers() throws IOException {
      require(
          attemptOffers == attemptAdmitted + attemptOverloaded + attemptClosed,
          "attempt offer equation failed: " + pointId);
      require(
          attemptAdmitted == completionRecords, "attempt completion equation failed: " + pointId);
      require(
          logicalOffers == logicalInitiallyAdmitted + logicalOverloaded + logicalClosed,
          "logical offer equation failed: " + pointId);
      require(
          logicalInitiallyAdmitted == logicalTerminal,
          "logical terminal equation failed: " + pointId);
      require(queueRecords == attemptOffers, "queue samples do not match attempts: " + pointId);
      require(resourceRecords > 0, "resource samples missing: " + pointId);
      long submissionCompletions = variants.values().stream().mapToLong(Long::longValue).sum();
      require(
          completionRecords == submissionCompletions + explicitFailures,
          "completion variants do not reconcile: " + pointId);
      require(latencies.count() == logicalTerminal, "latency denominator changed: " + pointId);
      require(
          initialQueues.count() == cutInitialDecisions,
          "scheduled-cut queue denominator changed: " + pointId);
      require(
          producerLags.count() == logicalOffers, "producer lag denominator changed: " + pointId);
      require(arrivalKeys.sameMultiset(queueKeys), "arrival/queue exact join failed: " + pointId);
      require(
          arrivalTuples.sameSequence(queueTuples),
          "arrival/queue decision time, attempt kind, or decision depth changed: " + pointId);
      require(
          admittedKeys.sameMultiset(completionKeys),
          "admission/completion exact join failed: " + pointId);
      require(
          durableCompletionKeys.sameMultiset(acceptedKeys),
          "durable completion/accepted trace exact join failed: " + pointId);
      require(
          retryExpectedKeys.sameMultiset(retryFirstArrivalKeys),
          "checkpoint-required/first-retry exact join failed: " + pointId);
      require(
          initiallyAdmittedLogicalKeys.sameMultiset(terminalLogicalKeys),
          "logical admission/terminal exact join failed: " + pointId);
      require(phaseCut != null, "phase-cut record missing: " + pointId);
      require(resourceTerminal, "resource terminal sample missing: " + pointId);
      require(
          resourceFirstPeriodicObservedNanos >= phaseOriginNanos
              && resourceFirstPeriodicObservedNanos <= phaseOriginNanos + 2_000_000_000L,
          "resource series does not cover phase start: " + pointId);
      long drainObserved = positiveLong(phaseCut.path("terminalDrain"), "observedNanos");
      require(
          resourceLastObservedNanos >= scheduledWindowEndNanos
              && resourceLastObservedNanos <= drainObserved,
          "resource series does not cover terminal drain: " + pointId);
      require(latestCompletedCheckpointTerminalNanos >= 0, "point has no completed checkpoint");
      boolean proactiveCompleted =
          maintenance.values().stream()
              .anyMatch(
                  value ->
                      value.enqueued
                          && value.completed
                          && value.reason.equals("PROACTIVE_PHASE_CHECKPOINT"));
      require(proactiveCompleted, "point has no successful proactive checkpoint");
      for (MaintenanceAttempt value : maintenance.values()) {
        require(!value.enqueued || value.completed, "admitted checkpoint did not complete");
      }
      if (!"WARMUP".equals(phase)) {
        require(acceptedTraceRecords > 0, "measured point has no accepted recovery trace");
      }
    }

    void verifyScheduledWindow() {
      long durationSeconds = durationSeconds();
      long durationNanos = Math.multiplyExact(durationSeconds, 1_000_000_000L);
      long expectedOperations = ScheduledArrival.operationsFor(offeredRate, durationNanos);
      require(
          logicalOffers == expectedOperations,
          "scheduled-arrival duration count changed: " + pointId);
      require(
          nextInitialOperation == expectedOperations,
          "initial scheduled-arrival ledger changed: " + pointId);
      require(
          scheduledWindowEndNanos == Math.addExact(phaseOriginNanos, durationNanos),
          "phase duration differs from frozen profile: " + pointId);
      long lastScheduled =
          Math.addExact(
              scheduledOrigin, ScheduledArrival.at(0, expectedOperations - 1, offeredRate));
      require(
          finalInitialActualOffer >= lastScheduled
              && finalInitialActualOffer - lastScheduled <= 250_000_000L,
          "admission decisions do not close within the frozen grace: " + pointId);
    }

    long variant(String name) {
      return variants.getOrDefault(name, 0L);
    }

    long durationSeconds() {
      return switch (phase) {
        case "WARMUP" -> expectedProfile.warmupSeconds;
        case "MEASUREMENT" -> expectedProfile.measurementSeconds;
        case "SOAK" -> expectedProfile.soakSeconds;
        default -> throw new IllegalStateException("unknown release phase " + phase);
      };
    }

    RunAccounting terminalAccounting() {
      return new RunAccounting(
          attemptOffers,
          attemptAdmitted,
          attemptOverloaded,
          attemptClosed,
          completeVariants(variants),
          explicitFailures,
          attemptAdmitted - completionRecords);
    }

    RunAccounting cutAccounting() {
      long completed = cutVariants.values().stream().mapToLong(Long::longValue).sum();
      return new RunAccounting(
          cutOffers,
          cutAdmitted,
          cutOverloaded,
          cutClosed,
          completeVariants(cutVariants),
          cutExplicitFailures,
          cutAdmitted - completed - cutExplicitFailures);
    }

    void closeQuietly() {
      latencies.closeQuietly();
      initialQueues.closeQuietly();
      producerLags.closeQuietly();
      arrivalKeys.closeQuietly();
      queueKeys.closeQuietly();
      arrivalTuples.closeQuietly();
      queueTuples.closeQuietly();
      admittedKeys.closeQuietly();
      completionKeys.closeQuietly();
      durableCompletionKeys.closeQuietly();
      acceptedKeys.closeQuietly();
      retryExpectedKeys.closeQuietly();
      retryFirstArrivalKeys.closeQuietly();
      initiallyAdmittedLogicalKeys.closeQuietly();
      terminalLogicalKeys.closeQuietly();
      envelopes.closeQuietly();
    }

    private long operationIndex(JsonNode record) {
      String logicalOperationId = text(record, "logicalOperationId");
      String prefix = pointId + "-op-";
      require(logicalOperationId.startsWith(prefix), "logical operation ID changed");
      try {
        long value = Long.parseLong(logicalOperationId.substring(prefix.length()));
        require(value >= 0, "logical operation index is negative");
        return value;
      } catch (NumberFormatException failure) {
        throw new IllegalStateException("logical operation index changed", failure);
      }
    }

    private static long attemptKey(long operationIndex, int attempt) {
      require(attempt >= 0 && attempt <= 0xffff, "attempt exceeds exact ledger encoding");
      require(
          operationIndex <= (Long.MAX_VALUE >>> 16),
          "logical operation exceeds exact ledger encoding");
      return (operationIndex << 16) | attempt;
    }

    private static final class MaintenanceAttempt {
      private final String reason;
      private final long scheduledOffset;
      private final long scheduled;
      private final long offered;
      private final boolean enqueued;
      private boolean completed;
      private long terminal = -1;
      private long suffixRecordsBeforeReset = -1;
      private long suffixBytesBeforeReset = -1;

      MaintenanceAttempt(
          String reason,
          long scheduledOffset,
          long scheduled,
          long offered,
          boolean enqueued,
          boolean completed) {
        this.reason = reason;
        this.scheduledOffset = scheduledOffset;
        this.scheduled = scheduled;
        this.offered = offered;
        this.enqueued = enqueued;
        this.completed = completed;
      }
    }
  }

  /** Ordered, file-backed exact join for fields duplicated by arrival and queue streams. */
  private static final class AttemptTupleSpool {
    private final Path path;
    private DataOutputStream output;
    private long count;

    AttemptTupleSpool(Path path) {
      this.path = path;
    }

    void add(
        long operationIndex,
        int attempt,
        String attemptKind,
        long offeredNanos,
        int decisionDepth) {
      require(operationIndex >= 0 && attempt >= 0, "attempt tuple identity is negative");
      require(offeredNanos >= 0, "attempt tuple offered time is negative");
      require(decisionDepth >= 0 && decisionDepth <= 64, "attempt tuple depth changed");
      int kind =
          switch (attemptKind) {
            case "INITIAL_SCHEDULED" -> 0;
            case "CHECKPOINT_RETRY" -> 1;
            default -> throw new IllegalStateException("attempt tuple kind changed");
          };
      try {
        if (output == null) {
          output =
              new DataOutputStream(
                  new BufferedOutputStream(
                      count == 0 && !Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                          ? Files.newOutputStream(
                              path,
                              java.nio.file.StandardOpenOption.CREATE_NEW,
                              java.nio.file.StandardOpenOption.WRITE)
                          : Files.newOutputStream(
                              path,
                              java.nio.file.StandardOpenOption.APPEND,
                              java.nio.file.StandardOpenOption.WRITE),
                      64 * 1024));
        }
        output.writeLong(operationIndex);
        output.writeInt(attempt);
        output.writeByte(kind);
        output.writeLong(offeredNanos);
        output.writeInt(decisionDepth);
        count = Math.incrementExact(count);
      } catch (IOException failure) {
        throw new IllegalStateException("cannot append exact attempt tuple", failure);
      }
    }

    boolean sameSequence(AttemptTupleSpool other) throws IOException {
      close();
      other.close();
      return count == other.count && (count == 0 || Files.mismatch(path, other.path) == -1);
    }

    void close() throws IOException {
      if (output != null) {
        output.close();
        output = null;
      }
    }

    void closeQuietly() {
      try {
        close();
      } catch (IOException ignored) {
        // A prior verification failure remains primary.
      }
    }
  }

  /** Fixed-width, file-backed operation-to-envelope binding used by every raw stream join. */
  private static final class EnvelopeLedger {
    private static final int HASH_BYTES = 32;
    private final Path path;
    private FileChannel channel;
    private long records;

    EnvelopeLedger(Path path) {
      this.path = path;
    }

    void append(long operationIndex, String hash) {
      require(operationIndex == records, "initial envelope ledger is not contiguous");
      byte[] bytes = HexFormat.of().parseHex(hash);
      require(bytes.length == HASH_BYTES, "envelope digest length changed");
      try {
        ensureOpen();
        writeFully(ByteBuffer.wrap(bytes), Math.multiplyExact(operationIndex, HASH_BYTES));
        records = Math.incrementExact(records);
      } catch (IOException failure) {
        throw new IllegalStateException("cannot append envelope ledger", failure);
      }
    }

    void requireMatches(long operationIndex, String hash) {
      require(operationIndex >= 0 && operationIndex < records, "envelope precedes initial offer");
      byte[] expected = new byte[HASH_BYTES];
      try {
        ensureOpen();
        readFully(ByteBuffer.wrap(expected), Math.multiplyExact(operationIndex, HASH_BYTES));
      } catch (IOException failure) {
        throw new IllegalStateException("cannot read envelope ledger", failure);
      }
      require(
          Arrays.equals(expected, HexFormat.of().parseHex(hash)),
          "retry/completion/accepted envelope identity changed");
    }

    private void ensureOpen() throws IOException {
      if (channel != null) return;
      channel =
          records == 0 && !Files.exists(path, LinkOption.NOFOLLOW_LINKS)
              ? FileChannel.open(
                  path,
                  java.nio.file.StandardOpenOption.CREATE_NEW,
                  java.nio.file.StandardOpenOption.READ,
                  java.nio.file.StandardOpenOption.WRITE)
              : FileChannel.open(
                  path,
                  java.nio.file.StandardOpenOption.READ,
                  java.nio.file.StandardOpenOption.WRITE);
    }

    private void writeFully(ByteBuffer buffer, long position) throws IOException {
      while (buffer.hasRemaining()) position += channel.write(buffer, position);
    }

    private void readFully(ByteBuffer buffer, long position) throws IOException {
      while (buffer.hasRemaining()) {
        int count = channel.read(buffer, position);
        if (count < 0) throw new EOFException("truncated envelope ledger");
        position += count;
      }
    }

    void close() throws IOException {
      if (channel != null) {
        channel.close();
        channel = null;
      }
    }

    void closeQuietly() {
      try {
        close();
      } catch (IOException ignored) {
        // A prior verification failure remains primary.
      }
    }
  }

  /** Reconstructs the exact v2 binary trace and its live result/state digests from raw JSONL. */
  private static final class TraceAccumulator {
    private static final int MAGIC = 0x4d313052;
    private static final int VERSION = 2;
    private final String traceId;
    private final M08EnvelopeCodec envelopeCodec = new M08EnvelopeCodec();
    private final MessageDigest traceDigest = sha256Digest();
    private final MessageDigest resultDigest = sha256Digest();
    private final Map<String, Long> acceptedSequencesByPoint = new HashMap<>();
    private long records;
    private String lastSemanticDigest;
    private TraceEvidence finished;

    TraceAccumulator(String traceId) {
      this.traceId = traceId;
      updateInt(traceDigest, MAGIC);
      updateInt(traceDigest, VERSION);
      updateText(traceDigest, traceId);
    }

    void accept(JsonNode record) {
      require(finished == null, "accepted trace was already finalized");
      require(traceId.equals(text(record, "recoveryTraceId")), "recovery trace ID changed");
      long ordinal = positiveLong(record, "traceOrdinal");
      require(ordinal == Math.incrementExact(records), "accepted trace ordinal changed");
      byte[] envelope = Base64.getDecoder().decode(text(record, "canonicalEnvelopeBase64"));
      require(envelope.length > 0 && envelope.length <= 1024 * 1024, "trace envelope is unbounded");
      require(
          sha256(envelope).equals(sha256Text(record, "canonicalEnvelopeSha256")),
          "accepted trace envelope hash changed");
      String pointId = text(record, "pointId");
      String logicalId = text(record, "logicalOperationId");
      int attempt = record.path("attempt").intValue();
      require(attempt == 0, "load qualification accepted a checkpoint retry attempt");
      long expectedProducerSequence =
          Math.incrementExact(acceptedSequencesByPoint.getOrDefault(pointId, 0L));
      final M08Envelope decoded;
      try {
        decoded = envelopeCodec.decodeCanonical(envelope, 1);
      } catch (StructuralRejectionException rejection) {
        throw new IllegalStateException(
            "accepted trace is not a canonical M08C1 envelope", rejection);
      }
      require(
          decoded.slot().producerId().equals("m10-load-" + pointId)
              && decoded.slot().producerEpoch() == 1
              && decoded.slot().shardId() == 1
              && decoded.slot().producerSequence() == expectedProducerSequence,
          "accepted M08C1 producer slot is not contiguous across overload decisions");
      acceptedSequencesByPoint.put(pointId, expectedProducerSequence);
      String result = text(record, "canonicalResultDigest");
      String semantic = text(record, "semanticStateDigest");
      updateLong(traceDigest, ordinal);
      updateText(traceDigest, pointId);
      updateText(traceDigest, logicalId);
      updateInt(traceDigest, attempt);
      updateInt(traceDigest, envelope.length);
      traceDigest.update(envelope);
      updateText(traceDigest, result);
      updateText(traceDigest, semantic);
      resultDigest.update(result.getBytes(StandardCharsets.UTF_8));
      resultDigest.update((byte) '\n');
      lastSemanticDigest = semantic;
      records = ordinal;
    }

    TraceEvidence finish() {
      require(records > 0 && lastSemanticDigest != null, "accepted trace is empty");
      if (finished == null) {
        finished =
            new TraceEvidence(
                records,
                HexFormat.of().formatHex(traceDigest.digest()),
                HexFormat.of().formatHex(resultDigest.digest()),
                lastSemanticDigest);
      }
      return finished;
    }

    private static void updateText(MessageDigest digest, String value) {
      byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
      require(bytes.length > 0 && bytes.length <= 4 * 1024, "trace text is unbounded");
      updateInt(digest, bytes.length);
      digest.update(bytes);
    }

    private static void updateInt(MessageDigest digest, int value) {
      digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
    }

    private static void updateLong(MessageDigest digest, long value) {
      digest.update(ByteBuffer.allocate(Long.BYTES).putLong(value).array());
    }
  }

  private record TraceEvidence(
      long records, String traceSha256, String resultSha256, String semanticStateDigest) {}

  /**
   * Disk-backed exact nearest-rank selector; memory stays bounded for multi-million record points.
   */
  private static final class LongSpool {
    private static final int CHUNK_LONGS = 1_000_000;
    private final Path path;
    private DataOutputStream output;
    private long count;

    LongSpool(Path path) {
      this.path = path;
    }

    void add(long value) {
      require(value >= 0, "raw sample is negative");
      try {
        ensureOutput();
        output.writeLong(value);
        count++;
      } catch (IOException failure) {
        throw new IllegalStateException("cannot append long spool", failure);
      }
    }

    private void ensureOutput() throws IOException {
      if (output != null) return;
      output =
          new DataOutputStream(
              new BufferedOutputStream(
                  count == 0 && !Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                      ? Files.newOutputStream(
                          path,
                          java.nio.file.StandardOpenOption.CREATE_NEW,
                          java.nio.file.StandardOpenOption.WRITE)
                      : Files.newOutputStream(
                          path,
                          java.nio.file.StandardOpenOption.APPEND,
                          java.nio.file.StandardOpenOption.WRITE),
                  64 * 1024));
    }

    long count() {
      return count;
    }

    void close() throws IOException {
      if (output != null) {
        output.close();
        output = null;
      }
    }

    void closeQuietly() {
      try {
        close();
      } catch (IOException ignored) {
        // A prior verification failure remains primary.
      }
    }

    boolean sameMultiset(LongSpool other) throws IOException {
      close();
      other.close();
      if (count != other.count) return false;
      if (count == 0) return true;
      try (SortedLongReader left = new SortedLongReader(path);
          SortedLongReader right = new SortedLongReader(other.path)) {
        while (true) {
          Long leftValue = left.next();
          Long rightValue = right.next();
          if (leftValue == null || rightValue == null) return leftValue == rightValue;
          if (!leftValue.equals(rightValue)) return false;
        }
      }
    }

    long quantile(double quantile) throws IOException {
      return quantiles(List.of(quantile)).values().iterator().next();
    }

    long max() throws IOException {
      close();
      require(count > 0, "raw sample spool is empty");
      return externalSelect(List.of(count)).get(count);
    }

    Map<String, Long> quantiles(List<Double> quantiles) throws IOException {
      close();
      require(count > 0, "raw sample spool is empty");
      List<Long> targetRanks =
          quantiles.stream().map(value -> (long) Math.ceil(value * count)).toList();
      Map<Long, Long> selected = externalSelect(targetRanks);
      Map<String, Long> result = new LinkedHashMap<>();
      for (int index = 0; index < quantiles.size(); index++) {
        result.put(label(quantiles.get(index)), selected.get(targetRanks.get(index)));
      }
      return Map.copyOf(result);
    }

    private Map<Long, Long> externalSelect(List<Long> ranks) throws IOException {
      List<Path> chunks = new ArrayList<>();
      try (var input =
          new DataInputStream(new BufferedInputStream(Files.newInputStream(path), 64 * 1024))) {
        while (true) {
          long[] values = new long[CHUNK_LONGS];
          int size = 0;
          try {
            while (size < values.length) {
              long value = input.readLong();
              values[size++] = value;
            }
          } catch (EOFException end) {
            // last partial chunk
          }
          if (size == 0) break;
          Arrays.sort(values, 0, size);
          Path chunk = path.resolveSibling(path.getFileName() + ".sorted-" + chunks.size());
          try (var output =
              new DataOutputStream(
                  new BufferedOutputStream(Files.newOutputStream(chunk), 64 * 1024))) {
            for (int index = 0; index < size; index++) output.writeLong(values[index]);
          }
          chunks.add(chunk);
          if (size < values.length) break;
        }
      }
      PriorityQueue<Cursor> queue = new PriorityQueue<>(Comparator.comparingLong(Cursor::value));
      List<Cursor> cursors = new ArrayList<>();
      try {
        for (Path chunk : chunks) {
          Cursor cursor = new Cursor(chunk);
          cursors.add(cursor);
          if (cursor.advance()) queue.add(cursor);
        }
        List<Long> orderedRanks = ranks.stream().distinct().sorted().toList();
        Map<Long, Long> selected = new HashMap<>();
        long position = 0;
        int target = 0;
        while (!queue.isEmpty() && target < orderedRanks.size()) {
          Cursor cursor = queue.remove();
          position++;
          while (target < orderedRanks.size() && orderedRanks.get(target) == position) {
            selected.put(position, cursor.value());
            target++;
          }
          if (cursor.advance()) queue.add(cursor);
        }
        require(selected.size() == orderedRanks.size(), "external percentile selection incomplete");
        return selected;
      } finally {
        for (Cursor cursor : cursors) cursor.close();
        for (Path chunk : chunks) Files.deleteIfExists(chunk);
      }
    }

    private static String label(double quantile) {
      if (Double.compare(quantile, 0.5) == 0) return "p50";
      if (Double.compare(quantile, 0.95) == 0) return "p95";
      if (Double.compare(quantile, 0.99) == 0) return "p99";
      if (Double.compare(quantile, 0.999) == 0) return "p99_9";
      throw new IllegalArgumentException("unsupported quantile");
    }

    private static final class Cursor implements AutoCloseable {
      private final DataInputStream input;
      private long value;

      Cursor(Path path) throws IOException {
        input = new DataInputStream(new BufferedInputStream(Files.newInputStream(path), 64 * 1024));
      }

      boolean advance() throws IOException {
        try {
          value = input.readLong();
          return true;
        } catch (EOFException end) {
          return false;
        }
      }

      long value() {
        return value;
      }

      @Override
      public void close() throws IOException {
        input.close();
      }
    }

    private static final class SortedLongReader implements AutoCloseable {
      private final List<Path> chunks = new ArrayList<>();
      private final List<Cursor> cursors = new ArrayList<>();
      private final PriorityQueue<Cursor> queue =
          new PriorityQueue<>(Comparator.comparingLong(Cursor::value));

      SortedLongReader(Path source) throws IOException {
        try (var input =
            new DataInputStream(new BufferedInputStream(Files.newInputStream(source), 64 * 1024))) {
          while (true) {
            long[] values = new long[CHUNK_LONGS];
            int size = 0;
            try {
              while (size < values.length) values[size++] = input.readLong();
            } catch (EOFException end) {
              // final partial chunk
            }
            if (size == 0) break;
            Arrays.sort(values, 0, size);
            Path chunk = source.resolveSibling(source.getFileName() + ".join-" + chunks.size());
            try (var output =
                new DataOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(chunk), 64 * 1024))) {
              for (int index = 0; index < size; index++) output.writeLong(values[index]);
            }
            chunks.add(chunk);
            if (size < values.length) break;
          }
        }
        for (Path chunk : chunks) {
          Cursor cursor = new Cursor(chunk);
          cursors.add(cursor);
          if (cursor.advance()) queue.add(cursor);
        }
      }

      Long next() throws IOException {
        if (queue.isEmpty()) return null;
        Cursor cursor = queue.remove();
        long value = cursor.value();
        if (cursor.advance()) queue.add(cursor);
        return value;
      }

      @Override
      public void close() throws IOException {
        IOException failure = null;
        for (Cursor cursor : cursors) {
          try {
            cursor.close();
          } catch (IOException closeFailure) {
            if (failure == null) failure = closeFailure;
            else failure.addSuppressed(closeFailure);
          }
        }
        for (Path chunk : chunks) Files.deleteIfExists(chunk);
        if (failure != null) throw failure;
      }
    }
  }
}
