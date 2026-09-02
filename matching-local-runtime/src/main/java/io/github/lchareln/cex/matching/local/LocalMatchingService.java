package io.github.lchareln.cex.matching.local;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * Bounded, single-owner-worker admission boundary around one {@link LocalMatchingRuntime}.
 *
 * <p>Business submissions and explicit checkpoint maintenance share one finite FIFO. Enqueue only
 * transfers ownership; it is never a durable acknowledgement. This class deliberately does not turn
 * {@link SubmissionResult.CheckpointRequired} into hidden checkpoint/retry work.
 */
public final class LocalMatchingService implements AutoCloseable {
  private final Object gate = new Object();
  private final ServiceConfig config;
  private final RuntimePort runtime;
  private final ArrayBlockingQueue<WorkItem> queue;
  private final Thread worker;
  private final EnumMap<AdmissionRejectionCode, Long> rejectionCounts =
      zeroCounts(AdmissionRejectionCode.class);
  private final EnumMap<AdmissionRejectionCode, Long> checkpointRejectionCounts =
      zeroCounts(AdmissionRejectionCode.class);
  private final EnumMap<SubmissionResultVariant, Long> submissionResultCounts =
      zeroCounts(SubmissionResultVariant.class);

  private ServiceState state = ServiceState.ACCEPTING;
  private String failureDetail = "";
  private long nextWorkSequence = 1;
  private long nextAdmissionSequence = 1;
  private int maximumQueueDepth;
  private long offers;
  private long admitted;
  private long overloaded;
  private long closedOrInvalid;
  private long submissionResultCompletions;
  private long explicitServiceFailures;
  private long pending;
  private long durableAcknowledgements;
  private long checkpointOffers;
  private long checkpointAdmitted;
  private long checkpointOverloaded;
  private long checkpointClosed;
  private long checkpointCompletions;
  private long checkpointFailures;
  private long checkpointPending;
  private long nextMetricsCutToken = 1;
  private long lastGateNanos;
  private Throwable runtimeCloseFailure;

  private LocalMatchingService(
      ServiceConfig config, RuntimePort runtime, ArrayBlockingQueue<WorkItem> queue) {
    this.config = config;
    this.runtime = runtime;
    this.queue = queue;
    worker = Thread.ofPlatform().name(config.workerName()).unstarted(this::runWorker);
  }

  public static LocalMatchingService open(WalConfig walConfig, ServiceConfig serviceConfig)
      throws IOException {
    return open(walConfig, serviceConfig, FaultInjector.NONE);
  }

  /** Opens one runtime and transfers all post-open access to the service owner worker. */
  public static LocalMatchingService open(
      WalConfig walConfig, ServiceConfig serviceConfig, FaultInjector faultInjector)
      throws IOException {
    Objects.requireNonNull(walConfig, "walConfig");
    Objects.requireNonNull(serviceConfig, "serviceConfig");
    Objects.requireNonNull(faultInjector, "faultInjector");
    LocalMatchingRuntime runtime = LocalMatchingRuntime.open(walConfig, faultInjector);
    final RuntimePort runtimePort;
    try {
      runtimePort = new LocalRuntimePort(runtime);
    } catch (RuntimeException | Error constructionFailure) {
      closeAfterConstructionFailure(runtime, constructionFailure);
      throw constructionFailure;
    }
    return start(serviceConfig, runtimePort);
  }

  /** Package-private deterministic seam used by the repository-owned M10 judge probe. */
  static LocalMatchingService openForTesting(ServiceConfig serviceConfig, RuntimePort runtime) {
    Objects.requireNonNull(serviceConfig, "serviceConfig");
    Objects.requireNonNull(runtime, "runtime");
    return start(serviceConfig, runtime);
  }

  private static LocalMatchingService start(ServiceConfig serviceConfig, RuntimePort runtime) {
    try {
      ArrayBlockingQueue<WorkItem> queue = new ArrayBlockingQueue<>(serviceConfig.queueCapacity());
      LocalMatchingService service = new LocalMatchingService(serviceConfig, runtime, queue);
      service.worker.start();
      return service;
    } catch (RuntimeException | Error startFailure) {
      closeAfterConstructionFailure(runtime, startFailure);
      throw startFailure;
    }
  }

  private static void closeAfterConstructionFailure(
      AutoCloseable ownedResource, Throwable constructionFailure) {
    try {
      ownedResource.close();
    } catch (Throwable closeFailure) {
      constructionFailure.addSuppressed(closeFailure);
    }
  }

