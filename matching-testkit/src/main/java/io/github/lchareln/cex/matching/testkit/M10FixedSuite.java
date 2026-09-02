package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.local.AdmissionRejectionCode;
import io.github.lchareln.cex.matching.local.AdmissionResult;
import io.github.lchareln.cex.matching.local.CheckpointAdmissionResult;
import io.github.lchareln.cex.matching.local.CheckpointCompletion;
import io.github.lchareln.cex.matching.local.CompletionHandle;
import io.github.lchareln.cex.matching.local.FaultPoint;
import io.github.lchareln.cex.matching.local.LocalMatchingRuntime;
import io.github.lchareln.cex.matching.local.LocalMatchingService;
import io.github.lchareln.cex.matching.local.M10ServiceJudgeProbe;
import io.github.lchareln.cex.matching.local.RecoveryBudget;
import io.github.lchareln.cex.matching.local.ServiceCompletion;
import io.github.lchareln.cex.matching.local.ServiceConfig;
import io.github.lchareln.cex.matching.local.ServiceMetricsSnapshot;
import io.github.lchareln.cex.matching.local.ServiceState;
import io.github.lchareln.cex.matching.local.SubmissionResult;
import io.github.lchareln.cex.matching.local.SubmissionResultVariant;
import io.github.lchareln.cex.matching.local.WalConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Real-service admission scenarios plus deterministic qualification-method scenarios. */
final class M10FixedSuite {
  private static final Duration TIMEOUT = Duration.ofSeconds(5);
  private final M09ScenarioSupport support = new M09ScenarioSupport();

  Result run(Path workingRoot, M10MethodSuite.Result method) {
    M09ScenarioSupport.deleteTree(workingRoot);
    try {
      Files.createDirectories(workingRoot);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot create M10 fixed working root", failure);
    }
    Map<String, ObjectNode> evidence = new LinkedHashMap<>();
    capacity(evidence);
    boundedAndOwned(workingRoot.resolve("bounded-owned"), evidence);
    checkpoint(workingRoot.resolve("checkpoint"), evidence);
    failure(workingRoot.resolve("failure"), evidence);
    quiesce(workingRoot.resolve("quiesce"), evidence);
    methodEvidence(method, evidence);
    loadRecovery(workingRoot.resolve("load-recovery"), method, evidence);
    require(
        evidence.keySet().stream().toList().equals(M10StartCheckRunner.SCENARIO_IDS),
        "M10 fixed scenario order or identity changed");
    ArrayNode results = JsonSupport.MAPPER.createArrayNode();
    evidence.forEach(
        (id, observations) -> {
          ObjectNode scenario = results.addObject();
          scenario.put("id", id);
          scenario.put("status", M10CheckRunner.PASS);
          scenario.put("evidenceMode", evidenceMode(id));
          scenario.set("observations", observations);
        });
    return new Result(results, evidence.size());
  }

  private static void capacity(Map<String, ObjectNode> evidence) {
    int rejected = 0;
    try {
      new ServiceConfig(0);
    } catch (IllegalArgumentException expected) {
      rejected++;
    }
    try {
      new ServiceConfig(-1);
    } catch (IllegalArgumentException expected) {
      rejected++;
    }
    require(
        rejected == 2 && ServiceConfig.qualification().queueCapacity() == 64,
        "capacity constructor accepted a non-positive value or qualification changed");
    evidence.put(
        "CAPACITY_REJECTS_NON_POSITIVE",
        object("negativeAndZeroRejected", rejected, "qualificationCapacity", 64));
  }

