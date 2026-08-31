package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.CancelOrderInput;
import io.github.lchareln.cex.matching.ExecutionBatch;
import io.github.lchareln.cex.matching.PlaceLimitOrderInput;
import io.github.lchareln.cex.matching.PlaceLimitOrderRequest;
import io.github.lchareln.cex.matching.SingleInstrumentMatchingEngine;
import io.github.lchareln.cex.matching.local.CanonicalResult;
import io.github.lchareln.cex.matching.local.FaultInjector;
import io.github.lchareln.cex.matching.local.FaultPoint;
import io.github.lchareln.cex.matching.local.LocalMatchingRuntime;
import io.github.lchareln.cex.matching.local.M08Command;
import io.github.lchareln.cex.matching.local.PreflightRejectionCode;
import io.github.lchareln.cex.matching.local.StructuralRejectionCode;
import io.github.lchareln.cex.matching.local.SubmissionResult;
import io.github.lchareln.cex.matching.local.WalConfig;
import io.github.lchareln.cex.matching.local.WalPosition;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Deterministic 96 by 48 runtime suite with an independent no-I/O identity model. */
final class M08GeneratedSuite {
  private static final long SHARD = 5808;
  private static final int MIN_MAX_RECORD_BYTES = 1_048_608;
  private static final long ROLLOVER_SEGMENT_BYTES = 1_100_000;
  private static final String LARGE_FIELD = "x".repeat(12_000);
  private static final String PROFILE_PATH =
      "matching-testkit/src/test/resources/m08/fixtures/property-suite-v1.json";
  private static final String PROFILE_SCHEMA_PATH = "schemas/matching.m08.generator.v1.schema.json";

  Result run(Path workingRoot) {
    OperationProfile profile = OperationProfile.load(repositoryRoot(workingRoot));
    Path root = workingRoot.toAbsolutePath().normalize();
    deleteTree(root);
    try {
      Files.createDirectories(root);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot create M08 generated working root", failure);
    }
    StringBuilder canonical = new StringBuilder("M08H1\n");
    Metrics metrics = new Metrics(profile);
    SplitMix64 random = new SplitMix64(5808L);
    try {
      for (int history = 0; history < M08StartCheckRunner.HISTORIES; history++) {
        Path directory = root.resolve("history-%03d".formatted(history));
        provision(directory);
        switch (history % 4) {
          case 0 -> runCanonicalLane(history, directory, random, profile, canonical, metrics);
          case 1 -> runIdentityLane(history, directory, random, profile, canonical, metrics);
          case 2 -> runFaultLane(history, directory, random, profile, canonical, metrics);
          case 3 -> runRolloverLane(history, directory, random, profile, canonical, metrics);
          default -> throw new IllegalStateException("unreachable lane");
        }
        deleteTree(directory);
        metrics.histories++;
      }
      require(metrics.histories == 96, "M08 generated history count changed");
      require(metrics.operations == 4_608, "M08 generated operation count changed");
      require(metrics.comparisons == 4_608, "M08 generated comparison count changed");
      require(metrics.ledgerChecks == 4_608, "M08 generated ledger count changed");
      require(metrics.faultWindows >= 288, "M08 generated forced-record window count changed");
      require(metrics.restarts >= 432, "M08 generated restart count is incomplete");
      require(metrics.rollovers >= 24, "M08 generated rollover lane never rolled");
      require(
          metrics.invalidEnvelopes
              == M08StartCheckRunner.TOTAL_GENERATED_OPERATIONS / profile.invalidEnvelopeOneIn(),
          "M08 invalid-envelope denominator was not consumed across the generated corpus");
      require(
          metrics.businessRejections
              == M08StartCheckRunner.TOTAL_GENERATED_OPERATIONS / profile.businessRejectionOneIn(),
          "M08 business-rejection denominator was not consumed across the generated corpus");
      for (OperationKind kind : OperationKind.values()) {
        require(
            metrics.selectedOperations.getOrDefault(kind, 0) > 0,
            "M08 weighted operation was never selected: " + kind);
      }
      require(
          metrics.ledgerAppends == metrics.ledgerRecordForces
              && metrics.ledgerRecordForces == metrics.ledgerApplies,
          "third ledger append/force/apply counts diverged");
      require(
          metrics.ledgerAppends > 0
              && metrics.ledgerDirectoryForces >= metrics.histories
              && metrics.restartLedgerChecks > 0,
          "third ledger did not exercise durability boundaries");
      byte[] bytes = canonical.toString().getBytes(StandardCharsets.UTF_8);
      return new Result(
          metrics.histories,
          metrics.operations,
          metrics.comparisons,
          metrics.ledgerChecks,
          metrics.ledgerAppends,
          metrics.ledgerRecordForces,
          metrics.ledgerApplies,
          metrics.ledgerDirectoryForces,
          metrics.restartLedgerChecks,
          metrics.restarts,
          metrics.faultWindows,
          metrics.rollovers,
          metrics.invalidEnvelopes,
          metrics.businessRejections,
          operationCounts(metrics),
          bytes,
          Hashing.sha256Hex(bytes));
    } finally {
      deleteTree(root);
    }
  }

