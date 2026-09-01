package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.local.LocalMatchingRuntime;
import io.github.lchareln.cex.matching.local.M09RuntimeJudgeProbe;
import io.github.lchareln.cex.matching.local.SnapshotCorruptionException;
import io.github.lchareln.cex.matching.local.SubmissionResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Seven real child-JVM Runtime.halt(86) observations at the frozen M09 windows. */
final class M09CrashSmoke {
  private final M09ScenarioSupport support = new M09ScenarioSupport();
  private final M09FileInventory inventory = new M09FileInventory();

  Result run(M09Corpus corpus, Path workingRoot) {
    Path root = workingRoot.toAbsolutePath().normalize();
    M09ScenarioSupport.deleteTree(root);
    provision(root);
    ArrayNode results = JsonSupport.MAPPER.createArrayNode();
    try {
      for (String window : corpus.generator().crashWindows()) {
        Path scenario = provision(root.resolve(window.toLowerCase(java.util.Locale.ROOT)));
        Path directory = scenario.resolve("runtime");
        Path marker = scenario.resolve("halt.marker");
        provision(directory);
        ChildResult child = runChild(directory, marker, window);
        systemRequire(
            child.exitCode() == M09CrashChildMain.HALT_CODE,
            "child did not halt at "
                + window
                + ": exit="
                + child.exitCode()
                + " output="
                + child.output());
        systemRequire(Files.isRegularFile(marker), "child halt marker is missing: " + window);
        Map<String, String> expected = marker(marker);
        validateHarnessObservation(
            child.exitCode(),
            true,
            window,
            expected.get("window"),
            M09CrashChildMain.expectedFaultPoint(window).name(),
            expected.get("faultPoint"),
            1,
            Integer.parseInt(expected.get("occurrence")));
        ExpectedInventory expectedInventory = expectedInventory(window);
        M09FileInventory.Inventory haltedInventory = inventory.inspect(directory);
        int canonicalSnapshots = validateInventory(haltedInventory, directory, expectedInventory);
        long expectedNext = Long.parseLong(expected.get("nextWalSequence"));
        String expectedDigest = expected.get("semanticDigest");
        int durableCommands = expectedNext == 2 ? 1 : 2;
        try (LocalMatchingRuntime restored = LocalMatchingRuntime.open(support.config(directory))) {
          require(restored.nextWalSequence() == expectedNext, "child restart WAL sequence changed");
          require(
              restored.semanticStateDigest().equals(expectedDigest),
              "child restart semantic digest changed");
          M09ScenarioSupport.CommandStream retry = support.stream("child-" + window);
          for (int index = 1; index <= durableCommands; index++) {
            SubmissionResult result = restored.submit(retry.next(M09ScenarioSupport.cancel(index)));
            M09ScenarioSupport.requireDuplicate(result, "child restart duplicate " + index);
          }
        } catch (IOException failure) {
          throw new IllegalStateException("cannot reopen child halt directory " + window, failure);
        }
        var node = results.addObject();
        node.put("window", window);
        node.put("exitCode", child.exitCode());
        node.put("markerForced", true);
        node.put("faultPoint", expected.get("faultPoint"));
        node.put("occurrence", Integer.parseInt(expected.get("occurrence")));
        node.put("freshReopen", true);
        node.put("expectedNextWalSequence", expectedNext);
        node.put("processCrash", true);
        node.put("runtimeHalt", true);
        node.put("powerLossProof", false);
        node.put("haltAtDeclaredHookAndNamespaceObserved", true);
        node.put("underlyingOperationOrderClaim", false);
        node.put("physicalDurabilityClaim", false);
        ObjectNode expectedInventoryNode = node.putObject("expectedInventoryAtHalt");
        expectedInventoryNode.put("snapshotTemps", expectedInventory.snapshotTemps());
        expectedInventoryNode.put("snapshots", expectedInventory.snapshots());
        expectedInventoryNode.put("walSegments", expectedInventory.walSegments());
        expectedInventoryNode.put("canonicalSnapshotFiles", expectedInventory.canonicalSnapshots());
        expectedInventoryNode.put("tempState", expectedInventory.tempState());
        node.set("inventoryAtHalt", haltedInventory.report());
        node.put("canonicalSnapshotFilesAtHalt", canonicalSnapshots);
        node.put("stdout", child.output());
      }
      validateObservedCount(results.size(), corpus.generator().crashWindows().size());
      return new Result(results, results.size());
    } finally {
      M09ScenarioSupport.deleteTree(root);
    }
  }

