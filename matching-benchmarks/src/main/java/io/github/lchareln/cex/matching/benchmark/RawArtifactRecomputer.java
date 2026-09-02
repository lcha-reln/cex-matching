package io.github.lchareln.cex.matching.benchmark;

import io.github.lchareln.cex.matching.local.RecoveryBudget;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Independently decompresses raw shards and re-derives every publishable M10 measurement. */
final class RawArtifactRecomputer {
  private static final long MAXIMUM_RESOURCE_GAP_NANOS = 2_000_000_000L;

  RawRecomputation recompute(
      Path output,
      ArtifactContext context,
      QualificationProfile profile,
      QualificationArtifactSink.Inventory inventory,
      Map<String, LoadPointResult> expectedPoints,
      long expectedAcceptedTraceRecords,
      ObjectMapper mapper)
      throws IOException {
    Map<String, MutablePoint> points = new LinkedHashMap<>();
    Map<String, TraceAccumulator> traces = new LinkedHashMap<>();

    // Cuts are read first so arrival/completion streams can be partitioned at the immutable
    // scheduled boundary. The later capture timestamp and terminal drain never move that boundary.
    long phaseCutRecords =
        readStream(
            output,
            context,
            profile,
            inventory,
            "raw-phase-cuts",
            QualificationArtifactSink.PHASE_CUT_SCHEMA,
            mapper,
            node -> phaseCut(points, node));
    long arrivalRecords =
        readStream(
            output,
            context,
            profile,
            inventory,
            "raw-arrivals",
            QualificationArtifactSink.ARRIVAL_SCHEMA,
            mapper,
            node -> arrival(points, node));
    // Maintenance is read before completions so each point's last successful checkpoint cut is
    // known when the completion stream is replayed. That makes the final recovery suffix an
    // independently derived raw value rather than a trusted summary field.
    long maintenanceRecords =
        readStream(
            output,
            context,
            profile,
            inventory,
            "raw-maintenance",
            QualificationArtifactSink.MAINTENANCE_SCHEMA,
            mapper,
            node -> maintenance(points, node));
    long completionRecords =
        readStream(
            output,
            context,
            profile,
            inventory,
            "raw-completions",
            QualificationArtifactSink.COMPLETION_SCHEMA,
            mapper,
            node -> completion(points, node));
    long queueRecords =
        readStream(
            output,
            context,
            profile,
            inventory,
            "raw-queue",
            QualificationArtifactSink.QUEUE_SCHEMA,
            mapper,
            node -> queue(points, node));
    long resourceRecords =
        readStream(
            output,
            context,
            profile,
            inventory,
            "resources",
            QualificationArtifactSink.RESOURCE_SCHEMA,
            mapper,
            node -> resource(points, node));
    long acceptedTraceRecords =
        readStream(
            output,
            context,
            profile,
            inventory,
            "accepted-trace",
            QualificationArtifactSink.ACCEPTED_TRACE_SCHEMA,
            mapper,
            node -> acceptedTrace(points, traces, node));
    require(
        acceptedTraceRecords == expectedAcceptedTraceRecords,
        "accepted trace record count changed after decompression");

    Map<String, String> reconstructedTraceHashes = new LinkedHashMap<>();
    traces.forEach((id, trace) -> reconstructedTraceHashes.put(id, trace.finish()));
    Map<String, RecomputedPoint> verified = new LinkedHashMap<>();
    for (Map.Entry<String, LoadPointResult> entry : expectedPoints.entrySet()) {
      MutablePoint raw = points.get(entry.getKey());
      require(raw != null, "raw streams omit published point: " + entry.getKey());
      verified.put(entry.getKey(), raw.finish(entry.getValue()));
    }
    for (Map.Entry<String, MutablePoint> entry : points.entrySet()) {
      entry.getValue().requireRawReconciled(entry.getKey());
    }
    validateRecovery(
        output.resolve("recovery.json"),
        context,
        profile,
        inventory,
        points,
        reconstructedTraceHashes,
        mapper);
    return new RawRecomputation(
        arrivalRecords,
        completionRecords,
        queueRecords,
        resourceRecords,
        maintenanceRecords,
        phaseCutRecords,
        acceptedTraceRecords,
        reconstructedTraceHashes.size(),
        true,
        true,
        true,
        verified);
  }

  private static long readStream(
      Path output,
      ArtifactContext context,
      QualificationProfile profile,
      QualificationArtifactSink.Inventory inventory,
      String stream,
      String schema,
      ObjectMapper mapper,
      RecordConsumer consumer)
      throws IOException {
    List<ShardedJsonlWriter.ShardInfo> shards = inventory.streams().get(stream);
    require(shards != null, "artifact inventory omits stream: " + stream);
    long total = 0;
    for (ShardedJsonlWriter.ShardInfo shard : shards) {
      Path path = output.resolve(shard.relativePath()).normalize();
      require(
          path.startsWith(output) && Files.size(path) == shard.compressedBytes(),
          "raw shard path or size changed: " + shard.relativePath());
      require(
          sha256(path).equals(shard.sha256()), "raw shard digest changed: " + shard.relativePath());
      long shardRecords = 0;
      try (var gzip = new GZIPInputStream(Files.newInputStream(path), 64 * 1024);
          var reader =
              new BufferedReader(new InputStreamReader(gzip, StandardCharsets.UTF_8), 64 * 1024)) {
        String line;
        while ((line = reader.readLine()) != null) {
          JsonNode node = mapper.readTree(line);
          requireCommon(node, context, profile, schema);
          consumer.accept(node);
          shardRecords = Math.incrementExact(shardRecords);
        }
      }
      require(
          shardRecords == shard.recordCount(),
          "decompressed record count changed: " + shard.relativePath());
      total = Math.addExact(total, shardRecords);
    }
    return total;
  }

