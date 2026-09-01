package io.github.lchareln.cex.matching.testkit;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Executes the frozen seed 5909, four-lane, 96 by 40 generated snapshot histories. */
final class M09GeneratedSuite {
  Result run(M09Corpus corpus, Path workingRoot) {
    Path root = workingRoot.toAbsolutePath().normalize();
    M09ScenarioSupport.deleteTree(root);
    provision(root);
    M09Corpus.GeneratorProfile profile = corpus.generator();
    ByteArrayOutputStream canonical = new ByteArrayOutputStream();
    write(canonical, "M09G1\n");
    Map<String, Integer> laneCounts = new LinkedHashMap<>();
    Map<M09History.Kind, Integer> operationCounts = new EnumMap<>(M09History.Kind.class);
    List<M09History> histories = new ArrayList<>();
    int operations = 0;
    int comparisons = 0;
    int ledgerChecks = 0;
    int inventoryChecks = 0;
    int restarts = 0;
    int snapshots = 0;
    int automaticCheckpoints = 0;
    int businessRejections = 0;
    int controlCommands = 0;
    int budgetPreludeOperations = 0;
    int budgetPredictionChecks = 0;
    int budgetPredictedAccepts = 0;
    int budgetPredictedRejects = 0;
    int checkpointRequiredWitnesses = 0;
    try {
      for (int history = 0; history < profile.histories(); history++) {
        M09Corpus.Lane lane = profile.lanes().get(history % profile.lanes().size());
        laneCounts.merge(lane.id(), 1, Integer::sum);
        long seed = profile.baseSeed() + history;
        M03SplitMix64V1 random = new M03SplitMix64V1(seed);
        List<M09History.Operation> planned = new ArrayList<>();
        Path historyRoot = root.resolve("history-%03d".formatted(history));
        provision(historyRoot);
        try (M09DualRuntimeHarness harness = new M09DualRuntimeHarness(historyRoot, history)) {
          if (history == 0) {
            harness.exerciseBudgetPrediction();
          }
          harness.prefix(lane.id());
          for (int operation = 0; operation < profile.operationsPerHistory(); operation++) {
            long draw = random.nextLong();
            M09History.Kind kind = select(draw);
            if (kind == M09History.Kind.DUPLICATE && (random.nextLong() & 1) != 0) {
              kind = M09History.Kind.CONFLICT;
            }
            int globalIndex = history * profile.operationsPerHistory() + operation;
            boolean businessRejection = globalIndex % profile.businessRejectionOneIn() == 0;
            boolean control =
                globalIndex % profile.controlCommandOneIn() == profile.controlCommandOneIn() / 2;
            if (businessRejection) {
              businessRejections++;
              kind = M09History.Kind.SUBMIT;
            }
            if (control) {
              controlCommands++;
              kind = M09History.Kind.SUBMIT;
            }
            M09DualRuntimeHarness.Observation observed =
                harness.execute(kind, draw, businessRejection, control);
            planned.add(new M09History.Operation(kind, draw));
            operationCounts.merge(kind, 1, Integer::sum);
            write(
                canonical,
                history
                    + "|"
                    + operation
                    + "|"
                    + lane.id()
                    + "|"
                    + kind
                    + "|"
                    + Long.toUnsignedString(draw)
                    + "|"
                    + observed.result()
                    + "|"
                    + observed.nextWalSequence()
                    + "|"
                    + observed.semanticDigest()
                    + "|"
                    + observed.suffixRecords()
                    + "|"
                    + observed.suffixBytes()
                    + '\n');
            operations++;
          }
          M09DualRuntimeHarness.Metrics metrics = harness.metrics();
          comparisons += metrics.comparisons();
          ledgerChecks += metrics.ledgerChecks();
          inventoryChecks += metrics.inventoryChecks();
          restarts += metrics.restarts();
          snapshots += metrics.snapshots();
          automaticCheckpoints += metrics.automaticCheckpoints();
          budgetPreludeOperations += metrics.budgetPreludeOperations();
          budgetPredictionChecks += metrics.budgetPredictionChecks();
          budgetPredictedAccepts += metrics.budgetPredictedAccepts();
          budgetPredictedRejects += metrics.budgetPredictedRejects();
          checkpointRequiredWitnesses += metrics.checkpointRequiredWitnesses();
        }
        histories.add(new M09History(lane.id(), history, seed, planned));
      }
      systemRequire(operations == 3_840, "M09 generated operation count changed");
      systemRequire(comparisons == 3_840, "M09 generated comparison count changed");
      systemRequire(ledgerChecks >= 3_840, "M09 independent ledger did not check every operation");
      systemRequire(
          laneCounts.values().stream().allMatch(count -> count == 24),
          "M09 generated lane distribution changed");
      systemRequire(businessRejections == 480, "M09 business rejection selector changed");
      systemRequire(controlCommands == 480, "M09 control command selector changed");
      systemRequire(budgetPreludeOperations == 65, "M09 generated budget prelude changed");
      int freshAppendCandidates =
          operationCounts.getOrDefault(M09History.Kind.SUBMIT, 0)
              + operationCounts.getOrDefault(M09History.Kind.ROLLOVER, 0)
              + operationCounts.getOrDefault(M09History.Kind.CRASH, 0)
              + 168
              + budgetPreludeOperations;
      systemRequire(
          budgetPredictionChecks == freshAppendCandidates + checkpointRequiredWitnesses,
          "M09 generated budget prediction scope changed");
      systemRequire(
          budgetPredictedRejects >= 1 && checkpointRequiredWitnesses >= 1,
          "M09 generated budget rejection was not independently predicted");
      byte[] bytes = canonical.toByteArray();
      return new Result(
          histories,
          laneCounts,
          operationCounts,
          operations,
          comparisons,
          ledgerChecks,
          inventoryChecks,
          restarts,
          snapshots,
          automaticCheckpoints,
          businessRejections,
          controlCommands,
          budgetPreludeOperations,
          budgetPredictionChecks,
          budgetPredictedAccepts,
          budgetPredictedRejects,
          checkpointRequiredWitnesses,
          bytes,
          Hashing.sha256Hex(bytes));
    } catch (IOException failure) {
      throw new IllegalStateException("M09 generated runtime I/O failed", failure);
    } finally {
      M09ScenarioSupport.deleteTree(root);
    }
  }