  private void boundedAndOwned(Path directory, Map<String, ObjectNode> evidence) {
    prepareDirectory(directory);
    M10ServiceJudgeProbe.Result passThrough =
        M10ServiceJudgeProbe.verifyExactSubmissionResultPassThrough();
    require(
        passThrough.sameSubmissionResultInstance()
            && passThrough.callerBytesOwned()
            && passThrough.submissions() == 1
            && passThrough.variantAccountingReconciles(),
        "same-package runtime port probe did not preserve the exact result/bytes");
    BlockingFault fault = new BlockingFault(false);
    M09ScenarioSupport.CommandStream stream = support.stream("m10-bounded");
    byte[] first = stream.next(M09ScenarioSupport.place(1, "SELL", 101, 1, 11, "CANCEL_TAKER"));
    byte[] second = stream.next(M09ScenarioSupport.place(2, "SELL", 102, 1, 12, "CANCEL_TAKER"));
    byte[] third = stream.next(M09ScenarioSupport.place(3, "SELL", 103, 1, 13, "CANCEL_TAKER"));
    byte[] rejectedBytes =
        stream.next(M09ScenarioSupport.place(4, "SELL", 104, 1, 14, "CANCEL_TAKER"));
    byte[] retryCopy = rejectedBytes.clone();
    AdmissionResult.Enqueued one;
    AdmissionResult.Enqueued two;
    AdmissionResult.Enqueued three;
    AdmissionResult.Rejected rejected;
    ServiceMetricsSnapshot terminal;
    try (LocalMatchingService service =
        LocalMatchingService.open(support.config(directory), new ServiceConfig(2), fault)) {
      one = enqueued(service.trySubmit(first), "first offer");
      fault.awaitEntered();
      two = enqueued(service.trySubmit(second), "second offer");
      Arrays.fill(second, (byte) 0x5a);
      three = enqueued(service.trySubmit(third), "third offer");
      long begin = System.nanoTime();
      AdmissionResult fourth = service.trySubmit(rejectedBytes);
      long elapsed = System.nanoTime() - begin;
      rejected = rejected(fourth, AdmissionRejectionCode.OVERLOADED_BEFORE_WAL, "fourth offer");
      require(elapsed < TimeUnit.SECONDS.toNanos(1), "full trySubmit waited for capacity");
      require(!one.completion().isDone(), "enqueue became an ACK");
      ServiceMetricsSnapshot cut = service.metrics();
      require(
          cut.queueDepth() == 2 && cut.maximumQueueDepth() == 2,
          "bounded queue did not expose the frozen full cut");
      require(
          cut.offersReconcile() && cut.completionsReconcile(), "full-cut metrics do not reconcile");
      fault.release();
      SubmissionResult firstResult = submission(await(one.completion()), "first completion");
      SubmissionResult secondResult = submission(await(two.completion()), "second completion");
      SubmissionResult thirdResult = submission(await(three.completion()), "third completion");
      require(
          firstResult instanceof SubmissionResult.NewDurablyApplied,
          "first result was not durable");
      require(
          secondResult instanceof SubmissionResult.NewDurablyApplied,
          "owned caller bytes were not applied");
      require(
          thirdResult instanceof SubmissionResult.NewDurablyApplied,
          "third result was not durable");
      long firstWal = ((SubmissionResult.NewDurablyApplied) firstResult).position().walSequence();
      long secondWal = ((SubmissionResult.NewDurablyApplied) secondResult).position().walSequence();
      long thirdWal = ((SubmissionResult.NewDurablyApplied) thirdResult).position().walSequence();
      require(
          firstWal < secondWal && secondWal < thirdWal,
          "one owner worker did not preserve FIFO WAL order");
      terminal = service.metrics();
      require(terminal.fullyReconciled(), "terminal business/maintenance metrics do not reconcile");
      require(
          terminal.offers() == 4 && terminal.admitted() == 3 && terminal.overloaded() == 1,
          "terminal offer accounting changed");
      require(
          terminal.pending() == 0 && terminal.submissionResultCompletions() == 3,
          "terminal completion accounting changed");
      require(
          terminal.submissionResultCounts().get(SubmissionResultVariant.NEW_DURABLY_APPLIED) == 3,
          "submission-result variant accounting changed");
    } catch (IOException failure) {
      throw new IllegalStateException("bounded service fixture failed", failure);
    } finally {
      fault.release();
    }

    try (LocalMatchingRuntime recovered = LocalMatchingRuntime.open(support.config(directory))) {
      require(
          recovered.nextWalSequence() == 4,
          "overload rejection appended a WAL record before recovery");
      SubmissionResult retried = recovered.submit(retryCopy);
      require(
          retried instanceof SubmissionResult.NewDurablyApplied,
          "overload rejection bound identity before retry: " + retried);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot reopen bounded fixture", failure);
    }

    evidence.put(
        "TRY_SUBMIT_OWNS_CALLER_BYTES",
        object("callerArrayMutatedAfterEnqueue", true, "ownedEnvelopeApplied", true));
    evidence.put(
        "FULL_QUEUE_REJECTS_IMMEDIATELY",
        object(
            "capacity",
            2,
            "maximumQueueDepth",
            terminal.maximumQueueDepth(),
            "rejection",
            rejected.code().name(),
            "waitedForCapacity",
            false));
    evidence.put(
        "OVERLOAD_REJECTION_PRECEDES_WAL",
        object("walRecordsBeforeRetry", 3, "overloadedAttempts", terminal.overloaded()));
    evidence.put(
        "OVERLOAD_REJECTION_PRESERVES_IDENTITY",
        object("sameEnvelopeRetry", true, "retryResult", "NEW_DURABLY_APPLIED"));
    evidence.put(
        "ENQUEUE_IS_NOT_DURABLE_ACK",
        object("admissionType", "ENQUEUED", "submissionResultAtAdmission", false));
    evidence.put("ONE_WORKER_PRESERVES_FIFO", object("admitted", 3, "strictWalOrder", true));
    evidence.put(
        "SUBMISSION_RESULT_PASSES_THROUGH_UNCHANGED",
        object(
            "realVariantsObserved",
            List.of("NEW_DURABLY_APPLIED"),
            "sealedSubmissionResultGrammarReused",
            true,
            "sameRuntimeResultInstance",
            passThrough.sameSubmissionResultInstance(),
            "syntheticBusinessResultType",
            false));
  }

