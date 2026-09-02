package io.github.lchareln.cex.matching.testkit;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Repository-owned SplitMix64 admission histories; the output is a replay input, not a load claim.
 */
final class M10GeneratedSuite {
  static final long BASE_SEED = 6010;
  static final int HISTORIES = 64;
  static final int ACTIONS_PER_HISTORY = 256;

  Result generate() {
    StringBuilder canonical = new StringBuilder(HISTORIES * ACTIONS_PER_HISTORY * 150);
    Map<Lane, Integer> laneCounts = new EnumMap<>(Lane.class);
    Map<ActionKind, Integer> actionCounts = new EnumMap<>(ActionKind.class);
    Map<Lane, Integer> laneWitnesses = new EnumMap<>(Lane.class);
    List<HistorySummary> summaries = new ArrayList<>(HISTORIES);
    int total = 0;
    long comparisons = 0;
    long ledgerChecks = 0;
    int terminalReconciliations = 0;
    for (int history = 0; history < HISTORIES; history++) {
      Lane lane = Lane.values()[history & 3];
      laneCounts.merge(lane, 1, Integer::sum);
      long seed = new M03SplitMix64V1(BASE_SEED + history).nextLong();
      M03SplitMix64V1 random = new M03SplitMix64V1(seed);
      Map<ActionKind, Integer> perHistory = new EnumMap<>(ActionKind.class);
      ModelState model = new ModelState(64);
      long scheduledNanos = 0;
      for (int action = 0; action < ACTIONS_PER_HISTORY; action++) {
        long bits = random.nextLong();
        ActionKind kind = kind(lane, action, bits);
        scheduledNanos += 500 + Long.remainderUnsigned(bits, 1_501);
        int payloadByte = (int) ((bits >>> 17) & 0xff);
        long token = mixToken(history, action, bits);
        append(canonical, history, action, lane, kind, scheduledNanos, token, payloadByte);
        ModelState.StepChecks checks = model.apply(kind, token);
        comparisons += checks.comparisons();
        ledgerChecks += checks.ledgerChecks();
        actionCounts.merge(kind, 1, Integer::sum);
        perHistory.merge(kind, 1, Integer::sum);
        total++;
      }
      verifyLaneGrammar(lane, perHistory);
      model.finish();
      comparisons += model.finishComparisons;
      ledgerChecks += model.finishLedgerChecks;
      terminalReconciliations++;
      requireLaneWitness(lane, model);
      laneWitnesses.merge(lane, 1, Integer::sum);
      summaries.add(
          new HistorySummary(
              history,
              lane.name(),
              seed,
              Map.copyOf(perHistory),
              model.maximumDepth,
              model.overloaded,
              model.checkpointResults,
              model.failures,
              model.retries,
              model.logicalOffers,
              model.attemptOffers));
    }
    byte[] bytes = canonical.toString().getBytes(StandardCharsets.UTF_8);
    require(total == HISTORIES * ACTIONS_PER_HISTORY, "generated action count changed");
    require(
        laneCounts.size() == 4 && laneCounts.values().stream().allMatch(value -> value == 16),
        "generated lane distribution changed");
    return new Result(
        List.copyOf(summaries),
        Map.copyOf(laneCounts),
        Map.copyOf(actionCounts),
        Map.copyOf(laneWitnesses),
        total,
        total,
        comparisons,
        ledgerChecks,
        terminalReconciliations,
        bytes,
        Hashing.sha256Hex(bytes));
  }