  private void runCanonicalLane(
      int history,
      Path directory,
      SplitMix64 random,
      OperationProfile profile,
      StringBuilder canonical,
      Metrics metrics) {
    ReferenceModel reference = new ReferenceModel();
    String producer = "canonical-" + history;
    WalConfig config =
        new WalConfig(directory, SHARD, ROLLOVER_SEGMENT_BYTES, MIN_MAX_RECORD_BYTES);
    DurabilityLedger ledger = new DurabilityLedger(config.maxSegmentBytes());
    LocalMatchingRuntime runtime = null;
    long sequence = 1;
    Planned latest = null;
    try {
      runtime = LocalMatchingRuntime.open(config);
      ledger.verifyRestart(runtime, metrics);
      for (int operation = 0; operation < 48; operation++) {
        if (runtime == null) {
          runtime = LocalMatchingRuntime.open(config);
          metrics.restarts++;
          ledger.verifyRestart(runtime, metrics);
        }
        OperationKind selected = profile.select(random.nextLong());
        boolean businessRejection = profile.isBusinessRejection(history, operation);
        OperationKind kind = latest == null || businessRejection ? OperationKind.SUBMIT : selected;
        metrics.selected(kind);
        switch (kind) {
          case SUBMIT -> {
            M08Command command = generatedCommand(random, businessRejection, false);
            Planned planned = planned(producer, 1, sequence++, history, operation, command);
            execute(
                runtime,
                reference,
                ledger,
                planned,
                history,
                operation,
                "CANONICAL_AND_BUSINESS:SUBMIT",
                canonical,
                metrics);
            latest = planned;
          }
          case DUPLICATE -> {
            require(latest != null, "canonical profile selected duplicate without a binding");
            execute(
                runtime,
                reference,
                ledger,
                latest,
                history,
                operation,
                "CANONICAL_AND_BUSINESS:DUPLICATE",
                canonical,
                metrics);
          }
          case CONFLICT -> {
            require(latest != null, "canonical profile selected conflict without a binding");
            Planned conflict =
                new Planned(
                    latest.producer(),
                    latest.epoch(),
                    latest.sequence(),
                    latest.commandId(),
                    differentCancel(latest.command(), random));
            execute(
                runtime,
                reference,
                ledger,
                conflict,
                history,
                operation,
                "CANONICAL_AND_BUSINESS:CONFLICT",
                canonical,
                metrics);
          }
          case RESTART -> {
            close(runtime);
            runtime = LocalMatchingRuntime.open(config);
            metrics.restarts++;
            ledger.verifyRestart(runtime, metrics);
            require(latest != null, "canonical profile selected restart without a binding");
            execute(
                runtime,
                reference,
                ledger,
                latest,
                history,
                operation,
                "CANONICAL_AND_BUSINESS:RESTART",
                canonical,
                metrics);
          }
          case ROLLOVER -> {
            M08Command command = generatedCommand(random, businessRejection, true);
            Planned planned = planned(producer, 1, sequence++, history, operation, command);
            execute(
                runtime,
                reference,
                ledger,
                planned,
                history,
                operation,
                "CANONICAL_AND_BUSINESS:ROLLOVER",
                canonical,
                metrics);
            latest = planned;
          }
          case FAULT -> {
            close(runtime);
            runtime =
                LocalMatchingRuntime.open(config, new OneShotFault(FaultPoint.AFTER_RECORD_FORCE));
            metrics.restarts++;
            ledger.verifyRestart(runtime, metrics);
            M08Command command = generatedCommand(random, businessRejection, false);
            Planned planned = planned(producer, 1, sequence++, history, operation, command);
            executeForcedUnknown(
                runtime, reference, ledger, planned, history, operation, canonical, metrics);
            latest = planned;
            close(runtime);
            runtime = null;
          }
        }
      }
      close(runtime);
      runtime = null;
      int segments = countFinalSegments(directory);
      metrics.rollovers += Math.max(0, segments - 1);
      ledger.contribute(metrics);
    } catch (IOException failure) {
      throw new IllegalStateException("M08 canonical generated lane failed", failure);
    } finally {
      close(runtime);
    }
  }

  private void runIdentityLane(
      int history,
      Path directory,
      SplitMix64 random,
      OperationProfile profile,
      StringBuilder canonical,
      Metrics metrics) {
    ReferenceModel reference = new ReferenceModel();
    WalConfig config = WalConfig.defaults(directory, SHARD);
    DurabilityLedger ledger = new DurabilityLedger(config.maxSegmentBytes());
    try (LocalMatchingRuntime runtime = LocalMatchingRuntime.open(config)) {
      ledger.verifyRestart(runtime, metrics);
      for (int block = 0; block < 4; block++) {
        String producer = "identity-" + history + "-" + block;
        int base = block * 12;
        M08Command command1 = cancel(random);
        Planned first = planned(producer, 1, 1, history, base, command1);
        execute(
            runtime,
            reference,
            ledger,
            first,
            history,
            base,
            "IDENTITY_SLOT_AND_EPOCH",
            canonical,
            metrics);
        execute(
            runtime,
            reference,
            ledger,
            first,
            history,
            base + 1,
            "IDENTITY_SLOT_AND_EPOCH",
            canonical,
            metrics);
        Planned payloadConflict =
            new Planned(
                first.producer(),
                first.epoch(),
                first.sequence(),
                first.commandId(),
                cancel(random));
        execute(
            runtime,
            reference,
            ledger,
            payloadConflict,
            history,
            base + 2,
            "IDENTITY_SLOT_AND_EPOCH",
            canonical,
            metrics);
        Planned slotConflict = planned(producer, 1, 1, history, base + 3, command1);
        execute(
            runtime,
            reference,
            ledger,
            slotConflict,
            history,
            base + 3,
            "IDENTITY_SLOT_AND_EPOCH",
            canonical,
            metrics);
        Planned idSlotConflict = new Planned(producer, 1, 2, first.commandId(), command1);
        execute(
            runtime,
            reference,
            ledger,
            idSlotConflict,
            history,
            base + 4,
            "IDENTITY_SLOT_AND_EPOCH",
            canonical,
            metrics);
        Planned gap = planned(producer, 1, 3, history, base + 5, cancel(random));
        execute(
            runtime,
            reference,
            ledger,
            gap,
            history,
            base + 5,
            "IDENTITY_SLOT_AND_EPOCH",
            canonical,
            metrics);
        Planned second = planned(producer, 1, 2, history, base + 6, cancel(random));
        execute(
            runtime,
            reference,
            ledger,
            second,
            history,
            base + 6,
            "IDENTITY_SLOT_AND_EPOCH",
            canonical,
            metrics);
        execute(
            runtime,
            reference,
            ledger,
            first,
            history,
            base + 7,
            "IDENTITY_SLOT_AND_EPOCH",
            canonical,
            metrics);
        Planned boundStaleConflict = planned(producer, 1, 1, history, base + 8, command1);
        execute(
            runtime,
            reference,
            ledger,
            boundStaleConflict,
            history,
            base + 8,
            "IDENTITY_SLOT_AND_EPOCH",
            canonical,
            metrics);
        Planned epochMustStart = planned(producer, 2, 2, history, base + 9, cancel(random));
        execute(
            runtime,
            reference,
            ledger,
            epochMustStart,
            history,
            base + 9,
            "IDENTITY_SLOT_AND_EPOCH",
            canonical,
            metrics);
        Planned epochTwo = planned(producer, 2, 1, history, base + 10, cancel(random));
        execute(
            runtime,
            reference,
            ledger,
            epochTwo,
            history,
            base + 10,
            "IDENTITY_SLOT_AND_EPOCH",
            canonical,
            metrics);
        Planned fenced = planned(producer, 1, 3, history, base + 11, cancel(random));
        execute(
            runtime,
            reference,
            ledger,
            fenced,
            history,
            base + 11,
            "IDENTITY_SLOT_AND_EPOCH",
            canonical,
            metrics);
      }
      ledger.contribute(metrics);
    } catch (IOException failure) {
      throw new IllegalStateException("M08 identity generated lane failed", failure);
    }
  }

