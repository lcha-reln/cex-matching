package io.github.lchareln.cex.matching.benchmark;

import io.github.lchareln.cex.matching.local.AdmissionRejectionCode;
import io.github.lchareln.cex.matching.local.AdmissionResult;
import io.github.lchareln.cex.matching.local.CanonicalResult;
import io.github.lchareln.cex.matching.local.CheckpointAdmissionResult;
import io.github.lchareln.cex.matching.local.CheckpointCompletion;
import io.github.lchareln.cex.matching.local.CompletionHandle;
import io.github.lchareln.cex.matching.local.LocalMatchingService;
import io.github.lchareln.cex.matching.local.M08Command;
import io.github.lchareln.cex.matching.local.M08EnvelopeCodec;
import io.github.lchareln.cex.matching.local.ServiceCompletion;
import io.github.lchareln.cex.matching.local.ServiceMetricsCut;
import io.github.lchareln.cex.matching.local.ServiceMetricsSnapshot;
import io.github.lchareln.cex.matching.local.SubmissionResult;
import io.github.lchareln.cex.matching.local.SubmissionResultVariant;
import java.io.IOException;
import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/** One real scheduled-arrival point with an isolated producer and asynchronous coordinator. */
final class LocalServiceLoadPoint {
  private static final long PARK_THRESHOLD_NANOS = 100_000L;
  private static final long MAX_TERMINAL_WAIT_NANOS = 30_000_000_000L;
  private static final long MAX_RESOURCE_GAP_NANOS = 2_000_000_000L;
  private static final int MAX_COORDINATOR_EVENTS = 262_144;

  private final LocalMatchingService service;
  private final QualificationArtifactSink sink;
  private final QualificationProfile profile;
  private final long runOriginNanos;
  private final M08EnvelopeCodec envelopeCodec = new M08EnvelopeCodec();
  private final JdkResourceCollector resources;
  private final RecoveryTrace recoveryTrace;
  private final ArrayBlockingQueue<CoordinatorEvent> events =
      new ArrayBlockingQueue<>(MAX_COORDINATOR_EVENTS);
  private final AtomicReference<Throwable> eventQueueFailure = new AtomicReference<>();
  private final ArrayDeque<LogicalOperation> checkpointRetries = new ArrayDeque<>();
  private final TreeMap<Long, PendingCompletion> pendingCompletions = new TreeMap<>();
  private long nextCompletionWorkSequence = 1;
  private long globalOperation;
  private long maintenanceAttempt;
  private long suffixRecords;
  private long suffixBytes;

  LocalServiceLoadPoint(
      LocalMatchingService service,
      QualificationArtifactSink sink,
      QualificationProfile profile,
      long runOriginNanos,
      long firstGlobalOperation,
      RecoveryTrace recoveryTrace) {
    this.service = service;
    this.sink = sink;
    this.profile = profile;
    this.runOriginNanos = runOriginNanos;
    this.globalOperation = firstGlobalOperation;
    this.recoveryTrace = recoveryTrace;
    resources = new JdkResourceCollector(() -> service.metrics().queueDepth());
  }

  long nextGlobalOperation() {
    return globalOperation;
  }