  private static void requireCommon(
      JsonNode node, ArtifactContext context, QualificationProfile profile, String schema) {
    require(schema.equals(text(node, "schemaVersion")), "raw schema changed");
    require(context.runId().equals(text(node, "runId")), "raw runId changed");
    require(context.profileId().name().equals(text(node, "profileId")), "raw profile changed");
    require(context.resultScope().equals(text(node, "resultScope")), "raw scope changed");
    require(
        context.eligibleForReleaseEvidence()
            == node.path("eligibleForReleaseEvidence").booleanValue(),
        "raw eligibility changed");
    require(context.sourceCommit().equals(text(node, "sourceCommit")), "raw source changed");
    require(
        context.workloadSha256().equals(text(node, "workloadSha256")), "raw workload hash changed");
    require("M10Q2".equals(text(node, "qualificationRuntimePolicyId")), "runtime policy changed");
    require(
        "M10_DEDICATED_NOT_M09_DEFAULT".equals(text(node, "qualificationRecoveryBudgetPolicy")),
        "qualification budget scope changed");
    require(
        node.path("qualificationMaxSuffixRecords").longValue()
            == profile.recoveryBudgetMaxSuffixRecords(),
        "qualification record budget changed");
    require(
        node.path("qualificationMaxSuffixBytes").longValue()
            == profile.recoveryBudgetMaxSuffixBytes(),
        "qualification byte budget changed");
    require(
        node.path("m09DefaultMaxSuffixRecords").longValue()
            == RecoveryBudget.M09_DEFAULT.maxSuffixRecords(),
        "M09 record default changed");
    require(
        node.path("m09DefaultMaxSuffixBytes").longValue()
            == RecoveryBudget.M09_DEFAULT.maxSuffixBytes(),
        "M09 byte default changed");
    require(
        node.path("plannedWalRecordCeilingBytes").intValue()
            == profile.plannedWalRecordCeilingBytes(),
        "planning ceiling changed");
    require(
        node.path("proactiveCheckpointOffsetNanos").longValue()
            == profile.proactiveCheckpointOffsetNanos(),
        "checkpoint offset changed");
    require("DATA".equals(text(node, "recordType")), "raw record is not DATA");
  }

  private static void phaseCut(Map<String, MutablePoint> points, JsonNode node) {
    MutablePoint point = point(points, node);
    require(point.phaseCut == null, "point has more than one phase-cut record");
    point.phaseCut = node.deepCopy();
    JsonNode cut = node.path("observationCut");
    require(cut.isObject(), "observation cut missing");
    point.cutObservedNanos = cut.path("observedNanos").longValue();
    point.scheduledWindowEndNanos = cut.path("scheduledWindowEndNanos").longValue();
    require(
        point.cutObservedNanos >= point.scheduledWindowEndNanos,
        "observation cut precedes scheduled window end");
  }

  private static void arrival(Map<String, MutablePoint> points, JsonNode node) {
    MutablePoint point = point(points, node);
    String logicalId = text(node, "logicalOperationId");
    int attempt = nonNegativeInt(node, "attempt");
    String envelopeHash = sha256Text(node, "canonicalEnvelopeSha256");
    String attemptKey = attemptKey(point.pointId, logicalId, attempt);
    point.attemptOffers++;
    long scheduled = node.path("scheduledArrivalNanos").longValue();
    long decision = node.path("admissionDecisionNanos").longValue();
    point.arrivalKeys.add(decisionKey(attemptKey, decision));
    long producerLag = node.path("producerLagNanos").longValue();
    require(producerLag == decision - scheduled && producerLag >= 0, "producer lag changed");
    require(
        "ADMISSION_GATE_DECISION".equals(text(node, "observationKind")),
        "arrival observation kind changed");
    require(!node.has("actualOfferNanos"), "arrival retained ambiguous offer time");
    String outcome = text(node, "admissionOutcome");
    boolean enqueued = "ENQUEUED_NOT_ACK".equals(outcome);
    if (enqueued) {
      point.attemptAdmitted++;
      point.admittedKeys.add(attemptKey);
    } else if ("REJECTED".equals(outcome)) {
      if ("OVERLOADED_BEFORE_WAL".equals(nullableText(node, "rejectionCode"))) {
        point.attemptOverloaded++;
      } else {
        point.attemptClosedOrInvalid++;
      }
    } else {
      throw new IllegalStateException("unknown raw admission outcome");
    }
    if (decision < point.scheduledWindowEndNanos) {
      point.cutOffers++;
      if (enqueued) {
        point.cutAdmitted++;
      } else if ("OVERLOADED_BEFORE_WAL".equals(nullableText(node, "rejectionCode"))) {
        point.cutOverloaded++;
      } else {
        point.cutClosedOrInvalid++;
      }
    }

    String attemptKind = text(node, "attemptKind");
    if (attempt == 0) {
      require("INITIAL_SCHEDULED".equals(attemptKind), "initial attempt kind changed");
      point.logicalOffers++;
      point.producerLags.add(producerLag);
      if (decision < point.scheduledWindowEndNanos) {
        point.initialDecisionsAtCut++;
      }
      if (enqueued) {
        point.logicalAdmitted++;
      } else if ("OVERLOADED_BEFORE_WAL".equals(nullableText(node, "rejectionCode"))) {
        point.logicalOverloaded++;
      } else {
        point.logicalClosedOrInvalid++;
      }
    } else {
      require("CHECKPOINT_RETRY".equals(attemptKind), "retry attempt kind changed");
      int origin = nonNegativeInt(node, "retryOriginAttempt");
      long retryOrdinal = node.path("retryOfferOrdinal").longValue();
      require(retryOrdinal > 0, "retry offer ordinal missing");
      if (retryOrdinal == 1) {
        point.retryFirstArrivalKeys.add(retryKey(point.pointId, logicalId, origin, envelopeHash));
      }
    }
  }

