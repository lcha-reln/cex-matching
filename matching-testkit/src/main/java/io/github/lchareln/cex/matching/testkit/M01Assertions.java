package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.PlaceLimitOrderInput;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

/** Exact scenario assertions plus local algebraic invariants for one M01 candidate. */
final class M01Assertions {
  static final String PASS = "PASS";
  static final String STUDENT_FAILURE = "STUDENT_FAILURE";
  static final String SYSTEM_ERROR = "SYSTEM_ERROR";

  Observation judge(M01ScenarioPack pack, M01Candidate.Factory candidateFactory) {
    Counters counters = new Counters();
    List<M01RunHistory.ScenarioRun> scenarioRuns = new ArrayList<>();
    try {
      for (M01ScenarioPack.Scenario scenario : pack.scenarios()) {
        M01Candidate candidate = Objects.requireNonNull(candidateFactory.create(), "candidate");
        M01ScenarioPack.Book previous = M01ScenarioPack.Book.empty();
        long lastAcceptedSequence = 0;
        List<M01RunHistory.CaseRun> caseRuns = new ArrayList<>();
        for (M01ScenarioPack.Case caseRecord : scenario.cases()) {
          M01Candidate.Outcome actual =
              Objects.requireNonNull(candidate.place(caseRecord.input()), "candidate outcome");
          M01Candidate.Outcome expected =
              new M01Candidate.Outcome(
                  caseRecord.expected().events(), caseRecord.expected().bookAfter());
          require(
              expected.equals(actual),
              scenario.scenarioId(),
              caseRecord.caseId(),
              "expected " + stable(expected) + ", actual " + stable(actual));

          lastAcceptedSequence =
              verifyTransition(
                  scenario.scenarioId(),
                  caseRecord.caseId(),
                  caseRecord.input(),
                  previous,
                  actual,
                  lastAcceptedSequence,
                  counters);
          caseRuns.add(
              new M01RunHistory.CaseRun(
                  caseRecord.caseId(), caseRecord.input(), actual.events(), actual.bookAfter()));
          previous = actual.bookAfter();
        }
        scenarioRuns.add(new M01RunHistory.ScenarioRun(scenario.scenarioId(), caseRuns));
      }
      return new Observation(
          PASS,
          null,
          null,
          "all frozen scenarios and invariants matched",
          new M01RunHistory(scenarioRuns),
          counters.snapshot());
    } catch (StudentFailure failure) {
      return new Observation(
          STUDENT_FAILURE,
          failure.scenarioId,
          failure.caseId,
          failure.getMessage(),
          null,
          counters.snapshot());
    } catch (RuntimeException exception) {
      return new Observation(
          SYSTEM_ERROR,
          null,
          null,
          "candidate raised " + exception.getClass().getSimpleName(),
          null,
          counters.snapshot());
    }
  }

