package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.MarketMode;
import io.github.lchareln.cex.matching.local.CheckpointResult;
import io.github.lchareln.cex.matching.local.FaultInjector;
import io.github.lchareln.cex.matching.local.FaultPoint;
import io.github.lchareln.cex.matching.local.LocalMatchingRuntime;
import io.github.lchareln.cex.matching.local.M08Command;
import io.github.lchareln.cex.matching.local.RuntimeState;
import io.github.lchareln.cex.matching.local.SubmissionResult;
import io.github.lchareln.cex.matching.local.WalConfig;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

/** Candidate snapshot runtime beside a retained-genesis-WAL runtime and independent ledger. */
final class M09DualRuntimeHarness implements AutoCloseable {
  private final M09ScenarioSupport support = new M09ScenarioSupport();
  private final M09FileInventory inventory = new M09FileInventory();
  private final Path candidateDirectory;
  private final Path genesisDirectory;
  private final WalConfig candidateConfig;
  private final WalConfig genesisConfig;
  private final M09StorageLedger ledger;
  private final String producer;
  private LocalMatchingRuntime candidate;
  private LocalMatchingRuntime genesis;
  private long producerSequence = 1;
  private long nextOrderId;
  private Planned latest;
  private int automaticCheckpoints;
  private int restarts;
  private int snapshots;
  private int comparisons;
  private int ledgerChecks;
  private int inventoryChecks;
  private int budgetPreludeOperations;
  private int budgetPredictionChecks;
  private int budgetPredictedAccepts;
  private int budgetPredictedRejects;
  private int checkpointRequiredWitnesses;

  M09DualRuntimeHarness(Path root, int history) throws IOException {
    candidateDirectory = Files.createDirectories(root.resolve("candidate"));
    genesisDirectory = Files.createDirectories(root.resolve("genesis"));
    candidateConfig = support.config(candidateDirectory);
    genesisConfig = support.unbounded(genesisDirectory);
    ledger =
        new M09StorageLedger(
            candidateConfig.maxSegmentBytes(),
            candidateConfig.recoveryBudget().maxSuffixRecords(),
            candidateConfig.recoveryBudget().maxSuffixBytes());
    producer = "generated-" + history;
    nextOrderId = 1_000_000L + history * 10_000L;
    open();
  }

  void prefix(String lane) throws IOException {
    switch (lane) {
      case "STATE_AND_IDENTITY" -> submit(command(1, false, false));
      case "CUT_AND_SUFFIX" -> {
        submit(command(2, false, true));
        checkpoint();
      }
      case "PUBLISH_AND_SELECTION" -> {
        submit(command(3, false, true));
        checkpoint();
        submit(command(4, false, true));
        checkpoint();
      }
      case "RETIREMENT_AND_BUDGET" -> {
        for (int index = 0; index < 3; index++) {
          submit(command(5 + index, false, true));
          checkpoint();
        }
      }
      default -> throw new IllegalStateException("unknown M09 generated lane " + lane);
    }
  }

  void exerciseBudgetPrediction() throws IOException {
    int beforeCheckpoints = checkpointRequiredWitnesses;
    for (int index = 0; index < 65; index++) {
      submit(M09ScenarioSupport.cancel(nextOrderId++));
      budgetPreludeOperations++;
    }
    M09ScenarioSupport.require(
        checkpointRequiredWitnesses == beforeCheckpoints + 1,
        "M09 generated budget prelude did not observe one checkpoint requirement");
    M09ScenarioSupport.require(
        ledger.suffixRecords() == 1, "M09 generated budget prelude did not retry after checkpoint");
  }