  private static ActionKind kind(Lane lane, int index, long bits) {
    return switch (lane) {
      case BELOW_CAPACITY -> (index & 1) == 0 ? ActionKind.OFFER : ActionKind.COMPLETE;
      case QUEUE_FULL -> index % 193 == 192 ? ActionKind.DRAIN : ActionKind.OFFER;
      case CHECKPOINT_PAUSE ->
          switch (index & 7) {
            case 3 -> ActionKind.CHECKPOINT_REQUIRED;
            case 4 -> ActionKind.PUBLISH_CHECKPOINT;
            case 5 -> ActionKind.RETRY_SAME_ENVELOPE;
            case 7 -> ActionKind.COMPLETE;
            default -> ActionKind.OFFER;
          };
      case FAIL_CLOSE_RETRY ->
          switch (index & 15) {
            case 0, 1, 3, 4, 9, 11, 15 -> ActionKind.OFFER;
            case 2, 10 -> ActionKind.COMPLETE;
            case 5 -> ActionKind.FAIL_WORKER;
            case 6 -> ActionKind.REJECT_CLOSED;
            case 7 -> ActionKind.REOPEN;
            case 8 -> ActionKind.RETRY_SAME_ENVELOPE;
            case 12 -> ActionKind.QUIESCE;
            case 13 -> ActionKind.REJECT_CLOSED;
            case 14 -> ActionKind.REOPEN;
            default -> throw new IllegalStateException("unreachable fail-close lane action");
          };
    };
  }

  private static long mixToken(int history, int action, long bits) {
    return bits ^ ((long) history << 48) ^ ((long) action << 32) ^ 0x4d31304849535431L;
  }

  private static void append(
      StringBuilder target,
      int history,
      int action,
      Lane lane,
      ActionKind kind,
      long scheduledNanos,
      long token,
      int payloadByte) {
    target
        .append("{\"history\":")
        .append(history)
        .append(",\"action\":")
        .append(action)
        .append(",\"lane\":\"")
        .append(lane)
        .append("\",\"kind\":\"")
        .append(kind)
        .append("\",\"scheduledNanos\":")
        .append(scheduledNanos)
        .append(",\"token\":\"")
        .append(Long.toUnsignedString(token))
        .append("\",\"payloadByte\":")
        .append(payloadByte)
        .append("}\n");
  }

  private static void verifyLaneGrammar(Lane lane, Map<ActionKind, Integer> counts) {
    require(counts.getOrDefault(ActionKind.OFFER, 0) > 0, lane + " has no offer");
    switch (lane) {
      case BELOW_CAPACITY ->
          require(
              counts.getOrDefault(ActionKind.COMPLETE, 0) > 0,
              "below-capacity lane has no completion");
      case QUEUE_FULL ->
          require(counts.getOrDefault(ActionKind.DRAIN, 0) > 0, "queue-full lane has no drain");
      case CHECKPOINT_PAUSE -> {
        require(
            counts.getOrDefault(ActionKind.CHECKPOINT_REQUIRED, 0) > 0,
            "checkpoint lane has no checkpoint result");
        require(
            counts.getOrDefault(ActionKind.PUBLISH_CHECKPOINT, 0) > 0,
            "checkpoint lane has no publication");
        require(
            counts.getOrDefault(ActionKind.RETRY_SAME_ENVELOPE, 0) > 0,
            "checkpoint lane has no retry");
      }
      case FAIL_CLOSE_RETRY -> {
        require(
            counts.getOrDefault(ActionKind.FAIL_WORKER, 0) > 0,
            "failure lane has no worker failure");
        require(
            counts.getOrDefault(ActionKind.REJECT_CLOSED, 0) > 0,
            "failure lane has no closed rejection");
        require(
            counts.getOrDefault(ActionKind.RETRY_SAME_ENVELOPE, 0) > 0,
            "failure lane has no retry");
      }
    }
  }