  private static ChildResult runChild(Path directory, Path marker, String window) {
    String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    List<String> command = new ArrayList<>();
    command.add(java);
    command.add("-cp");
    command.add(System.getProperty("java.class.path"));
    command.add(M09CrashChildMain.class.getName());
    command.add(directory.toString());
    command.add(marker.toString());
    command.add(window);
    try {
      Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
      boolean completed = process.waitFor(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
      if (!completed) {
        process.destroyForcibly();
        throw new IllegalStateException("M09 child halt timed out: " + window);
      }
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      return new ChildResult(process.exitValue(), output.strip());
    } catch (IOException failure) {
      throw new IllegalStateException("cannot start M09 child halt: " + window, failure);
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("M09 child halt interrupted: " + window, failure);
    }
  }

  private static Map<String, String> marker(Path path) {
    try {
      java.util.Properties properties = new java.util.Properties();
      try (var reader = Files.newBufferedReader(path)) {
        properties.load(reader);
      }
      return Map.of(
          "window", properties.getProperty("window"),
          "faultPoint", properties.getProperty("faultPoint"),
          "occurrence", properties.getProperty("occurrence"),
          "nextWalSequence", properties.getProperty("nextWalSequence"),
          "semanticDigest", properties.getProperty("semanticDigest"));
    } catch (IOException failure) {
      throw new IllegalStateException("cannot read M09 child marker", failure);
    }
  }

  private static Path provision(Path directory) {
    try {
      return Files.createDirectories(directory);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot provision M09 child halt directory", failure);
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

  static void validateHarnessObservation(
      int exitCode,
      boolean markerPresent,
      String expectedWindow,
      String observedWindow,
      String expectedFaultPoint,
      String observedFaultPoint,
      int expectedOccurrence,
      int observedOccurrence) {
    systemRequire(exitCode == M09CrashChildMain.HALT_CODE, "child halt exit changed");
    systemRequire(markerPresent, "child halt marker is missing");
    systemRequire(expectedWindow.equals(observedWindow), "child marker window changed");
    systemRequire(
        expectedFaultPoint.equals(observedFaultPoint), "child marker fault point changed");
    systemRequire(expectedOccurrence == observedOccurrence, "child marker occurrence changed");
  }

  static void validateObservedCount(int observed, int required) {
    systemRequire(observed == required && required == 7, "M09 child halt count changed");
  }

  private int validateInventory(
      M09FileInventory.Inventory observed, Path directory, ExpectedInventory expected) {
    validateInventoryCounts(
        observed.count(M09FileInventory.Kind.SNAPSHOT_TEMP),
        observed.count(M09FileInventory.Kind.SNAPSHOT),
        observed.count(M09FileInventory.Kind.WAL_SEGMENT),
        expected.snapshotTemps(),
        expected.snapshots(),
        expected.walSegments(),
        expected.window());
    List<Path> snapshotPaths = new ArrayList<>(M09ScenarioSupport.snapshotFiles(directory));
    snapshotPaths.addAll(M09ScenarioSupport.tempSnapshots(directory));
    int canonical = 0;
    for (Path path : snapshotPaths) {
      try {
        M09RuntimeJudgeProbe.requireCanonicalSnapshot(path);
        canonical++;
      } catch (SnapshotCorruptionException expectedCorruption) {
        // Empty and partial temp files are expected only in the two pre-force windows.
      } catch (IOException failure) {
        throw new IllegalStateException("cannot inspect child halt snapshot " + path, failure);
      }
    }
    require(
        canonical == expected.canonicalSnapshots(),
        "child halt canonical snapshot count changed: " + expected.window());
    List<M09FileInventory.Entry> temps =
        observed.entries().stream()
            .filter(entry -> entry.kind() == M09FileInventory.Kind.SNAPSHOT_TEMP)
            .toList();
    if ("EMPTY".equals(expected.tempState())) {
      require(temps.size() == 1 && temps.getFirst().size() == 0, "halt temp was not empty");
    } else if ("PARTIAL".equals(expected.tempState())) {
      require(
          temps.size() == 1 && temps.getFirst().size() > 0 && canonical == 0,
          "halt temp was not a non-canonical partial file");
    } else if ("CANONICAL".equals(expected.tempState())) {
      require(temps.size() == 1 && canonical == 1, "halt temp was not a canonical forced file");
    } else {
      require(temps.isEmpty(), "halt inventory unexpectedly retained a temp snapshot");
    }
    return canonical;
  }

  static void validateInventoryCounts(
      long observedTemps,
      long observedSnapshots,
      long observedWalSegments,
      long expectedTemps,
      long expectedSnapshots,
      long expectedWalSegments,
      String window) {
    require(observedTemps == expectedTemps, "child halt temp inventory changed: " + window);
    require(
        observedSnapshots == expectedSnapshots,
        "child halt final snapshot inventory changed: " + window);
    require(
        observedWalSegments == expectedWalSegments, "child halt WAL inventory changed: " + window);
  }

  private static ExpectedInventory expectedInventory(String window) {
    return switch (window) {
      case "BEFORE_SNAPSHOT_TEMP_WRITE" -> new ExpectedInventory(window, 1, 0, 1, 0, "EMPTY");
      case "AFTER_PARTIAL_SNAPSHOT_TEMP_WRITE" ->
          new ExpectedInventory(window, 1, 0, 1, 0, "PARTIAL");
      case "AFTER_SNAPSHOT_FILE_FORCE_BEFORE_RENAME" ->
          new ExpectedInventory(window, 1, 0, 1, 1, "CANONICAL");
      case "AFTER_SNAPSHOT_RENAME_BEFORE_DIRECTORY_FORCE",
          "AFTER_SNAPSHOT_DIRECTORY_FORCE_BEFORE_RETIREMENT" ->
          new ExpectedInventory(window, 0, 1, 1, 1, "ABSENT");
      case "AFTER_FIRST_SEGMENT_DELETE_BEFORE_DIRECTORY_FORCE",
          "AFTER_RETIREMENT_DIRECTORY_FORCE_BEFORE_RETURN" ->
          new ExpectedInventory(window, 0, 2, 2, 2, "ABSENT");
      default -> throw new IllegalStateException("missing M09 crash inventory " + window);
    };
  }

  private record ChildResult(int exitCode, String output) {}

  private record ExpectedInventory(
      String window,
      int snapshotTemps,
      int snapshots,
      int walSegments,
      int canonicalSnapshots,
      String tempState) {}

  record Result(ArrayNode windows, int observed) {
    Result {
      windows = windows.deepCopy();
    }

    @Override
    public ArrayNode windows() {
      return windows.deepCopy();
    }
  }
}