  /**
   * Attempts immediate bounded admission of one business envelope.
   *
   * <p>The method never waits for queue capacity or business execution. When admitted, it clones
   * the caller bytes before publishing the item to the owner worker.
   */
  public AdmissionResult trySubmit(byte[] canonicalEnvelope) {
    synchronized (gate) {
      offers = Math.incrementExact(offers);
      if (canonicalEnvelope == null) {
        return rejectBusiness(
            AdmissionRejectionCode.INVALID_ENVELOPE_REFERENCE,
            "canonicalEnvelope must not be null");
      }
      if (canonicalEnvelope.length == 0
          || canonicalEnvelope.length > M08EnvelopeCodec.MAX_ENVELOPE_BYTES) {
        return rejectBusiness(
            AdmissionRejectionCode.INVALID_ENVELOPE_SIZE,
            "canonicalEnvelope length must be between 1 and "
                + M08EnvelopeCodec.MAX_ENVELOPE_BYTES
                + " bytes");
      }
      AdmissionResult.Rejected unavailable = unavailableBusinessRejection();
      if (unavailable != null) {
        return unavailable;
      }
      if (queue.remainingCapacity() == 0) {
        return rejectBusiness(
            AdmissionRejectionCode.OVERLOADED_BEFORE_WAL,
            "bounded owner-worker queue is full before runtime submission");
      }

      byte[] ownedEnvelope = canonicalEnvelope.clone();
      long workSequence = nextSequence(nextWorkSequence, "workSequence");
      long admissionSequence = nextSequence(nextAdmissionSequence, "admissionSequence");
      CompletionHandle<ServiceCompletion> completion = new CompletionHandle<>();
      SubmissionWork work =
          new SubmissionWork(workSequence, admissionSequence, ownedEnvelope, completion);
      if (!queue.offer(work)) {
        throw new IllegalStateException("queue capacity changed while holding the service gate");
      }
      nextWorkSequence = Math.incrementExact(nextWorkSequence);
      nextAdmissionSequence = Math.incrementExact(nextAdmissionSequence);
      admitted = Math.incrementExact(admitted);
      pending = Math.incrementExact(pending);
      int decisionQueueDepth = queue.size();
      observeQueueDepth(decisionQueueDepth);
      long decisionNanos = gateNanos();
      gate.notifyAll();
      return new AdmissionResult.Enqueued(
          workSequence, admissionSequence, decisionQueueDepth, decisionNanos, completion);
    }
  }

  /**
   * Attempts to order one checkpoint through the same bounded owner-worker FIFO.
   *
   * <p>This is maintenance admission, not a business offer or ACK. A checkpoint failure fails the
   * service closed; {@link SubmissionResult.CheckpointRequired} itself remains visible to its
   * business caller and never invokes this method implicitly.
   */
  public CheckpointAdmissionResult tryCheckpoint() {
    synchronized (gate) {
      checkpointOffers = Math.incrementExact(checkpointOffers);
      CheckpointAdmissionResult.Rejected unavailable = unavailableCheckpointRejection();
      if (unavailable != null) {
        return unavailable;
      }
      if (queue.remainingCapacity() == 0) {
        return rejectCheckpoint(
            AdmissionRejectionCode.OVERLOADED_BEFORE_WAL,
            "bounded owner-worker queue is full before checkpoint maintenance");
      }

      long workSequence = nextSequence(nextWorkSequence, "workSequence");
      CompletionHandle<CheckpointCompletion> completion = new CompletionHandle<>();
      CheckpointWork work = new CheckpointWork(workSequence, completion);
      if (!queue.offer(work)) {
        throw new IllegalStateException("queue capacity changed while holding the service gate");
      }
      nextWorkSequence = Math.incrementExact(nextWorkSequence);
      checkpointAdmitted = Math.incrementExact(checkpointAdmitted);
      checkpointPending = Math.incrementExact(checkpointPending);
      observeQueueDepth(queue.size());
      long decisionNanos = gateNanos();
      gate.notifyAll();
      return new CheckpointAdmissionResult.Enqueued(workSequence, decisionNanos, completion);
    }
  }

  public ServiceState state() {
    synchronized (gate) {
      return state;
    }
  }

  public String failureDetail() {
    synchronized (gate) {
      return failureDetail;
    }
  }

  /**
   * Exact accounting cut; terminal quiesced snapshots have zero business and maintenance pending.
   */
  public ServiceMetricsSnapshot metrics() {
    synchronized (gate) {
      return snapshotUnderGate();
    }
  }