  LoadPointResult execute(
      QualificationArtifactSink.PointIdentity point,
      Duration duration,
      boolean retainDerivedSamples)
      throws IOException {
    if (!events.isEmpty()
        || !checkpointRetries.isEmpty()
        || !pendingCompletions.isEmpty()
        || eventQueueFailure.get() != null) {
      throw new IllegalStateException("previous phase left coordinator events");
    }
    ServiceMetricsCut startCut = service.metricsCut();
    ServiceMetricsSnapshot startMetrics = startCut.metrics();
    if (startMetrics.pending() != 0) {
      throw new IllegalStateException("scheduled phase must start with zero service pending");
    }
    long expectedNextWorkSequence =
        Math.addExact(
            Math.addExact(startMetrics.admitted(), startMetrics.checkpointAdmitted()), 1L);
    if (nextCompletionWorkSequence != expectedNextWorkSequence) {
      throw new IllegalStateException("completion observer lost the owner work-sequence boundary");
    }
    QualificationRecoveryPlan.PhasePlan recoveryPlan =
        QualificationRecoveryPlan.requireFits(
            profile,
            point.offeredRate(),
            duration,
            suffixRecords,
            suffixBytes,
            startMetrics.queueCapacity());
    long phaseOriginAbsolute = System.nanoTime();
    long phaseEndAbsolute = Math.addExact(phaseOriginAbsolute, duration.toNanos());
    PhaseState state =
        new PhaseState(
            point, recoveryPlan, startMetrics.pending(), phaseOriginAbsolute, phaseEndAbsolute);
    ResourceSampler resourceSampler = new ResourceSampler(state);
    Thread producer =
        Thread.ofPlatform()
            .name("m10-scheduled-producer-" + point.pointId())
            .unstarted(() -> produce(state));
    ServiceMetricsCut observationServiceCut = null;
    PhaseLifecycle lifecycle = new PhaseLifecycle(state, producer, resourceSampler);
    try (lifecycle) {
      lifecycle.start();
      while (observationServiceCut == null) {
        drainEvents(state);
        driveCoordinator(state);
        long now = System.nanoTime();
        if (now >= phaseEndAbsolute) {
          observationServiceCut = service.metricsCut();
          break;
        }
        LockSupport.parkNanos(PARK_THRESHOLD_NANOS);
      }
      joinProducer(producer);
      drainEvents(state);
      state.observationCutMetrics = observationServiceCut.metrics();
      state.observationCutObservedNanos = observationServiceCut.observedNanos() - runOriginNanos;
      awaitTerminal(state);
      lifecycle.markSuccessful();
    }

    ServiceMetricsCut terminalCut = service.metricsCut();
    ServiceMetricsSnapshot terminalMetrics = terminalCut.metrics();
    long terminalObservedNanos = terminalCut.observedNanos() - runOriginNanos;
    RunAccounting terminalAccounting = delta(startMetrics, terminalMetrics);
    RunReconciler.requireValid(
        terminalAccounting,
        true,
        state.attemptCompletionRecords,
        Math.toIntExact(state.attemptOffers),
        Math.toIntExact(state.resourceSamples));
    requireResourceCoverage(state, terminalObservedNanos);
    if (state.logicalLatencies.size() != state.logicalTerminal) {
      throw new IllegalStateException(
          "logical terminal and scheduled-arrival latency counts differ");
    }
    if (state.correctnessFailure != null) {
      throw new IllegalStateException(state.correctnessFailure);
    }
    if (state.methodFailure != null) {
      throw new IllegalStateException("scheduled producer method failure", state.methodFailure);
    }
    if (state.logicalLatencies.isEmpty() || state.producerLags.isEmpty()) {
      throw new IllegalStateException("point has no terminal latency or producer lag samples");
    }

    PhaseEvidence.PacingFidelity pacing = pacing(state);
    RunAccounting observationAccounting = state.observationCutAccounting();
    long scheduledEndRelative = state.phaseEndAbsolute - runOriginNanos;
    long cutLag = state.observationCutObservedNanos - scheduledEndRelative;
    long scheduledDecisionBacklog =
        Math.subtractExact(state.recoveryPlan.plannedInitialOffers(), state.initialDecisionsAtCut);
    long servicePendingAtCut = observationAccounting.pendingAtObservationCut();
    long endingBacklog = Math.addExact(scheduledDecisionBacklog, servicePendingAtCut);
    long postCutOverloaded =
        Math.subtractExact(terminalAccounting.overloaded(), observationAccounting.overloaded());
    PhaseEvidence.ObservationCut observationCut =
        new PhaseEvidence.ObservationCut(
            state.phaseOriginAbsolute - runOriginNanos,
            scheduledEndRelative,
            state.observationCutObservedNanos,
            cutLag,
            observationAccounting,
            state.observationCutMetrics.queueCapacity(),
            state.startingBacklog,
            state.recoveryPlan.plannedInitialOffers(),
            state.initialDecisionsAtCut,
            scheduledDecisionBacklog,
            servicePendingAtCut,
            endingBacklog,
            FrozenPercentiles.nearestRank(state.initialQueueDepthSamplesAtCut.copy(), 990),
            postCutOverloaded,
            pacing);
    PhaseEvidence.TerminalDrain terminalDrain =
        new PhaseEvidence.TerminalDrain(
            terminalObservedNanos,
            terminalObservedNanos - state.observationCutObservedNanos,
            terminalAccounting,
            state.logicalTerminal,
            state.logicalLatencies.size());
    PhaseEvidence phaseEvidence = new PhaseEvidence(observationCut, terminalDrain);
    RateMeasurement measurement = observationCut.rateMeasurement(point.offeredRate());
    List<Long> latencies = retainDerivedSamples ? state.logicalLatencies.boxedCopy() : List.of();
    List<Long> queues =
        retainDerivedSamples ? state.initialQueueDepthSamples.boxedCopy() : List.of();
    LoadPointResult result =
        new LoadPointResult(
            point,
            terminalAccounting,
            state.logicalOffers,
            state.logicalInitiallyAdmitted,
            state.logicalOverloaded,
            state.logicalClosedOrInvalid,
            state.logicalTerminal,
            latencies,
            queues,
            FrozenPercentiles.frozen(state.logicalLatencies.copy()),
            measurement,
            phaseEvidence,
            recoveryPlan,
            suffixRecords,
            suffixBytes);
    sink.recordPhaseCut(point, phaseEvidence);
    if (!pacing.passed()) {
      throw new IllegalStateException("scheduled producer pacing fidelity gate failed");
    }
    if (cutLag > profile.observationCutLagLimitNanos()) {
      throw new IllegalStateException(
          "observation cut lag exceeded frozen limit: " + cutLag + "ns");
    }
    return result;
  }

  private void produce(PhaseState state) {
    long produced = 0;
    Throwable failure = null;
    String producerId = "m10-load-" + state.point.pointId();
    QualificationProducerCursor producerCursor = new QualificationProducerCursor();
    try {
      for (long index = 0; index < state.recoveryPlan.plannedInitialOffers(); index++) {
        long scheduledAbsolute =
            ScheduledArrival.at(state.phaseOriginAbsolute, index, state.point.offeredRate());
        LogicalOperation operation =
            operation(
                state.point,
                index,
                producerId,
                producerCursor.nextSequence(),
                scheduledAbsolute - runOriginNanos);
        awaitScheduledArrival(scheduledAbsolute);
        AdmissionResult admission = service.trySubmit(operation.envelope());
        if (admission instanceof AdmissionResult.Enqueued) {
          producerCursor.admitted();
        }
        int attempt = operation.attempt();
        long decisionAbsolute = decisionNanos(admission);
        publishEvent(
            new OfferEvent(
                operation,
                attempt,
                "INITIAL_SCHEDULED",
                null,
                0,
                decisionAbsolute - runOriginNanos,
                admission));
        produced = Math.incrementExact(produced);
        long producerLag = Math.subtractExact(decisionAbsolute, scheduledAbsolute);
        if (producerLag < 0 || producerLag > profile.producerLagMaxLimitNanos()) {
          if (failure == null) {
            failure =
                new IllegalStateException(
                    "initial admission decision exceeded its scheduled lag limit: "
                        + producerLag
                        + "ns");
          }
        }
      }
    } catch (Throwable unexpected) {
      if (failure == null) {
        failure = unexpected;
      } else {
        failure.addSuppressed(unexpected);
      }
    } finally {
      publishEvent(new ProducerFinishedEvent(produced, failure));
    }
  }

