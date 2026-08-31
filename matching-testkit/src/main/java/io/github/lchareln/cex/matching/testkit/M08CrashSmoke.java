package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.local.FaultPoint;
import io.github.lchareln.cex.matching.local.LocalMatchingRuntime;
import io.github.lchareln.cex.matching.local.SubmissionResult;
import io.github.lchareln.cex.matching.local.WalConfig;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Parent-side child-process halt smoke with fresh reopen and semantic/file assertions. */
final class M08CrashSmoke {
  private static final long SHARD = 8_086;
  private static final Duration TIMEOUT = Duration.ofSeconds(30);
  private static final List<FaultPoint> WINDOWS =
      List.of(
          FaultPoint.AFTER_RECORD_LENGTH_WRITE,
          FaultPoint.AFTER_RECORD_FORCE,
          FaultPoint.AFTER_LIVE_APPLY_BEFORE_ACK);

  Result run(Path workingRoot) {
    Path root = workingRoot.toAbsolutePath().normalize();
    deleteTree(root);
    try {
      Files.createDirectories(root);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot create M08 crash-smoke root", failure);
    }
    ArrayNode windows = JsonSupport.MAPPER.createArrayNode();
    try {
      for (FaultPoint point : WINDOWS) {
        Path directory = root.resolve(point.name().toLowerCase(java.util.Locale.ROOT));
        runChild(directory, point);
        require(
            Files.isRegularFile(M08CrashChildMain.marker(directory, point)),
            "child halt marker is missing for " + point);
        String fileDigestBeforeReopen = walFileDigest(directory);
        byte[] envelope = M08CrashChildMain.envelope(SHARD, point);
        try (LocalMatchingRuntime recovered =
            LocalMatchingRuntime.open(WalConfig.defaults(directory, SHARD))) {
          SubmissionResult retry = recovered.submit(envelope);
          boolean torn = point == FaultPoint.AFTER_RECORD_LENGTH_WRITE;
          if (torn) {
            SubmissionResult.NewDurablyApplied applied =
                requireType(
                    retry,
                    SubmissionResult.NewDurablyApplied.class,
                    "length-only child crash was replayed");
            require(applied.position().walSequence() == 1, "torn-tail retry WAL sequence changed");
            require(
                recovered.semanticStateDigest().equals(applied.result().semanticStateDigest()),
                "torn-tail retry semantic digest disagreed with the applied result");
          } else {
            SubmissionResult.DuplicateReplayed duplicate =
                requireType(
                    retry,
                    SubmissionResult.DuplicateReplayed.class,
                    "durable child crash lost exact identity");
            require(
                recovered
                    .semanticStateDigest()
                    .equals(duplicate.originalResult().semanticStateDigest()),
                "fresh recovery semantic digest disagreed with original result");
            require(
                duplicate.originalPosition().walSequence() == 1,
                "durable child crash changed WAL position");
          }
          String fileDigestAfterRetry = walFileDigest(directory);
          require(
              torn
                  ? !fileDigestBeforeReopen.equals(fileDigestAfterRetry)
                  : fileDigestBeforeReopen.equals(fileDigestAfterRetry),
              "child crash WAL byte digest changed contrary to the recovery window");
          ObjectNode observation = windows.addObject();
          observation.put("faultPoint", point.name());
          observation.put("childExit", M08CrashChildMain.HALT_EXIT);
          observation.put("markerForced", true);
          observation.put("freshReopen", true);
          observation.put("retryResult", retry.getClass().getSimpleName());
          observation.put("semanticDigestChecked", true);
          observation.put("fileDigestBeforeReopen", fileDigestBeforeReopen);
          observation.put("fileDigestAfterRetry", fileDigestAfterRetry);
          observation.put(
              "fileDigestRelation", torn ? "CHANGED_BY_REPAIR_AND_RETRY" : "BYTE_EXACT");
          observation.put("preprovisionedWalDirectory", true);
          observation.put("ancestorDirectoryDurabilityExternal", true);
          observation.put("processCrashSmoke", true);
          observation.put("powerLossProof", false);
        } catch (IOException failure) {
          throw new IllegalStateException("cannot reopen M08 child-crash WAL", failure);
        }
      }
      return new Result(windows, WINDOWS.size());
    } finally {
      deleteTree(root);
    }
  }

  private static void runChild(Path directory, FaultPoint point) {
    Path output = directory.resolveSibling(directory.getFileName() + ".child-output.txt");
    try {
      Files.createDirectories(directory);
      Process process =
          new ProcessBuilder(
                  Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                  "-cp",
                  System.getProperty("java.class.path"),
                  M08CrashChildMain.class.getName(),
                  directory.toString(),
                  Long.toString(SHARD),
                  point.name())
              .redirectErrorStream(true)
              .redirectOutput(output.toFile())
              .start();
      boolean exited = process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      if (!exited) {
        process.destroyForcibly();
        throw new IllegalStateException("M08 child halt smoke timed out at " + point);
      }
      String childOutput = Files.exists(output) ? Files.readString(output) : "";
      require(
          process.exitValue() == M08CrashChildMain.HALT_EXIT,
          "M08 child exited " + process.exitValue() + " at " + point + ": " + childOutput);
      Files.deleteIfExists(output);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot execute M08 child halt smoke", failure);
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("M08 child halt smoke interrupted", failure);
    }
  }

  private static <T> T requireType(Object value, Class<T> type, String message) {
    require(type.isInstance(value), message + ": " + value);
    return type.cast(value);
  }

  private static String walFileDigest(Path directory) {
    try {
      ByteArrayOutputStream canonical = new ByteArrayOutputStream();
      try (var paths = Files.list(directory)) {
        for (Path path :
            paths
                .filter(
                    value -> value.getFileName().toString().matches("segment-[0-9]{20}\\.m08w1"))
                .sorted(Comparator.comparing(Path::toString))
                .toList()) {
          canonical.write(path.getFileName().toString().getBytes(StandardCharsets.UTF_8));
          canonical.write(0);
          canonical.write(Files.readAllBytes(path));
        }
      }
      return Hashing.sha256Hex(canonical.toByteArray());
    } catch (IOException failure) {
      throw new IllegalStateException("cannot digest child-crash WAL files", failure);
    }
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
      throw new IllegalStateException("cannot clear M08 crash-smoke path", failure);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new M08SemanticFailure(message);
    }
  }

  record Result(ArrayNode windows, int processCrashes) {
    Result {
      windows = windows.deepCopy();
    }

    @Override
    public ArrayNode windows() {
      return windows.deepCopy();
    }
  }
}