  private void runFaultLane(
      int history,
      Path directory,
      SplitMix64 random,
      OperationProfile profile,
      StringBuilder canonical,
      Metrics metrics) {
    ReferenceModel reference = new ReferenceModel();
    WalConfig config = WalConfig.defaults(directory, SHARD);
    DurabilityLedger ledger = new DurabilityLedger(config.maxSegmentBytes());
    LocalMatchingRuntime runtime = null;
    String producer = "fault-" + history;
    long sequence = 1;
    Planned pending = null;
    Planned latest = null;
    try {
      for (int operation = 0; operation < 48; operation++) {
        int phase = operation % 4;
        if (phase == 0) {
          close(runtime);
          runtime =
              LocalMatchingRuntime.open(config, new OneShotFault(FaultPoint.AFTER_RECORD_FORCE));
          metrics.restarts++;
          ledger.verifyRestart(runtime, metrics);
          pending = planned(producer, 1, sequence++, history, operation, cancel(random));
          executeForcedUnknown(
              runtime, reference, ledger, pending, history, operation, canonical, metrics);
          close(runtime);
          runtime = null;
        } else if (phase == 1) {
          runtime = LocalMatchingRuntime.open(config);
          metrics.restarts++;
          ledger.verifyRestart(runtime, metrics);
          execute(
              runtime,
              reference,
              ledger,
              Objects.requireNonNull(pending),
              history,
              operation,
              "ACK_AND_FAIL_CLOSED",
              canonical,
              metrics);
        } else if (phase == 2) {
          latest = planned(producer, 1, sequence++, history, operation, cancel(random));
          execute(
              runtime,
              reference,
              ledger,
              latest,
              history,
              operation,
              "ACK_AND_FAIL_CLOSED",
              canonical,
              metrics);
        } else {
          close(runtime);
          runtime = LocalMatchingRuntime.open(config);
          metrics.restarts++;
          ledger.verifyRestart(runtime, metrics);
          execute(
              runtime,
              reference,
              ledger,
              Objects.requireNonNull(latest),
              history,
              operation,
              "ACK_AND_FAIL_CLOSED",
              canonical,
              metrics);
        }
      }
      ledger.contribute(metrics);
    } catch (IOException failure) {
      throw new IllegalStateException("M08 fault generated lane failed", failure);
    } finally {
      close(runtime);
    }
  }

  private void runRolloverLane(
      int history,
      Path directory,
      SplitMix64 random,
      OperationProfile profile,
      StringBuilder canonical,
      Metrics metrics) {
    ReferenceModel reference = new ReferenceModel();
    WalConfig config =
        new WalConfig(directory, SHARD, ROLLOVER_SEGMENT_BYTES, MIN_MAX_RECORD_BYTES);
    DurabilityLedger ledger = new DurabilityLedger(config.maxSegmentBytes());
    LocalMatchingRuntime runtime = null;
    String producer = "rollover-" + history;
    try {
      for (int operation = 0; operation < 48; operation++) {
        if (operation % 8 == 0) {
          close(runtime);
          runtime = LocalMatchingRuntime.open(config);
          metrics.restarts++;
          ledger.verifyRestart(runtime, metrics);
        }
        M08Command command =
            generatedCommand(random, profile.isBusinessRejection(history, operation), true);
        Planned planned = planned(producer, 1, operation + 1L, history, operation, command);
        execute(
            runtime,
            reference,
            ledger,
            planned,
            history,
            operation,
            "ROLLOVER_AND_RECOVERY",
            canonical,
            metrics);
      }
      close(runtime);
      runtime = null;
      int segments = countFinalSegments(directory);
      require(segments >= 2, "M08 rollover lane did not create multiple final segments");
      metrics.rollovers += segments - 1;
      try (LocalMatchingRuntime recovered = LocalMatchingRuntime.open(config)) {
        metrics.restarts++;
        ledger.verifyRestart(recovered, metrics);
        require(recovered.nextWalSequence() == 49, "M08 rollover recovery lost a WAL position");
      }
      ledger.contribute(metrics);
    } catch (IOException failure) {
      throw new IllegalStateException("M08 rollover generated lane failed", failure);
    } finally {
      close(runtime);
    }
  }

  private void execute(
      LocalMatchingRuntime runtime,
      ReferenceModel reference,
      DurabilityLedger ledger,
      Planned planned,
      int history,
      int operation,
      String lane,
      StringBuilder canonical,
      Metrics metrics) {
    if (metrics.profile.isBusinessRejection(history, operation)
        && (lane.equals("IDENTITY_SLOT_AND_EPOCH") || lane.equals("ACK_AND_FAIL_CLOSED"))) {
      planned = businessRejectionProbe(history, operation);
    }
    maybeProbeInvalidEnvelope(runtime, planned, history, operation, canonical, metrics);
    ReferenceDecision expected = reference.preflight(planned);
    LedgerDecision durabilityExpected = ledger.preflight(planned);
    require(
        durabilityExpected.matches(expected),
        "independent identity model and durability ledger disagreed before production");
    byte[] envelope = IndependentEncoding.envelope(planned);
    String before = runtime.semanticStateDigest();
    SubmissionResult actual = runtime.submit(envelope);
    String after = runtime.semanticStateDigest();
    if (expected.kind() == Kind.NEW) {
      SubmissionResult.NewDurablyApplied applied =
          requireType(
              actual, SubmissionResult.NewDurablyApplied.class, "expected durable new result");
      DurableBinding predicted = ledger.commitNew(planned, envelope);
      predicted.verify(applied.position(), applied.result());
      reference.commit(planned);
      require(!before.equals(after), "new M08 application did not advance semantic state");
      require(
          predicted.result().semanticDigest().equals(after),
          "independent transcript digest disagreed after new apply");
    } else if (expected.kind() == Kind.DUPLICATE) {
      SubmissionResult.DuplicateReplayed duplicate =
          requireType(
              actual, SubmissionResult.DuplicateReplayed.class, "expected duplicate replay");
      DurableBinding binding = durabilityExpected.binding();
      binding.verify(duplicate.originalPosition(), duplicate.originalResult());
      require(before.equals(after), "duplicate replay changed semantic state");
      require(
          ledger.semanticDigest().equals(after),
          "duplicate restart transcript changed independently predicted state");
    } else {
      SubmissionResult.PreflightRejected rejected =
          requireType(
              actual, SubmissionResult.PreflightRejected.class, "expected preflight rejection");
      require(expected.code() == rejected.code(), "M08 reference/production rejection disagreed");
      require(
          durabilityExpected.code() == rejected.code(),
          "durability ledger/production rejection disagreed");
      require(before.equals(after), "preflight rejection changed semantic state");
      require(ledger.semanticDigest().equals(after), "preflight changed transcript commitment");
    }
    verifyBusinessRejectionProfile(actual, history, operation, metrics);
    appendLine(canonical, history, operation, lane, expected.label(), actual, runtime);
    metrics.operations++;
    metrics.comparisons++;
    metrics.ledgerChecks += ledger.completeOperationCheck();
  }

