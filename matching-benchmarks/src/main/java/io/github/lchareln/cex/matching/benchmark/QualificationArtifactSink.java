package io.github.lchareln.cex.matching.benchmark;

import io.github.lchareln.cex.matching.local.RecoveryBudget;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Lossless raw M10 artifact sink; all high-cardinality streams are bounded gzip shards. */
public final class QualificationArtifactSink implements AutoCloseable {
  public static final String ARRIVAL_SCHEMA = "matching.m10.raw-arrival.v2";
  public static final String COMPLETION_SCHEMA = "matching.m10.raw-completion.v2";
  public static final String QUEUE_SCHEMA = "matching.m10.raw-queue.v2";
  public static final String RESOURCE_SCHEMA = "matching.m10.resource-observation.v1";
  public static final String MAINTENANCE_SCHEMA = "matching.m10.raw-maintenance.v1";
  public static final String PHASE_CUT_SCHEMA = "matching.m10.raw-phase-cut.v2";
  public static final String ACCEPTED_TRACE_SCHEMA = "matching.m10.accepted-trace.v2";
  public static final String RECOVERY_SCHEMA = "matching.m10.recovery.v1";
  public static final String QUALIFICATION_SCHEMA = "matching.m10.qualification.v2";

  private final ObjectMapper mapper = JsonMapper.builder().build();
  private final Path output;
  private final ArtifactContext context;
  private final QualificationProfile profile;
  private final ShardedJsonlWriter arrivals;
  private final ShardedJsonlWriter completions;
  private final ShardedJsonlWriter queue;
  private final ShardedJsonlWriter resources;
  private final ShardedJsonlWriter maintenance;
  private final ShardedJsonlWriter phaseCuts;
  private final ShardedJsonlWriter acceptedTrace;
  private final List<ObjectNode> recoveryRecords = new ArrayList<>();
  private boolean closed;

  public QualificationArtifactSink(
      Path output, ArtifactContext context, QualificationProfile profile) throws IOException {
    this.output = Objects.requireNonNull(output, "output").toAbsolutePath().normalize();
    this.context = Objects.requireNonNull(context, "context");
    this.profile = Objects.requireNonNull(profile, "profile");
    if (profile.id() != context.profileId()) {
      throw new IllegalArgumentException("raw sink profile and artifact context disagree");
    }
    if (Files.exists(this.output)) {
      throw new IOException("output already exists; refusing to overwrite: " + this.output);
    }
    Files.createDirectories(this.output);
    arrivals = new ShardedJsonlWriter(mapper, this.output, "raw-arrivals", ARRIVAL_SCHEMA);
    completions = new ShardedJsonlWriter(mapper, this.output, "raw-completions", COMPLETION_SCHEMA);
    queue = new ShardedJsonlWriter(mapper, this.output, "raw-queue", QUEUE_SCHEMA);
    resources = new ShardedJsonlWriter(mapper, this.output, "resources", RESOURCE_SCHEMA);
    maintenance =
        new ShardedJsonlWriter(mapper, this.output, "raw-maintenance", MAINTENANCE_SCHEMA);
    phaseCuts = new ShardedJsonlWriter(mapper, this.output, "raw-phase-cuts", PHASE_CUT_SCHEMA);
    acceptedTrace =
        new ShardedJsonlWriter(mapper, this.output, "accepted-trace", ACCEPTED_TRACE_SCHEMA);
  }

  public synchronized void recordArrival(
      PointIdentity point,
      String logicalOperationId,
      int attempt,
      String attemptKind,
      Integer retryOriginAttempt,
      long retryOfferOrdinal,
      long scheduledArrivalNanos,
      long admissionDecisionNanos,
      String admissionOutcome,
      String rejectionCode,
      byte[] canonicalEnvelope,
      int decisionQueueDepth)
      throws IOException {
    ObjectNode node = record(ARRIVAL_SCHEMA, point);
    node.put("logicalOperationId", logicalOperationId);
    node.put("attempt", attempt);
    if (retryOriginAttempt == null) {
      node.putNull("retryOriginAttempt");
    } else {
      node.put("retryOriginAttempt", retryOriginAttempt);
    }
    node.put("retryOfferOrdinal", retryOfferOrdinal);
    node.put("canonicalEnvelopeSha256", sha256(canonicalEnvelope));
    node.put("attemptKind", attemptKind);
    node.put("timeDomain", "RUN_RELATIVE_MONOTONIC_NANOS");
    node.put("latencyOrigin", "SCHEDULED_ARRIVAL");
    node.put("scheduledArrivalNanos", scheduledArrivalNanos);
    node.put("admissionDecisionNanos", admissionDecisionNanos);
    node.put("producerLagNanos", Math.subtractExact(admissionDecisionNanos, scheduledArrivalNanos));
    node.put("admissionOutcome", admissionOutcome);
    putNullable(node, "rejectionCode", rejectionCode);
    node.put("observationKind", "ADMISSION_GATE_DECISION");
    node.put("decisionQueueDepth", decisionQueueDepth);
    arrivals.write(node);

    ObjectNode queueNode = record(QUEUE_SCHEMA, point);
    queueNode.put("logicalOperationId", logicalOperationId);
    queueNode.put("attempt", attempt);
    queueNode.put("attemptKind", attemptKind);
    queueNode.put("admissionDecisionNanos", admissionDecisionNanos);
    queueNode.put("observationKind", "ADMISSION_GATE_DECISION");
    queueNode.put("decisionQueueDepth", decisionQueueDepth);
    queue.write(queueNode);
  }