  private LogicalOperation operation(
      QualificationArtifactSink.PointIdentity point,
      long pointIndex,
      String producerId,
      long producerSequence,
      long scheduledArrivalNanos) {
    globalOperation = Math.incrementExact(globalOperation);
    long sequence = globalOperation;
    String logicalId = point.pointId() + "-op-" + pointIndex;
    M08Command command =
        new M08Command.Place(
            "BTC-USDT",
            BigInteger.valueOf(sequence),
            "BUY",
            BigInteger.valueOf(100),
            BigInteger.ONE,
            "IOC",
            0,
            "NONE",
            Optional.empty());
    byte[] envelope =
        envelopeCodec.encode(
            producerId, 1, 1, producerSequence, new UUID(0x4d31304c4f414400L, sequence), command);
    return new LogicalOperation(logicalId, scheduledArrivalNanos, envelope, 0);
  }

  private void drainEvents(PhaseState state) throws IOException {
    requireEventQueueHealthy();
    CoordinatorEvent event;
    while ((event = events.poll()) != null) {
      switch (event) {
        case OfferEvent offer -> processOffer(offer, state);
        case ProducerFinishedEvent producer -> {
          state.producerFinished = true;
          state.producedInitialOffers = producer.producedInitialOffers();
          if (producer.failure() != null) {
            state.methodFailure = producer.failure();
          }
        }
        case ResourceEvent resource -> processResource(resource, state);
      }
    }
    drainCompletedHandles(state);
    requireEventQueueHealthy();
  }

  private void processOffer(OfferEvent event, PhaseState state) throws IOException {
    int decisionQueueDepth =
        switch (event.admission()) {
          case AdmissionResult.Enqueued enqueued -> enqueued.decisionQueueDepth();
          case AdmissionResult.Rejected rejected -> rejected.decisionQueueDepth();
        };
    sink.recordArrival(
        state.point,
        event.operation().id(),
        event.attempt(),
        event.attemptKind(),
        event.retryOriginAttempt(),
        event.retryOfferOrdinal(),
        event.operation().scheduledArrivalNanos(),
        event.admissionDecisionNanos(),
        event.admission() instanceof AdmissionResult.Enqueued ? "ENQUEUED_NOT_ACK" : "REJECTED",
        rejectionCode(event.admission()),
        event.operation().envelope(),
        decisionQueueDepth);
    state.attemptOffers = Math.incrementExact(state.attemptOffers);
    boolean decidedAtScheduledCut =
        event.admissionDecisionNanos() < state.phaseEndAbsolute - runOriginNanos;
    if (decidedAtScheduledCut) {
      state.recordCutAdmission(event.admission());
    }
    if (event.attemptKind().equals("INITIAL_SCHEDULED")) {
      state.logicalOffers = Math.incrementExact(state.logicalOffers);
      state.producerLags.add(
          event.admissionDecisionNanos() - event.operation().scheduledArrivalNanos());
      state.initialQueueDepthSamples.add((long) decisionQueueDepth);
      if (decidedAtScheduledCut) {
        state.initialDecisionsAtCut = Math.incrementExact(state.initialDecisionsAtCut);
        state.initialQueueDepthSamplesAtCut.add((long) decisionQueueDepth);
      }
    }
    if (event.admission() instanceof AdmissionResult.Enqueued enqueued) {
      registerPendingCompletion(
          new PendingBusinessCompletion(
              enqueued.workSequence(),
              event.operation(),
              event.attempt(),
              enqueued.admissionSequence(),
              enqueued.completion()));
      if (event.attempt() == 0) {
        state.logicalInitiallyAdmitted = Math.incrementExact(state.logicalInitiallyAdmitted);
      }
      return;
    }
    AdmissionResult.Rejected rejected = (AdmissionResult.Rejected) event.admission();
    if (event.attempt() == 0) {
      if (rejected.code() == AdmissionRejectionCode.OVERLOADED_BEFORE_WAL) {
        state.logicalOverloaded = Math.incrementExact(state.logicalOverloaded);
      } else {
        state.logicalClosedOrInvalid = Math.incrementExact(state.logicalClosedOrInvalid);
      }
    } else if (rejected.code() == AdmissionRejectionCode.OVERLOADED_BEFORE_WAL) {
      event.operation().incrementAttempt();
      checkpointRetries.addFirst(event.operation());
    } else {
      state.correctnessFailure = "checkpoint retry was rejected: " + rejected.code();
    }
  }

  private static String rejectionCode(AdmissionResult admission) {
    return admission instanceof AdmissionResult.Rejected rejected ? rejected.code().name() : null;
  }