  private void checkpoint(Path directory, Map<String, ObjectNode> evidence) {
    prepareDirectory(directory);
    WalConfig config =
        new WalConfig(
            directory,
            M09ScenarioSupport.SHARD,
            WalConfig.DEFAULT_MAX_SEGMENT_BYTES,
            WalConfig.DEFAULT_MAX_RECORD_BYTES,
            new RecoveryBudget(1, 1_048_576));
    M09ScenarioSupport.CommandStream stream = support.stream("m10-checkpoint");
    byte[] first = stream.next(M09ScenarioSupport.place(10, "SELL", 110, 1, 10, "CANCEL_TAKER"));
    byte[] second = stream.next(M09ScenarioSupport.place(11, "SELL", 111, 1, 11, "CANCEL_TAKER"));
    byte[] retry = second.clone();
    try (LocalMatchingService service = LocalMatchingService.open(config, new ServiceConfig(4))) {
      SubmissionResult firstResult =
          submission(
              await(enqueued(service.trySubmit(first), "checkpoint first").completion()),
              "checkpoint first");
      require(
          firstResult instanceof SubmissionResult.NewDurablyApplied, "checkpoint prelude failed");
      long logicalScheduled = System.nanoTime();
      SubmissionResult required =
          submission(
              await(enqueued(service.trySubmit(second), "checkpoint required").completion()),
              "checkpoint required");
      require(
          required instanceof SubmissionResult.CheckpointRequired,
          "checkpoint-required result was hidden or changed: " + required);
      CheckpointAdmissionResult.Enqueued checkpoint = checkpointEnqueued(service.tryCheckpoint());
      CheckpointCompletion checkpointCompletion = awaitCheckpoint(checkpoint.completion());
      require(
          checkpointCompletion instanceof CheckpointCompletion.Completed,
          "checkpoint maintenance did not complete");
      SubmissionResult retried =
          submission(
              await(enqueued(service.trySubmit(retry), "checkpoint retry").completion()),
              "checkpoint retry");
      long logicalTerminal = System.nanoTime();
      require(
          retried instanceof SubmissionResult.NewDurablyApplied,
          "same-envelope retry did not apply after checkpoint");
      require(
          logicalTerminal - logicalScheduled > 0, "logical latency did not include maintenance");
      ServiceMetricsSnapshot metrics = service.metrics();
      require(metrics.fullyReconciled(), "checkpoint ledgers did not reconcile");
      require(
          metrics.offers() == 3 && metrics.checkpointOffers() == 1,
          "business attempts and maintenance ledger were conflated");
      evidence.put(
          "CHECKPOINT_RETRY_REUSES_ENVELOPE",
          object(
              "logicalOperations", 2,
              "businessAttempts", 3,
              "checkpointRequiredResults", 1,
              "maintenanceAttempts", 1,
              "retryByteExact", Arrays.equals(second, retry)));
      evidence.put(
          "CHECKPOINT_PAUSE_COUNTS_IN_LATENCY",
          object(
              "latencyOrigin", "ORIGINAL_LOGICAL_SCHEDULED_ARRIVAL",
              "maintenanceInsideLogicalLatency", true,
              "intermediateSubmissionResultRetained", true));
    } catch (IOException failure) {
      throw new IllegalStateException("checkpoint service fixture failed", failure);
    }
  }