  private static void completion(Map<String, MutablePoint> points, JsonNode node) {
    MutablePoint point = point(points, node);
    String logicalId = text(node, "logicalOperationId");
    int attempt = nonNegativeInt(node, "attempt");
    String envelopeHash = sha256Text(node, "canonicalEnvelopeSha256");
    String key = attemptKey(point.pointId, logicalId, attempt);
    point.completionKeys.add(key);
    point.attemptCompletionRecords++;
    String kind = text(node, "completionKind");
    String variant = null;
    int newWalRecordLength = 0;
    if ("SUBMISSION_RESULT".equals(kind)) {
      variant = text(node, "submissionResultVariant");
      require(
          RunReconciler.SUBMISSION_RESULT_VARIANTS.contains(variant),
          "unknown raw submission result variant");
      point.variants.merge(variant, 1L, Math::addExact);
      if ("NEW_DURABLY_APPLIED".equals(variant) || "DUPLICATE_REPLAYED".equals(variant)) {
        point.durableCompletionKeys.add(key);
      }
      if ("NEW_DURABLY_APPLIED".equals(variant)) {
        newWalRecordLength = node.path("walRecordLength").intValue();
        require(
            newWalRecordLength > 0
                && newWalRecordLength <= QualificationProfile.PLANNED_WAL_RECORD_CEILING_BYTES,
            "actual WAL record exceeds M10Q2 planning ceiling");
      } else {
        require(node.path("walRecordLength").isNull(), "non-new result carried WAL length");
      }
      if ("CHECKPOINT_REQUIRED".equals(variant)) {
        point.retryExpectedKeys.add(retryKey(point.pointId, logicalId, attempt, envelopeHash));
      }
    } else if ("EXPLICIT_SERVICE_FAILURE".equals(kind)) {
      point.explicitFailures++;
    } else {
      throw new IllegalStateException("unknown raw completion kind");
    }
    require(
        "OWNER_COMPLETED_UNDER_GATE".equals(text(node, "timeOrigin")),
        "completion time origin changed");
    require(!node.has("terminalNanos"), "completion retained ambiguous terminal time");
    long terminal = node.path("ownerCompletedNanos").longValue();
    if (newWalRecordLength > 0 && terminal > point.latestCompletedCheckpointTerminalNanos) {
      point.actualSuffixRecordsFromRaw = Math.incrementExact(point.actualSuffixRecordsFromRaw);
      point.actualSuffixBytesFromRaw =
          Math.addExact(point.actualSuffixBytesFromRaw, newWalRecordLength);
    }
    if (terminal < point.scheduledWindowEndNanos) {
      if (variant == null) {
        point.cutExplicitFailures++;
      } else {
        point.cutVariants.merge(variant, 1L, Math::addExact);
      }
    }
    if (node.path("logicalTerminal").booleanValue()) {
      point.logicalTerminal++;
      long scheduled = node.path("scheduledArrivalNanos").longValue();
      long latency = node.path("latencyFromScheduledNanos").longValue();
      require(latency == terminal - scheduled && latency >= 0, "raw latency origin changed");
      point.logicalLatencies.add(latency);
    }
  }

  private static void queue(Map<String, MutablePoint> points, JsonNode node) {
    MutablePoint point = point(points, node);
    String key =
        attemptKey(
            point.pointId, text(node, "logicalOperationId"), nonNegativeInt(node, "attempt"));
    point.queueRecords++;
    long decision = node.path("admissionDecisionNanos").longValue();
    require(decision >= 0, "queue admission decision time missing");
    point.queueKeys.add(decisionKey(key, decision));
    require(
        "ADMISSION_GATE_DECISION".equals(text(node, "observationKind")),
        "queue observation is not gate-frozen");
    require(!node.has("offeredNanos"), "queue retained ambiguous offer time");
    long depth = node.path("decisionQueueDepth").longValue();
    require(depth >= 0, "queue depth missing");
    if ("INITIAL_SCHEDULED".equals(text(node, "attemptKind"))
        && decision < point.scheduledWindowEndNanos) {
      point.initialQueueDepthAtCut.add(depth);
    }
  }