  private void executeForcedUnknown(
      LocalMatchingRuntime runtime,
      ReferenceModel reference,
      DurabilityLedger ledger,
      Planned planned,
      int history,
      int operation,
      StringBuilder canonical,
      Metrics metrics) {
    maybeProbeInvalidEnvelope(runtime, planned, history, operation, canonical, metrics);
    ReferenceDecision expected = reference.preflight(planned);
    require(expected.kind() == Kind.NEW, "fault lane must submit a new identity");
    LedgerDecision durabilityExpected = ledger.preflight(planned);
    require(durabilityExpected.kind() == Kind.NEW, "durability ledger rejected fault lane new");
    byte[] envelope = IndependentEncoding.envelope(planned);
    SubmissionResult.DurabilityUnknown unknown =
        requireType(
            runtime.submit(envelope),
            SubmissionResult.DurabilityUnknown.class,
            "forced-record window returned an ACK");
    WalPosition position = unknown.attemptedPosition().orElseThrow();
    DurableBinding predicted = ledger.commitDurableUnknown(planned, envelope);
    predicted.position().verify(position);
    reference.commit(planned);
    require("APPEND_OR_FORCE".equals(unknown.stage()), "fault window classification changed");
    require(runtime.state().name().equals("FAILED_CLOSED"), "faulted runtime did not fail closed");
    appendLine(
        canonical, history, operation, "ACK_AND_FAIL_CLOSED", "DURABLE_UNKNOWN", unknown, runtime);
    metrics.operations++;
    metrics.comparisons++;
    metrics.ledgerChecks += ledger.completeOperationCheck();
    metrics.faultWindows++;
  }

  private static void verifyBusinessRejectionProfile(
      SubmissionResult actual, int history, int operation, Metrics metrics) {
    if (!metrics.profile.isBusinessRejection(history, operation)) {
      return;
    }
    CanonicalResult result =
        switch (actual) {
          case SubmissionResult.NewDurablyApplied value -> value.result();
          case SubmissionResult.DuplicateReplayed value -> value.originalResult();
          default ->
              throw new IllegalStateException(
                  "profile business-rejection witness was not a durable result: " + actual);
        };
    require(
        result.events().stream().anyMatch(event -> event.contains("Rejected")),
        "profile business-rejection witness did not contain a rejected event");
    metrics.businessRejections++;
  }

  private void maybeProbeInvalidEnvelope(
      LocalMatchingRuntime runtime,
      Planned planned,
      int history,
      int operation,
      StringBuilder canonical,
      Metrics metrics) {
    if (!metrics.profile.isInvalidEnvelope(history, operation)) {
      return;
    }
    long beforeWal = runtime.nextWalSequence();
    String beforeDigest = runtime.semanticStateDigest();
    SubmissionResult.StructuralRejected rejected =
        requireType(
            runtime.submit(IndependentEncoding.envelope(planned, SHARD + 1)),
            SubmissionResult.StructuralRejected.class,
            "profile invalid envelope was not structurally rejected");
    require(
        rejected.code() == StructuralRejectionCode.WRONG_SHARD,
        "profile invalid envelope returned the wrong structural code");
    require(runtime.nextWalSequence() == beforeWal, "invalid envelope reached the WAL");
    require(
        runtime.semanticStateDigest().equals(beforeDigest),
        "invalid envelope changed semantic state");
    canonical
        .append(history)
        .append('|')
        .append(operation)
        .append("|PROFILE_INVALID_ENVELOPE|WRONG_SHARD|")
        .append(rejected.getClass().getSimpleName())
        .append("|NONE|NONE|")
        .append(beforeDigest)
        .append('\n');
    metrics.invalidEnvelopes++;
  }

  private static void appendLine(
      StringBuilder canonical,
      int history,
      int operation,
      String lane,
      String expected,
      SubmissionResult actual,
      LocalMatchingRuntime runtime) {
    canonical
        .append(history)
        .append('|')
        .append(operation)
        .append('|')
        .append(lane)
        .append('|')
        .append(expected)
        .append('|')
        .append(actual.getClass().getSimpleName())
        .append('|')
        .append(position(actual))
        .append('|')
        .append(resultDigest(actual))
        .append('|')
        .append(runtime.semanticStateDigest())
        .append('\n');
  }

  private static String position(SubmissionResult result) {
    if (result instanceof SubmissionResult.NewDurablyApplied value) {
      return compact(value.position());
    }
    if (result instanceof SubmissionResult.DuplicateReplayed value) {
      return compact(value.originalPosition());
    }
    if (result instanceof SubmissionResult.DurabilityUnknown value) {
      return value.attemptedPosition().map(M08GeneratedSuite::compact).orElse("NONE");
    }
    return "NONE";
  }

  private static String resultDigest(SubmissionResult result) {
    if (result instanceof SubmissionResult.NewDurablyApplied value) {
      return value.result().resultDigest();
    }
    if (result instanceof SubmissionResult.DuplicateReplayed value) {
      return value.originalResult().resultDigest();
    }
    return "NONE";
  }

  private static String compact(WalPosition position) {
    return position.segmentId()
        + ":"
        + position.walSequence()
        + ":"
        + position.applicationSequence();
  }

  private static M08Command cancel(SplitMix64 random) {
    return new M08Command.Cancel("BTC-USDT", BigInteger.valueOf(positive(random.nextLong())));
  }

  private static M08Command generatedCommand(
      SplitMix64 random, boolean businessRejection, boolean rolloverPressure) {
    if (businessRejection) {
      return new M08Command.Cancel("", BigInteger.valueOf(-positive(random.nextLong())));
    }
    if (!rolloverPressure) {
      return cancel(random);
    }
    return new M08Command.Place(
        LARGE_FIELD,
        BigInteger.valueOf(positive(random.nextLong())),
        LARGE_FIELD,
        BigInteger.ONE,
        BigInteger.ONE,
        LARGE_FIELD,
        0,
        "NONE",
        Optional.empty());
  }