  private void failure(Path directory, Map<String, ObjectNode> evidence) {
    prepareDirectory(directory);
    AtomicBoolean failed = new AtomicBoolean();
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    var fault =
        (io.github.lchareln.cex.matching.local.FaultInjector)
            point -> {
              if (point == FaultPoint.BEFORE_LIVE_APPLY && failed.compareAndSet(false, true)) {
                entered.countDown();
                awaitLatch(release, "failure release");
                throw new IOException("m10 injected apply failure");
              }
            };
    M09ScenarioSupport.CommandStream stream = support.stream("m10-failure");
    try (LocalMatchingService service =
        LocalMatchingService.open(support.config(directory), new ServiceConfig(2), fault)) {
      AdmissionResult.Enqueued one =
          enqueued(
              service.trySubmit(
                  stream.next(M09ScenarioSupport.place(20, "SELL", 120, 1, 20, "CANCEL_TAKER"))),
              "failure first");
      awaitLatch(entered, "failure entered");
      AdmissionResult.Enqueued two =
          enqueued(
              service.trySubmit(
                  stream.next(M09ScenarioSupport.place(21, "SELL", 121, 1, 21, "CANCEL_TAKER"))),
              "failure second");
      AdmissionResult.Enqueued three =
          enqueued(
              service.trySubmit(
                  stream.next(M09ScenarioSupport.place(22, "SELL", 122, 1, 22, "CANCEL_TAKER"))),
              "failure third");
      release.countDown();
      SubmissionResult first = submission(await(one.completion()), "failure first");
      require(
          first instanceof SubmissionResult.DurabilityUnknown,
          "failed runtime result was upgraded or hidden");
      require(
          await(two.completion()) instanceof ServiceCompletion.ExplicitFailure,
          "accepted pending item two was silently dropped");
      require(
          await(three.completion()) instanceof ServiceCompletion.ExplicitFailure,
          "accepted pending item three was silently dropped");
      AdmissionResult.Rejected after =
          rejected(
              service.trySubmit(
                  stream.next(M09ScenarioSupport.place(23, "SELL", 123, 1, 23, "CANCEL_TAKER"))),
              AdmissionRejectionCode.SERVICE_FAILED_CLOSED,
              "post-failure offer");
      ServiceMetricsSnapshot metrics = service.metrics();
      require(
          service.state() == ServiceState.FAILED_CLOSED, "worker failure did not close admission");
      require(
          metrics.pending() == 0 && metrics.explicitServiceFailures() == 2,
          "accepted pending failures did not reconcile");
      require(metrics.fullyReconciled(), "failure-cut metrics did not reconcile");
      evidence.put(
          "WORKER_FAILURE_CLOSES_ADMISSION",
          object("state", service.state().name(), "newOfferRejection", after.code().name()));
      evidence.put(
          "ACCEPTED_PENDING_COMMANDS_FAIL_EXPLICITLY",
          object(
              "pendingAccepted",
              2,
              "explicitFailures",
              metrics.explicitServiceFailures(),
              "terminalPending",
              metrics.pending()));
    } catch (IOException failureException) {
      throw new IllegalStateException("failure service fixture failed", failureException);
    } finally {
      release.countDown();
    }
  }