  public synchronized void recordCompletion(
      PointIdentity point,
      String logicalOperationId,
      int attempt,
      long scheduledArrivalNanos,
      long ownerCompletedNanos,
      String completionKind,
      String resultVariant,
      String serviceFailureCode,
      boolean logicalTerminal,
      String canonicalResultDigest,
      String semanticStateDigest,
      byte[] canonicalEnvelope,
      Integer walRecordLength)
      throws IOException {
    ObjectNode node = record(COMPLETION_SCHEMA, point);
    node.put("logicalOperationId", logicalOperationId);
    node.put("attempt", attempt);
    node.put("canonicalEnvelopeSha256", sha256(canonicalEnvelope));
    node.put("timeDomain", "RUN_RELATIVE_MONOTONIC_NANOS");
    node.put("latencyOrigin", "SCHEDULED_ARRIVAL");
    node.put("timeOrigin", "OWNER_COMPLETED_UNDER_GATE");
    node.put("scheduledArrivalNanos", scheduledArrivalNanos);
    node.put("ownerCompletedNanos", ownerCompletedNanos);
    node.put(
        "latencyFromScheduledNanos",
        Math.subtractExact(ownerCompletedNanos, scheduledArrivalNanos));
    node.put("completionKind", completionKind);
    putNullable(node, "submissionResultVariant", resultVariant);
    putNullable(node, "serviceFailureCode", serviceFailureCode);
    node.put("logicalTerminal", logicalTerminal);
    putNullable(node, "canonicalResultDigest", canonicalResultDigest);
    putNullable(node, "semanticStateDigest", semanticStateDigest);
    if (walRecordLength == null) {
      node.putNull("walRecordLength");
    } else {
      node.put("walRecordLength", walRecordLength);
    }
    completions.write(node);
  }

  public synchronized void recordResource(
      PointIdentity point,
      long sampleSequence,
      String sampleKind,
      long scheduledSampleNanos,
      ResourceObservation observation)
      throws IOException {
    ObjectNode node = record(RESOURCE_SCHEMA, point);
    node.put("sampleSequence", sampleSequence);
    node.put("sampleKind", sampleKind);
    node.put("scheduledSampleNanos", scheduledSampleNanos);
    node.put("observedNanos", observation.observedNanos());
    node.put("samplingLagNanos", observation.observedNanos() - scheduledSampleNanos);
    node.put("allocationUnit", "CUMULATIVE_BYTES_ALL_THREADS");
    node.put("totalThreadAllocatedBytes", observation.totalThreadAllocatedBytes());
    node.put("gcCountUnit", "CUMULATIVE_COLLECTIONS");
    node.put("garbageCollectionCount", observation.garbageCollectionCount());
    node.put("gcTimeUnit", "CUMULATIVE_MILLISECONDS");
    node.put("garbageCollectionMillis", observation.garbageCollectionMillis());
    node.put("cpuUnit", "CUMULATIVE_PROCESS_NANOSECONDS");
    node.put("processCpuNanos", observation.processCpuNanos());
    node.put("memoryUnit", "BYTES");
    node.put("heapUsedBytes", observation.heapUsedBytes());
    node.put("committedVirtualMemoryBytes", observation.committedVirtualMemoryBytes());
    node.put("systemMemoryUsedBytes", observation.systemMemoryUsedBytes());
    node.put("queueDepth", observation.queueDepth());
    resources.write(node);
  }

