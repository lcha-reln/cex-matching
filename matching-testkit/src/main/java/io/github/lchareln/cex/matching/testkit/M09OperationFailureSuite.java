package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.local.FaultInjector;
import io.github.lchareln.cex.matching.local.FaultPoint;
import io.github.lchareln.cex.matching.local.LocalMatchingRuntime;
import io.github.lchareln.cex.matching.local.RuntimeState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.node.ArrayNode;

/** Eight deterministic failures at declared pre-operation hooks, separately from child halts. */
final class M09OperationFailureSuite {
  private static final Map<String, FaultPoint> POINTS = points();
  private final M09ScenarioSupport support = new M09ScenarioSupport();

  Result run(M09Corpus corpus, Path workingRoot) {
    Path root = workingRoot.toAbsolutePath().normalize();
    M09ScenarioSupport.deleteTree(root);
    provision(root);
    ArrayNode results = JsonSupport.MAPPER.createArrayNode();
    try {
      for (String seam : corpus.generator().failureSeams()) {
        FaultPoint point = POINTS.get(seam);
        if (point == null) {
          throw new IllegalStateException("missing executable M09 failure seam " + seam);
        }
        Path directory = provision(root.resolve(seam.toLowerCase(java.util.Locale.ROOT)));
        String recovery =
            "WAL_SUFFIX_READ".equals(seam)
                ? suffixReadFailure(directory, point)
                : checkpointFailure(directory, seam, point);
        var node = results.addObject();
        node.put("seam", seam);
        node.put("faultPoint", point.name());
        node.put("classification", "INJECTED_OPERATION_FAILURE");
        node.put("faultInjectedAtDeclaredPreOperationHook", true);
        node.put("underlyingOperationExecutionClaim", false);
        node.put("actualFilesystemFailure", false);
        node.put("ackReturned", false);
        node.put("freshReopen", recovery);
        node.put("powerLossProof", false);
      }
      require(results.size() == 8, "M09 failure seam count changed");
      return new Result(results, results.size());
    } finally {
      M09ScenarioSupport.deleteTree(root);
    }
  }

  private String checkpointFailure(Path directory, String seam, FaultPoint point) {
    M09ScenarioSupport.CommandStream stream = support.stream("seam-" + seam);
    byte[] first = stream.next(M09ScenarioSupport.cancel(1));
    byte[] second = stream.next(M09ScenarioSupport.cancel(2));
    RecordingFailure failure = new RecordingFailure(point);
    try (LocalMatchingRuntime runtime =
        LocalMatchingRuntime.open(support.config(directory), failure)) {
      M09ScenarioSupport.requireNew(runtime.submit(first), "seam first submit");
      if (seam.startsWith("RETIREMENT_")) {
        runtime.checkpoint();
        M09ScenarioSupport.requireNew(runtime.submit(second), "retirement seam second submit");
      }
      try {
        runtime.checkpoint();
        throw new IllegalStateException("M09 failure seam was not injected: " + seam);
      } catch (IOException expected) {
        systemRequire(failure.hit(), "M09 failure seam was not reached: " + seam);
        systemRequire(
            expected == failure.injected(),
            "M09 failure seam surfaced an unrelated IOException: " + seam);
        require(runtime.state() == RuntimeState.FAILED_CLOSED, "checkpoint seam stayed open");
      }
    } catch (IOException failureToOpenOrClose) {
      throw new IllegalStateException(
          "M09 checkpoint seam harness failed: " + seam, failureToOpenOrClose);
    }
    try (LocalMatchingRuntime restored = LocalMatchingRuntime.open(support.config(directory))) {
      M09ScenarioSupport.requireDuplicate(restored.submit(first), "seam recovery first duplicate");
      if (seam.startsWith("RETIREMENT_")) {
        M09ScenarioSupport.requireDuplicate(
            restored.submit(second), "seam recovery second duplicate");
      }
      return "OPEN_AT_WAL_" + restored.nextWalSequence();
    } catch (IOException recoveryFailure) {
      throw new IllegalStateException(
          "M09 checkpoint seam did not recover: " + seam, recoveryFailure);
    }
  }