  private void quiesce(Path directory, Map<String, ObjectNode> evidence) {
    prepareDirectory(directory);
    BlockingFault fault = new BlockingFault(false);
    M09ScenarioSupport.CommandStream stream = support.stream("m10-quiesce");
    LocalMatchingService service;
    try {
      service = LocalMatchingService.open(support.config(directory), new ServiceConfig(2), fault);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot open quiesce service", failure);
    }
    try {
      AdmissionResult.Enqueued first =
          enqueued(
              service.trySubmit(
                  stream.next(M09ScenarioSupport.place(30, "SELL", 130, 1, 30, "CANCEL_TAKER"))),
              "quiesce first");
      fault.awaitEntered();
      AdmissionResult.Enqueued second =
          enqueued(
              service.trySubmit(
                  stream.next(M09ScenarioSupport.place(31, "SELL", 131, 1, 31, "CANCEL_TAKER"))),
              "quiesce second");
      CompletableFuture<Void> closing = CompletableFuture.runAsync(() -> closeUnchecked(service));
      awaitState(service, ServiceState.QUIESCING);
      AdmissionResult.Rejected after =
          rejected(
              service.trySubmit(
                  stream.next(M09ScenarioSupport.place(32, "SELL", 132, 1, 32, "CANCEL_TAKER"))),
              AdmissionRejectionCode.NOT_ACCEPTING,
              "quiescing offer");
      fault.release();
      require(
          submission(await(first.completion()), "quiesce first")
              instanceof SubmissionResult.NewDurablyApplied,
          "first admitted item did not drain");
      require(
          submission(await(second.completion()), "quiesce second")
              instanceof SubmissionResult.NewDurablyApplied,
          "second admitted item did not drain");
      awaitFuture(closing, "service close");
      ServiceMetricsSnapshot terminal = service.metrics();
      require(
          terminal.state() == ServiceState.CLOSED && terminal.pending() == 0,
          "quiesce did not reach a drained terminal cut");
      require(terminal.fullyReconciled(), "quiesce accounting did not reconcile");
      evidence.put(
          "QUIESCE_REJECTS_NEW_OFFERS",
          object("observedState", "QUIESCING", "rejection", after.code().name()));
      evidence.put(
          "QUIESCE_DRAINS_ACCEPTED_OFFERS",
          object("acceptedBeforeQuiesce", 2, "completed", 2, "terminalPending", 0));
      evidence.put(
          "OFFER_AND_COMPLETION_TOTALS_RECONCILE",
          object(
              "offers", terminal.offers(),
              "admitted", terminal.admitted(),
              "closedOrInvalid", terminal.closedOrInvalid(),
              "offerEquation", terminal.offersReconcile(),
              "completionEquation", terminal.completionsReconcile(),
              "variantEquation", terminal.submissionResultVariantsReconcile()));
    } finally {
      fault.release();
      closeUnchecked(service);
    }
  }

  private static void methodEvidence(
      M10MethodSuite.Result method, Map<String, ObjectNode> evidence) {
    evidence.put(
        "OPEN_LOOP_USES_SCHEDULED_ARRIVAL",
        object(
            "generator", "OPEN_LOOP_SCHEDULED_ARRIVALS",
            "latencyOrigin", "SCHEDULED_ARRIVAL",
            "rawScheduledArrivals", method.scheduledArrivals()));
    evidence.put(
        "RAW_SAMPLES_RECONCILE_PERCENTILES",
        object(
            "rankRule",
            "NEAREST_RANK_CEIL_Q_TIMES_N",
            "publishedPercentilesRecomputed",
            true,
            "rawCompletionSamples",
            method.completionSamples()));
    evidence.put(
        "ENVIRONMENT_AND_MICRO_RESULTS_ARE_SEPARATE",
        object(
            "microHarness", "JMH_SAMPLE_TIME",
            "microScope", "DIAGNOSTIC_ONLY",
            "endToEndScope", "METHOD_SMOKE_ONLY",
            "combinedScore", false,
            "environmentRequiredForRelease", true));
    evidence.put(
        "KNEE_AND_ABOVE_KNEE_ARE_EXPLICIT",
        object(
            "sweepKnee", method.knee(),
            "qopCandidate", method.qopCandidate(),
            "qop", method.qop(),
            "qopSelection", "HIGHEST_UNSATURATED_MEASURED_RATE_AT_OR_BELOW_CANDIDATE",
            "aboveKneeRetained", true,
            "resourceDimensions", List.of("ALLOCATION", "GC", "CPU", "MEMORY", "QUEUE_DEPTH")));
    evidence.put(
        "LOAD_RECOVERY_REMAINS_EXACT",
        object(
            "methodModelExact", method.recovery().path("exact").booleanValue(),
            "productionRuntimeClaimFromMethodModel", false,
            "releaseBundleRequiresRealLoadRecovery", true,
            "systemErrorNeverPass", true));
  }