  private static void resource(Map<String, MutablePoint> points, JsonNode node) {
    MutablePoint point = point(points, node);
    point.resourceRecords++;
    long sequence = node.path("sampleSequence").longValue();
    require(sequence == point.resourceRecords, "resource sequence changed");
    String kind = text(node, "sampleKind");
    require("PERIODIC".equals(kind) || "TERMINAL".equals(kind), "resource kind changed");
    long scheduled = node.path("scheduledSampleNanos").longValue();
    long observed = node.path("observedNanos").longValue();
    require(observed >= scheduled, "resource sample precedes its schedule");
    long samplingLag = node.path("samplingLagNanos").longValue();
    require(
        samplingLag == observed - scheduled
            && samplingLag >= 0
            && samplingLag <= MAXIMUM_RESOURCE_GAP_NANOS,
        "resource sampling lag exceeded two seconds");
    if (point.lastResourceObserved >= 0) {
      require(observed > point.lastResourceObserved, "resource time regressed");
      require(
          scheduled - point.lastResourceScheduled <= MAXIMUM_RESOURCE_GAP_NANOS,
          "resource scheduled gap exceeded two seconds");
      require(
          observed - point.lastResourceObserved <= MAXIMUM_RESOURCE_GAP_NANOS,
          "resource observed gap exceeded two seconds");
      require(
          node.path("totalThreadAllocatedBytes").longValue() >= point.lastAllocated,
          "allocation counter regressed");
      require(
          node.path("garbageCollectionCount").longValue() >= point.lastGcCount,
          "GC count regressed");
      require(
          node.path("garbageCollectionMillis").longValue() >= point.lastGcMillis,
          "GC time regressed");
      require(node.path("processCpuNanos").longValue() >= point.lastCpuNanos, "CPU time regressed");
    }
    require(node.path("totalThreadAllocatedBytes").longValue() >= 0, "allocation missing");
    require(node.path("garbageCollectionCount").longValue() >= 0, "GC count missing");
    require(node.path("garbageCollectionMillis").longValue() >= 0, "GC time missing");
    require(node.path("processCpuNanos").longValue() >= 0, "CPU missing");
    require(node.path("heapUsedBytes").longValue() >= 0, "heap gauge missing");
    require(node.path("committedVirtualMemoryBytes").longValue() >= 0, "memory gauge missing");
    require(node.path("systemMemoryUsedBytes").longValue() >= 0, "system gauge missing");
    require(node.path("queueDepth").intValue() >= 0, "queue gauge missing");
    point.lastResourceObserved = observed;
    point.lastResourceScheduled = scheduled;
    point.lastAllocated = node.path("totalThreadAllocatedBytes").longValue();
    point.lastGcCount = node.path("garbageCollectionCount").longValue();
    point.lastGcMillis = node.path("garbageCollectionMillis").longValue();
    point.lastCpuNanos = node.path("processCpuNanos").longValue();
    point.resourceTerminal = "TERMINAL".equals(kind);
  }

  private static void maintenance(Map<String, MutablePoint> points, JsonNode node) {
    MutablePoint point = point(points, node);
    require("M09S1_CHECKPOINT".equals(text(node, "maintenanceType")), "maintenance type changed");
    String eventKind = text(node, "eventKind");
    require(
        "ADMISSION".equals(eventKind) || "COMPLETION".equals(eventKind),
        "maintenance event changed");
    require(node.path("scheduledPhaseOffsetNanos").longValue() >= 0, "maintenance offset missing");
    if ("COMPLETION".equals(eventKind) && "COMPLETED".equals(text(node, "completionKind"))) {
      long terminalNanos = node.path("terminalNanos").longValue();
      require(terminalNanos >= 0, "checkpoint terminal time missing");
      point.latestCompletedCheckpointTerminalNanos =
          Math.max(point.latestCompletedCheckpointTerminalNanos, terminalNanos);
      require(
          node.path("suffixRecordsAfterReset").longValue() == 0,
          "checkpoint did not reset records");
      require(
          node.path("suffixBytesAfterReset").longValue() == 0, "checkpoint did not reset bytes");
    }
  }

  private static void acceptedTrace(
      Map<String, MutablePoint> points, Map<String, TraceAccumulator> traces, JsonNode node) {
    MutablePoint point = point(points, node);
    require("BASE64".equals(text(node, "canonicalEnvelopeEncoding")), "trace encoding changed");
    byte[] envelope = Base64.getDecoder().decode(text(node, "canonicalEnvelopeBase64"));
    require(envelope.length > 0, "accepted trace envelope is empty");
    require(
        sha256(envelope).equals(sha256Text(node, "canonicalEnvelopeSha256")),
        "trace envelope hash changed");
    String logicalId = text(node, "logicalOperationId");
    int attempt = nonNegativeInt(node, "attempt");
    point.acceptedKeys.add(attemptKey(point.pointId, logicalId, attempt));
    point.acceptedTraceRecords++;
    String traceId = text(node, "recoveryTraceId");
    long ordinal = node.path("traceOrdinal").longValue();
    RecoveryTrace.Entry entry =
        new RecoveryTrace.Entry(
            ordinal,
            point.pointId,
            logicalId,
            attempt,
            envelope,
            text(node, "canonicalResultDigest"),
            text(node, "semanticStateDigest"));
    traces.computeIfAbsent(traceId, TraceAccumulator::new).accept(entry);
  }