  private void processCompletion(CompletionEvent event, PhaseState state) throws IOException {
    long terminalNanos = event.completion().ownerCompletedNanos() - runOriginNanos;
    state.attemptCompletionRecords = Math.incrementExact(state.attemptCompletionRecords);
    if (event.completion() instanceof ServiceCompletion.ExplicitFailure failure) {
      if (terminalNanos < state.phaseEndAbsolute - runOriginNanos) {
        state.recordCutExplicitFailure();
      }
      sink.recordCompletion(
          state.point,
          event.operation().id(),
          event.attempt(),
          event.operation().scheduledArrivalNanos(),
          terminalNanos,
          "EXPLICIT_SERVICE_FAILURE",
          null,
          failure.code().name(),
          true,
          null,
          null,
          event.operation().envelope(),
          null);
      terminal(event.operation(), terminalNanos, state);
      state.correctnessFailure = "admitted operation failed explicitly: " + failure.code();
      return;
    }

    SubmissionResult result = ((ServiceCompletion.SubmissionCompleted) event.completion()).result();
    SubmissionResultVariant variant = SubmissionResultVariant.from(result);
    if (terminalNanos < state.phaseEndAbsolute - runOriginNanos) {
      state.recordCutSubmissionResult(variant);
    }
    boolean checkpointRequired = result instanceof SubmissionResult.CheckpointRequired;
    CanonicalResult canonical = canonicalResult(result);
    Integer walRecordLength =
        result instanceof SubmissionResult.NewDurablyApplied applied
            ? applied.position().recordLength()
            : null;
    sink.recordCompletion(
        state.point,
        event.operation().id(),
        event.attempt(),
        event.operation().scheduledArrivalNanos(),
        terminalNanos,
        "SUBMISSION_RESULT",
        variant.name(),
        null,
        !checkpointRequired,
        canonical == null ? null : canonical.resultDigest(),
        canonical == null ? null : canonical.semanticStateDigest(),
        event.operation().envelope(),
        walRecordLength);
    if (checkpointRequired) {
      throw new IllegalStateException(
          "qualification encountered CheckpointRequired despite proactive finite-budget plan");
    }
    terminal(event.operation(), terminalNanos, state);
    if (result instanceof SubmissionResult.NewDurablyApplied applied) {
      int recordLength = applied.position().recordLength();
      if (recordLength > profile.plannedWalRecordCeilingBytes()) {
        throw new IllegalStateException(
            "actual WAL record exceeded frozen planning ceiling: " + recordLength);
      }
      suffixRecords = Math.incrementExact(suffixRecords);
      suffixBytes = Math.addExact(suffixBytes, recordLength);
      requireActualSuffixWithinBudget();
    }
    if (canonical != null && variant.durableAcknowledgement()) {
      byte[] envelope = event.operation().envelope();
      long ordinal =
          recoveryTrace.append(
              state.point,
              event.operation().id(),
              event.attempt(),
              envelope,
              canonical.resultDigest(),
              canonical.semanticStateDigest());
      sink.recordAcceptedTrace(
          state.point,
          recoveryTrace.traceId(),
          ordinal,
          event.operation().id(),
          event.attempt(),
          envelope,
          canonical.resultDigest(),
          canonical.semanticStateDigest());
    } else {
      state.correctnessFailure = "non-durable terminal result in qualification: " + variant;
    }
  }

  private static void terminal(LogicalOperation operation, long terminalNanos, PhaseState state) {
    state.logicalTerminal = Math.incrementExact(state.logicalTerminal);
    state.logicalLatencies.add(terminalNanos - operation.scheduledArrivalNanos());
  }

  private void driveCoordinator(PhaseState state) throws IOException {
    long now = System.nanoTime();
    if (state.checkpointInFlight == null) {
      if (state.checkpointRequired) {
        offerCheckpoint(state, "CHECKPOINT_REQUIRED_RETRY", now);
      } else if (!state.proactiveCheckpointCompleted
          && now >= state.phaseOriginAbsolute + profile.proactiveCheckpointOffsetNanos()) {
        offerCheckpoint(
            state,
            "PROACTIVE_PHASE_CHECKPOINT",
            state.phaseOriginAbsolute + profile.proactiveCheckpointOffsetNanos());
      }
    }
    if (!state.checkpointRequired
        && state.checkpointInFlight == null
        && !checkpointRetries.isEmpty()) {
      LogicalOperation retry = checkpointRetries.removeFirst();
      offerRetry(state, retry);
    }
  }

  private void offerRetry(PhaseState state, LogicalOperation operation) throws IOException {
    int attempt = operation.attempt();
    long retryOrdinal = operation.nextRetryOfferOrdinal();
    AdmissionResult admission = service.trySubmit(operation.envelope());
    OfferEvent event =
        new OfferEvent(
            operation,
            attempt,
            "CHECKPOINT_RETRY",
            operation.retryOriginAttempt(),
            retryOrdinal,
            decisionNanos(admission) - runOriginNanos,
            admission);
    processOffer(event, state);
  }

  private void offerCheckpoint(PhaseState state, String reason, long scheduledAbsolute)
      throws IOException {
    maintenanceAttempt = Math.incrementExact(maintenanceAttempt);
    long scheduledNanos = scheduledAbsolute - runOriginNanos;
    CheckpointAdmissionResult admission = service.tryCheckpoint();
    long offeredAbsolute = decisionNanos(admission);
    long offeredNanos = offeredAbsolute - runOriginNanos;
    long offerLag = Math.subtractExact(offeredAbsolute, scheduledAbsolute);
    if ("PROACTIVE_PHASE_CHECKPOINT".equals(reason)
        && offerLag > QualificationProfile.PROACTIVE_CHECKPOINT_ADMISSION_LAG_LIMIT_NANOS) {
      throw new IllegalStateException(
          "proactive checkpoint admission lag exceeded frozen limit: " + offerLag + "ns");
    }
    if (admission instanceof CheckpointAdmissionResult.Rejected rejected) {
      sink.recordMaintenanceAdmission(
          state.point,
          maintenanceAttempt,
          reason,
          scheduledAbsolute - state.phaseOriginAbsolute,
          scheduledNanos,
          offeredNanos,
          "REJECTED",
          rejected.code().name());
      throw new IllegalStateException("checkpoint admission failed: " + rejected.code());
    }
    sink.recordMaintenanceAdmission(
        state.point,
        maintenanceAttempt,
        reason,
        scheduledAbsolute - state.phaseOriginAbsolute,
        scheduledNanos,
        offeredNanos,
        "ENQUEUED",
        null);
    CheckpointAttempt attempt =
        new CheckpointAttempt(
            maintenanceAttempt,
            reason,
            scheduledAbsolute - state.phaseOriginAbsolute,
            scheduledNanos,
            offeredNanos);
    state.checkpointInFlight = attempt;
    CheckpointAdmissionResult.Enqueued enqueued = (CheckpointAdmissionResult.Enqueued) admission;
    registerPendingCompletion(
        new PendingCheckpointCompletion(enqueued.workSequence(), attempt, enqueued.completion()));
  }