  Observation execute(
      M09History.Kind kind, long draw, boolean businessRejection, boolean controlCommand)
      throws IOException {
    String result;
    switch (kind) {
      case SUBMIT -> result = submit(command(draw, controlCommand, businessRejection));
      case DUPLICATE -> result = duplicate(false);
      case CONFLICT -> result = duplicate(true);
      case ROLLOVER -> result = submit(M09ScenarioSupport.largeBusinessRejection(nextOrderId++));
      case SNAPSHOT, RETIRE -> {
        checkpoint();
        result = "CHECKPOINT|" + ledger.snapshotGeneration() + '|' + ledger.retiredThrough();
      }
      case RESTART -> {
        restart();
        result = "RESTART|" + candidate.nextWalSequence();
      }
      case CRASH -> result = forcedUnknown(command(draw, controlCommand, businessRejection));
      default -> throw new IllegalStateException("unhandled M09 generated operation " + kind);
    }
    verifyEquivalent();
    return new Observation(
        result,
        candidate.nextWalSequence(),
        candidate.semanticStateDigest(),
        ledger.suffixRecords(),
        ledger.suffixBytes());
  }

  private M08Command command(long draw, boolean control, boolean businessRejection) {
    if (control) {
      return new M08Command.ChangeMarketMode(
          Long.MAX_VALUE,
          MarketMode.OPEN,
          MarketMode.CANCEL_ONLY,
          "generated-control-" + Long.toUnsignedString(draw));
    }
    if (businessRejection) {
      return new M08Command.Place(
          "BTC-USDT",
          BigInteger.valueOf(nextOrderId++),
          "BUY",
          BigInteger.valueOf(-1),
          BigInteger.ONE,
          "GTC",
          0,
          "NONE",
          Optional.empty());
    }
    String side = (draw & 1) == 0 ? "BUY" : "SELL";
    long price = 20_000 + Long.remainderUnsigned(draw, 17);
    return M09ScenarioSupport.place(
        nextOrderId++, side, price, 1 + Long.remainderUnsigned(draw >>> 8, 5), 0, "NONE");
  }

  private String submit(M08Command command) throws IOException {
    long sequence = producerSequence++;
    UUID id = new UUID(0x5909000000000000L, sequence);
    byte[] envelope = support.envelope(producer, 1, sequence, id, command);
    boolean predictedAccepts = predict(envelope);
    SubmissionResult candidateResult = candidate.submit(envelope);
    ledger.observeNewSubmit(envelope, predictedAccepts, candidateResult);
    if (candidateResult instanceof SubmissionResult.CheckpointRequired) {
      checkpointRequiredWitnesses++;
      checkpoint();
      automaticCheckpoints++;
      predictedAccepts = predict(envelope);
      candidateResult = candidate.submit(envelope);
      ledger.observeNewSubmit(envelope, predictedAccepts, candidateResult);
    }
    SubmissionResult genesisResult = genesis.submit(envelope);
    requireComparable(candidateResult, genesisResult, "generated submit");
    M09ScenarioSupport.require(
        candidateResult instanceof SubmissionResult.NewDurablyApplied,
        "generated new command did not durably apply");
    latest = new Planned(sequence, id, command, envelope);
    return M09ScenarioSupport.signature(candidateResult);
  }

  private String duplicate(boolean conflict) {
    if (latest == null) {
      throw new IllegalStateException("M09 generated duplicate has no prior binding");
    }
    byte[] envelope =
        conflict
            ? support.envelope(
                producer,
                1,
                latest.sequence(),
                new UUID(latest.commandId().getMostSignificantBits() ^ 1, latest.sequence()),
                M09ScenarioSupport.cancel(nextOrderId + 99))
            : latest.envelope();
    SubmissionResult candidateResult = candidate.submit(envelope);
    ledger.observePreflightNoAppend(candidateResult);
    SubmissionResult genesisResult = genesis.submit(envelope);
    requireComparable(candidateResult, genesisResult, "generated duplicate/conflict");
    M09ScenarioSupport.require(
        conflict
            ? candidateResult instanceof SubmissionResult.PreflightRejected
            : candidateResult instanceof SubmissionResult.DuplicateReplayed,
        "generated identity operation changed classification");
    return M09ScenarioSupport.signature(candidateResult);
  }