  public synchronized void recordMaintenanceAdmission(
      PointIdentity point,
      long maintenanceAttempt,
      String reason,
      long scheduledPhaseOffsetNanos,
      long scheduledNanos,
      long offeredNanos,
      String admissionOutcome,
      String rejectionCode)
      throws IOException {
    ObjectNode node = record(MAINTENANCE_SCHEMA, point);
    node.put("eventKind", "ADMISSION");
    node.put("maintenanceType", "M09S1_CHECKPOINT");
    node.put("maintenanceAttempt", maintenanceAttempt);
    node.put("reason", reason);
    node.put("scheduledPhaseOffsetNanos", scheduledPhaseOffsetNanos);
    node.put("scheduledNanos", scheduledNanos);
    node.put("offeredNanos", offeredNanos);
    node.put("offerLagNanos", offeredNanos - scheduledNanos);
    node.put("admissionOutcome", admissionOutcome);
    putNullable(node, "rejectionCode", rejectionCode);
    maintenance.write(node);
  }

  public synchronized void recordMaintenanceCompletion(
      PointIdentity point,
      long maintenanceAttempt,
      String reason,
      long scheduledPhaseOffsetNanos,
      long scheduledNanos,
      long offeredNanos,
      long terminalNanos,
      String completionKind,
      String failureCode,
      long suffixRecordsBeforeReset,
      long suffixBytesBeforeReset)
      throws IOException {
    ObjectNode node = record(MAINTENANCE_SCHEMA, point);
    node.put("eventKind", "COMPLETION");
    node.put("maintenanceType", "M09S1_CHECKPOINT");
    node.put("maintenanceAttempt", maintenanceAttempt);
    node.put("reason", reason);
    node.put("scheduledPhaseOffsetNanos", scheduledPhaseOffsetNanos);
    node.put("scheduledNanos", scheduledNanos);
    node.put("offeredNanos", offeredNanos);
    node.put("terminalNanos", terminalNanos);
    node.put("pauseFromScheduledNanos", terminalNanos - scheduledNanos);
    node.put("completionKind", completionKind);
    putNullable(node, "failureCode", failureCode);
    node.put("suffixRecordsBeforeReset", suffixRecordsBeforeReset);
    node.put("suffixBytesBeforeReset", suffixBytesBeforeReset);
    node.put(
        "suffixRecordsAfterReset",
        "COMPLETED".equals(completionKind) ? 0 : suffixRecordsBeforeReset);
    node.put(
        "suffixBytesAfterReset", "COMPLETED".equals(completionKind) ? 0 : suffixBytesBeforeReset);
    maintenance.write(node);
  }

  public synchronized void recordAcceptedTrace(
      PointIdentity point,
      String recoveryTraceId,
      long traceOrdinal,
      String logicalOperationId,
      int attempt,
      byte[] canonicalEnvelope,
      String canonicalResultDigest,
      String semanticStateDigest)
      throws IOException {
    ObjectNode node = record(ACCEPTED_TRACE_SCHEMA, point);
    node.put("recoveryTraceId", recoveryTraceId);
    node.put("traceOrdinal", traceOrdinal);
    node.put("logicalOperationId", logicalOperationId);
    node.put("attempt", attempt);
    node.put("canonicalEnvelopeEncoding", "BASE64");
    node.put("canonicalEnvelopeSha256", sha256(canonicalEnvelope));
    node.put(
        "canonicalEnvelopeBase64", java.util.Base64.getEncoder().encodeToString(canonicalEnvelope));
    node.put("canonicalResultDigest", canonicalResultDigest);
    node.put("semanticStateDigest", semanticStateDigest);
    acceptedTrace.write(node);
  }