  private static M08Command differentCancel(M08Command original, SplitMix64 random) {
    BigInteger candidate = BigInteger.valueOf(positive(random.nextLong()));
    M08Command.Cancel replacement = new M08Command.Cancel("BTC-USDT", candidate);
    if (!replacement.toString().equals(original.toString())) {
      return replacement;
    }
    return new M08Command.Cancel("BTC-USDT", candidate.add(BigInteger.ONE));
  }

  private static Planned businessRejectionProbe(int history, int operation) {
    return planned(
        "business-profile-" + history + '-' + operation,
        1,
        1,
        history,
        operation,
        new M08Command.Cancel("", BigInteger.valueOf(-(operation + 1L))));
  }

  private static Planned planned(
      String producer, long epoch, long sequence, int history, int operation, M08Command command) {
    long high = 0x5808000000000000L | Integer.toUnsignedLong(history);
    long low = (Integer.toUnsignedLong(operation) << 32) | (sequence & 0xffff_ffffL);
    return new Planned(producer, epoch, sequence, new UUID(high, low), command);
  }

  private static long positive(long value) {
    long normalized = value & Long.MAX_VALUE;
    return normalized == 0 ? 1 : normalized;
  }

  private static Map<String, Integer> operationCounts(Metrics metrics) {
    Map<String, Integer> counts = new HashMap<>();
    metrics.selectedOperations.forEach((kind, count) -> counts.put(kind.name(), count));
    return Map.copyOf(counts);
  }

  private static Path repositoryRoot(Path workingRoot) {
    String configured = System.getProperty("matching.repositoryRoot");
    if (configured != null && !configured.isBlank()) {
      Path root = Path.of(configured).toAbsolutePath().normalize();
      if (Files.isRegularFile(root.resolve(PROFILE_PATH))) {
        return root;
      }
    }
    for (Path candidate : List.of(Path.of("").toAbsolutePath(), workingRoot.toAbsolutePath())) {
      Path current = candidate.normalize();
      while (current != null) {
        if (Files.isRegularFile(current.resolve(PROFILE_PATH))) {
          return current;
        }
        current = current.getParent();
      }
    }
    throw new IllegalStateException("cannot locate repository-owned M08 property profile");
  }

  private static int countFinalSegments(Path directory) {
    try (var paths = Files.list(directory)) {
      return Math.toIntExact(
          paths
              .filter(path -> path.getFileName().toString().matches("segment-[0-9]{20}\\.m08w1"))
              .count());
    } catch (IOException failure) {
      throw new IllegalStateException("cannot count M08 final segments", failure);
    }
  }

  private static void close(LocalMatchingRuntime runtime) {
    if (runtime == null) {
      return;
    }
    try {
      runtime.close();
    } catch (IOException failure) {
      throw new IllegalStateException("cannot close M08 runtime", failure);
    }
  }