  private void loadRecovery(
      Path directory, M10MethodSuite.Result method, Map<String, ObjectNode> evidence) {
    prepareDirectory(directory);
    M09ScenarioSupport.CommandStream stream = support.stream("m10-load-recovery");
    String liveDigest = "";
    long terminalWal = 0;
    try (LocalMatchingService service =
        LocalMatchingService.open(support.config(directory), new ServiceConfig(8))) {
      for (int index = 0; index < 5; index++) {
        byte[] envelope =
            stream.next(
                M09ScenarioSupport.place(
                    100 + index, "SELL", 200 + index, 1, 100 + index, "CANCEL_TAKER"));
        SubmissionResult result =
            submission(
                await(enqueued(service.trySubmit(envelope), "load recovery offer").completion()),
                "load recovery completion");
        require(
            result instanceof SubmissionResult.NewDurablyApplied,
            "load recovery fixture did not durably apply");
        SubmissionResult.NewDurablyApplied applied = (SubmissionResult.NewDurablyApplied) result;
        liveDigest = applied.result().semanticStateDigest();
        terminalWal = applied.position().walSequence();
      }
      require(service.metrics().fullyReconciled(), "load service metrics do not reconcile");
    } catch (IOException failure) {
      throw new IllegalStateException("load service fixture failed", failure);
    }
    try (LocalMatchingRuntime recovered = LocalMatchingRuntime.open(support.config(directory))) {
      require(
          liveDigest.equals(recovered.semanticStateDigest()),
          "load-then-recovery semantic digest changed");
      require(
          recovered.nextWalSequence() == terminalWal + 1,
          "load-then-recovery WAL boundary changed");
      evidence.put(
          "LOAD_RECOVERY_REMAINS_EXACT",
          object(
              "realRuntimeCommands",
              5,
              "liveDigest",
              liveDigest,
              "recoveredDigest",
              recovered.semanticStateDigest(),
              "exact",
              true,
              "methodModelExact",
              method.recovery().path("exact").booleanValue(),
              "releaseBundleRequiresRealLoadRecovery",
              true,
              "systemErrorNeverPass",
              true));
    } catch (IOException failure) {
      throw new IllegalStateException("load recovery reopen failed", failure);
    }
  }

  private static AdmissionResult.Enqueued enqueued(AdmissionResult result, String context) {
    if (result instanceof AdmissionResult.Enqueued enqueued) return enqueued;
    throw new M10SemanticFailure(context + " was not enqueued: " + result);
  }

  private static AdmissionResult.Rejected rejected(
      AdmissionResult result, AdmissionRejectionCode code, String context) {
    if (result instanceof AdmissionResult.Rejected rejected && rejected.code() == code)
      return rejected;
    throw new M10SemanticFailure(context + " had wrong rejection: " + result);
  }

  private static CheckpointAdmissionResult.Enqueued checkpointEnqueued(
      CheckpointAdmissionResult result) {
    if (result instanceof CheckpointAdmissionResult.Enqueued enqueued) return enqueued;
    throw new M10SemanticFailure("checkpoint was not enqueued: " + result);
  }