  private String forcedUnknown(M08Command command) throws IOException {
    long sequence = producerSequence++;
    UUID id = new UUID(0x5909000000000000L, sequence);
    byte[] envelope = support.envelope(producer, 1, sequence, id, command);
    boolean predictedAccepts = predict(envelope);
    if (!predictedAccepts) {
      SubmissionResult checkpointRequired = candidate.submit(envelope);
      ledger.observeNewSubmit(envelope, false, checkpointRequired);
      checkpointRequiredWitnesses++;
      checkpoint();
      automaticCheckpoints++;
      predictedAccepts = predict(envelope);
    }
    closeRuntimes();
    OneShotFailure fault = new OneShotFailure(FaultPoint.BEFORE_LIVE_APPLY);
    candidate = LocalMatchingRuntime.open(candidateConfig, fault);
    genesis = LocalMatchingRuntime.open(genesisConfig);
    SubmissionResult raw = candidate.submit(envelope);
    systemRequire(
        raw instanceof SubmissionResult.DurabilityUnknown,
        "generated crash boundary did not return DurabilityUnknown");
    systemRequire(fault.hit(), "generated crash boundary hook was not reached");
    SubmissionResult.DurabilityUnknown unknown = (SubmissionResult.DurabilityUnknown) raw;
    long nextBeforeBlockedRetry = candidate.nextWalSequence();
    M09FileInventory.Inventory beforeBlockedRetry = inventory.inspect(candidateDirectory);
    SubmissionResult blocked = candidate.submit(envelope);
    long nextAfterBlockedRetry = candidate.nextWalSequence();
    M09FileInventory.Inventory afterBlockedRetry = inventory.inspect(candidateDirectory);
    requireForcedUnknownHarness(
        unknown,
        candidate.state(),
        blocked,
        fault.expectedDetail(),
        nextBeforeBlockedRetry,
        nextAfterBlockedRetry,
        beforeBlockedRetry,
        afterBlockedRetry);
    inventoryChecks++;
    ledger.observeForcedDurable(envelope, predictedAccepts, unknown);
    SubmissionResult genesisResult = genesis.submit(envelope);
    M09ScenarioSupport.require(
        genesisResult instanceof SubmissionResult.NewDurablyApplied,
        "genesis control did not apply crash-boundary command");
    closeRuntimes();
    open();
    restarts++;
    ledger.verifyRestart(candidate.nextWalSequence());
    SubmissionResult candidateRetry = candidate.submit(envelope);
    SubmissionResult genesisRetry = genesis.submit(envelope);
    requireComparable(candidateRetry, genesisRetry, "generated forced-unknown retry");
    M09ScenarioSupport.require(
        candidateRetry instanceof SubmissionResult.DuplicateReplayed,
        "forced durable command did not recover as duplicate");
    latest = new Planned(sequence, id, command, envelope);
    return "FORCED_UNKNOWN_RECOVERED|" + M09ScenarioSupport.signature(candidateRetry);
  }

  private void checkpoint() throws IOException {
    CheckpointResult result = candidate.checkpoint();
    ledger.observeCheckpoint(result);
    ledger.verifyInventory(inventory.inspect(candidateDirectory));
    inventoryChecks++;
    snapshots++;
  }

  private void restart() throws IOException {
    closeRuntimes();
    open();
    restarts++;
    ledger.verifyRestart(candidate.nextWalSequence());
    ledger.verifyInventory(inventory.inspect(candidateDirectory));
    inventoryChecks++;
    ledgerChecks++;
  }

  private void open() throws IOException {
    candidate = LocalMatchingRuntime.open(candidateConfig);
    try {
      genesis = LocalMatchingRuntime.open(genesisConfig);
    } catch (IOException failure) {
      candidate.close();
      candidate = null;
      throw failure;
    }
  }

  private void verifyEquivalent() {
    M09ScenarioSupport.requireEquivalent(candidate, genesis);
    ledger.verifyRestart(candidate.nextWalSequence());
    comparisons++;
    ledgerChecks++;
  }

  private static void requireComparable(
      SubmissionResult candidate, SubmissionResult genesis, String boundary) {
    M09ScenarioSupport.require(
        M09ScenarioSupport.signature(candidate).equals(M09ScenarioSupport.signature(genesis)),
        boundary + " diverged from retained genesis WAL runtime");
  }