  private static M09History.Kind select(long draw) {
    int selected = (int) Long.remainderUnsigned(draw, 100);
    if (selected < 44) return M09History.Kind.SUBMIT;
    if (selected < 54) return M09History.Kind.DUPLICATE;
    if (selected < 68) return M09History.Kind.SNAPSHOT;
    if (selected < 80) return M09History.Kind.RESTART;
    if (selected < 86) return M09History.Kind.ROLLOVER;
    if (selected < 94) return M09History.Kind.RETIRE;
    return M09History.Kind.CRASH;
  }

  private static void write(ByteArrayOutputStream target, String value) {
    try {
      target.write(value.getBytes(StandardCharsets.UTF_8));
    } catch (IOException impossible) {
      throw new IllegalStateException("cannot write in-memory M09 history", impossible);
    }
  }

  private static void provision(Path directory) {
    try {
      Files.createDirectories(directory);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot provision M09 generated directory", failure);
    }
  }

  private static void systemRequire(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }

  record Result(
      List<M09History> historyPlans,
      Map<String, Integer> laneCounts,
      Map<M09History.Kind, Integer> operationCounts,
      int operations,
      int comparisons,
      int ledgerChecks,
      int inventoryChecks,
      int restarts,
      int snapshots,
      int automaticCheckpoints,
      int businessRejections,
      int controlCommands,
      int budgetPreludeOperations,
      int budgetPredictionChecks,
      int budgetPredictedAccepts,
      int budgetPredictedRejects,
      int checkpointRequiredWitnesses,
      byte[] canonicalBytes,
      String digest) {
    Result {
      historyPlans = List.copyOf(historyPlans);
      laneCounts = Map.copyOf(laneCounts);
      operationCounts = Map.copyOf(operationCounts);
      canonicalBytes = canonicalBytes.clone();
    }

    @Override
    public byte[] canonicalBytes() {
      return canonicalBytes.clone();
    }
  }
}