  private static <T> T requireType(Object value, Class<T> type, String message) {
    require(type.isInstance(value), message + ": " + value);
    return type.cast(value);
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
      throw new IllegalStateException("cannot clear M08 generated path", failure);
    }
  }

  private static void provision(Path directory) {
    try {
      Files.createDirectories(directory);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot provision generated WAL directory", failure);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new M08SemanticFailure(message);
    }
  }

  private record Planned(
      String producer, long epoch, long sequence, UUID commandId, M08Command command) {
    private Planned {
      Objects.requireNonNull(producer, "producer");
      Objects.requireNonNull(commandId, "commandId");
      Objects.requireNonNull(command, "command");
    }

    private String slot() {
      return producer + "|" + epoch + "|" + SHARD + "|" + sequence;
    }

    private String payloadKey() {
      return command.toString();
    }
  }

  private enum Kind {
    NEW,
    DUPLICATE,
    REJECTED
  }

  private record ReferenceDecision(Kind kind, PreflightRejectionCode code, String label) {
    private static ReferenceDecision fresh() {
      return new ReferenceDecision(Kind.NEW, null, "NEW");
    }

    private static ReferenceDecision duplicate() {
      return new ReferenceDecision(Kind.DUPLICATE, null, "DUPLICATE");
    }

    private static ReferenceDecision rejected(PreflightRejectionCode code) {
      return new ReferenceDecision(Kind.REJECTED, code, code.name());
    }
  }

  private static final class ReferenceModel {
    private final Map<UUID, ReferenceBinding> byId = new HashMap<>();
    private final Map<String, ReferenceBinding> bySlot = new HashMap<>();
    private final Map<String, Cursor> producers = new HashMap<>();

    private ReferenceDecision preflight(Planned planned) {
      ReferenceBinding id = byId.get(planned.commandId());
      ReferenceBinding slot = bySlot.get(planned.slot());
      if (id != null) {
        if (!id.slot.equals(planned.slot())) {
          return ReferenceDecision.rejected(PreflightRejectionCode.COMMAND_ID_SLOT_CONFLICT);
        }
        if (!id.payloadKey.equals(planned.payloadKey())) {
          return ReferenceDecision.rejected(PreflightRejectionCode.COMMAND_ID_PAYLOAD_CONFLICT);
        }
        require(slot == id, "reference identity indexes diverged");
        return ReferenceDecision.duplicate();
      }
      if (slot != null) {
        return ReferenceDecision.rejected(PreflightRejectionCode.SLOT_IDENTITY_CONFLICT);
      }
      String producerKey = planned.producer() + "|" + SHARD;
      Cursor cursor = producers.get(producerKey);
      if (cursor == null) {
        return planned.sequence() == 1
            ? ReferenceDecision.fresh()
            : ReferenceDecision.rejected(PreflightRejectionCode.PRODUCER_EPOCH_MUST_START_AT_ONE);
      }
      if (planned.epoch() < cursor.epoch) {
        return ReferenceDecision.rejected(PreflightRejectionCode.PRODUCER_EPOCH_FENCED);
      }
      if (planned.epoch() > cursor.epoch) {
        return planned.sequence() == 1
            ? ReferenceDecision.fresh()
            : ReferenceDecision.rejected(PreflightRejectionCode.PRODUCER_EPOCH_MUST_START_AT_ONE);
      }
      if (planned.sequence() == cursor.nextSequence) {
        return ReferenceDecision.fresh();
      }
      return planned.sequence() > cursor.nextSequence
          ? ReferenceDecision.rejected(PreflightRejectionCode.PRODUCER_SEQUENCE_GAP)
          : ReferenceDecision.rejected(PreflightRejectionCode.PRODUCER_SEQUENCE_STALE);
    }

    private void commit(Planned planned) {
      require(preflight(planned).kind() == Kind.NEW, "reference committed non-new identity");
      ReferenceBinding binding = new ReferenceBinding(planned.slot(), planned.payloadKey());
      byId.put(planned.commandId(), binding);
      bySlot.put(planned.slot(), binding);
      producers.put(
          planned.producer() + "|" + SHARD,
          new Cursor(planned.epoch(), Math.incrementExact(planned.sequence())));
    }
  }

  private record ReferenceBinding(String slot, String payloadKey) {}

  private record LedgerDecision(Kind kind, PreflightRejectionCode code, DurableBinding binding) {
    private boolean matches(ReferenceDecision reference) {
      return kind == reference.kind() && code == reference.code();
    }
  }

  /**
   * Third ledger: no production envelope codec, WAL parser, identity index, or local runtime is
   * called. It derives bytes, coordinates, core result, and the M08T1 replay commitment from the
   * operation history, then compares those predictions with the runtime boundary.
   */
  private static final class DurabilityLedger {
    private static final int WAL_HEADER_BYTES = 36;
    private static final int WAL_RECORD_OVERHEAD = 32;

    private final long maxSegmentBytes;
    private final SingleInstrumentMatchingEngine core = new SingleInstrumentMatchingEngine();
    private final Map<UUID, DurableBinding> byId = new HashMap<>();
    private final Map<String, DurableBinding> bySlot = new HashMap<>();
    private final Map<String, Cursor> producers = new HashMap<>();

    private long segmentId = 1;
    private long activeSize = WAL_HEADER_BYTES;
    private long nextWalSequence = 1;
    private String transcriptDigest = IndependentEncoding.genesisTranscriptDigest();
    private int appends;
    private int recordForces;
    private int applies;
    private int directoryForces = 1;
    private int restartChecks;
    private boolean contributed;

    private DurabilityLedger(long maxSegmentBytes) {
      this.maxSegmentBytes = maxSegmentBytes;
    }

    private LedgerDecision preflight(Planned planned) {
      DurableBinding id = byId.get(planned.commandId());
      DurableBinding slot = bySlot.get(planned.slot());
      if (id != null) {
        if (!id.slot().equals(planned.slot())) {
          return rejected(PreflightRejectionCode.COMMAND_ID_SLOT_CONFLICT);
        }
        if (!id.payloadKey().equals(planned.payloadKey())) {
          return rejected(PreflightRejectionCode.COMMAND_ID_PAYLOAD_CONFLICT);
        }
        require(slot == id, "durability ledger indexes diverged");
        return new LedgerDecision(Kind.DUPLICATE, null, id);
      }
      if (slot != null) {
        return rejected(PreflightRejectionCode.SLOT_IDENTITY_CONFLICT);
      }
      Cursor cursor = producers.get(producerKey(planned));
      if (cursor == null) {
        return planned.sequence() == 1
            ? fresh()
            : rejected(PreflightRejectionCode.PRODUCER_EPOCH_MUST_START_AT_ONE);
      }
      if (planned.epoch() < cursor.epoch()) {
        return rejected(PreflightRejectionCode.PRODUCER_EPOCH_FENCED);
      }
      if (planned.epoch() > cursor.epoch()) {
        return planned.sequence() == 1
            ? fresh()
            : rejected(PreflightRejectionCode.PRODUCER_EPOCH_MUST_START_AT_ONE);
      }
      if (planned.sequence() == cursor.nextSequence()) {
        return fresh();
      }
      return planned.sequence() > cursor.nextSequence()
          ? rejected(PreflightRejectionCode.PRODUCER_SEQUENCE_GAP)
          : rejected(PreflightRejectionCode.PRODUCER_SEQUENCE_STALE);
    }

    private DurableBinding commitNew(Planned planned, byte[] envelope) {
      return commitDurable(planned, envelope);
    }

    private DurableBinding commitDurableUnknown(Planned planned, byte[] envelope) {
      return commitDurable(planned, envelope);
    }

    private DurableBinding commitDurable(Planned planned, byte[] envelope) {
      require(preflight(planned).kind() == Kind.NEW, "durability ledger committed non-new input");
      int recordLength = Math.addExact(WAL_RECORD_OVERHEAD, envelope.length);
      if (activeSize + recordLength > maxSegmentBytes) {
        segmentId = Math.incrementExact(segmentId);
        activeSize = WAL_HEADER_BYTES;
        directoryForces++;
      }
      long applicationSequence = core.marketControlSnapshot().nextApplicationSequence().value();
      ExpectedPosition position =
          new ExpectedPosition(
              segmentId, nextWalSequence, applicationSequence, activeSize, recordLength);
      activeSize += recordLength;
      nextWalSequence = Math.incrementExact(nextWalSequence);
      appends++;
      recordForces++;

      ExpectedResult result = apply(planned.command(), applicationSequence);
      DurableBinding binding =
          new DurableBinding(planned.slot(), planned.payloadKey(), position, result);
      byId.put(planned.commandId(), binding);
      bySlot.put(planned.slot(), binding);
      producers.put(
          producerKey(planned),
          new Cursor(planned.epoch(), Math.incrementExact(planned.sequence())));
      return binding;
    }

    private ExpectedResult apply(M08Command command, long expectedSequence) {
      ExecutionBatch batch =
          switch (command) {
            case M08Command.Cancel cancel ->
                core.cancel(new CancelOrderInput(cancel.instrumentId(), cancel.orderId()));
            case M08Command.Place place ->
                core.placeRequest(
                    new PlaceLimitOrderRequest(
                        new PlaceLimitOrderInput(
                            place.instrumentId(),
                            place.orderId(),
                            place.side(),
                            place.priceTicks(),
                            place.quantityLots()),
                        place.executionPolicy()));
            default ->
                throw new IllegalStateException(
                    "generated ledger does not support " + command.getClass().getName());
          };
      long applicationSequence = batch.context().applicationSequence().orElseThrow().value();
      require(applicationSequence == expectedSequence, "ledger core application sequence changed");
      List<String> events =
          batch.events().stream().map(event -> event.getClass().getName() + ":" + event).toList();
      String context = batch.context().toString();
      String publicCoreState =
          IndependentEncoding.semanticDigest(
              core.marketControlSnapshot().toString(), core.snapshot().toString());
      transcriptDigest =
          IndependentEncoding.advanceTranscript(
              transcriptDigest,
              command,
              "EXECUTION",
              applicationSequence,
              events,
              context,
              publicCoreState);
      String semantic = IndependentEncoding.semanticDigest(publicCoreState, transcriptDigest);
      applies++;
      return new ExpectedResult(
          "EXECUTION",
          applicationSequence,
          events,
          context,
          semantic,
          IndependentEncoding.resultDigest(
              "EXECUTION", applicationSequence, events, context, semantic));
    }

    private String semanticDigest() {
      String publicCoreState =
          IndependentEncoding.semanticDigest(
              core.marketControlSnapshot().toString(), core.snapshot().toString());
      return IndependentEncoding.semanticDigest(publicCoreState, transcriptDigest);
    }

    private void verifyRestart(LocalMatchingRuntime runtime, Metrics metrics) {
      require(
          runtime.nextWalSequence() == nextWalSequence,
          "restart WAL cursor disagreed with third ledger");
      require(
          runtime.semanticStateDigest().equals(semanticDigest()),
          "restart transcript digest disagreed with third ledger");
      restartChecks++;
      metrics.restartLedgerChecks++;
    }

    private int completeOperationCheck() {
      return 1;
    }

    private void contribute(Metrics metrics) {
      require(!contributed, "durability ledger metrics contributed twice");
      contributed = true;
      metrics.ledgerAppends += appends;
      metrics.ledgerRecordForces += recordForces;
      metrics.ledgerApplies += applies;
      metrics.ledgerDirectoryForces += directoryForces;
      require(restartChecks > 0, "durability ledger did not verify a fresh or recovered open");
    }

    private static LedgerDecision fresh() {
      return new LedgerDecision(Kind.NEW, null, null);
    }

    private static LedgerDecision rejected(PreflightRejectionCode code) {
      return new LedgerDecision(Kind.REJECTED, code, null);
    }

    private static String producerKey(Planned planned) {
      return planned.producer() + "|" + SHARD;
    }
  }

  private record ExpectedPosition(
      long segmentId, long walSequence, long applicationSequence, long offset, int recordLength) {
    private void verify(WalPosition actual) {
      require(segmentId == actual.segmentId(), "third ledger segment id changed");
      require(walSequence == actual.walSequence(), "third ledger WAL sequence changed");
      require(
          applicationSequence == actual.applicationSequence(),
          "third ledger application sequence changed");
      require(offset == actual.offset(), "third ledger record offset changed");
      require(recordLength == actual.recordLength(), "third ledger record length changed");
    }
  }

  private record ExpectedResult(
      String resultType,
      long applicationSequence,
      List<String> events,
      String context,
      String semanticDigest,
      String resultDigest) {
    private ExpectedResult {
      events = List.copyOf(events);
    }

    private void verify(CanonicalResult actual) {
      require(resultType.equals(actual.resultType()), "third ledger result type changed");
      require(
          applicationSequence == actual.applicationSequence(),
          "third ledger result application sequence changed");
      require(events.equals(actual.events()), "third ledger business events changed");
      require(context.equals(actual.context()), "third ledger result context changed");
      require(
          semanticDigest.equals(actual.semanticStateDigest()),
          "third ledger semantic transcript changed");
      require(resultDigest.equals(actual.resultDigest()), "third ledger result digest changed");
    }
  }

  private record DurableBinding(
      String slot, String payloadKey, ExpectedPosition position, ExpectedResult result) {
    private void verify(WalPosition actualPosition, CanonicalResult actualResult) {
      position.verify(actualPosition);
      result.verify(actualResult);
    }
  }

  private static final class IndependentEncoding {
    private static final String TRANSCRIPT_DOMAIN = "M08T1_GENESIS_REPLAY_TRANSCRIPT";

    private IndependentEncoding() {}

    private static byte[] envelope(Planned planned) {
      return envelope(planned, SHARD);
    }

    private static byte[] envelope(Planned planned, long shardId) {
      byte[] command = command(planned.command());
      Writer writer = new Writer();
      writer.putInt(0x4D303843);
      writer.putInt(1);
      writer.putString(planned.producer());
      writer.putLong(planned.epoch());
      writer.putLong(shardId);
      writer.putLong(planned.sequence());
      writer.putLong(planned.commandId().getMostSignificantBits());
      writer.putLong(planned.commandId().getLeastSignificantBits());
      writer.putBytes(digest(command));
      writer.putByteArray(command);
      return writer.bytes();
    }

    private static byte[] command(M08Command command) {
      Writer writer = new Writer();
      writer.putInt(1);
      switch (command) {
        case M08Command.Place place -> {
          writer.putInt(1);
          writer.putString(place.instrumentId());
          writer.putString(place.orderId().toString());
          writer.putString(place.side());
          writer.putString(place.priceTicks().toString());
          writer.putString(place.quantityLots().toString());
          writer.putString(place.executionPolicy());
          writer.putLong(place.participantGroupId());
          writer.putString(place.stpPolicy());
          writer.putByte(place.expectedActive().isPresent() ? 1 : 0);
          place
              .expectedActive()
              .ifPresent(
                  identity -> {
                    writer.putLong(identity.version().value());
                    writer.putString(identity.contentHash());
                  });
        }
        case M08Command.Cancel cancel -> {
          writer.putInt(2);
          writer.putString(cancel.instrumentId());
          writer.putString(cancel.orderId().toString());
        }
        default ->
            throw new IllegalStateException(
                "generated independent encoder does not support " + command.getClass().getName());
      }
      return writer.bytes();
    }

    private static String genesisTranscriptDigest() {
      Writer writer = new Writer();
      writer.putString(TRANSCRIPT_DOMAIN);
      writer.putString("GENESIS");
      return Hashing.sha256Hex(writer.bytes());
    }

    private static String advanceTranscript(
        String previous,
        M08Command command,
        String resultType,
        long applicationSequence,
        List<String> events,
        String context,
        String publicCoreState) {
      Writer writer = new Writer();
      writer.putString(TRANSCRIPT_DOMAIN);
      writer.putString(previous);
      writer.putByteArray(command(command));
      writer.putString(resultType);
      writer.putLong(applicationSequence);
      writer.putInt(events.size());
      events.forEach(writer::putString);
      writer.putString(context);
      writer.putString(publicCoreState);
      return Hashing.sha256Hex(writer.bytes());
    }

    private static String semanticDigest(String first, String second) {
      Writer writer = new Writer();
      writer.putString(first);
      writer.putString(second);
      return Hashing.sha256Hex(writer.bytes());
    }

    private static String resultDigest(
        String resultType,
        long applicationSequence,
        List<String> events,
        String context,
        String semanticDigest) {
      Writer writer = new Writer();
      writer.putString(resultType);
      writer.putLong(applicationSequence);
      writer.putInt(events.size());
      events.forEach(writer::putString);
      writer.putString(context);
      writer.putString(semanticDigest);
      return Hashing.sha256Hex(writer.bytes());
    }

    private static byte[] digest(byte[] bytes) {
      try {
        return MessageDigest.getInstance("SHA-256").digest(bytes);
      } catch (NoSuchAlgorithmException failure) {
        throw new IllegalStateException("SHA-256 is unavailable", failure);
      }
    }

    private static final class Writer {
      private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      private final DataOutputStream data = new DataOutputStream(bytes);

      private void putByte(int value) {
        write(() -> data.writeByte(value));
      }

      private void putInt(int value) {
        write(() -> data.writeInt(value));
      }

      private void putLong(long value) {
        write(() -> data.writeLong(value));
      }

      private void putBytes(byte[] value) {
        write(() -> data.write(value));
      }

      private void putByteArray(byte[] value) {
        putInt(value.length);
        putBytes(value);
      }

      private void putString(String value) {
        putByteArray(value.getBytes(StandardCharsets.UTF_8));
      }

      private byte[] bytes() {
        return bytes.toByteArray();
      }

      private static void write(IoWrite operation) {
        try {
          operation.run();
        } catch (IOException impossible) {
          throw new IllegalStateException("in-memory canonical encoding failed", impossible);
        }
      }
    }

    @FunctionalInterface
    private interface IoWrite {
      void run() throws IOException;
    }
  }

  private record Cursor(long epoch, long nextSequence) {}

  private enum OperationKind {
    SUBMIT,
    DUPLICATE,
    CONFLICT,
    RESTART,
    ROLLOVER,
    FAULT
  }

  private record OperationProfile(
      int submitWeight,
      int duplicateWeight,
      int conflictWeight,
      int restartWeight,
      int rolloverWeight,
      int faultWeight,
      int invalidEnvelopeOneIn,
      int businessRejectionOneIn) {
    private static OperationProfile load(Path repositoryRoot) {
      try {
        JsonNode document =
            JsonSupport.parse(Files.readAllBytes(repositoryRoot.resolve(PROFILE_PATH)));
        JsonSupport.validate(
            document, Files.readString(repositoryRoot.resolve(PROFILE_SCHEMA_PATH)), true);
        JsonNode domain = document.path("operationDomain");
        OperationProfile profile =
            new OperationProfile(
                domain.path("submitWeight").intValue(),
                domain.path("duplicateWeight").intValue(),
                domain.path("conflictWeight").intValue(),
                domain.path("restartWeight").intValue(),
                domain.path("rolloverWeight").intValue(),
                domain.path("faultWeight").intValue(),
                domain.path("invalidEnvelopeOneIn").intValue(),
                domain.path("businessRejectionOneIn").intValue());
        require(
            profile.equals(new OperationProfile(56, 12, 10, 10, 6, 6, 24, 8)),
            "repository-owned M08 operation domain changed");
        return profile;
      } catch (IOException failure) {
        throw new IllegalStateException(
            "cannot read repository-owned M08 property profile", failure);
      }
    }

    private OperationKind select(long entropy) {
      int selected = Math.floorMod(entropy, 100);
      int boundary = submitWeight;
      if (selected < boundary) {
        return OperationKind.SUBMIT;
      }
      boundary += duplicateWeight;
      if (selected < boundary) {
        return OperationKind.DUPLICATE;
      }
      boundary += conflictWeight;
      if (selected < boundary) {
        return OperationKind.CONFLICT;
      }
      boundary += restartWeight;
      if (selected < boundary) {
        return OperationKind.RESTART;
      }
      boundary += rolloverWeight;
      if (selected < boundary) {
        return OperationKind.ROLLOVER;
      }
      require(boundary + faultWeight == 100, "M08 operation weights do not total 100");
      return OperationKind.FAULT;
    }

    private boolean isInvalidEnvelope(int history, int operation) {
      return ordinal(history, operation) % invalidEnvelopeOneIn == 0;
    }

    private boolean isBusinessRejection(int history, int operation) {
      return ordinal(history, operation) % businessRejectionOneIn == 0;
    }

    private static int ordinal(int history, int operation) {
      return history * M08StartCheckRunner.OPERATIONS_PER_HISTORY + operation + 1;
    }
  }

  private static final class Metrics {
    private final OperationProfile profile;
    private final Map<OperationKind, Integer> selectedOperations = new HashMap<>();
    private int histories;
    private int operations;
    private int comparisons;
    private int ledgerChecks;
    private int ledgerAppends;
    private int ledgerRecordForces;
    private int ledgerApplies;
    private int ledgerDirectoryForces;
    private int restartLedgerChecks;
    private int restarts;
    private int faultWindows;
    private int rollovers;
    private int invalidEnvelopes;
    private int businessRejections;

    private Metrics(OperationProfile profile) {
      this.profile = profile;
    }

    private void selected(OperationKind kind) {
      selectedOperations.merge(kind, 1, Math::addExact);
    }
  }

  private static final class SplitMix64 {
    private long state;

    private SplitMix64(long seed) {
      state = seed;
    }

    private long nextLong() {
      state += 0x9E3779B97F4A7C15L;
      long value = state;
      value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
      value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
      return value ^ (value >>> 31);
    }
  }

  private static final class OneShotFault implements FaultInjector {
    private final FaultPoint target;
    private boolean thrown;

    private OneShotFault(FaultPoint target) {
      this.target = target;
    }

    @Override
    public void hit(FaultPoint point) throws IOException {
      if (!thrown && point == target) {
        thrown = true;
        throw new IOException("injected " + point);
      }
    }
  }

  record Result(
      int histories,
      int operations,
      int comparisons,
      int ledgerChecks,
      int ledgerAppends,
      int ledgerRecordForces,
      int ledgerApplies,
      int ledgerDirectoryForces,
      int restartLedgerChecks,
      int restarts,
      int faultWindows,
      int rollovers,
      int invalidEnvelopes,
      int businessRejections,
      Map<String, Integer> selectedOperations,
      byte[] canonicalBytes,
      String digest) {
    Result {
      selectedOperations = Map.copyOf(selectedOperations);
      canonicalBytes = canonicalBytes.clone();
    }

    @Override
    public byte[] canonicalBytes() {
      return canonicalBytes.clone();
    }
  }
}