  private static long verifyTransition(
      String scenarioId,
      String caseId,
      PlaceLimitOrderInput input,
      M01ScenarioPack.Book before,
      M01Candidate.Outcome outcome,
      long lastAcceptedSequence,
      Counters counters) {
    counters.cases++;
    verifyBookStructure(scenarioId, caseId, before, counters);
    verifyBookStructure(scenarioId, caseId, outcome.bookAfter(), counters);
    List<M01ScenarioPack.Event> events = outcome.events();
    require(!events.isEmpty(), scenarioId, caseId, "event batch is empty");
    counters.eventBatchChecks++;

    if (events.getFirst() instanceof M01ScenarioPack.Rejected) {
      require(events.size() == 1, scenarioId, caseId, "Rejected must be the only event");
      require(
          before.equals(outcome.bookAfter()),
          scenarioId,
          caseId,
          "Rejected command changed the book");
      counters.rejected++;
      counters.conservationChecks++;
      return lastAcceptedSequence;
    }

    require(
        events.getFirst() instanceof M01ScenarioPack.Accepted,
        scenarioId,
        caseId,
        "valid batch does not start with Accepted");
    M01ScenarioPack.Accepted accepted = (M01ScenarioPack.Accepted) events.getFirst();
    require(
        accepted.sequence() == Math.incrementExact(lastAcceptedSequence),
        scenarioId,
        caseId,
        "acceptance sequence is not contiguous");
    require(
        accepted.orderId() == input.orderId().longValueExact()
            && accepted.side().equals(input.side())
            && accepted.priceTicks() == input.priceTicks().longValueExact()
            && accepted.quantityLots() == input.quantityLots().longValueExact(),
        scenarioId,
        caseId,
        "Accepted does not reproduce the normalized command");
    counters.accepted++;

    MutableBook expectedBook = new MutableBook(before);
    long remaining = accepted.quantityLots();
    boolean restedSeen = false;
    for (int index = 1; index < events.size(); index++) {
      M01ScenarioPack.Event event = events.get(index);
      if (event instanceof M01ScenarioPack.Trade trade) {
        require(!restedSeen, scenarioId, caseId, "Trade appears after Rested");
        require(trade.quantityLots() > 0, scenarioId, caseId, "Trade quantity is not positive");
        require(
            trade.takerSequence() == accepted.sequence()
                && trade.takerOrderId() == accepted.orderId(),
            scenarioId,
            caseId,
            "Trade taker differs from Accepted");
        expectedBook.consumeBest(scenarioId, caseId, accepted, trade, remaining);
        remaining -= trade.quantityLots();
        counters.trades++;
        counters.positiveTradeChecks++;
        counters.makerPriceChecks++;
        counters.priorityChecks++;
      } else if (event instanceof M01ScenarioPack.Rested rested) {
        require(index == events.size() - 1, scenarioId, caseId, "Rested is not final");
        require(!restedSeen, scenarioId, caseId, "multiple Rested events");
        require(
            rested.sequence() == accepted.sequence()
                && rested.orderId() == accepted.orderId()
                && rested.side().equals(accepted.side())
                && rested.priceTicks() == accepted.priceTicks()
                && rested.remainingQuantityLots() == remaining,
            scenarioId,
            caseId,
            "Rested does not contain the taker remainder");
        require(remaining > 0, scenarioId, caseId, "zero quantity was rested");
        expectedBook.rest(rested);
        restedSeen = true;
      } else {
        throw new StudentFailure(scenarioId, caseId, "unexpected event after Accepted");
      }
    }
    require(
        remaining == 0 || restedSeen,
        scenarioId,
        caseId,
        "positive taker remainder was not rested");
    require(remaining != 0 || !restedSeen, scenarioId, caseId, "fully filled taker emitted Rested");
    require(
        expectedBook.snapshot().equals(outcome.bookAfter()),
        scenarioId,
        caseId,
        "bookAfter differs from the invariant transition");

    BigInteger beforeQuantity = totalQuantity(before);
    BigInteger afterQuantity = totalQuantity(outcome.bookAfter());
    BigInteger tradedQuantity =
        events.stream()
            .filter(M01ScenarioPack.Trade.class::isInstance)
            .map(M01ScenarioPack.Trade.class::cast)
            .map(trade -> BigInteger.valueOf(trade.quantityLots()))
            .reduce(BigInteger.ZERO, BigInteger::add);
    require(
        beforeQuantity
            .add(BigInteger.valueOf(accepted.quantityLots()))
            .equals(afterQuantity.add(tradedQuantity.multiply(BigInteger.TWO))),
        scenarioId,
        caseId,
        "two-sided quantity conservation failed");
    counters.conservationChecks++;
    return accepted.sequence();
  }

  private static void verifyBookStructure(
      String scenarioId, String caseId, M01ScenarioPack.Book book, Counters counters) {
    long previousBid = Long.MAX_VALUE;
    for (M01ScenarioPack.Level level : book.bids()) {
      require(level.priceTicks() < previousBid, scenarioId, caseId, "bids are not descending");
      verifyLevel(scenarioId, caseId, level);
      previousBid = level.priceTicks();
    }
    long previousAsk = Long.MIN_VALUE;
    for (M01ScenarioPack.Level level : book.asks()) {
      require(level.priceTicks() > previousAsk, scenarioId, caseId, "asks are not ascending");
      verifyLevel(scenarioId, caseId, level);
      previousAsk = level.priceTicks();
    }
    require(
        book.bids().isEmpty()
            || book.asks().isEmpty()
            || book.bids().getFirst().priceTicks() < book.asks().getFirst().priceTicks(),
        scenarioId,
        caseId,
        "book remains crossed after the batch");
    counters.bookStructureChecks++;
  }

  private static void verifyLevel(String scenarioId, String caseId, M01ScenarioPack.Level level) {
    require(!level.orders().isEmpty(), scenarioId, caseId, "empty price level remains active");
    long previousSequence = 0;
    for (M01ScenarioPack.RestingOrder order : level.orders()) {
      require(
          order.remainingQuantityLots() > 0,
          scenarioId,
          caseId,
          "non-positive resting quantity remains active");
      require(
          order.sequence() > previousSequence,
          scenarioId,
          caseId,
          "price level is not FIFO by acceptance sequence");
      previousSequence = order.sequence();
    }
  }

  private static BigInteger totalQuantity(M01ScenarioPack.Book book) {
    BigInteger total = BigInteger.ZERO;
    for (M01ScenarioPack.Level level : book.bids()) {
      for (M01ScenarioPack.RestingOrder order : level.orders()) {
        total = total.add(BigInteger.valueOf(order.remainingQuantityLots()));
      }
    }
    for (M01ScenarioPack.Level level : book.asks()) {
      for (M01ScenarioPack.RestingOrder order : level.orders()) {
        total = total.add(BigInteger.valueOf(order.remainingQuantityLots()));
      }
    }
    return total;
  }

  private static String stable(M01Candidate.Outcome outcome) {
    return "events=" + outcome.events() + ", bookAfter=" + outcome.bookAfter();
  }

  private static void require(boolean condition, String scenarioId, String caseId, String message) {
    if (!condition) {
      throw new StudentFailure(scenarioId, caseId, message);
    }
  }

  record Observation(
      String classification,
      String scenarioId,
      String caseId,
      String message,
      M01RunHistory history,
      Metrics metrics) {}