  public synchronized void recordPhaseCut(PointIdentity point, PhaseEvidence evidence)
      throws IOException {
    ObjectNode node = record(PHASE_CUT_SCHEMA, point);
    PhaseEvidence.ObservationCut observation = evidence.observationCut();
    ObjectNode cut = node.putObject("observationCut");
    cut.put("phaseOriginNanos", observation.phaseOriginNanos());
    cut.put("scheduledWindowEndNanos", observation.scheduledWindowEndNanos());
    cut.put("observedNanos", observation.observedNanos());
    cut.put("observationLagNanos", observation.observationLagNanos());
    writeAccounting(cut.putObject("attemptAccounting"), observation.attemptAccounting());
    cut.put("queueCapacity", observation.queueCapacity());
    cut.put("startingBacklog", observation.startingBacklog());
    cut.put("plannedInitialOffers", observation.plannedInitialOffers());
    cut.put("initialDecisionsAtCut", observation.initialDecisionsAtCut());
    cut.put("scheduledDecisionBacklogAtCut", observation.scheduledDecisionBacklogAtCut());
    cut.put("servicePendingAtCut", observation.servicePendingAtCut());
    cut.put("endingBacklog", observation.endingBacklog());
    cut.put("p99QueueDepth", observation.p99QueueDepth());
    cut.put("postCutOverloaded", observation.postCutOverloaded());
    PhaseEvidence.PacingFidelity pacing = observation.pacingFidelity();
    ObjectNode pacingNode = cut.putObject("pacingFidelity");
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
    PhaseEvidence.TerminalDrain drain = evidence.terminalDrain();
    ObjectNode drainNode = node.putObject("terminalDrain");
    drainNode.put("observedNanos", drain.observedNanos());
    drainNode.put("elapsedAfterObservationCutNanos", drain.elapsedAfterObservationCutNanos());
    writeAccounting(drainNode.putObject("attemptAccounting"), drain.attemptAccounting());
    drainNode.put("logicalTerminalCompletions", drain.logicalTerminalCompletions());
    drainNode.put("logicalLatencySamples", drain.logicalLatencySamples());
    phaseCuts.write(node);
  }

  public synchronized void recordRecovery(PointIdentity point, RecoveryVerification verification) {
    ObjectNode node = record(RECOVERY_SCHEMA, point);
    node.put("recoveryTraceId", verification.recoveryTraceId());
    node.put("durableOperations", verification.durableOperations());
    node.put("duplicatesReplayed", verification.duplicatesReplayed());
    node.put("liveResultDigest", verification.liveResultDigest());
    node.put("recoveredResultDigest", verification.recoveredResultDigest());
    node.put("directReplayResultDigest", verification.directReplayResultDigest());
    node.put("liveSemanticStateDigest", verification.liveSemanticStateDigest());
    node.put("recoveredSemanticStateDigest", verification.recoveredSemanticStateDigest());
    node.put("directReplaySemanticStateDigest", verification.directReplaySemanticStateDigest());
    node.put("recoveryTraceSha256", verification.recoveryTraceSha256());
    node.put("configuredMaxSuffixRecords", verification.configuredMaxSuffixRecords());
    node.put("configuredMaxSuffixBytes", verification.configuredMaxSuffixBytes());
    node.put("actualSuffixRecords", verification.actualSuffixRecords());
    node.put("actualSuffixBytes", verification.actualSuffixBytes());
    node.put("recoveryElapsedNanos", verification.recoveryElapsedNanos());
    node.put("recoveredWalConfig", "M10_DEDICATED_FINITE_WITH_M09S1");
    node.put("directReplayWalConfig", "M08_LEGACY_UNBOUNDED_NO_SNAPSHOT");
    node.put(
        "exactResultDigest",
        verification.liveResultDigest().equals(verification.recoveredResultDigest()));
    node.put(
        "exactSemanticStateDigest",
        verification.liveSemanticStateDigest().equals(verification.recoveredSemanticStateDigest()));
    recoveryRecords.add(node);
  }

  public synchronized Inventory finishRawArtifacts() throws IOException {
    close();
    return inventory;
  }

  /** Seals cumulative raw shards so M10Q2 can verify one attempt before considering fallback. */
  public synchronized Inventory snapshotRawArtifacts() throws IOException {
    if (closed) {
      throw new IllegalStateException("raw artifacts are closed");
    }
    Map<String, List<ShardedJsonlWriter.ShardInfo>> streams = new LinkedHashMap<>();
    streams.put("raw-arrivals", arrivals.snapshot());
    streams.put("raw-completions", completions.snapshot());
    streams.put("raw-queue", queue.snapshot());
    streams.put("resources", resources.snapshot());
    streams.put("raw-maintenance", maintenance.snapshot());
    streams.put("raw-phase-cuts", phaseCuts.snapshot());
    streams.put("accepted-trace", acceptedTrace.snapshot());
    writeRecovery();
    return inventory(streams);
  }

  private Inventory inventory;

  @Override
  public synchronized void close() throws IOException {
    if (closed) {
      return;
    }
    Map<String, List<ShardedJsonlWriter.ShardInfo>> streams = new LinkedHashMap<>();
    streams.put("raw-arrivals", arrivals.finish());
    streams.put("raw-completions", completions.finish());
    streams.put("raw-queue", queue.finish());
    streams.put("resources", resources.finish());
    streams.put("raw-maintenance", maintenance.finish());
    streams.put("raw-phase-cuts", phaseCuts.finish());
    streams.put("accepted-trace", acceptedTrace.finish());
    writeRecovery();
    inventory = inventory(streams);
    closed = true;
  }

