package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.local.FaultInjector;
import io.github.lchareln.cex.matching.local.FaultPoint;
import io.github.lchareln.cex.matching.local.LocalMatchingRuntime;
import io.github.lchareln.cex.matching.local.M08Command;
import io.github.lchareln.cex.matching.local.M08EnvelopeCodec;
import io.github.lchareln.cex.matching.local.RuntimeState;
import io.github.lchareln.cex.matching.local.SubmissionResult;
import io.github.lchareln.cex.matching.local.WalConfig;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Exercises failures before real JDK I/O operations, separately from post-success crash hooks. */
final class M08OperationFailureSuite {
  private static final long SHARD = 8_088;
  private static final List<FaultPoint> OPEN_FAILURES =
      List.of(
          FaultPoint.BEFORE_DIRECTORY_LOCK,
          FaultPoint.BEFORE_SEGMENT_HEADER_FORCE,
          FaultPoint.BEFORE_SEGMENT_ATOMIC_RENAME,
          FaultPoint.BEFORE_DIRECTORY_FORCE);
  private final M08EnvelopeCodec codec = new M08EnvelopeCodec();

  Result run(Path workingRoot) {
    Path root = workingRoot.toAbsolutePath().normalize();
    deleteTree(root);
    try {
      Files.createDirectories(root);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot create operation-failure root", failure);
    }
    ArrayNode results = JsonSupport.MAPPER.createArrayNode();
    try {
      submitFailure(
          root.resolve("write-body"),
          FaultPoint.BEFORE_RECORD_BODY_WRITE,
          false,
          InjectionKind.INJECTED_ENOSPC,
          results);
      submitFailure(
          root.resolve("record-force"),
          FaultPoint.BEFORE_RECORD_FORCE,
          true,
          InjectionKind.GENERIC_IO_EXCEPTION,
          results);
      for (FaultPoint point : OPEN_FAILURES) {
        openFailure(
            root.resolve(point.name().toLowerCase(java.util.Locale.ROOT)),
            point,
            point == FaultPoint.BEFORE_DIRECTORY_FORCE
                ? InjectionKind.INJECTED_READ_ONLY
                : InjectionKind.GENERIC_IO_EXCEPTION,
            results);
      }
      tailForceFailure(root.resolve("tail-truncate-force"), results);
      require(results.size() == 7, "M08 injected operation-failure count changed");
      return new Result(results, 7);
    } finally {
      deleteTree(root);
    }
  }

  private void submitFailure(
      Path directory,
      FaultPoint point,
      boolean completeFrameVisible,
      InjectionKind injectionKind,
      ArrayNode results) {
    provision(directory);
    WalConfig config = WalConfig.defaults(directory, SHARD);
    byte[] envelope = envelope(point);
    RecordingFailure fault = new RecordingFailure(point, injectionKind);
    try (LocalMatchingRuntime runtime = LocalMatchingRuntime.open(config, fault)) {
      SubmissionResult.DurabilityUnknown unknown =
          requireType(
              runtime.submit(envelope),
              SubmissionResult.DurabilityUnknown.class,
              "operation failure returned ACK");
      require("APPEND_OR_FORCE".equals(unknown.stage()), "operation failure stage changed");
      require(runtime.state() == RuntimeState.FAILED_CLOSED, "operation failure stayed open");
      require(fault.hitBeforeOperation(), "before-operation hook was not reached");
    } catch (IOException failure) {
      throw new IllegalStateException("submit operation failure scenario failed", failure);
    }
    String retry;
    try (LocalMatchingRuntime recovered = LocalMatchingRuntime.open(config)) {
      SubmissionResult result = recovered.submit(envelope);
      retry = result.getClass().getSimpleName();
      require(
          completeFrameVisible
              ? result instanceof SubmissionResult.DuplicateReplayed
              : result instanceof SubmissionResult.NewDurablyApplied,
          "same-host reopen classified before-operation failure incorrectly: " + point);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot reopen operation-failure WAL", failure);
    }
    add(results, point, injectionKind, "SUBMIT_FAILED_CLOSED", retry);
  }

  private static void openFailure(
      Path directory, FaultPoint point, InjectionKind injectionKind, ArrayNode results) {
    provision(directory);
    WalConfig config = WalConfig.defaults(directory, SHARD);
    RecordingFailure fault = new RecordingFailure(point, injectionKind);
    try {
      LocalMatchingRuntime.open(config, fault);
      throw new IllegalStateException("before-operation open failure returned a runtime: " + point);
    } catch (IOException expected) {
      require(fault.hitBeforeOperation(), "before-operation open hook was not reached");
    }
    try (LocalMatchingRuntime recovered = LocalMatchingRuntime.open(config)) {
      require(recovered.nextWalSequence() == 1, "failed open created an application record");
    } catch (IOException failure) {
      throw new IllegalStateException("failed-open recovery did not reopen", failure);
    }
    add(results, point, injectionKind, "OPEN_FAILED", "FRESH_REOPENED");
  }