  private static void requireLaneWitness(Lane lane, ModelState state) {
    switch (lane) {
      case BELOW_CAPACITY ->
          require(
              state.overloaded == 0 && state.maximumDepth <= 1,
              "below-capacity interpreter saturated");
      case QUEUE_FULL ->
          require(
              state.overloaded > 0 && state.maximumDepth == 64,
              "queue-full interpreter did not reach bounded overload");
      case CHECKPOINT_PAUSE ->
          require(
              state.checkpointResults > 0 && state.maintenanceCompletions > 0 && state.retries > 0,
              "checkpoint interpreter missed result/maintenance/retry");
      case FAIL_CLOSE_RETRY ->
          require(
              state.failures > 0 && state.closedOrInvalid > 0 && state.retries > 0,
              "failure interpreter missed fail-close/retry");
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }

  enum Lane {
    BELOW_CAPACITY,
    QUEUE_FULL,
    CHECKPOINT_PAUSE,
    FAIL_CLOSE_RETRY
  }

  enum ActionKind {
    OFFER,
    COMPLETE,
    DRAIN,
    CHECKPOINT_REQUIRED,
    PUBLISH_CHECKPOINT,
    RETRY_SAME_ENVELOPE,
    FAIL_WORKER,
    REJECT_CLOSED,
    REOPEN,
    QUIESCE
  }

  record HistorySummary(
      int index,
      String lane,
      long derivedSeed,
      Map<ActionKind, Integer> counts,
      int maximumDepth,
      long overloaded,
      long checkpointResults,
      long failures,
      long retries,
      long logicalOffers,
      long attemptOffers) {}

  record Result(
      List<HistorySummary> histories,
      Map<Lane, Integer> laneCounts,
      Map<ActionKind, Integer> actionCounts,
      Map<Lane, Integer> laneWitnesses,
      int actions,
      int executedActions,
      long comparisons,
      long ledgerChecks,
      int terminalReconciliations,
      byte[] canonicalBytes,
      String digest) {
    Result {
      canonicalBytes = canonicalBytes.clone();
    }

    @Override
    public byte[] canonicalBytes() {
      return canonicalBytes.clone();
    }

    Map<String, Integer> laneReport() {
      Map<String, Integer> result = new LinkedHashMap<>();
      laneCounts.forEach((key, value) -> result.put(key.name(), value));
      return Map.copyOf(result);
    }

    Map<String, Integer> actionReport() {
      Map<String, Integer> result = new LinkedHashMap<>();
      actionCounts.forEach((key, value) -> result.put(key.name(), value));
      return Map.copyOf(result);
    }

    Map<String, Integer> laneWitnessReport() {
      Map<String, Integer> result = new LinkedHashMap<>();
      laneWitnesses.forEach((key, value) -> result.put(key.name(), value));
      return Map.copyOf(result);
    }
  }

  /**
   * Fresh per-history interpreter for the declared admission, attempt, maintenance, and logical
   * ledgers.
   */
  private static final class ModelState {
    private final int capacity;
    private final ArrayDeque<Long> pending = new ArrayDeque<>();
    private final ArrayDeque<Long> retryTokens = new ArrayDeque<>();
    private final HashSet<Long> openLogical = new HashSet<>();
    private boolean accepting = true;
    private long logicalOffers;
    private long logicalInitiallyAdmitted;
    private long logicalOverloaded;
    private long logicalClosed;
    private long logicalTerminal;
    private long attemptOffers;
    private long admittedAttempts;
    private long overloaded;
    private long closedOrInvalid;
    private long submissionResults;
    private long explicitFailures;
    private long maintenanceOffers;
    private long maintenanceAdmitted;
    private long maintenanceCompletions;
    private long checkpointResults;
    private long failures;
    private long retries;
    private int maximumDepth;
    private long finishComparisons;
    private long finishLedgerChecks;

    ModelState(int capacity) {
      this.capacity = capacity;
    }

    StepChecks apply(ActionKind kind, long token) {
      switch (kind) {
        case OFFER -> initialOffer(token);
        case COMPLETE -> completeOne();
        case DRAIN -> drain();
        case CHECKPOINT_REQUIRED -> checkpointRequired();
        case PUBLISH_CHECKPOINT -> {
          maintenanceOffers++;
          maintenanceAdmitted++;
          maintenanceCompletions++;
        }
        case RETRY_SAME_ENVELOPE -> retry();
        case FAIL_WORKER -> failWorker();
        case REJECT_CLOSED -> rejectClosed();
        case REOPEN -> accepting = true;
        case QUIESCE -> {
          accepting = false;
          drain();
        }
      }
      verifyLedgers();
      return new StepChecks(4, 4);
    }

    void finish() {
      drain();
      if (!retryTokens.isEmpty()) {
        // Terminal coordinator reconciliation is outside the 256 generated actions but uses the
        // same attempt ledger and identity tokens; it prevents an observation cut from hiding
        // UNKNOWN/checkpoint work.
        accepting = true;
        while (!retryTokens.isEmpty()) {
          retry();
          completeOne();
        }
      }
      verifyLedgers();
      if (!pending.isEmpty() || !openLogical.isEmpty()) {
        throw new IllegalStateException(
            "generated history did not reach a terminal observation cut");
      }
      require(
          logicalOffers == logicalInitiallyAdmitted + logicalOverloaded + logicalClosed,
          "generated logical offer ledger does not reconcile");
      require(
          logicalInitiallyAdmitted == logicalTerminal,
          "generated logical terminal ledger does not reconcile");
      require(
          maintenanceOffers == maintenanceAdmitted && maintenanceAdmitted == maintenanceCompletions,
          "generated maintenance ledger does not reconcile");
      finishComparisons = 3;
      finishLedgerChecks = 3;
    }

    private void initialOffer(long token) {
      logicalOffers++;
      attemptOffers++;
      if (!accepting) {
        closedOrInvalid++;
        logicalClosed++;
      } else if (pending.size() == capacity) {
        overloaded++;
        logicalOverloaded++;
      } else {
        admittedAttempts++;
        logicalInitiallyAdmitted++;
        require(openLogical.add(token), "generated logical identity duplicated");
        pending.addLast(token);
        maximumDepth = Math.max(maximumDepth, pending.size());
      }
    }

    private void retry() {
      require(!retryTokens.isEmpty(), "generated retry has no preserved logical identity");
      long token = retryTokens.removeFirst();
      retries++;
      attemptOffers++;
      if (!accepting) {
        closedOrInvalid++;
        retryTokens.addFirst(token);
      } else if (pending.size() == capacity) {
        overloaded++;
        retryTokens.addFirst(token);
      } else {
        admittedAttempts++;
        pending.addLast(token);
        maximumDepth = Math.max(maximumDepth, pending.size());
      }
    }

    private void completeOne() {
      if (pending.isEmpty()) return;
      long token = pending.removeFirst();
      submissionResults++;
      if (openLogical.remove(token)) logicalTerminal++;
    }

    private void drain() {
      while (!pending.isEmpty()) completeOne();
    }

    private void checkpointRequired() {
      require(!pending.isEmpty(), "generated checkpoint result has no admitted attempt");
      long token = pending.removeFirst();
      submissionResults++;
      checkpointResults++;
      retryTokens.addLast(token);
    }

    private void failWorker() {
      accepting = false;
      failures++;
      boolean preserved = false;
      while (!pending.isEmpty()) {
        long token = pending.removeFirst();
        explicitFailures++;
        if (!preserved) {
          retryTokens.addLast(token);
          preserved = true;
        } else if (openLogical.remove(token)) {
          logicalTerminal++;
        }
      }
    }

    private void rejectClosed() {
      require(!accepting, "generated closed rejection occurred while accepting");
      logicalOffers++;
      attemptOffers++;
      logicalClosed++;
      closedOrInvalid++;
    }

    private void verifyLedgers() {
      require(pending.size() <= capacity, "generated queue exceeded capacity");
      require(
          attemptOffers == admittedAttempts + overloaded + closedOrInvalid,
          "generated attempt offer ledger does not reconcile");
      require(
          admittedAttempts == submissionResults + explicitFailures + pending.size(),
          "generated attempt completion ledger does not reconcile");
      require(
          maintenanceOffers == maintenanceAdmitted,
          "generated maintenance admission ledger does not reconcile");
    }

    record StepChecks(long comparisons, long ledgerChecks) {}
  }
}