  private static ServiceCompletion await(CompletionHandle<ServiceCompletion> completion) {
    try {
      return completion.await(TIMEOUT);
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("completion wait interrupted", failure);
    } catch (TimeoutException failure) {
      throw new IllegalStateException("completion wait failed", failure);
    }
  }

  private static CheckpointCompletion awaitCheckpoint(
      CompletionHandle<CheckpointCompletion> completion) {
    try {
      return completion.await(TIMEOUT);
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("checkpoint wait interrupted", failure);
    } catch (TimeoutException failure) {
      throw new IllegalStateException("checkpoint wait failed", failure);
    }
  }

  private static SubmissionResult submission(ServiceCompletion completion, String context) {
    if (completion instanceof ServiceCompletion.SubmissionCompleted completed)
      return completed.result();
    throw new M10SemanticFailure(context + " was an explicit failure: " + completion);
  }

  private static void awaitFuture(CompletableFuture<?> future, String context) {
    try {
      future.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(context + " interrupted", failure);
    } catch (ExecutionException | TimeoutException failure) {
      throw new IllegalStateException(context + " failed", failure);
    }
  }

  private static void awaitState(LocalMatchingService service, ServiceState expected) {
    long deadline = System.nanoTime() + TIMEOUT.toNanos();
    while (service.state() != expected && System.nanoTime() < deadline) {
      LockSupport.parkNanos(100_000L);
    }
    require(service.state() == expected, "service did not enter " + expected);
  }

  private static void closeUnchecked(LocalMatchingService service) {
    try {
      service.close();
    } catch (IOException failure) {
      throw new IllegalStateException("cannot close local matching service", failure);
    }
  }

  private static void prepareDirectory(Path directory) {
    try {
      Files.createDirectories(directory);
    } catch (IOException failure) {
      throw new IllegalStateException(
          "cannot create fixed runtime directory " + directory, failure);
    }
  }

  private static void awaitLatch(CountDownLatch latch, String context) throws IOException {
    try {
      if (!latch.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
        throw new IOException(context + " timed out");
      }
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IOException(context + " interrupted", failure);
    }
  }

  private static ObjectNode object(Object... values) {
    if ((values.length & 1) != 0) throw new IllegalArgumentException("key/value pairs required");
    ObjectNode node = JsonSupport.MAPPER.createObjectNode();
    for (int index = 0; index < values.length; index += 2) {
      String key = (String) values[index];
      Object value = values[index + 1];
      switch (value) {
        case String string -> node.put(key, string);
        case Integer integer -> node.put(key, integer);
        case Long number -> node.put(key, number);
        case Boolean bool -> node.put(key, bool);
        case List<?> list -> {
          ArrayNode array = node.putArray(key);
          list.forEach(item -> array.add(String.valueOf(item)));
        }
        default -> throw new IllegalArgumentException("unsupported observation type " + value);
      }
    }
    return node;
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new M10SemanticFailure(message);
  }

  private static String evidenceMode(String id) {
    int index = M10StartCheckRunner.SCENARIO_IDS.indexOf(id);
    if (index < 0) throw new IllegalArgumentException("unknown fixed scenario " + id);
    if (index < 15) return "REAL_LOCAL_MATCHING_SERVICE";
    if (index < 19) return "DETERMINISTIC_METHOD_MODEL";
    return "REAL_LOCAL_RUNTIME_PLUS_METHOD_MODEL";
  }

  record Result(ArrayNode scenarios, int passed) {}

  private static final class BlockingFault
      implements io.github.lchareln.cex.matching.local.FaultInjector {
    private final AtomicBoolean blocked;
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    BlockingFault(boolean initiallyBlocked) {
      blocked = new AtomicBoolean(initiallyBlocked);
    }

    @Override
    public void hit(FaultPoint point) throws IOException {
      if (point == FaultPoint.BEFORE_LIVE_APPLY && blocked.compareAndSet(false, true)) {
        entered.countDown();
        awaitLatch(release, "blocked apply release");
      }
    }

    void awaitEntered() {
      try {
        awaitLatch(entered, "blocked apply entered");
      } catch (IOException failure) {
        throw new IllegalStateException(failure);
      }
    }

    void release() {
      release.countDown();
    }
  }
}