  private void tailForceFailure(Path directory, ArrayNode results) {
    provision(directory);
    WalConfig config = WalConfig.defaults(directory, SHARD);
    byte[] envelope = envelope(FaultPoint.BEFORE_TAIL_TRUNCATE_FORCE);
    try (LocalMatchingRuntime runtime =
        LocalMatchingRuntime.open(
            config,
            new RecordingFailure(
                FaultPoint.AFTER_RECORD_LENGTH_WRITE, InjectionKind.GENERIC_IO_EXCEPTION))) {
      require(
          runtime.submit(envelope) instanceof SubmissionResult.DurabilityUnknown,
          "tail setup returned ACK");
    } catch (IOException failure) {
      throw new IllegalStateException("cannot create incomplete final tail", failure);
    }
    RecordingFailure failure =
        new RecordingFailure(
            FaultPoint.BEFORE_TAIL_TRUNCATE_FORCE, InjectionKind.GENERIC_IO_EXCEPTION);
    try {
      LocalMatchingRuntime.open(config, failure);
      throw new IllegalStateException("tail truncate-force failure opened runtime");
    } catch (IOException expected) {
      require(failure.hitBeforeOperation(), "tail force before-operation hook was not reached");
    }
    RecordingFailure activeForce =
        new RecordingFailure(
            FaultPoint.BEFORE_RECOVERY_ACTIVE_FORCE, InjectionKind.GENERIC_IO_EXCEPTION);
    try {
      LocalMatchingRuntime.open(config, activeForce);
      throw new IllegalStateException("recovery active-force failure opened runtime");
    } catch (IOException expected) {
      require(activeForce.hitBeforeOperation(), "recovery active force hook was not reached");
    }
    RecordingFailure directoryForce =
        new RecordingFailure(
            FaultPoint.BEFORE_RECOVERY_DIRECTORY_FORCE, InjectionKind.GENERIC_IO_EXCEPTION);
    try {
      LocalMatchingRuntime.open(config, directoryForce);
      throw new IllegalStateException("recovery directory-force failure opened runtime");
    } catch (IOException expected) {
      require(directoryForce.hitBeforeOperation(), "recovery directory force hook was not reached");
    }
    try (LocalMatchingRuntime recovered = LocalMatchingRuntime.open(config)) {
      require(recovered.nextWalSequence() == 1, "failed tail force replayed an incomplete record");
      require(
          recovered.submit(envelope) instanceof SubmissionResult.NewDurablyApplied,
          "repaired tail did not accept exact retry as new");
    } catch (IOException unexpected) {
      throw new IllegalStateException("tail force recovery did not reopen", unexpected);
    }
    add(
        results,
        FaultPoint.BEFORE_TAIL_TRUNCATE_FORCE,
        InjectionKind.GENERIC_IO_EXCEPTION,
        "RECOVERY_OPEN_FAILED_AFTER_TRUNCATE_BEFORE_FORCE",
        "FRESH_REOPENED");
    ObjectNode tail = (ObjectNode) results.get(results.size() - 1);
    tail.put("recoveryActiveForceFailureBlockedOpen", true);
    tail.put("recoveryDirectoryForceFailureBlockedOpen", true);
    tail.put("recoveryOrder", "SCAN_REPAIR_ACTIVE_FORCE_DIRECTORY_FORCE_APPLY_OPEN");
  }

  private byte[] envelope(FaultPoint point) {
    return codec.encode(
        "operation-failure-" + point,
        1,
        SHARD,
        1,
        new UUID(0x0880000000000000L, point.ordinal() + 1L),
        new M08Command.Cancel("BTC-USDT", BigInteger.valueOf(point.ordinal() + 1L)));
  }

  private static void add(
      ArrayNode results,
      FaultPoint point,
      InjectionKind injectionKind,
      String immediateResult,
      String freshReopenResult) {
    ObjectNode value = results.addObject();
    value.put("faultPoint", point.name());
    value.put("classification", "INJECTED_OPERATION_FAILURE");
    value.put("injectedFaultKind", injectionKind.name());
    value.put("actualFilesystem", false);
    value.put("hookTiming", "BEFORE_OPERATION");
    value.put("operationExecuted", false);
    value.put("ackReturned", false);
    value.put("immediateResult", immediateResult);
    value.put("freshReopenResult", freshReopenResult);
    value.put("powerLossProof", false);
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
      throw new IllegalStateException("cannot clear operation-failure path", failure);
    }
  }

  private static void provision(Path directory) {
    try {
      Files.createDirectories(directory);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot provision operation-failure WAL directory", failure);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new M08SemanticFailure(message);
    }
  }

  private static final class RecordingFailure implements FaultInjector {
    private final FaultPoint target;
    private final InjectionKind injectionKind;
    private boolean hit;

    private RecordingFailure(FaultPoint target, InjectionKind injectionKind) {
      this.target = target;
      this.injectionKind = injectionKind;
    }

    @Override
    public void hit(FaultPoint point) throws IOException {
      if (!hit && point == target) {
        hit = true;
        switch (injectionKind) {
          case INJECTED_ENOSPC ->
              throw new FileSystemException("m08-wal", null, "injected ENOSPC before " + point);
          case INJECTED_READ_ONLY ->
              throw new FileSystemException(
                  "m08-wal", null, "injected read-only filesystem before " + point);
          case GENERIC_IO_EXCEPTION -> throw new IOException("injected " + point);
        }
      }
    }

    private boolean hitBeforeOperation() {
      return hit;
    }
  }

  private enum InjectionKind {
    GENERIC_IO_EXCEPTION,
    INJECTED_ENOSPC,
    INJECTED_READ_ONLY
  }

  record Result(ArrayNode failures, int operationFailures) {
    Result {
      failures = failures.deepCopy();
    }

    @Override
    public ArrayNode failures() {
      return failures.deepCopy();
    }
  }
}