  private static MutablePoint point(Map<String, MutablePoint> points, JsonNode node) {
    String pointId = text(node, "pointId");
    MutablePoint point = points.computeIfAbsent(pointId, MutablePoint::new);
    point.requireIdentity(
        text(node, "phase"),
        node.path("sweep").intValue(),
        node.path("ladderPermille").intValue(),
        node.path("offeredRate").longValue());
    return point;
  }

  private static void validateRecovery(
      Path path,
      ArtifactContext context,
      QualificationProfile profile,
      QualificationArtifactSink.Inventory inventory,
      Map<String, MutablePoint> points,
      Map<String, String> reconstructedTraceHashes,
      ObjectMapper mapper)
      throws IOException {
    require(Files.size(path) == inventory.recoveryBytes(), "recovery.json size changed");
    require(sha256(path).equals(inventory.recoverySha256()), "recovery.json digest changed");
    JsonNode root = mapper.readTree(path.toFile());
    requireRecoveryCommon(root, context, profile);
    require("RECOVERY_COLLECTION".equals(text(root, "recordType")), "recovery collection changed");
    JsonNode records = root.path("records");
    require(records.isArray(), "recovery records missing");
    require(records.size() == inventory.recoveryRecordCount(), "recovery record count changed");
    for (JsonNode record : records) {
      requireRecoveryCommon(record, context, profile);
      require("DATA".equals(text(record, "recordType")), "recovery row changed");
      require(record.path("exactResultDigest").booleanValue(), "recovered result is not exact");
      require(
          record.path("exactSemanticStateDigest").booleanValue(), "recovered state is not exact");
      require(
          text(record, "liveResultDigest").equals(text(record, "directReplayResultDigest")),
          "direct replay result differs");
      require(
          text(record, "liveSemanticStateDigest")
              .equals(text(record, "directReplaySemanticStateDigest")),
          "direct replay semantic state differs");
      String traceId = text(record, "recoveryTraceId");
      require(
          text(record, "recoveryTraceSha256").equals(reconstructedTraceHashes.get(traceId)),
          "accepted trace does not reconstruct binary recovery hash");
      require(record.path("recoveryElapsedNanos").longValue() > 0, "recovery time missing");
      long actualRecords = record.path("actualSuffixRecords").longValue();
      long actualBytes = record.path("actualSuffixBytes").longValue();
      MutablePoint point = points.get(text(record, "pointId"));
      require(point != null, "recovery row has no raw point");
      require(
          point.latestCompletedCheckpointTerminalNanos >= 0,
          "recovery point has no completed checkpoint cut");
      require(
          actualRecords == point.actualSuffixRecordsFromRaw,
          "recovery suffix record count differs from decompressed raw");
      require(
          actualBytes == point.actualSuffixBytesFromRaw,
          "recovery suffix byte count differs from decompressed raw");
      require(
          actualRecords >= 0 && actualRecords <= profile.recoveryBudgetMaxSuffixRecords(),
          "actual recovery record suffix changed");
      require(
          actualBytes >= 0 && actualBytes <= profile.recoveryBudgetMaxSuffixBytes(),
          "actual recovery byte suffix changed");
      require(
          "M08_LEGACY_UNBOUNDED_NO_SNAPSHOT".equals(text(record, "directReplayWalConfig")),
          "direct replay config changed");
    }
    require(
        reconstructedTraceHashes.size() == records.size(),
        "recovery and reconstructed trace inventories differ");
  }

  private static void requireRecoveryCommon(
      JsonNode node, ArtifactContext context, QualificationProfile profile) {
    require(
        QualificationArtifactSink.RECOVERY_SCHEMA.equals(text(node, "schemaVersion")),
        "recovery schema changed");
    require(context.runId().equals(text(node, "runId")), "recovery runId changed");
    require(context.profileId().name().equals(text(node, "profileId")), "recovery profile changed");
    require(context.resultScope().equals(text(node, "resultScope")), "recovery scope changed");
    require(context.sourceCommit().equals(text(node, "sourceCommit")), "recovery source changed");
    require("M10Q2".equals(text(node, "qualificationRuntimePolicyId")), "recovery policy changed");
    require(
        node.path("qualificationMaxSuffixRecords").longValue()
            == profile.recoveryBudgetMaxSuffixRecords(),
        "recovery record bound changed");
    require(
        node.path("qualificationMaxSuffixBytes").longValue()
            == profile.recoveryBudgetMaxSuffixBytes(),
        "recovery byte bound changed");
  }

  private static RunAccounting accounting(JsonNode node) {
    Map<String, Long> variants = new LinkedHashMap<>();
    JsonNode variantNode = node.path("submissionResultVariants");
    for (String variant : RunReconciler.SUBMISSION_RESULT_VARIANTS.stream().sorted().toList()) {
      variants.put(variant, variantNode.path(variant).longValue());
    }
    return new RunAccounting(
        node.path("offers").longValue(),
        node.path("admitted").longValue(),
        node.path("overloaded").longValue(),
        node.path("closedOrInvalid").longValue(),
        variants,
        node.path("explicitServiceFailures").longValue(),
        node.path("pending").longValue());
  }

  private static String attemptKey(String pointId, String logicalId, int attempt) {
    return pointId + '\n' + logicalId + '\n' + attempt;
  }