  record Metrics(
      int cases,
      int accepted,
      int rejected,
      int trades,
      int eventBatchChecks,
      int positiveTradeChecks,
      int conservationChecks,
      int makerPriceChecks,
      int priorityChecks,
      int bookStructureChecks) {}

  private static final class Counters {
    private int cases;
    private int accepted;
    private int rejected;
    private int trades;
    private int eventBatchChecks;
    private int positiveTradeChecks;
    private int conservationChecks;
    private int makerPriceChecks;
    private int priorityChecks;
    private int bookStructureChecks;

    private Metrics snapshot() {
      return new Metrics(
          cases,
          accepted,
          rejected,
          trades,
          eventBatchChecks,
          positiveTradeChecks,
          conservationChecks,
          makerPriceChecks,
          priorityChecks,
          bookStructureChecks);
    }
  }

  private static final class MutableBook {
    private final NavigableMap<Long, ArrayDeque<MutableOrder>> bids =
        new TreeMap<>(Collections.reverseOrder());
    private final NavigableMap<Long, ArrayDeque<MutableOrder>> asks = new TreeMap<>();

    private MutableBook(M01ScenarioPack.Book source) {
      copy(source.bids(), bids);
      copy(source.asks(), asks);
    }

    private void consumeBest(
        String scenarioId,
        String caseId,
        M01ScenarioPack.Accepted accepted,
        M01ScenarioPack.Trade trade,
        long takerRemaining) {
      NavigableMap<Long, ArrayDeque<MutableOrder>> opposite =
          "BUY".equals(accepted.side()) ? asks : bids;
      require(!opposite.isEmpty(), scenarioId, caseId, "Trade has no resting maker");
      long makerPrice = opposite.firstKey();
      boolean crosses =
          "BUY".equals(accepted.side())
              ? accepted.priceTicks() >= makerPrice
              : accepted.priceTicks() <= makerPrice;
      require(crosses, scenarioId, caseId, "Trade does not cross the taker limit");
      ArrayDeque<MutableOrder> level = opposite.firstEntry().getValue();
      MutableOrder maker = level.getFirst();
      require(
          trade.makerSequence() == maker.sequence && trade.makerOrderId() == maker.orderId,
          scenarioId,
          caseId,
          "Trade skipped the best FIFO maker");
      require(
          trade.priceTicks() == makerPrice,
          scenarioId,
          caseId,
          "Trade price is not the resting maker price");
      require(
          trade.quantityLots() == Math.min(takerRemaining, maker.remaining),
          scenarioId,
          caseId,
          "Trade quantity is not min(taker remaining, maker remaining)");
      maker.remaining -= trade.quantityLots();
      if (maker.remaining == 0) {
        level.removeFirst();
        if (level.isEmpty()) {
          opposite.remove(makerPrice);
        }
      }
    }

    private void rest(M01ScenarioPack.Rested rested) {
      NavigableMap<Long, ArrayDeque<MutableOrder>> side = "BUY".equals(rested.side()) ? bids : asks;
      side.computeIfAbsent(rested.priceTicks(), ignored -> new ArrayDeque<>())
          .addLast(
              new MutableOrder(
                  rested.sequence(), rested.orderId(), rested.remainingQuantityLots()));
    }

    private M01ScenarioPack.Book snapshot() {
      return new M01ScenarioPack.Book(snapshot(bids), snapshot(asks));
    }

    private static void copy(
        List<M01ScenarioPack.Level> levels, NavigableMap<Long, ArrayDeque<MutableOrder>> target) {
      for (M01ScenarioPack.Level level : levels) {
        ArrayDeque<MutableOrder> orders = new ArrayDeque<>();
        for (M01ScenarioPack.RestingOrder order : level.orders()) {
          orders.addLast(
              new MutableOrder(order.sequence(), order.orderId(), order.remainingQuantityLots()));
        }
        target.put(level.priceTicks(), orders);
      }
    }

    private static List<M01ScenarioPack.Level> snapshot(
        NavigableMap<Long, ArrayDeque<MutableOrder>> source) {
      List<M01ScenarioPack.Level> levels = new ArrayList<>(source.size());
      source.forEach(
          (price, queue) -> {
            List<M01ScenarioPack.RestingOrder> orders = new ArrayList<>(queue.size());
            for (MutableOrder order : queue) {
              orders.add(
                  new M01ScenarioPack.RestingOrder(order.sequence, order.orderId, order.remaining));
            }
            levels.add(new M01ScenarioPack.Level(price, orders));
          });
      return List.copyOf(levels);
    }
  }

  private static final class MutableOrder {
    private final long sequence;
    private final long orderId;
    private long remaining;

    private MutableOrder(long sequence, long orderId, long remaining) {
      this.sequence = sequence;
      this.orderId = orderId;
      this.remaining = remaining;
    }
  }

  private static final class StudentFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String scenarioId;
    private final String caseId;

    private StudentFailure(String scenarioId, String caseId, String message) {
      super("scenario " + scenarioId + ", case " + caseId + ": " + message);
      this.scenarioId = scenarioId;
      this.caseId = caseId;
    }
  }
}