  private void registerPendingCompletion(PendingCompletion completion) {
    if (completion.workSequence() < nextCompletionWorkSequence) {
      throw new IllegalStateException("completion registered after its work-sequence boundary");
    }
    if (pendingCompletions.size() >= MAX_COORDINATOR_EVENTS) {
      throw new IllegalStateException(
          "bounded completion observer exceeded " + MAX_COORDINATOR_EVENTS);
    }
    if (pendingCompletions.putIfAbsent(completion.workSequence(), completion) != null) {
      throw new IllegalStateException("duplicate completion work sequence");
    }
  }

  private void drainCompletedHandles(PhaseState state) throws IOException {
    while (true) {
      PendingCompletion pending = pendingCompletions.get(nextCompletionWorkSequence);
      if (pending == null || !pending.isDone()) {
        return;
      }
      pendingCompletions.remove(nextCompletionWorkSequence);
      switch (pending) {
        case PendingBusinessCompletion business -> {
          ServiceCompletion completion = completedValue(business.completion());
          if (completion.workSequence() != business.workSequence()
              || completion.admissionSequence() != business.admissionSequence()) {
            throw new IllegalStateException("business admission/completion identity changed");
          }
          processCompletion(
              new CompletionEvent(business.operation(), business.attempt(), completion), state);
        }
        case PendingCheckpointCompletion checkpoint -> {
          CheckpointCompletion completion = completedValue(checkpoint.completion());
          if (completion.workSequence() != checkpoint.workSequence()) {
            throw new IllegalStateException("checkpoint admission/completion identity changed");
          }
          processCheckpoint(new CheckpointCompletionEvent(checkpoint.attempt(), completion), state);
        }
      }
      nextCompletionWorkSequence = Math.incrementExact(nextCompletionWorkSequence);
    }
  }

  private static <T> T completedValue(CompletionHandle<T> completion) {
    try {
      return completion.get();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("completion observation interrupted", interrupted);
    }
  }

  private void processCheckpoint(CheckpointCompletionEvent event, PhaseState state)
      throws IOException {
    if (state.checkpointInFlight == null
        || state.checkpointInFlight.maintenanceAttempt() != event.attempt().maintenanceAttempt()) {
      throw new IllegalStateException("checkpoint completion identity changed");
    }
    long terminalNanos = event.completion().ownerCompletedNanos() - runOriginNanos;
    long observedBeforeRecords = suffixRecords;
    long observedBeforeBytes = suffixBytes;
    if (event.completion() instanceof CheckpointCompletion.ExplicitFailure failure) {
      sink.recordMaintenanceCompletion(
          state.point,
          event.attempt().maintenanceAttempt(),
          event.attempt().reason(),
          event.attempt().scheduledPhaseOffsetNanos(),
          event.attempt().scheduledNanos(),
          event.attempt().offeredNanos(),
          terminalNanos,
          "EXPLICIT_FAILURE",
          failure.code().name(),
          observedBeforeRecords,
          observedBeforeBytes);
      state.correctnessFailure = "checkpoint failed explicitly: " + failure.code();
      state.checkpointInFlight = null;
      return;
    }
    CheckpointCompletion.Completed completed = (CheckpointCompletion.Completed) event.completion();
    long beforeRecords = completed.result().suffixRecordsBeforeCheckpoint();
    long beforeBytes = completed.result().suffixBytesBeforeCheckpoint();
    if (beforeRecords != observedBeforeRecords || beforeBytes != observedBeforeBytes) {
      throw new IllegalStateException(
          "checkpoint WAL prefix differs from owner completion accounting");
    }
    if (beforeRecords > state.recoveryPlan.worstRecordsBeforeCheckpoint()
        || beforeBytes > state.recoveryPlan.worstBytesBeforeCheckpoint()) {
      throw new IllegalStateException("actual proactive-checkpoint prefix exceeds phase plan");
    }
    suffixRecords = 0;
    suffixBytes = 0;
    sink.recordMaintenanceCompletion(
        state.point,
        event.attempt().maintenanceAttempt(),
        event.attempt().reason(),
        event.attempt().scheduledPhaseOffsetNanos(),
        event.attempt().scheduledNanos(),
        event.attempt().offeredNanos(),
        terminalNanos,
        "COMPLETED",
        null,
        beforeRecords,
        beforeBytes);
    if (event.attempt().reason().equals("PROACTIVE_PHASE_CHECKPOINT")) {
      state.proactiveCheckpointCompleted = true;
    }
    state.checkpointRequired = false;
    state.checkpointInFlight = null;
  }

  private void awaitTerminal(PhaseState state) throws IOException {
    long deadline = Math.addExact(System.nanoTime(), MAX_TERMINAL_WAIT_NANOS);
    while (true) {
      drainEvents(state);
      driveCoordinator(state);
      boolean done =
          state.producerFinished
              && state.logicalTerminal == state.logicalInitiallyAdmitted
              && checkpointRetries.isEmpty()
              && !state.checkpointRequired
              && state.checkpointInFlight == null
              && state.proactiveCheckpointCompleted;
      if (done) {
        drainEvents(state);
        return;
      }
      if (state.correctnessFailure != null) {
        throw new IllegalStateException(state.correctnessFailure);
      }
      if (System.nanoTime() >= deadline) {
        throw new IllegalStateException("timeout waiting for admitted logical operations");
      }
      LockSupport.parkNanos(PARK_THRESHOLD_NANOS);
    }
  }

