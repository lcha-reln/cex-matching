package io.github.lchareln.cex.matching.local;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalMatchingServiceTest {
  private static final long SHARD = 83;

  @TempDir Path temporaryDirectory;

  private final M08EnvelopeCodec codec = new M08EnvelopeCodec();

  @Test
  void requiresPositiveFiniteCapacityAndFreezesQualificationCapacity() {
    assertThrows(IllegalArgumentException.class, () -> new ServiceConfig(0));
    assertThrows(IllegalArgumentException.class, () -> new ServiceConfig(-1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ServiceConfig(ServiceConfig.MAX_QUEUE_CAPACITY + 1));
    assertThrows(IllegalArgumentException.class, () -> new ServiceConfig(1, " "));
    assertEquals(256, ServiceConfig.MAX_QUEUE_CAPACITY);
    assertEquals(64, ServiceConfig.qualification().queueCapacity());
  }

  @Test
  void rejectsImpossibleEnvelopeSizesBeforeOwnershipOrRuntimeSubmission() throws Exception {
    RecordingRuntimePort port =
        new RecordingRuntimePort(
            new SubmissionResult.StructuralRejected(
                StructuralRejectionCode.MALFORMED_ENVELOPE, "must not be reached"));
    try (LocalMatchingService service =
        LocalMatchingService.openForTesting(new ServiceConfig(1), port)) {
      AdmissionResult.Rejected empty =
          assertInstanceOf(AdmissionResult.Rejected.class, service.trySubmit(new byte[0]));
      AdmissionResult.Rejected oversized =
          assertInstanceOf(
              AdmissionResult.Rejected.class,
              service.trySubmit(new byte[M08EnvelopeCodec.MAX_ENVELOPE_BYTES + 1]));

      assertEquals(AdmissionRejectionCode.INVALID_ENVELOPE_SIZE, empty.code());
      assertEquals(AdmissionRejectionCode.INVALID_ENVELOPE_SIZE, oversized.code());
      assertEquals(0, empty.decisionQueueDepth());
      assertEquals(0, oversized.decisionQueueDepth());
      assertEquals(null, port.observedEnvelope());
      ServiceMetricsSnapshot metrics = service.metrics();
      assertEquals(2, metrics.offers());
      assertEquals(0, metrics.admitted());
      assertEquals(2, metrics.closedOrInvalid());
      assertTrue(metrics.fullyReconciled());
    }
  }

  @Test
  void testingPortProvesCallerCopyAndExactSubmissionResultInstancePassThrough() throws Exception {
    SubmissionResult expected =
        new SubmissionResult.StructuralRejected(
            StructuralRejectionCode.NON_CANONICAL_ENVELOPE, "recording result");
    RecordingRuntimePort port = new RecordingRuntimePort(expected);
    byte[] caller = {1, 2, 3};
    byte[] original = caller.clone();
    try (LocalMatchingService service =
        LocalMatchingService.openForTesting(new ServiceConfig(1), port)) {
      ServiceMetricsCut before = service.metricsCut();
      AdmissionResult.Enqueued enqueued = enqueued(service.trySubmit(caller));
      caller[0] = 99;
      ServiceCompletion.SubmissionCompleted completed =
          assertInstanceOf(
              ServiceCompletion.SubmissionCompleted.class, await(enqueued.completion()));
      assertSame(expected, completed.result());
      ServiceMetricsCut after = service.metricsCut();
      assertEquals(enqueued.workSequence(), completed.workSequence());
      assertEquals(enqueued.admissionSequence(), completed.admissionSequence());
      assertTrue(before.observedNanos() < enqueued.decisionNanos());
      assertTrue(enqueued.decisionNanos() < completed.ownerCompletedNanos());
      assertTrue(completed.ownerCompletedNanos() < after.observedNanos());
      assertEquals(before.cutToken() + 1, after.cutToken());
      assertNotSame(caller, port.observedEnvelope());
      assertArrayEquals(original, port.observedEnvelope());
    }
  }

  @Test
  void callbackFreeHandleCannotCaptureOwnerAndOwnerStillAdvancesAndCloses() throws Exception {
    assertFalse(CompletionStage.class.isAssignableFrom(CompletionHandle.class));
    assertFalse(Future.class.isAssignableFrom(CompletionHandle.class));
    assertTrue(
        Arrays.stream(CompletionHandle.class.getDeclaredConstructors())
            .noneMatch(constructor -> Modifier.isPublic(constructor.getModifiers())));
    Set<String> publicMethods =
        Arrays.stream(CompletionHandle.class.getDeclaredMethods())
            .filter(method -> Modifier.isPublic(method.getModifiers()))
            .map(method -> method.getName())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    assertEquals(Set.of("isDone", "get", "await"), publicMethods);

    CompletionHandle<String> oneShot = new CompletionHandle<>();
    assertThrows(IllegalArgumentException.class, () -> oneShot.await(Duration.ofNanos(-1)));
    assertThrows(TimeoutException.class, () -> oneShot.await(Duration.ZERO));
    oneShot.complete("first");
    assertThrows(IllegalStateException.class, () -> oneShot.complete("second"));
    assertEquals("first", oneShot.get());
    assertEquals("first", oneShot.await(Duration.ZERO));

    BlockingFirstRuntimePort port = new BlockingFirstRuntimePort();
    LocalMatchingService service = LocalMatchingService.openForTesting(new ServiceConfig(2), port);
    AdmissionResult.Enqueued first = enqueued(service.trySubmit(new byte[] {1}));
    port.awaitFirstEntered();
    AdmissionResult.Enqueued second = enqueued(service.trySubmit(new byte[] {2}));
    AtomicReference<Throwable> observerFailure = new AtomicReference<>();
    Thread observer =
        Thread.ofPlatform()
            .start(
                () -> {
                  try {
                    ServiceCompletion firstCompletion =
                        first.completion().await(Duration.ofSeconds(5));
                    ServiceCompletion secondCompletion =
                        second.completion().await(Duration.ofSeconds(5));
                    assertEquals(first.workSequence(), firstCompletion.workSequence());
                    assertEquals(second.workSequence(), secondCompletion.workSequence());
                  } catch (Throwable failure) {
                    observerFailure.set(failure);
                  }
                });

    port.releaseFirst();
    service.close();
    observer.join(Duration.ofSeconds(5));

    assertFalse(observer.isAlive());
    assertEquals(null, observerFailure.get());
    assertEquals(2, port.submissions());
    assertTrue(first.completion().isDone());
    assertTrue(second.completion().isDone());
    assertEquals(ServiceState.CLOSED, service.state());
  }

  @Test
  void boundedOfferOwnsBytesPreservesFifoAndRejectsBeforeWalAndIdentity() throws Exception {
    Path directory = directory("bounded-fifo");
    BlockingFault fault = new BlockingFault(FaultPoint.BEFORE_LIVE_APPLY, false);
    byte[] first = envelope(1);
    byte[] second = envelope(2);
    byte[] expectedSecond = second.clone();
    byte[] third = envelope(3);
    byte[] overloaded = envelope(4);

    AdmissionResult.Enqueued admittedFirst;
    AdmissionResult.Enqueued admittedSecond;
    AdmissionResult.Enqueued admittedThird;
    try (LocalMatchingService service =
        LocalMatchingService.open(config(directory), new ServiceConfig(2), fault)) {
      admittedFirst = enqueued(service.trySubmit(first));
      fault.awaitEntered();
      admittedSecond = enqueued(service.trySubmit(second));
      second[second.length - 1] ^= 1;
      admittedThird = enqueued(service.trySubmit(third));

      AdmissionResult.Rejected rejected =
          assertInstanceOf(AdmissionResult.Rejected.class, service.trySubmit(overloaded));
      assertEquals(AdmissionRejectionCode.OVERLOADED_BEFORE_WAL, rejected.code());
      assertEquals(2, rejected.decisionQueueDepth());
      CheckpointAdmissionResult.Rejected rejectedCheckpoint =
          assertInstanceOf(CheckpointAdmissionResult.Rejected.class, service.tryCheckpoint());
      assertEquals(AdmissionRejectionCode.OVERLOADED_BEFORE_WAL, rejectedCheckpoint.code());
      assertFalse(admittedFirst.completion().isDone());
      assertFalse(admittedSecond.completion().isDone());
      assertFalse(admittedThird.completion().isDone());

      ServiceMetricsSnapshot underLoad = service.metrics();
      assertEquals(4, underLoad.offers());
      assertEquals(3, underLoad.admitted());
      assertEquals(1, underLoad.overloaded());
      assertEquals(3, underLoad.pending());
      assertEquals(2, underLoad.queueDepth());
      assertEquals(2, underLoad.maximumQueueDepth());
      assertEquals(1, underLoad.checkpointOffers());
      assertEquals(1, underLoad.checkpointOverloaded());
      assertTrue(underLoad.fullyReconciled());
      fault.release();
    }

    SubmissionResult.NewDurablyApplied firstResult = durable(await(admittedFirst.completion()));
    SubmissionResult.NewDurablyApplied secondResult = durable(await(admittedSecond.completion()));
    SubmissionResult.NewDurablyApplied thirdResult = durable(await(admittedThird.completion()));
    assertEquals(1, firstResult.position().walSequence());
    assertEquals(2, secondResult.position().walSequence());
    assertEquals(3, thirdResult.position().walSequence());

    try (LocalMatchingRuntime recovered = LocalMatchingRuntime.open(config(directory))) {
      assertEquals(4, recovered.nextWalSequence());
      SubmissionResult.DuplicateReplayed duplicate =
          assertInstanceOf(
              SubmissionResult.DuplicateReplayed.class, recovered.submit(expectedSecond));
      assertArrayEquals(
          secondResult.result().auditBytes(), duplicate.originalResult().auditBytes());
      assertInstanceOf(SubmissionResult.NewDurablyApplied.class, recovered.submit(overloaded));
    }
  }

  @Test
  void checkpointRequiredStaysVisibleAndCoordinatorOrdersMaintenanceAndSameEnvelopeRetry()
      throws Exception {
    Path directory = directory("checkpoint-coordinator");
    WalConfig budgeted =
        new WalConfig(
            directory,
            SHARD,
            WalConfig.DEFAULT_MAX_SEGMENT_BYTES,
            WalConfig.DEFAULT_MAX_RECORD_BYTES,
            new RecoveryBudget(1, 1_048_576));
    byte[] first = envelope(1);
    byte[] second = envelope(2);

    try (LocalMatchingService service = LocalMatchingService.open(budgeted, new ServiceConfig(4))) {
      durable(await(enqueued(service.trySubmit(first)).completion()));
      SubmissionResult.CheckpointRequired required =
          assertInstanceOf(
              SubmissionResult.CheckpointRequired.class,
              submitted(await(enqueued(service.trySubmit(second)).completion())));
      assertEquals(1, required.suffixRecords());

      CheckpointAdmissionResult.Enqueued checkpoint = checkpoint(service.tryCheckpoint());
      CheckpointCompletion.Completed checkpointed =
          assertInstanceOf(CheckpointCompletion.Completed.class, await(checkpoint.completion()));
      assertEquals(1, checkpointed.result().anchor().lastWalSequence());

      SubmissionResult.NewDurablyApplied retried =
          durable(await(enqueued(service.trySubmit(second)).completion()));
      assertEquals(2, retried.position().walSequence());

      ServiceMetricsSnapshot metrics = service.metrics();
      assertEquals(3, metrics.offers());
      assertEquals(3, metrics.submissionResultCompletions());
      assertEquals(
          1, metrics.submissionResultCounts().get(SubmissionResultVariant.CHECKPOINT_REQUIRED));
      assertEquals(1, metrics.checkpointOffers());
      assertEquals(1, metrics.checkpointCompletions());
      assertTrue(metrics.fullyReconciled());
    }
  }

  @Test
  void checkpointFailureFailsCurrentMaintenanceAndEveryAcceptedPendingItemExplicitly()
      throws Exception {
    Path directory = directory("checkpoint-failure");
    BlockingFault fault = new BlockingFault(FaultPoint.BEFORE_SNAPSHOT_TEMP_WRITE, true);
    try (LocalMatchingService service =
        LocalMatchingService.open(config(directory), new ServiceConfig(2), fault)) {
      durable(await(enqueued(service.trySubmit(envelope(1))).completion()));
      CheckpointAdmissionResult.Enqueued checkpoint = checkpoint(service.tryCheckpoint());
      fault.awaitEntered();
      AdmissionResult.Enqueued pending = enqueued(service.trySubmit(envelope(2)));
      fault.release();

      CheckpointCompletion.ExplicitFailure checkpointFailure =
          assertInstanceOf(
              CheckpointCompletion.ExplicitFailure.class, await(checkpoint.completion()));
      assertEquals(ServiceFailureCode.CHECKPOINT_FAILED, checkpointFailure.code());
      ServiceCompletion.ExplicitFailure pendingFailure =
          assertInstanceOf(ServiceCompletion.ExplicitFailure.class, await(pending.completion()));
      assertEquals(ServiceFailureCode.CHECKPOINT_FAILED, pendingFailure.code());

      awaitState(service, ServiceState.FAILED_CLOSED);
      AdmissionResult.Rejected later =
          assertInstanceOf(AdmissionResult.Rejected.class, service.trySubmit(envelope(2)));
      assertEquals(AdmissionRejectionCode.SERVICE_FAILED_CLOSED, later.code());
      ServiceMetricsSnapshot metrics = service.metrics();
      assertEquals(1, metrics.explicitServiceFailures());
      assertEquals(1, metrics.checkpointFailures());
      assertEquals(0, metrics.pending());
      assertEquals(0, metrics.checkpointPending());
      assertTrue(metrics.fullyReconciled());
    }
  }

  @Test
  void unexpectedWorkerFailureClosesAdmissionAndFailsEveryAcceptedItemExplicitly()
      throws Exception {
    Path directory = directory("unexpected-worker-failure");
    BlockingFault fault = new BlockingFault(FaultPoint.BEFORE_LIVE_APPLY, true);
    try (LocalMatchingService service =
        LocalMatchingService.open(config(directory), new ServiceConfig(2), fault)) {
      AdmissionResult.Enqueued current = enqueued(service.trySubmit(envelope(1)));
      fault.awaitEntered();
      AdmissionResult.Enqueued queued = enqueued(service.trySubmit(envelope(2)));
      fault.release();

      ServiceCompletion.ExplicitFailure currentFailure =
          assertInstanceOf(ServiceCompletion.ExplicitFailure.class, await(current.completion()));
      ServiceCompletion.ExplicitFailure queuedFailure =
          assertInstanceOf(ServiceCompletion.ExplicitFailure.class, await(queued.completion()));
      assertEquals(ServiceFailureCode.UNEXPECTED_WORKER_FAILURE, currentFailure.code());
      assertEquals(ServiceFailureCode.UNEXPECTED_WORKER_FAILURE, queuedFailure.code());
      awaitState(service, ServiceState.FAILED_CLOSED);
      AdmissionResult.Rejected later =
          assertInstanceOf(AdmissionResult.Rejected.class, service.trySubmit(envelope(2)));
      assertEquals(AdmissionRejectionCode.SERVICE_FAILED_CLOSED, later.code());
      assertEquals(2, service.metrics().explicitServiceFailures());
      assertTrue(service.metrics().fullyReconciled());
    }
  }

  @Test
  void throwingRuntimeStateFailsDequeuedCurrentAndQueuedItemWithoutPendingLeak() throws Exception {
    StateThrowingRuntimePort port = new StateThrowingRuntimePort();
    try (LocalMatchingService service =
        LocalMatchingService.openForTesting(new ServiceConfig(1), port)) {
      AdmissionResult.Enqueued current = enqueued(service.trySubmit(new byte[] {1}));
      port.awaitSubmitEntered();
      AdmissionResult.Enqueued queued = enqueued(service.trySubmit(new byte[] {2}));
      port.releaseSubmit();

      ServiceCompletion.ExplicitFailure currentFailure =
          assertInstanceOf(ServiceCompletion.ExplicitFailure.class, await(current.completion()));
      ServiceCompletion.ExplicitFailure queuedFailure =
          assertInstanceOf(ServiceCompletion.ExplicitFailure.class, await(queued.completion()));
      assertEquals(ServiceFailureCode.UNEXPECTED_WORKER_FAILURE, currentFailure.code());
      assertEquals(ServiceFailureCode.UNEXPECTED_WORKER_FAILURE, queuedFailure.code());
      awaitState(service, ServiceState.FAILED_CLOSED);

      ServiceMetricsSnapshot metrics = service.metrics();
      assertEquals(2, metrics.admitted());
      assertEquals(2, metrics.explicitServiceFailures());
      assertEquals(0, metrics.submissionResultCompletions());
      assertEquals(0, metrics.pending());
      assertEquals(0, metrics.queueDepth());
      assertTrue(metrics.fullyReconciled());
    }
  }

  @Test
  void durabilityUnknownPassesThroughUnchangedAndFailsLaterPendingItemsExplicitly()
      throws Exception {
    Path directory = directory("durability-unknown");
    BlockingIoFault fault = new BlockingIoFault(FaultPoint.AFTER_RECORD_FORCE);
    try (LocalMatchingService service =
        LocalMatchingService.open(config(directory), new ServiceConfig(2), fault)) {
      AdmissionResult.Enqueued current = enqueued(service.trySubmit(envelope(1)));
      fault.awaitEntered();
      AdmissionResult.Enqueued queued = enqueued(service.trySubmit(envelope(2)));
      fault.release();

      ServiceCompletion.SubmissionCompleted completed =
          assertInstanceOf(
              ServiceCompletion.SubmissionCompleted.class, await(current.completion()));
      assertInstanceOf(SubmissionResult.DurabilityUnknown.class, completed.result());
      ServiceCompletion.ExplicitFailure failed =
          assertInstanceOf(ServiceCompletion.ExplicitFailure.class, await(queued.completion()));
      assertEquals(ServiceFailureCode.RUNTIME_FAILED_CLOSED, failed.code());
      awaitState(service, ServiceState.FAILED_CLOSED);
      assertEquals(1, service.metrics().submissionResultCompletions());
      assertEquals(1, service.metrics().explicitServiceFailures());
      assertTrue(service.metrics().fullyReconciled());
    }
  }

  @Test
  void quiesceRejectsNewOffersThenDrainsEveryAcceptedBusinessAndMaintenanceItem() throws Exception {
    Path directory = directory("quiesce-drain");
    BlockingFault fault = new BlockingFault(FaultPoint.BEFORE_LIVE_APPLY, false);
    LocalMatchingService service =
        LocalMatchingService.open(config(directory), new ServiceConfig(3), fault);
    AdmissionResult.Enqueued first = enqueued(service.trySubmit(envelope(1)));
    fault.awaitEntered();
    CheckpointAdmissionResult.Enqueued checkpoint = checkpoint(service.tryCheckpoint());
    AdmissionResult.Enqueued second = enqueued(service.trySubmit(envelope(2)));
    AtomicReference<Throwable> closeFailure = new AtomicReference<>();
    Thread closer =
        Thread.ofPlatform()
            .start(
                () -> {
                  try {
                    service.close();
                  } catch (Throwable failure) {
                    closeFailure.set(failure);
                  }
                });
    awaitState(service, ServiceState.QUIESCING);
    AdmissionResult.Rejected afterQuiesce =
        assertInstanceOf(AdmissionResult.Rejected.class, service.trySubmit(envelope(3)));
    assertEquals(AdmissionRejectionCode.NOT_ACCEPTING, afterQuiesce.code());
    CheckpointAdmissionResult.Rejected checkpointAfterQuiesce =
        assertInstanceOf(CheckpointAdmissionResult.Rejected.class, service.tryCheckpoint());
    assertEquals(AdmissionRejectionCode.NOT_ACCEPTING, checkpointAfterQuiesce.code());

    fault.release();
    closer.join(Duration.ofSeconds(5));
    assertFalse(closer.isAlive());
    assertEquals(null, closeFailure.get());
    durable(await(first.completion()));
    assertInstanceOf(CheckpointCompletion.Completed.class, await(checkpoint.completion()));
    durable(await(second.completion()));
    assertEquals(ServiceState.CLOSED, service.state());
    assertEquals(0, service.metrics().pending());
    assertEquals(0, service.metrics().checkpointPending());
    assertTrue(service.metrics().fullyReconciled());
  }

  @Test
  void runtimeCloseFailureRemainsFailedClosedAndPropagatesToTheCloser() {
    CloseFailingRuntimePort port = new CloseFailingRuntimePort();
    LocalMatchingService service = LocalMatchingService.openForTesting(new ServiceConfig(1), port);

    IOException failure = assertThrows(IOException.class, service::close);

    assertTrue(failure.getMessage().contains("could not close"));
    assertInstanceOf(IOException.class, failure.getCause());
    assertEquals(ServiceState.FAILED_CLOSED, service.state());
    assertTrue(service.failureDetail().contains("injected runtime close failure"));
    assertTrue(port.closeAttempted.get());
    assertThrows(IOException.class, service::close);
  }

  @Test
  void structuralAndPreflightResultsRemainSubmissionCompletionsNotAdmissionRejections()
      throws Exception {
    Path directory = directory("result-passthrough");
    byte[] malformed = envelope(1);
    malformed[malformed.length - 1] ^= 1;
    try (LocalMatchingService service =
        LocalMatchingService.open(config(directory), new ServiceConfig(4))) {
      assertInstanceOf(
          SubmissionResult.StructuralRejected.class,
          submitted(await(enqueued(service.trySubmit(malformed)).completion())));
      byte[] first = envelope(1);
      durable(await(enqueued(service.trySubmit(first)).completion()));
      assertInstanceOf(
          SubmissionResult.DuplicateReplayed.class,
          submitted(await(enqueued(service.trySubmit(first)).completion())));
      assertInstanceOf(
          SubmissionResult.PreflightRejected.class,
          submitted(await(enqueued(service.trySubmit(envelope(3))).completion())));

      ServiceMetricsSnapshot metrics = service.metrics();
      assertEquals(4, metrics.admitted());
      assertEquals(0, metrics.overloaded());
      assertEquals(4, metrics.submissionResultCompletions());
      assertEquals(2, metrics.durableAcknowledgements());
      assertTrue(metrics.fullyReconciled());
    }
  }

  private Path directory(String name) throws IOException {
    return Files.createDirectories(temporaryDirectory.resolve(name));
  }

  private WalConfig config(Path directory) {
    return WalConfig.snapshotDefaults(directory, SHARD);
  }

  private byte[] envelope(long sequence) {
    return codec.encode(
        "service-producer",
        1,
        SHARD,
        sequence,
        new UUID(0x10, sequence),
        new M08Command.Cancel("BTC-USDT", BigInteger.valueOf(sequence)));
  }

  private static AdmissionResult.Enqueued enqueued(AdmissionResult result) {
    return assertInstanceOf(AdmissionResult.Enqueued.class, result);
  }

  private static CheckpointAdmissionResult.Enqueued checkpoint(CheckpointAdmissionResult result) {
    return assertInstanceOf(CheckpointAdmissionResult.Enqueued.class, result);
  }

  private static SubmissionResult submitted(ServiceCompletion completion) {
    return assertInstanceOf(ServiceCompletion.SubmissionCompleted.class, completion).result();
  }

  private static SubmissionResult.NewDurablyApplied durable(ServiceCompletion completion) {
    return assertInstanceOf(SubmissionResult.NewDurablyApplied.class, submitted(completion));
  }

  private static <T> T await(CompletionHandle<T> completion) throws Exception {
    return completion.await(Duration.ofSeconds(5));
  }

  private static void awaitState(LocalMatchingService service, ServiceState expected)
      throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (service.state() != expected && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertEquals(expected, service.state());
  }

  private static class BlockingFault implements FaultInjector {
    private final FaultPoint target;
    private final boolean throwAssertion;
    private final AtomicBoolean armed = new AtomicBoolean(true);
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch released = new CountDownLatch(1);

    private BlockingFault(FaultPoint target, boolean throwAssertion) {
      this.target = target;
      this.throwAssertion = throwAssertion;
    }

    @Override
    public void hit(FaultPoint point) throws IOException {
      if (point != target || !armed.compareAndSet(true, false)) {
        return;
      }
      entered.countDown();
      awaitLatch(released, "fault release");
      if (throwAssertion) {
        throw new AssertionError("injected unexpected worker failure at " + point);
      }
    }

    final void awaitEntered() throws IOException {
      awaitLatch(entered, "fault entry");
    }

    final void release() {
      released.countDown();
    }
  }

  private static final class BlockingIoFault extends BlockingFault {
    private final FaultPoint target;

    private BlockingIoFault(FaultPoint target) {
      super(target, false);
      this.target = target;
    }

    @Override
    public void hit(FaultPoint point) throws IOException {
      if (point == target) {
        super.hit(point);
        throw new IOException("injected I/O failure at " + point);
      }
      super.hit(point);
    }
  }

  private static final class RecordingRuntimePort implements LocalMatchingService.RuntimePort {
    private final SubmissionResult result;
    private volatile byte[] observedEnvelope;

    private RecordingRuntimePort(SubmissionResult result) {
      this.result = result;
    }

    @Override
    public SubmissionResult submit(byte[] canonicalEnvelope) {
      observedEnvelope = canonicalEnvelope;
      return result;
    }

    @Override
    public CheckpointResult checkpoint() {
      return new CheckpointResult(new SnapshotAnchor(1, SHARD, 0, 0), 0);
    }

    @Override
    public RuntimeState state() {
      return RuntimeState.OPEN;
    }

    @Override
    public void close() {}

    private byte[] observedEnvelope() {
      return observedEnvelope;
    }
  }

  private static final class StateThrowingRuntimePort implements LocalMatchingService.RuntimePort {
    private final CountDownLatch submitEntered = new CountDownLatch(1);
    private final CountDownLatch submitReleased = new CountDownLatch(1);

    @Override
    public SubmissionResult submit(byte[] canonicalEnvelope) {
      submitEntered.countDown();
      try {
        awaitLatch(submitReleased, "state-throwing submit release");
      } catch (IOException failure) {
        throw new IllegalStateException(failure);
      }
      return new SubmissionResult.StructuralRejected(
          StructuralRejectionCode.MALFORMED_ENVELOPE, "state-throwing fixture");
    }

    @Override
    public CheckpointResult checkpoint() {
      throw new UnsupportedOperationException("checkpoint is outside this fixture");
    }

    @Override
    public RuntimeState state() {
      throw new AssertionError("injected runtime state observation failure");
    }

    @Override
    public void close() {}

    private void awaitSubmitEntered() throws IOException {
      awaitLatch(submitEntered, "state-throwing submit entry");
    }

    private void releaseSubmit() {
      submitReleased.countDown();
    }
  }

  private static final class BlockingFirstRuntimePort implements LocalMatchingService.RuntimePort {
    private final CountDownLatch firstEntered = new CountDownLatch(1);
    private final CountDownLatch firstReleased = new CountDownLatch(1);
    private int submissions;

    @Override
    public synchronized SubmissionResult submit(byte[] canonicalEnvelope) {
      submissions++;
      if (submissions == 1) {
        firstEntered.countDown();
        try {
          awaitLatch(firstReleased, "callback-free first submission release");
        } catch (IOException failure) {
          throw new IllegalStateException(failure);
        }
      }
      return new SubmissionResult.StructuralRejected(
          StructuralRejectionCode.MALFORMED_ENVELOPE, "callback-free fixture");
    }

    @Override
    public CheckpointResult checkpoint() {
      throw new UnsupportedOperationException("checkpoint is outside this fixture");
    }

    @Override
    public RuntimeState state() {
      return RuntimeState.OPEN;
    }

    @Override
    public void close() {}

    void awaitFirstEntered() throws IOException {
      awaitLatch(firstEntered, "callback-free first submission entry");
    }

    void releaseFirst() {
      firstReleased.countDown();
    }

    synchronized int submissions() {
      return submissions;
    }
  }

  private static final class CloseFailingRuntimePort implements LocalMatchingService.RuntimePort {
    private final AtomicBoolean closeAttempted = new AtomicBoolean();

    @Override
    public SubmissionResult submit(byte[] canonicalEnvelope) {
      return new SubmissionResult.StructuralRejected(
          StructuralRejectionCode.MALFORMED_ENVELOPE, "close-only fixture");
    }

    @Override
    public CheckpointResult checkpoint() {
      return new CheckpointResult(new SnapshotAnchor(1, SHARD, 0, 0), 0);
    }

    @Override
    public RuntimeState state() {
      return RuntimeState.OPEN;
    }

    @Override
    public void close() throws IOException {
      closeAttempted.set(true);
      throw new IOException("injected runtime close failure");
    }
  }

  private static void awaitLatch(CountDownLatch latch, String name) throws IOException {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new IOException("timed out waiting for " + name);
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted waiting for " + name, interrupted);
    }
  }
}