  private String suffixReadFailure(Path directory, FaultPoint point) {
    M09ScenarioSupport.CommandStream stream = support.stream("suffix-read-seam");
    byte[] prefix = stream.next(M09ScenarioSupport.cancel(1));
    byte[] suffix = stream.next(M09ScenarioSupport.cancel(2));
    try (LocalMatchingRuntime runtime = LocalMatchingRuntime.open(support.config(directory))) {
      M09ScenarioSupport.requireNew(runtime.submit(prefix), "suffix seam prefix");
      runtime.checkpoint();
      M09ScenarioSupport.requireNew(runtime.submit(suffix), "suffix seam suffix");
    } catch (IOException failure) {
      throw new IllegalStateException("cannot prepare M09 suffix seam", failure);
    }
    RecordingFailure failure = new RecordingFailure(point);
    try (LocalMatchingRuntime ignored =
        LocalMatchingRuntime.open(support.config(directory), failure)) {
      throw new IllegalStateException("WAL suffix read seam was not injected: " + ignored.state());
    } catch (IOException expected) {
      systemRequire(failure.hit(), "WAL suffix read hook was not reached");
      systemRequire(expected == failure.injected(), "WAL suffix seam surfaced unrelated failure");
    }
    try (LocalMatchingRuntime restored = LocalMatchingRuntime.open(support.config(directory))) {
      M09ScenarioSupport.requireDuplicate(
          restored.submit(suffix), "suffix seam recovery duplicate");
      return "OPEN_AT_WAL_" + restored.nextWalSequence();
    } catch (IOException recoveryFailure) {
      throw new IllegalStateException("M09 suffix seam did not recover", recoveryFailure);
    }
  }

  private static Map<String, FaultPoint> points() {
    Map<String, FaultPoint> values = new LinkedHashMap<>();
    values.put("SNAPSHOT_TEMP_WRITE", FaultPoint.BEFORE_SNAPSHOT_TEMP_WRITE);
    values.put("SNAPSHOT_FILE_FORCE", FaultPoint.BEFORE_SNAPSHOT_FILE_FORCE);
    values.put("SNAPSHOT_ATOMIC_RENAME", FaultPoint.BEFORE_SNAPSHOT_ATOMIC_RENAME);
    values.put("SNAPSHOT_DIRECTORY_FORCE", FaultPoint.BEFORE_SNAPSHOT_DIRECTORY_FORCE);
    values.put("RETIREMENT_SEGMENT_DELETE", FaultPoint.BEFORE_RETENTION_DELETE);
    values.put("RETIREMENT_DIRECTORY_FORCE", FaultPoint.BEFORE_RETENTION_DIRECTORY_FORCE);
    values.put("SNAPSHOT_READ", FaultPoint.BEFORE_SNAPSHOT_READ);
    values.put("WAL_SUFFIX_READ", FaultPoint.BEFORE_SNAPSHOT_SUFFIX_READ);
    return Map.copyOf(values);
  }

  private static Path provision(Path directory) {
    try {
      return Files.createDirectories(directory);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot provision M09 failure seam directory", failure);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new M09SemanticFailure(message);
    }
  }

  private static void systemRequire(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }

  private static final class RecordingFailure implements FaultInjector {
    private final FaultPoint target;
    private boolean hit;
    private IOException injected;

    private RecordingFailure(FaultPoint target) {
      this.target = target;
    }

    @Override
    public void hit(FaultPoint point) throws IOException {
      if (!hit && point == target) {
        hit = true;
        injected = new IOException("injected M09 operation failure " + point);
        throw injected;
      }
    }

    private boolean hit() {
      return hit;
    }

    private IOException injected() {
      return injected;
    }
  }

  record Result(ArrayNode failures, int observed) {
    Result {
      failures = failures.deepCopy();
    }

    @Override
    public ArrayNode failures() {
      return failures.deepCopy();
    }
  }
}