  private void processResource(ResourceEvent event, PhaseState state) throws IOException {
    if (event.failure() != null || event.observation() == null) {
      throw new IllegalStateException("resource collector failed", event.failure());
    }
    if (event.sequence() != state.resourceSamples + 1) {
      throw new IllegalStateException("resource sample sequence is not contiguous");
    }
    ResourceObservation current = event.observation();
    long samplingLag = Math.subtractExact(current.observedNanos(), event.scheduledNanos());
    if (samplingLag < 0 || samplingLag > MAX_RESOURCE_GAP_NANOS) {
      throw new IllegalStateException("resource sampling lag exceeded two seconds");
    }
    if (state.lastResource != null) {
      ResourceObservation previous = state.lastResource;
      if (current.observedNanos() <= previous.observedNanos()
          || current.observedNanos() - previous.observedNanos() > MAX_RESOURCE_GAP_NANOS
          || current.totalThreadAllocatedBytes() < previous.totalThreadAllocatedBytes()
          || current.garbageCollectionCount() < previous.garbageCollectionCount()
          || current.garbageCollectionMillis() < previous.garbageCollectionMillis()
          || current.processCpuNanos() < previous.processCpuNanos()) {
        throw new IllegalStateException("resource time or cumulative dimension regressed");
      }
    }
    sink.recordResource(
        state.point, event.sequence(), event.sampleKind(), event.scheduledNanos(), current);
    state.lastResource = current;
    state.resourceSamples = event.sequence();
    if (event.sampleKind().equals("PERIODIC") && state.firstPeriodicResource == null) {
      state.firstPeriodicResource = current;
    }
    if (event.sampleKind().equals("TERMINAL")) {
      state.terminalResource = current;
    }
  }

  private static PhaseEvidence.PacingFidelity pacing(PhaseState state) {
    long[] lags = state.producerLags.copy();
    long p99 = FrozenPercentiles.nearestRank(lags, 990);
    long max = java.util.Arrays.stream(lags).max().orElseThrow();
    if (state.producedInitialOffers != state.logicalOffers
        || lags.length != state.producedInitialOffers) {
      throw new IllegalStateException("materialized initial-decision evidence does not reconcile");
    }
    boolean allScheduledArrivalsMaterialized =
        state.recoveryPlan.plannedInitialOffers() == state.producedInitialOffers;
    boolean allAdmissionDecisionsWithinLagLimits =
        allScheduledArrivalsMaterialized
            && p99 <= state.profile.producerLagP99LimitNanos()
            && max <= state.profile.producerLagMaxLimitNanos();
    return new PhaseEvidence.PacingFidelity(
        state.recoveryPlan.plannedInitialOffers(),
        state.producedInitialOffers,
        p99,
        max,
        state.profile.producerLagP99LimitNanos(),
        state.profile.producerLagMaxLimitNanos(),
        allScheduledArrivalsMaterialized,
        allAdmissionDecisionsWithinLagLimits,
        allScheduledArrivalsMaterialized && allAdmissionDecisionsWithinLagLimits);
  }

  private static void requireResourceCoverage(PhaseState state, long terminalObservedNanos) {
    long phaseOrigin = state.phaseOriginAbsolute - state.runOriginNanos;
    long scheduledEnd = state.phaseEndAbsolute - state.runOriginNanos;
    if (state.firstPeriodicResource == null
        || state.terminalResource == null
        || state.firstPeriodicResource.observedNanos() < phaseOrigin
        || state.firstPeriodicResource.observedNanos() > phaseOrigin + 2_000_000_000L
        || state.terminalResource.observedNanos() < scheduledEnd
        || state.terminalResource.observedNanos() > terminalObservedNanos) {
      throw new IllegalStateException("resource series does not cover the complete phase");
    }
  }

  private void requireActualSuffixWithinBudget() {
    if (suffixRecords > profile.recoveryBudgetMaxSuffixRecords()
        || suffixBytes > profile.recoveryBudgetMaxSuffixBytes()) {
      throw new IllegalStateException("actual WAL suffix exceeded dedicated qualification budget");
    }
  }

  private static void joinProducer(Thread producer) {
    try {
      producer.join(1_000L);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted waiting for scheduled producer", interrupted);
    }
    if (producer.isAlive()) {
      throw new IllegalStateException("scheduled producer did not terminate");
    }
  }

  static void awaitScheduledArrival(long targetNanos) throws InterruptedException {
    while (true) {
      if (Thread.currentThread().isInterrupted()) {
        throw new InterruptedException("scheduled producer interrupted");
      }
      long remaining = targetNanos - System.nanoTime();
      if (remaining <= 0) {
        return;
      }
      if (remaining > PARK_THRESHOLD_NANOS) {
        LockSupport.parkNanos(remaining - PARK_THRESHOLD_NANOS);
      } else {
        Thread.onSpinWait();
      }
    }
  }

  @FunctionalInterface
  interface CleanupAction {
    void run() throws Exception;
  }

  static final class CleanupFailures {
    private Throwable failure;

    void run(CleanupAction action) {
      try {
        action.run();
      } catch (Throwable next) {
        if (failure == null) {
          failure = next;
        } else if (failure != next) {
          failure.addSuppressed(next);
        }
      }
    }

    boolean hasFailure() {
      return failure != null;
    }

    void throwIfAny() throws IOException {
      if (failure == null) {
        return;
      }
      switch (failure) {
        case IOException ioFailure -> throw ioFailure;
        case RuntimeException runtimeFailure -> throw runtimeFailure;
        case Error error -> throw error;
        default -> throw new IOException("phase cleanup failed", failure);
      }
    }
  }

  private static RunAccounting delta(ServiceMetricsSnapshot start, ServiceMetricsSnapshot end) {
    Map<String, Long> variants = new LinkedHashMap<>();
    for (SubmissionResultVariant variant : SubmissionResultVariant.values()) {
      variants.put(
          variant.name(),
          end.submissionResultCounts().get(variant) - start.submissionResultCounts().get(variant));
    }
    return new RunAccounting(
        end.offers() - start.offers(),
        end.admitted() - start.admitted(),
        end.overloaded() - start.overloaded(),
        end.closedOrInvalid() - start.closedOrInvalid(),
        variants,
        end.explicitServiceFailures() - start.explicitServiceFailures(),
        end.pending() - start.pending());
  }

  private static CanonicalResult canonicalResult(SubmissionResult result) {
    return switch (result) {
      case SubmissionResult.NewDurablyApplied value -> value.result();
      case SubmissionResult.DuplicateReplayed value -> value.originalResult();
      default -> null;
    };
  }