  private Inventory inventory(Map<String, List<ShardedJsonlWriter.ShardInfo>> streams)
      throws IOException {
    Path recoveryPath = output.resolve("recovery.json");
    return new Inventory(
        streams, recoveryRecords.size(), Files.size(recoveryPath), sha256(recoveryPath));
  }

  public void writeQualification(ObjectNode qualification) throws IOException {
    if (!closed) {
      throw new IllegalStateException("raw artifacts must be closed before qualification summary");
    }
    if (!QUALIFICATION_SCHEMA.equals(qualification.path("schemaVersion").stringValue())) {
      throw new IllegalArgumentException("qualification summary has the wrong schema");
    }
    mapper
        .writerWithDefaultPrettyPrinter()
        .writeValue(output.resolve("qualification.json").toFile(), qualification);
  }

  public ObjectMapper mapper() {
    return mapper;
  }

  private void writeRecovery() throws IOException {
    ObjectNode root = common(RECOVERY_SCHEMA);
    root.put("recordType", "RECOVERY_COLLECTION");
    ArrayNode records = root.putArray("records");
    recoveryRecords.forEach(records::add);
    Path temporary = output.resolve(".recovery.json.tmp");
    mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), root);
    Files.move(
        temporary,
        output.resolve("recovery.json"),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING);
  }

  private ObjectNode record(String schemaVersion, PointIdentity point) {
    ObjectNode node = common(schemaVersion);
    node.put("recordType", "DATA");
    node.put("pointId", point.pointId());
    node.put("phase", point.phase());
    node.put("sweep", point.sweep());
    node.put("ladderPermille", point.ladderPermille());
    node.put("offeredRate", point.offeredRate());
    return node;
  }

  private ObjectNode common(String schemaVersion) {
    ObjectNode node = mapper.createObjectNode();
    node.put("schemaVersion", schemaVersion);
    node.put("runId", context.runId());
    node.put("profileId", context.profileId().name());
    node.put("resultScope", context.resultScope());
    node.put("eligibleForReleaseEvidence", context.eligibleForReleaseEvidence());
    node.put("sourceCommit", context.sourceCommit());
    node.put("workloadSha256", context.workloadSha256());
    node.put("qualificationRuntimePolicyId", "M10Q2");
    node.put("qualificationRecoveryBudgetPolicy", "M10_DEDICATED_NOT_M09_DEFAULT");
    node.put("qualificationMaxSuffixRecords", profile.recoveryBudgetMaxSuffixRecords());
    node.put("qualificationMaxSuffixBytes", profile.recoveryBudgetMaxSuffixBytes());
    node.put("m09DefaultMaxSuffixRecords", RecoveryBudget.M09_DEFAULT.maxSuffixRecords());
    node.put("m09DefaultMaxSuffixBytes", RecoveryBudget.M09_DEFAULT.maxSuffixBytes());
    node.put("plannedWalRecordCeilingBytes", profile.plannedWalRecordCeilingBytes());
    node.put("proactiveCheckpointOffsetNanos", profile.proactiveCheckpointOffsetNanos());
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

  private static void putNullable(ObjectNode node, String field, String value) {
    if (value == null) {
      node.putNull(field);
    } else {
      node.put(field, value);
    }
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
      return java.util.HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static String sha256(byte[] value) {
    Objects.requireNonNull(value, "value");
    try {
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  public record PointIdentity(
      String pointId, String phase, int sweep, int ladderPermille, long offeredRate) {
    public PointIdentity {
      Objects.requireNonNull(pointId, "pointId");
      Objects.requireNonNull(phase, "phase");
      if (pointId.isBlank()
          || phase.isBlank()
          || sweep < 0
          || ladderPermille < 0
          || offeredRate <= 0) {
        throw new IllegalArgumentException("invalid measured point identity");
      }
    }
  }

  public record Inventory(
      Map<String, List<ShardedJsonlWriter.ShardInfo>> streams,
      long recoveryRecordCount,
      long recoveryBytes,
      String recoverySha256) {
    public Inventory {
      Map<String, List<ShardedJsonlWriter.ShardInfo>> copy = new LinkedHashMap<>();
      streams.forEach((name, value) -> copy.put(name, List.copyOf(value)));
      streams = Map.copyOf(copy);
      if (recoveryRecordCount < 0
          || recoveryBytes <= 0
          || !recoverySha256.matches("[0-9a-f]{64}")) {
        throw new IllegalArgumentException("invalid recovery artifact inventory");
      }
    }
  }
}