  private static String decisionKey(String attemptKey, long admissionDecisionNanos) {
    return attemptKey + '\n' + admissionDecisionNanos;
  }

  private static String retryKey(
      String pointId, String logicalId, int checkpointRequiredAttempt, String envelopeHash) {
    return pointId + '\n' + logicalId + '\n' + checkpointRequiredAttempt + '\n' + envelopeHash;
  }

  private static int nonNegativeInt(JsonNode node, String field) {
    int value = node.path(field).intValue();
    require(value >= 0, "negative integer field: " + field);
    return value;
  }

  private static String sha256Text(JsonNode node, String field) {
    String value = text(node, field);
    require(value.matches("[0-9a-f]{64}"), "invalid SHA-256 field: " + field);
    return value;
  }

  private static String text(JsonNode node, String field) {
    String value = node.path(field).stringValue();
    if (value == null) {
      throw new IllegalStateException("missing text field: " + field);
    }
    return value;
  }

  private static String nullableText(JsonNode node, String field) {
    JsonNode value = node.path(field);
    return value.isNull() ? null : value.stringValue();
  }

  private static String sha256(Path path) throws IOException {
    MessageDigest digest = sha256Digest();
    try (var input = Files.newInputStream(path)) {
      byte[] buffer = new byte[64 * 1024];
      int count;
      while ((count = input.read(buffer)) >= 0) {
        digest.update(buffer, 0, count);
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private static String sha256(byte[] value) {
    return HexFormat.of().formatHex(sha256Digest().digest(value));
  }

  private static MessageDigest sha256Digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }

  record RawRecomputation(
      long arrivalRecords,
      long completionRecords,
      long queueRecords,
      long resourceRecords,
      long maintenanceRecords,
      long phaseCutRecords,
      long acceptedTraceRecords,
      int reconstructedRecoveryTraces,
      boolean exactAttemptJoinAndTraceHash,
      boolean exactRecoverySuffixRecordsAndBytes,
      boolean allNewWalRecordsWithinPlanningCeiling,
      Map<String, RecomputedPoint> points) {
    RawRecomputation {
      points = Map.copyOf(points);
    }
  }

  record RecomputedPoint(
      RunAccounting terminalAccounting,
      RunAccounting observationCutAccounting,
      long logicalOffers,
      long logicalAdmitted,
      long logicalOverloaded,
      long logicalClosedOrInvalid,
      long logicalTerminal,
      Map<String, Long> latencyPercentilesNanos,
      long p99QueueDepth,
      long producerLagP99Nanos,
      long producerLagMaxNanos,
      int resourceRecords,
      boolean exactAttemptJoin,
      boolean exactCheckpointRetryEnvelopeJoin) {}

  private static final class MutablePoint {
    private final String pointId;
    private final Map<String, Long> variants = zeroVariants();
    private final Map<String, Long> cutVariants = zeroVariants();
    private final LongSampleBuffer logicalLatencies = new LongSampleBuffer();
    private final LongSampleBuffer initialQueueDepthAtCut = new LongSampleBuffer();
    private final LongSampleBuffer producerLags = new LongSampleBuffer();
    private final KeyMultiset arrivalKeys = new KeyMultiset();
    private final KeyMultiset admittedKeys = new KeyMultiset();
    private final KeyMultiset completionKeys = new KeyMultiset();
    private final KeyMultiset queueKeys = new KeyMultiset();
    private final KeyMultiset durableCompletionKeys = new KeyMultiset();
    private final KeyMultiset acceptedKeys = new KeyMultiset();
    private final KeyMultiset retryExpectedKeys = new KeyMultiset();
    private final KeyMultiset retryFirstArrivalKeys = new KeyMultiset();
    private String phase;
    private int sweep = -1;
    private int ladderPermille = -1;
    private long offeredRate = -1;
    private JsonNode phaseCut;
    private long cutObservedNanos;
    private long scheduledWindowEndNanos;
    private long attemptOffers;
    private long attemptAdmitted;
    private long attemptOverloaded;
    private long attemptClosedOrInvalid;
    private long explicitFailures;
    private int attemptCompletionRecords;
    private long logicalOffers;
    private long logicalAdmitted;
    private long logicalOverloaded;
    private long logicalClosedOrInvalid;
    private long logicalTerminal;
    private int queueRecords;
    private int resourceRecords;
    private long acceptedTraceRecords;
    private long cutOffers;
    private long cutAdmitted;
    private long cutOverloaded;
    private long cutClosedOrInvalid;
    private long cutExplicitFailures;
    private long initialDecisionsAtCut;
    private long lastResourceObserved = -1;
    private long lastResourceScheduled = -1;
    private long lastAllocated;
    private long lastGcCount;
    private long lastGcMillis;
    private long lastCpuNanos;
    private long latestCompletedCheckpointTerminalNanos = -1;
    private long actualSuffixRecordsFromRaw;
    private long actualSuffixBytesFromRaw;
    private boolean resourceTerminal;

    MutablePoint(String pointId) {
      this.pointId = pointId;
    }

    void requireIdentity(String phase, int sweep, int permille, long rate) {
      if (this.phase == null) {
        this.phase = phase;
        this.sweep = sweep;
        ladderPermille = permille;
        offeredRate = rate;
      } else {
        require(
            this.phase.equals(phase)
                && this.sweep == sweep
                && ladderPermille == permille
                && offeredRate == rate,
            "point identity changed across raw streams");
      }
    }

    void requireRawReconciled(String id) {
      require(phaseCut != null, "phase cut missing at " + id);
      long submissionResults = variants.values().stream().mapToLong(Long::longValue).sum();
      long pending = attemptAdmitted - submissionResults - explicitFailures;
      require(pending >= 0, "raw completion overcount at " + id);
      RunAccounting terminalAccounting =
          new RunAccounting(
              attemptOffers,
              attemptAdmitted,
              attemptOverloaded,
              attemptClosedOrInvalid,
              variants,
              explicitFailures,
              pending);
      RunReconciler.requireValid(
          terminalAccounting, true, attemptCompletionRecords, queueRecords, resourceRecords);
      require(
          logicalOffers == logicalAdmitted + logicalOverloaded + logicalClosedOrInvalid,
          "raw logical offers do not reconcile at " + id);
      require(
          logicalTerminal == logicalLatencies.size(),
          "raw logical latency denominator changed at " + id);
      require(arrivalKeys.equals(queueKeys), "arrival/queue attempt join changed at " + id);
      require(
          admittedKeys.equals(completionKeys),
          "admission/completion attempt join changed at " + id);
      require(
          durableCompletionKeys.equals(acceptedKeys),
          "completion/accepted trace join changed at " + id);
      require(
          retryExpectedKeys.equals(retryFirstArrivalKeys),
          "checkpoint retry envelope changed at " + id);
      require(resourceTerminal, "resource series omits terminal drain at " + id);
      JsonNode observation = phaseCut.path("observationCut");
      RunAccounting publishedObservation = accounting(observation.path("attemptAccounting"));
      long cutSubmissionResults = cutVariants.values().stream().mapToLong(Long::longValue).sum();
      RunAccounting observationAccounting =
          new RunAccounting(
              cutOffers,
              cutAdmitted,
              cutOverloaded,
              cutClosedOrInvalid,
              cutVariants,
              cutExplicitFailures,
              cutAdmitted - cutSubmissionResults - cutExplicitFailures);
      require(
          observationAccounting.equals(publishedObservation),
          "scheduled-cut accounting changed at " + id);
      long planned = observation.path("plannedInitialOffers").longValue();
      long scheduledBacklog = planned - initialDecisionsAtCut;
      long servicePending = observationAccounting.pendingAtObservationCut();
      require(planned == logicalOffers, "planned arrival count changed at " + id);
      require(
          observation.path("initialDecisionsAtCut").longValue() == initialDecisionsAtCut,
          "scheduled-cut initial-decision count changed at " + id);
      require(
          observation.path("scheduledDecisionBacklogAtCut").longValue() == scheduledBacklog,
          "scheduled-decision backlog changed at " + id);
      require(
          observation.path("servicePendingAtCut").longValue() == servicePending,
          "scheduled-cut service pending changed at " + id);
      require(
          observation.path("endingBacklog").longValue() == scheduledBacklog + servicePending,
          "scheduled-cut ending backlog changed at " + id);
      require(
          observation.path("postCutOverloaded").longValue() == attemptOverloaded - cutOverloaded,
          "post-cut overload changed at " + id);
      RunAccounting publishedTerminal =
          accounting(phaseCut.path("terminalDrain").path("attemptAccounting"));
      require(
          terminalAccounting.equals(publishedTerminal), "terminal cut accounting changed at " + id);
      require(
          phaseCut.path("terminalDrain").path("logicalTerminalCompletions").longValue()
              == logicalTerminal,
          "terminal logical count changed at " + id);
    }

    RecomputedPoint finish(LoadPointResult expected) {
      requireRawReconciled(expected.point().pointId());
      long submissionResults = variants.values().stream().mapToLong(Long::longValue).sum();
      RunAccounting terminalAccounting =
          new RunAccounting(
              attemptOffers,
              attemptAdmitted,
              attemptOverloaded,
              attemptClosedOrInvalid,
              variants,
              explicitFailures,
              attemptAdmitted - submissionResults - explicitFailures);
      long cutSubmissionResults = cutVariants.values().stream().mapToLong(Long::longValue).sum();
      RunAccounting observationAccounting =
          new RunAccounting(
              cutOffers,
              cutAdmitted,
              cutOverloaded,
              cutClosedOrInvalid,
              cutVariants,
              cutExplicitFailures,
              cutAdmitted - cutSubmissionResults - cutExplicitFailures);
      Map<String, Long> percentiles = FrozenPercentiles.frozen(logicalLatencies.copy());
      long p99Queue = FrozenPercentiles.nearestRank(initialQueueDepthAtCut.copy(), 990);
      long p99ProducerLag = FrozenPercentiles.nearestRank(producerLags.copy(), 990);
      long maxProducerLag = java.util.Arrays.stream(producerLags.copy()).max().orElseThrow();
      require(
          terminalAccounting.equals(expected.attemptAccounting()),
          "raw terminal accounting changed");
      require(
          observationAccounting.equals(
              expected.phaseEvidence().observationCut().attemptAccounting()),
          "raw observation-cut accounting changed");
      PhaseEvidence.ObservationCut expectedCut = expected.phaseEvidence().observationCut();
      long plannedInitialOffers =
          phaseCut.path("observationCut").path("plannedInitialOffers").longValue();
      long scheduledDecisionBacklog =
          Math.subtractExact(plannedInitialOffers, initialDecisionsAtCut);
      long servicePendingAtCut = observationAccounting.pendingAtObservationCut();
      long endingBacklog = Math.addExact(scheduledDecisionBacklog, servicePendingAtCut);
      long postCutOverloaded = Math.subtractExact(attemptOverloaded, cutOverloaded);
      require(
          plannedInitialOffers == expectedCut.plannedInitialOffers(), "planned arrivals changed");
      require(
          initialDecisionsAtCut == expectedCut.initialDecisionsAtCut(),
          "initial decisions at cut changed");
      require(
          scheduledDecisionBacklog == expectedCut.scheduledDecisionBacklogAtCut(),
          "scheduled-decision backlog changed");
      require(
          servicePendingAtCut == expectedCut.servicePendingAtCut(),
          "service pending at scheduled cut changed");
      require(endingBacklog == expectedCut.endingBacklog(), "ending demand backlog changed");
      require(postCutOverloaded == expectedCut.postCutOverloaded(), "post-cut overload changed");
      require(logicalOffers == expected.logicalOffers(), "raw logical offers changed");
      require(logicalAdmitted == expected.logicalInitiallyAdmitted(), "raw admission changed");
      require(logicalOverloaded == expected.logicalOverloaded(), "raw overload changed");
      require(
          logicalClosedOrInvalid == expected.logicalClosedOrInvalid(), "raw closed count changed");
      require(
          logicalTerminal == expected.logicalTerminalCompletions(), "raw terminal count changed");
      require(percentiles.equals(expected.latencyPercentilesNanos()), "raw percentiles changed");
      require(p99Queue == expected.rateMeasurement().p99QueueDepth(), "raw queue p99 changed");
      PhaseEvidence.PacingFidelity pacing =
          expected.phaseEvidence().observationCut().pacingFidelity();
      require(p99ProducerLag == pacing.producerLagP99Nanos(), "raw producer p99 changed");
      require(maxProducerLag == pacing.producerLagMaxNanos(), "raw producer max changed");
      JsonNode rawPacing = phaseCut.path("observationCut").path("pacingFidelity");
      require(rawPacing.path("passed").booleanValue(), "raw pacing gate did not pass");
      require(
          rawPacing.path("allScheduledArrivalsMaterialized").booleanValue()
              && logicalOffers == plannedInitialOffers,
          "not every scheduled arrival materialized");
      require(
          rawPacing.path("allAdmissionDecisionsWithinLagLimits").booleanValue()
              && producerLags.size() == plannedInitialOffers
              && p99ProducerLag <= pacing.producerLagP99LimitNanos()
              && maxProducerLag <= pacing.producerLagMaxLimitNanos(),
          "admission-decision lag gate changed");
      require(
          rawPacing.path("producerLagP99Nanos").longValue() == p99ProducerLag,
          "phase-cut p99 changed");
      require(
          rawPacing.path("producerLagMaxNanos").longValue() == maxProducerLag,
          "phase-cut max changed");
      return new RecomputedPoint(
          terminalAccounting,
          observationAccounting,
          logicalOffers,
          logicalAdmitted,
          logicalOverloaded,
          logicalClosedOrInvalid,
          logicalTerminal,
          percentiles,
          p99Queue,
          p99ProducerLag,
          maxProducerLag,
          resourceRecords,
          true,
          true);
    }

    private static Map<String, Long> zeroVariants() {
      Map<String, Long> values = new LinkedHashMap<>();
      RunReconciler.SUBMISSION_RESULT_VARIANTS.stream()
          .sorted()
          .forEach(value -> values.put(value, 0L));
      return values;
    }
  }

  private static final class KeyMultiset {
    private static final BigInteger MODULUS = BigInteger.ONE.shiftLeft(256);
    private BigInteger sum = BigInteger.ZERO;
    private BigInteger xor = BigInteger.ZERO;
    private long count;

    void add(String key) {
      BigInteger hash =
          new BigInteger(1, sha256Digest().digest(key.getBytes(StandardCharsets.UTF_8)));
      sum = sum.add(hash).mod(MODULUS);
      xor = xor.xor(hash);
      count = Math.incrementExact(count);
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof KeyMultiset value
          && count == value.count
          && sum.equals(value.sum)
          && xor.equals(value.xor);
    }

    @Override
    public int hashCode() {
      return java.util.Objects.hash(sum, xor, count);
    }
  }

  private static final class TraceAccumulator {
    private final MessageDigest digest;
    private long records;
    private boolean finished;

    TraceAccumulator(String traceId) {
      digest = RecoveryTrace.beginReconstructedDigest(traceId);
    }

    void accept(RecoveryTrace.Entry entry) {
      require(!finished, "trace digest already finished");
      require(entry.ordinal() == Math.incrementExact(records), "accepted trace ordinal changed");
      RecoveryTrace.updateReconstructedDigest(digest, entry);
      records = entry.ordinal();
    }

    String finish() {
      require(!finished && records > 0, "accepted trace is empty or already consumed");
      finished = true;
      return HexFormat.of().formatHex(digest.digest());
    }
  }

  @FunctionalInterface
  private interface RecordConsumer {
    void accept(JsonNode node);
  }
}