  static void requireForcedUnknownHarness(
      SubmissionResult.DurabilityUnknown unknown,
      RuntimeState state,
      SubmissionResult blocked,
      String expectedDetail,
      long nextBeforeBlockedRetry,
      long nextAfterBlockedRetry,
      M09FileInventory.Inventory beforeBlockedRetry,
      M09FileInventory.Inventory afterBlockedRetry) {
    systemRequire("APPLY_OR_ACK".equals(unknown.stage()), "forced unknown stage changed");
    systemRequire(expectedDetail.equals(unknown.detail()), "forced unknown detail changed");
    systemRequire(
        state == RuntimeState.FAILED_CLOSED, "forced unknown runtime did not fail closed");
    systemRequire(
        blocked instanceof SubmissionResult.FailedClosed,
        "same-instance retry escaped failed-closed state");
    SubmissionResult.FailedClosed failedClosed = (SubmissionResult.FailedClosed) blocked;
    systemRequire(
        (unknown.stage() + ": " + unknown.detail()).equals(failedClosed.detail()),
        "same-instance failed-closed detail changed");
    systemRequire(
        nextBeforeBlockedRetry == nextAfterBlockedRetry,
        "same-instance failed-closed retry advanced WAL");
    systemRequire(
        beforeBlockedRetry.entries().equals(afterBlockedRetry.entries()),
        "same-instance failed-closed retry changed runtime files");
  }

  private static void systemRequire(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }

  Metrics metrics() {
    return new Metrics(
        automaticCheckpoints,
        restarts,
        snapshots,
        comparisons,
        ledgerChecks,
        inventoryChecks,
        budgetPreludeOperations,
        budgetPredictionChecks,
        budgetPredictedAccepts,
        budgetPredictedRejects,
        checkpointRequiredWitnesses);
  }

  private boolean predict(byte[] envelope) {
    boolean accepts = ledger.accepts(envelope.length);
    budgetPredictionChecks++;
    if (accepts) {
      budgetPredictedAccepts++;
    } else {
      budgetPredictedRejects++;
    }
    return accepts;
  }

  @Override
  public void close() throws IOException {
    closeRuntimes();
  }

  private void closeRuntimes() throws IOException {
    IOException failure = null;
    if (candidate != null) {
      try {
        candidate.close();
      } catch (IOException closeFailure) {
        failure = closeFailure;
      }
      candidate = null;
    }
    if (genesis != null) {
      try {
        genesis.close();
      } catch (IOException closeFailure) {
        if (failure == null) {
          failure = closeFailure;
        } else {
          failure.addSuppressed(closeFailure);
        }
      }
      genesis = null;
    }
    if (failure != null) {
      throw failure;
    }
  }

  record Observation(
      String result,
      long nextWalSequence,
      String semanticDigest,
      long suffixRecords,
      long suffixBytes) {}

  record Metrics(
      int automaticCheckpoints,
      int restarts,
      int snapshots,
      int comparisons,
      int ledgerChecks,
      int inventoryChecks,
      int budgetPreludeOperations,
      int budgetPredictionChecks,
      int budgetPredictedAccepts,
      int budgetPredictedRejects,
      int checkpointRequiredWitnesses) {}

  private record Planned(long sequence, UUID commandId, M08Command command, byte[] envelope) {
    private Planned {
      envelope = envelope.clone();
    }

    @Override
    public byte[] envelope() {
      return envelope.clone();
    }
  }

  private static final class OneShotFailure implements FaultInjector {
    private final FaultPoint target;
    private IOException injected;
    private boolean hit;

    private OneShotFailure(FaultPoint target) {
      this.target = target;
    }

    @Override
    public void hit(FaultPoint point) throws IOException {
      if (!hit && point == target) {
        hit = true;
        injected = new IOException("injected M09 generated boundary " + point);
        throw injected;
      }
    }

    private boolean hit() {
      return hit;
    }

    private String expectedDetail() {
      if (injected == null) {
        throw new IllegalStateException("M09 generated fault has no injected IOException");
      }
      return "IOException: " + injected.getMessage();
    }
  }
}