  private static long decisionNanos(AdmissionResult result) {
    return switch (result) {
      case AdmissionResult.Enqueued value -> value.decisionNanos();
      case AdmissionResult.Rejected value -> value.decisionNanos();
    };
  }

  private static long decisionNanos(CheckpointAdmissionResult result) {
    return switch (result) {
      case CheckpointAdmissionResult.Enqueued value -> value.decisionNanos();
      case CheckpointAdmissionResult.Rejected value -> value.decisionNanos();
    };
  }

  private void publishEvent(CoordinatorEvent event) {
    if (!events.offer(event)) {
      publishFailure(
          new IllegalStateException(
              "bounded coordinator event queue exceeded " + MAX_COORDINATOR_EVENTS));
    }
  }

  private void publishFailure(Throwable failure) {
    eventQueueFailure.compareAndSet(null, failure);
  }

  private void requireEventQueueHealthy() {
    Throwable failure = eventQueueFailure.get();
    if (failure != null) {
      throw new IllegalStateException("asynchronous qualification coordinator failed", failure);
    }
  }

  private sealed interface CoordinatorEvent
      permits OfferEvent, ProducerFinishedEvent, ResourceEvent {}

  private record OfferEvent(
      LogicalOperation operation,
      int attempt,
      String attemptKind,
      Integer retryOriginAttempt,
      long retryOfferOrdinal,
      long admissionDecisionNanos,
      AdmissionResult admission)
      implements CoordinatorEvent {}

  private record CompletionEvent(
      LogicalOperation operation, int attempt, ServiceCompletion completion) {}

  private record CheckpointCompletionEvent(
      CheckpointAttempt attempt, CheckpointCompletion completion) {}

  private record ProducerFinishedEvent(long producedInitialOffers, Throwable failure)
      implements CoordinatorEvent {}

  private record ResourceEvent(
      long sequence,
      String sampleKind,
      long scheduledNanos,
      ResourceObservation observation,
      Throwable failure)
      implements CoordinatorEvent {}

  private record CheckpointAttempt(
      long maintenanceAttempt,
      String reason,
      long scheduledPhaseOffsetNanos,
      long scheduledNanos,
      long offeredNanos) {}

  private sealed interface PendingCompletion
      permits PendingBusinessCompletion, PendingCheckpointCompletion {
    long workSequence();

    boolean isDone();
  }

  private record PendingBusinessCompletion(
      long workSequence,
      LogicalOperation operation,
      int attempt,
      long admissionSequence,
      CompletionHandle<ServiceCompletion> completion)
      implements PendingCompletion {
    @Override
    public boolean isDone() {
      return completion.isDone();
    }
  }

  private record PendingCheckpointCompletion(
      long workSequence,
      CheckpointAttempt attempt,
      CompletionHandle<CheckpointCompletion> completion)
      implements PendingCompletion {
    @Override
    public boolean isDone() {
      return completion.isDone();
    }
  }

  private static final class LogicalOperation {
    private final String id;
    private final long scheduledArrivalNanos;
    private final byte[] envelope;
    private int attempt;
    private Integer retryOriginAttempt;
    private long retryOfferOrdinal;

    LogicalOperation(String id, long scheduledArrivalNanos, byte[] envelope, int attempt) {
      this.id = id;
      this.scheduledArrivalNanos = scheduledArrivalNanos;
      this.envelope = envelope.clone();
      this.attempt = attempt;
    }

    String id() {
      return id;
    }

    long scheduledArrivalNanos() {
      return scheduledArrivalNanos;
    }

    byte[] envelope() {
      return envelope.clone();
    }

    int attempt() {
      return attempt;
    }

    void incrementAttempt() {
      attempt = Math.incrementExact(attempt);
    }

    void beginCheckpointRetry(int completedAttempt) {
      if (completedAttempt != attempt) {
        throw new IllegalStateException("checkpoint retry attempt changed");
      }
      retryOriginAttempt = completedAttempt;
      retryOfferOrdinal = 0;
      incrementAttempt();
    }

    int retryOriginAttempt() {
      if (retryOriginAttempt == null) {
        throw new IllegalStateException("operation has no checkpoint retry origin");
      }
      return retryOriginAttempt;
    }

    long nextRetryOfferOrdinal() {
      retryOfferOrdinal = Math.incrementExact(retryOfferOrdinal);
      return retryOfferOrdinal;
    }
  }

  private final class ResourceSampler implements AutoCloseable {
    private final PhaseState state;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread thread;

    ResourceSampler(PhaseState state) {
      this.state = state;
      thread =
          Thread.ofPlatform()
              .name("m10-resource-sampler-" + state.point.pointId())
              .unstarted(this::sample);
    }

    void start() {
      thread.start();
    }

    private void sample() {
      long sequence = 0;
      long target = state.phaseOriginAbsolute;
      try {
        while (running.get()) {
          awaitWhileRunning(target);
          if (!running.get()) {
            break;
          }
          long observedAbsolute = System.nanoTime();
          long lag = Math.subtractExact(observedAbsolute, target);
          if (lag > MAX_RESOURCE_GAP_NANOS) {
            throw new IllegalStateException("resource sampler fell more than two seconds behind");
          }
          sequence = Math.incrementExact(sequence);
          publishEvent(
              new ResourceEvent(
                  sequence,
                  "PERIODIC",
                  target - runOriginNanos,
                  resources.observe(observedAbsolute - runOriginNanos),
                  null));
          // Schedule from the actual observation so a delayed sampler never emits catch-up bursts
          // that falsely resemble regular coverage.
          target = Math.addExact(observedAbsolute, profile.resourceIntervalNanos());
        }
        sequence = Math.incrementExact(sequence);
        long terminalScheduled = System.nanoTime() - runOriginNanos;
        publishEvent(
            new ResourceEvent(
                sequence,
                "TERMINAL",
                terminalScheduled,
                resources.observe(System.nanoTime() - runOriginNanos),
                null));
      } catch (Throwable failure) {
        publishEvent(
            new ResourceEvent(
                Math.incrementExact(sequence), "COLLECTOR_FAILURE", 0, null, failure));
      }
    }