  /** Captures accounting and a strictly ordered monotonic cut while holding the admission gate. */
  public ServiceMetricsCut metricsCut() {
    synchronized (gate) {
      long cutToken = nextMetricsCutToken;
      nextMetricsCutToken = Math.incrementExact(nextMetricsCutToken);
      return new ServiceMetricsCut(cutToken, gateNanos(), snapshotUnderGate());
    }
  }

  /**
   * Stops admission, drains every already admitted FIFO item, and waits for the owner to close the
   * runtime. The public callback-free handle never invokes caller code on the owner; the explicit
   * thread check still rejects a reentrant call from a package-private runtime test seam.
   */
  @Override
  public void close() throws IOException {
    if (Thread.currentThread() == worker) {
      throw new IllegalStateException("close cannot wait for itself on the owner-worker thread");
    }
    synchronized (gate) {
      if (state == ServiceState.ACCEPTING) {
        state = ServiceState.QUIESCING;
      }
      gate.notifyAll();
    }
    try {
      worker.join();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted while waiting for accepted work to drain", interrupted);
    }
    synchronized (gate) {
      if (runtimeCloseFailure != null) {
        throw new IOException(
            "owner worker could not close the local runtime", runtimeCloseFailure);
      }
    }
  }

  private void runWorker() {
    try {
      while (true) {
        WorkItem work = awaitWork();
        if (work == null) {
          break;
        }
        if (!process(work)) {
          break;
        }
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      failAllAccepted(
          null,
          ServiceFailureCode.UNEXPECTED_WORKER_INTERRUPTION,
          "owner worker interrupted while accepting: " + describe(interrupted));
    } catch (Throwable unexpected) {
      failAllAccepted(
          null,
          ServiceFailureCode.UNEXPECTED_WORKER_FAILURE,
          "owner worker terminated unexpectedly: " + describe(unexpected));
    } finally {
      closeOwnedRuntime();
    }
  }

  private WorkItem awaitWork() throws InterruptedException {
    synchronized (gate) {
      while (queue.isEmpty() && state == ServiceState.ACCEPTING) {
        gate.wait();
      }
      if (queue.isEmpty()) {
        return null;
      }
      return queue.remove();
    }
  }

  private boolean process(WorkItem work) {
    if (work instanceof SubmissionWork submission) {
      return processSubmission(submission);
    }
    return processCheckpoint((CheckpointWork) work);
  }

  private boolean processSubmission(SubmissionWork work) {
    final SubmissionResult result;
    final RuntimeState runtimeState;
    try {
      result = Objects.requireNonNull(runtime.submit(work.ownedEnvelope()), "submission result");
      runtimeState = Objects.requireNonNull(runtime.state(), "runtime state");
    } catch (Throwable unexpected) {
      failAllAccepted(
          work,
          ServiceFailureCode.UNEXPECTED_WORKER_FAILURE,
          "runtime submission/state observation threw unexpectedly: " + describe(unexpected));
      return false;
    }

    List<CompletionAction> actions = new ArrayList<>();
    boolean runtimeFailedClosed =
        result instanceof SubmissionResult.DurabilityUnknown
            || result instanceof SubmissionResult.FailedClosed
            || runtimeState != RuntimeState.OPEN;
    synchronized (gate) {
      accountSubmissionResult(result);
      long ownerCompletedNanos = gateNanos();
      actions.add(
          new SubmissionCompletionAction(
              work,
              new ServiceCompletion.SubmissionCompleted(
                  work.workSequence(), work.admissionSequence(), ownerCompletedNanos, result)));
      if (runtimeFailedClosed) {
        enterFailedClosed("local runtime returned a fail-closed submission outcome");
        drainAcceptedFailures(
            actions,
            ServiceFailureCode.RUNTIME_FAILED_CLOSED,
            "not submitted because the preceding runtime outcome failed closed");
      }
    }
    complete(actions);
    return !runtimeFailedClosed;
  }

  private boolean processCheckpoint(CheckpointWork work) {
    final CheckpointResult result;
    try {
      result = Objects.requireNonNull(runtime.checkpoint(), "checkpoint result");
    } catch (Throwable failure) {
      failAllAccepted(
          work,
          ServiceFailureCode.CHECKPOINT_FAILED,
          "checkpoint maintenance failed: " + describe(failure));
      return false;
    }
    CheckpointCompletion completion;
    synchronized (gate) {
      checkpointCompletions = Math.incrementExact(checkpointCompletions);
      checkpointPending = Math.decrementExact(checkpointPending);
      completion = new CheckpointCompletion.Completed(work.workSequence(), gateNanos(), result);
    }
    work.completion().complete(completion);
    return true;
  }

  private void failAllAccepted(WorkItem current, ServiceFailureCode code, String detail) {
    List<CompletionAction> actions = new ArrayList<>();
    synchronized (gate) {
      enterFailedClosed(detail);
      if (current != null) {
        accountExplicitFailure(current, actions, code, detail);
      }
      drainAcceptedFailures(actions, code, detail);
    }
    complete(actions);
  }

  private void drainAcceptedFailures(
      List<CompletionAction> actions, ServiceFailureCode code, String detail) {
    WorkItem queued;
    while ((queued = queue.poll()) != null) {
      accountExplicitFailure(queued, actions, code, detail);
    }
  }

  private void accountExplicitFailure(
      WorkItem work, List<CompletionAction> actions, ServiceFailureCode code, String detail) {
    if (work instanceof SubmissionWork submission) {
      explicitServiceFailures = Math.incrementExact(explicitServiceFailures);
      pending = Math.decrementExact(pending);
      actions.add(
          new SubmissionCompletionAction(
              submission,
              new ServiceCompletion.ExplicitFailure(
                  submission.workSequence(),
                  submission.admissionSequence(),
                  gateNanos(),
                  code,
                  detail)));
      return;
    }
    CheckpointWork checkpoint = (CheckpointWork) work;
    checkpointFailures = Math.incrementExact(checkpointFailures);
    checkpointPending = Math.decrementExact(checkpointPending);
    actions.add(
        new CheckpointCompletionAction(
            checkpoint,
            new CheckpointCompletion.ExplicitFailure(
                checkpoint.workSequence(), gateNanos(), code, detail)));
  }

  private void accountSubmissionResult(SubmissionResult result) {
    submissionResultCompletions = Math.incrementExact(submissionResultCompletions);
    pending = Math.decrementExact(pending);
    SubmissionResultVariant variant = SubmissionResultVariant.from(result);
    increment(submissionResultCounts, variant);
    if (variant.durableAcknowledgement()) {
      durableAcknowledgements = Math.incrementExact(durableAcknowledgements);
    }
  }

  private void closeOwnedRuntime() {
    Throwable closeFailure = null;
    try {
      runtime.close();
    } catch (Throwable failure) {
      closeFailure = failure;
    }
    synchronized (gate) {
      if (closeFailure != null) {
        runtimeCloseFailure = closeFailure;
        enterFailedClosed("runtime close failed: " + describe(closeFailure));
      } else if (state == ServiceState.QUIESCING || state == ServiceState.ACCEPTING) {
        state = ServiceState.CLOSED;
      }
      gate.notifyAll();
    }
  }

  private void enterFailedClosed(String detail) {
    state = ServiceState.FAILED_CLOSED;
    if (failureDetail.isEmpty()) {
      failureDetail = detail;
    } else if (!failureDetail.contains(detail)) {
      failureDetail = failureDetail + "; " + detail;
    }
    gate.notifyAll();
  }

  private AdmissionResult.Rejected unavailableBusinessRejection() {
    return switch (state) {
      case ACCEPTING -> null;
      case QUIESCING, CLOSED ->
          rejectBusiness(AdmissionRejectionCode.NOT_ACCEPTING, "service is not accepting offers");
      case FAILED_CLOSED ->
          rejectBusiness(
              AdmissionRejectionCode.SERVICE_FAILED_CLOSED,
              "service failed closed: " + failureDetail);
    };
  }

  private CheckpointAdmissionResult.Rejected unavailableCheckpointRejection() {
    return switch (state) {
      case ACCEPTING -> null;
      case QUIESCING, CLOSED ->
          rejectCheckpoint(
              AdmissionRejectionCode.NOT_ACCEPTING, "service is not accepting maintenance");
      case FAILED_CLOSED ->
          rejectCheckpoint(
              AdmissionRejectionCode.SERVICE_FAILED_CLOSED,
              "service failed closed: " + failureDetail);
    };
  }

  private AdmissionResult.Rejected rejectBusiness(AdmissionRejectionCode code, String detail) {
    increment(rejectionCounts, code);
    if (code == AdmissionRejectionCode.OVERLOADED_BEFORE_WAL) {
      overloaded = Math.incrementExact(overloaded);
    } else {
      closedOrInvalid = Math.incrementExact(closedOrInvalid);
    }
    return new AdmissionResult.Rejected(code, detail, queue.size(), gateNanos());
  }

  private CheckpointAdmissionResult.Rejected rejectCheckpoint(
      AdmissionRejectionCode code, String detail) {
    increment(checkpointRejectionCounts, code);
    if (code == AdmissionRejectionCode.OVERLOADED_BEFORE_WAL) {
      checkpointOverloaded = Math.incrementExact(checkpointOverloaded);
    } else {
      checkpointClosed = Math.incrementExact(checkpointClosed);
    }
    return new CheckpointAdmissionResult.Rejected(code, detail, gateNanos());
  }

  private ServiceMetricsSnapshot snapshotUnderGate() {
    return new ServiceMetricsSnapshot(
        state,
        config.queueCapacity(),
        queue.size(),
        maximumQueueDepth,
        offers,
        admitted,
        overloaded,
        closedOrInvalid,
        submissionResultCompletions,
        explicitServiceFailures,
        pending,
        durableAcknowledgements,
        Map.copyOf(rejectionCounts),
        Map.copyOf(submissionResultCounts),
        checkpointOffers,
        checkpointAdmitted,
        checkpointOverloaded,
        checkpointClosed,
        checkpointCompletions,
        checkpointFailures,
        checkpointPending,
        Map.copyOf(checkpointRejectionCounts));
  }

  private long gateNanos() {
    long observed = System.nanoTime();
    if (observed <= lastGateNanos) {
      observed = Math.incrementExact(lastGateNanos);
    }
    lastGateNanos = observed;
    return observed;
  }

  private void observeQueueDepth(int observedDepth) {
    maximumQueueDepth = Math.max(maximumQueueDepth, observedDepth);
  }

  private static void complete(List<CompletionAction> actions) {
    for (CompletionAction action : actions) {
      action.complete();
    }
  }

  private static long nextSequence(long value, String name) {
    if (value <= 0 || value == Long.MAX_VALUE) {
      throw new IllegalStateException(name + " exhausted");
    }
    return value;
  }

  private static String describe(Throwable failure) {
    String message = failure.getMessage();
    return failure.getClass().getSimpleName() + (message == null ? "" : ": " + message);
  }

  private static <E extends Enum<E>> EnumMap<E, Long> zeroCounts(Class<E> type) {
    EnumMap<E, Long> counts = new EnumMap<>(type);
    for (E value : type.getEnumConstants()) {
      counts.put(value, 0L);
    }
    return counts;
  }

  private static <E extends Enum<E>> void increment(EnumMap<E, Long> counts, E key) {
    counts.put(key, Math.incrementExact(counts.get(key)));
  }

  private sealed interface WorkItem permits SubmissionWork, CheckpointWork {
    long workSequence();
  }

  private record SubmissionWork(
      long workSequence,
      long admissionSequence,
      byte[] ownedEnvelope,
      CompletionHandle<ServiceCompletion> completion)
      implements WorkItem {}

  private record CheckpointWork(
      long workSequence, CompletionHandle<CheckpointCompletion> completion) implements WorkItem {}

  private sealed interface CompletionAction
      permits SubmissionCompletionAction, CheckpointCompletionAction {
    void complete();
  }

  private record SubmissionCompletionAction(SubmissionWork work, ServiceCompletion completion)
      implements CompletionAction {
    @Override
    public void complete() {
      work.completion().complete(completion);
    }
  }

  private record CheckpointCompletionAction(CheckpointWork work, CheckpointCompletion completion)
      implements CompletionAction {
    @Override
    public void complete() {
      work.completion().complete(completion);
    }
  }

  /**
   * Small owner-worker port; package-private solely for an independent same-package judge probe.
   */
  interface RuntimePort extends AutoCloseable {
    SubmissionResult submit(byte[] canonicalEnvelope);

    CheckpointResult checkpoint() throws IOException;

    RuntimeState state();

    void close() throws IOException;
  }

  private record LocalRuntimePort(LocalMatchingRuntime delegate) implements RuntimePort {
    private LocalRuntimePort {
      Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public SubmissionResult submit(byte[] canonicalEnvelope) {
      return delegate.submit(canonicalEnvelope);
    }

    @Override
    public CheckpointResult checkpoint() throws IOException {
      return delegate.checkpoint();
    }

    @Override
    public RuntimeState state() {
      return delegate.state();
    }

    @Override
    public void close() throws IOException {
      delegate.close();
    }
  }
}