    private void awaitWhileRunning(long targetNanos) {
      while (running.get()) {
        long remaining = targetNanos - System.nanoTime();
        if (remaining <= 0) {
          return;
        }
        LockSupport.parkNanos(Math.min(remaining, profile.resourceIntervalNanos()));
      }
    }

    @Override
    public void close() {
      running.set(false);
      LockSupport.unpark(thread);
      try {
        thread.join();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted waiting for resource sampler", interrupted);
      }
    }
  }

  private final class PhaseLifecycle implements AutoCloseable {
    private final PhaseState state;
    private final Thread producer;
    private final ResourceSampler resourceSampler;
    private boolean successful;

    PhaseLifecycle(PhaseState state, Thread producer, ResourceSampler resourceSampler) {
      this.state = state;
      this.producer = producer;
      this.resourceSampler = resourceSampler;
    }

    void start() {
      resourceSampler.start();
      producer.start();
    }

    void markSuccessful() {
      successful = true;
    }

    @Override
    public void close() throws IOException {
      CleanupFailures cleanup = new CleanupFailures();
      cleanup.run(this::stopProducer);
      cleanup.run(resourceSampler::close);
      cleanup.run(() -> drainEvents(state));
      if (!successful || cleanup.hasFailure()) {
        // An aborted measured point owns no reusable runtime. Closing it drains any accepted
        // checkpoint/business item and prevents a late producer admission from escaping cleanup.
        cleanup.run(service::close);
      }
      cleanup.run(this::stopProducer);
      cleanup.run(() -> drainEvents(state));
      cleanup.throwIfAny();
    }

    private void stopProducer() {
      if (producer.isAlive()) {
        producer.interrupt();
      }
      joinProducer(producer);
    }
  }

  private final class PhaseState {
    private final QualificationArtifactSink.PointIdentity point;
    private final QualificationRecoveryPlan.PhasePlan recoveryPlan;
    private final long startingBacklog;
    private final long phaseOriginAbsolute;
    private final long phaseEndAbsolute;
    private final QualificationProfile profile = LocalServiceLoadPoint.this.profile;
    private final long runOriginNanos = LocalServiceLoadPoint.this.runOriginNanos;
    private final LongSampleBuffer logicalLatencies = new LongSampleBuffer();
    private final LongSampleBuffer initialQueueDepthSamples = new LongSampleBuffer();
    private final LongSampleBuffer initialQueueDepthSamplesAtCut = new LongSampleBuffer();
    private final LongSampleBuffer producerLags = new LongSampleBuffer();
    private final Map<String, Long> cutSubmissionResultCounts = new LinkedHashMap<>();
    private long logicalOffers;
    private long logicalInitiallyAdmitted;
    private long logicalOverloaded;
    private long logicalClosedOrInvalid;
    private long logicalTerminal;
    private long attemptOffers;
    private int attemptCompletionRecords;
    private long resourceSamples;
    private long cutOffers;
    private long cutAdmitted;
    private long cutOverloaded;
    private long cutClosedOrInvalid;
    private long cutExplicitServiceFailures;
    private long initialDecisionsAtCut;
    private boolean producerFinished;
    private long producedInitialOffers;
    private boolean proactiveCheckpointCompleted;
    private boolean checkpointRequired;
    private CheckpointAttempt checkpointInFlight;
    private ServiceMetricsSnapshot observationCutMetrics;
    private long observationCutObservedNanos;
    private ResourceObservation firstPeriodicResource;
    private ResourceObservation terminalResource;
    private ResourceObservation lastResource;
    private String correctnessFailure;
    private Throwable methodFailure;

    PhaseState(
        QualificationArtifactSink.PointIdentity point,
        QualificationRecoveryPlan.PhasePlan recoveryPlan,
        long startingBacklog,
        long phaseOriginAbsolute,
        long phaseEndAbsolute) {
      this.point = point;
      this.recoveryPlan = recoveryPlan;
      this.startingBacklog = startingBacklog;
      this.phaseOriginAbsolute = phaseOriginAbsolute;
      this.phaseEndAbsolute = phaseEndAbsolute;
      for (SubmissionResultVariant variant : SubmissionResultVariant.values()) {
        cutSubmissionResultCounts.put(variant.name(), 0L);
      }
    }

    void recordCutAdmission(AdmissionResult admission) {
      cutOffers = Math.incrementExact(cutOffers);
      if (admission instanceof AdmissionResult.Enqueued) {
        cutAdmitted = Math.incrementExact(cutAdmitted);
      } else {
        AdmissionResult.Rejected rejected = (AdmissionResult.Rejected) admission;
        if (rejected.code() == AdmissionRejectionCode.OVERLOADED_BEFORE_WAL) {
          cutOverloaded = Math.incrementExact(cutOverloaded);
        } else {
          cutClosedOrInvalid = Math.incrementExact(cutClosedOrInvalid);
        }
      }
    }

    void recordCutSubmissionResult(SubmissionResultVariant variant) {
      cutSubmissionResultCounts.compute(
          variant.name(), (ignored, count) -> Math.incrementExact(count));
    }

    void recordCutExplicitFailure() {
      cutExplicitServiceFailures = Math.incrementExact(cutExplicitServiceFailures);
    }

    RunAccounting observationCutAccounting() {
      long terminalCompletions =
          cutSubmissionResultCounts.values().stream().mapToLong(Long::longValue).sum();
      long pending =
          Math.subtractExact(
              cutAdmitted, Math.addExact(terminalCompletions, cutExplicitServiceFailures));
      return new RunAccounting(
          cutOffers,
          cutAdmitted,
          cutOverloaded,
          cutClosedOrInvalid,
          cutSubmissionResultCounts,
          cutExplicitServiceFailures,
          pending);
    }
  }
}
