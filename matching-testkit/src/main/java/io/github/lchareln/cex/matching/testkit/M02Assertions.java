package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.CancelOrderInput;
import io.github.lchareln.cex.matching.PlaceLimitOrderInput;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Exact M02 oracle plus an independent lifecycle ledger and behavioral book model. */
final class M02Assertions {
  static final String PASS = "PASS";
  static final String STUDENT_FAILURE = "STUDENT_FAILURE";
  static final String SYSTEM_ERROR = "SYSTEM_ERROR";

  Observation judge(M02ScenarioPack pack, M02Candidate.Factory candidateFactory) {
    Counters counters = new Counters();
    List<M02RunHistory.ScenarioRun> scenarioRuns = new ArrayList<>();
    try {
      for (M02ScenarioPack.Scenario scenario : pack.scenarios()) {
        M02Candidate candidate = Objects.requireNonNull(candidateFactory.create(), "candidate");
        Ledger ledger = new Ledger();
        M02ScenarioPack.Book previous = M02ScenarioPack.Book.empty();
        List<M02RunHistory.CommandRun> commandRuns = new ArrayList<>();
        for (M02ScenarioPack.Command command : scenario.commands()) {
          M02Candidate.Outcome actual = execute(candidate, command);
          M02Candidate.Outcome expected =
              new M02Candidate.Outcome(command.expected().events(), command.expected().bookAfter());
          require(
              expected.equals(actual),
              scenario.scenarioId(),
              command.caseId(),
              "expected " + stable(expected) + ", actual " + stable(actual));
          verifyTransition(scenario.scenarioId(), command, previous, actual, ledger, counters);
          commandRuns.add(history(command, actual));
          previous = actual.bookAfter();
        }
        scenarioRuns.add(new M02RunHistory.ScenarioRun(scenario.scenarioId(), commandRuns));
      }
      return new Observation(
          PASS,
          null,
          null,
          "all frozen scenarios, lifecycle transitions, and registry invariants matched",
          new M02RunHistory(scenarioRuns),
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

  private static M02Candidate.Outcome execute(
      M02Candidate candidate, M02ScenarioPack.Command command) {
    return Objects.requireNonNull(
        switch (command) {
          case M02ScenarioPack.PlaceCommand place -> candidate.place(place.input());
          case M02ScenarioPack.CancelCommand cancel -> candidate.cancel(cancel.input());
        },
        "candidate outcome");
  }

  private static M02RunHistory.CommandRun history(
      M02ScenarioPack.Command command, M02Candidate.Outcome outcome) {
    return switch (command) {
      case M02ScenarioPack.PlaceCommand place ->
          new M02RunHistory.PlaceRun(
              place.caseId(), place.input(), outcome.events(), outcome.bookAfter());
      case M02ScenarioPack.CancelCommand cancel ->
          new M02RunHistory.CancelRun(
              cancel.caseId(), cancel.input(), outcome.events(), outcome.bookAfter());
    };
  }

  private static void verifyTransition(
      String scenarioId,
      M02ScenarioPack.Command command,
      M02ScenarioPack.Book before,
      M02Candidate.Outcome outcome,
      Ledger ledger,
      Counters counters) {
    String caseId = command.caseId();
    counters.commands++;
    verifyBookStructure(scenarioId, caseId, before, counters);
    verifyBookStructure(scenarioId, caseId, outcome.bookAfter(), counters);
    require(!outcome.events().isEmpty(), scenarioId, caseId, "event batch is empty");
    counters.eventBatchChecks++;
    if (command instanceof M02ScenarioPack.PlaceCommand place) {
      counters.placeCommands++;
      verifyPlace(scenarioId, caseId, place.input(), before, outcome, ledger, counters);
    } else if (command instanceof M02ScenarioPack.CancelCommand cancel) {
      counters.cancelCommands++;
      verifyCancel(scenarioId, caseId, cancel.input(), before, outcome, ledger, counters);
    }
    verifyRegistryAndBook(scenarioId, caseId, outcome.bookAfter(), ledger, counters);
    counters.lifecycleChecks++;
  }

  private static void verifyPlace(
      String scenarioId,
      String caseId,
      PlaceLimitOrderInput input,
      M02ScenarioPack.Book before,
      M02Candidate.Outcome outcome,
      Ledger ledger,
      Counters counters) {
    M02ScenarioPack.Event first = outcome.events().getFirst();
    if (first instanceof M02ScenarioPack.Rejected rejected) {
      require(outcome.events().size() == 1, scenarioId, caseId, "Rejected is not singleton");
      require(before.equals(outcome.bookAfter()), scenarioId, caseId, "Rejected changed the book");
      require(
          expectedPlaceValidation(input).equals(rejected.code() + ":" + rejected.field()),
          scenarioId,
          caseId,
          "PLACE validation priority differs from the frozen contract");
      counters.validationRejected++;
      counters.bookTransitionChecks++;
      return;
    }
    if (first instanceof M02ScenarioPack.PlaceRejected rejected) {
      require(outcome.events().size() == 1, scenarioId, caseId, "PlaceRejected is not singleton");
      require(before.equals(outcome.bookAfter()), scenarioId, caseId, "PlaceRejected changed book");
      require(
          "DUPLICATE_ORDER_ID".equals(rejected.code())
              && rejected.orderId() == input.orderId().longValueExact()
              && ledger.orders.containsKey(rejected.orderId()),
          scenarioId,
          caseId,
          "duplicate identity was not already authoritative");
      counters.placeRejected++;
      counters.bookTransitionChecks++;
      return;
    }

    require(first instanceof M02ScenarioPack.Accepted, scenarioId, caseId, "PLACE did not accept");
    M02ScenarioPack.Accepted accepted = (M02ScenarioPack.Accepted) first;
    require(
        accepted.sequence() == Math.incrementExact(ledger.lastSequence),
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
        "Accepted does not reproduce the command");
    require(
        !ledger.orders.containsKey(accepted.orderId()),
        scenarioId,
        caseId,
        "terminal or active order identity was resurrected");
    ledger.lastSequence = accepted.sequence();
    MutableBook expectedBook = new MutableBook(before);
    LedgerOrder taker = LedgerOrder.accepted(accepted);
    boolean restedSeen = false;
    for (int index = 1; index < outcome.events().size(); index++) {
      M02ScenarioPack.Event event = outcome.events().get(index);
      if (event instanceof M02ScenarioPack.Trade trade) {
        require(!restedSeen, scenarioId, caseId, "Trade follows Rested");
        require(
            trade.takerSequence() == accepted.sequence()
                && trade.takerOrderId() == accepted.orderId(),
            scenarioId,
            caseId,
            "Trade taker differs from Accepted");
        long makerId =
            expectedBook.consumeBest(scenarioId, caseId, accepted, trade, taker.remaining);
        LedgerOrder maker = ledger.orders.get(makerId);
        require(
            maker != null && maker.state == State.RESTING,
            scenarioId,
            caseId,
            "maker is not active in lifecycle ledger");
        maker.fill(trade.quantityLots());
        taker.fill(trade.quantityLots());
        counters.trades++;
      } else if (event instanceof M02ScenarioPack.Rested rested) {
        require(index == outcome.events().size() - 1, scenarioId, caseId, "Rested is not final");
        require(!restedSeen, scenarioId, caseId, "multiple Rested events");
        require(
            rested.sequence() == taker.sequence
                && rested.orderId() == taker.orderId
                && rested.side().equals(taker.side)
                && rested.priceTicks() == taker.price
                && rested.remainingQuantityLots() == taker.remaining
                && taker.remaining > 0,
            scenarioId,
            caseId,
            "Rested does not equal the independent taker remainder");
        taker.state = State.RESTING;
        expectedBook.rest(rested);
        restedSeen = true;
      } else {
        throw new StudentFailure(scenarioId, caseId, "unexpected event after Accepted");
      }
    }
    require(
        (taker.remaining == 0 && !restedSeen) || (taker.remaining > 0 && restedSeen),
        scenarioId,
        caseId,
        "accepted taker has inconsistent terminal remainder");
    if (taker.remaining == 0) {
      taker.state = State.FILLED;
    }
    ledger.orders.put(taker.orderId, taker);
    require(
        expectedBook.snapshot().equals(outcome.bookAfter()),
        scenarioId,
        caseId,
        "book differs from independent PLACE transition");
    counters.accepted++;
    counters.bookTransitionChecks++;
  }

  private static void verifyCancel(
      String scenarioId,
      String caseId,
      CancelOrderInput input,
      M02ScenarioPack.Book before,
      M02Candidate.Outcome outcome,
      Ledger ledger,
      Counters counters) {
    require(outcome.events().size() == 1, scenarioId, caseId, "CANCEL batch is not singleton");
    M02ScenarioPack.Event event = outcome.events().getFirst();
    if (event instanceof M02ScenarioPack.Rejected rejected) {
      require(
          before.equals(outcome.bookAfter()), scenarioId, caseId, "Rejected CANCEL changed book");
      require(
          expectedCancelValidation(input).equals(rejected.code() + ":" + rejected.field()),
          scenarioId,
          caseId,
          "CANCEL validation priority differs from the frozen contract");
      counters.validationRejected++;
      counters.bookTransitionChecks++;
      return;
    }
    long orderId = input.orderId().longValueExact();
    if (event instanceof M02ScenarioPack.CancelRejected rejected) {
      require(rejected.orderId() == orderId, scenarioId, caseId, "CancelRejected ID differs");
      LedgerOrder known = ledger.orders.get(orderId);
      boolean correct =
          switch (rejected.code()) {
            case "ORDER_NOT_FOUND" -> known == null;
            case "ORDER_ALREADY_FILLED" -> known != null && known.state == State.FILLED;
            case "ORDER_ALREADY_CANCELED" -> known != null && known.state == State.CANCELED;
            default -> false;
          };
      require(correct, scenarioId, caseId, "CancelRejected disagrees with independent ledger");
      require(
          before.equals(outcome.bookAfter()), scenarioId, caseId, "CancelRejected changed book");
      counters.cancelRejected++;
      counters.bookTransitionChecks++;
      return;
    }
    require(
        event instanceof M02ScenarioPack.Canceled, scenarioId, caseId, "invalid CANCEL grammar");
    M02ScenarioPack.Canceled canceled = (M02ScenarioPack.Canceled) event;
    LedgerOrder known = ledger.orders.get(orderId);
    require(
        known != null && known.state == State.RESTING,
        scenarioId,
        caseId,
        "CANCELED target is not active");
    require(
        canceled.orderId() == known.orderId
            && canceled.sequence() == known.sequence
            && canceled.side().equals(known.side)
            && canceled.priceTicks() == known.price
            && canceled.canceledQuantityLots() == known.remaining,
        scenarioId,
        caseId,
        "CANCELED does not describe the active remainder");
    MutableBook expectedBook = new MutableBook(before);
    expectedBook.cancel(scenarioId, caseId, known);
    known.cancel(canceled.canceledQuantityLots());
    require(
        expectedBook.snapshot().equals(outcome.bookAfter()),
        scenarioId,
        caseId,
        "book differs from independent CANCEL transition");
    counters.canceled++;
    counters.bookTransitionChecks++;
  }

  private static String expectedPlaceValidation(PlaceLimitOrderInput input) {
    if (!"BTC-USDT".equals(input.instrumentId())) return "UNKNOWN_INSTRUMENT:instrumentId";
    if (input.orderId().signum() <= 0) return "INVALID_ORDER_ID:orderId";
    if (!"BUY".equals(input.side()) && !"SELL".equals(input.side())) return "INVALID_SIDE:side";
    if (input.priceTicks().signum() <= 0) return "INVALID_PRICE:priceTicks";
    if (input.quantityLots().signum() <= 0) return "INVALID_QUANTITY:quantityLots";
    return "VALID";
  }

  private static String expectedCancelValidation(CancelOrderInput input) {
    if (!"BTC-USDT".equals(input.instrumentId())) return "UNKNOWN_INSTRUMENT:instrumentId";
    if (input.orderId().signum() <= 0) return "INVALID_ORDER_ID:orderId";
    return "VALID";
  }

  private static void verifyRegistryAndBook(
      String scenarioId,
      String caseId,
      M02ScenarioPack.Book book,
      Ledger ledger,
      Counters counters) {
    Map<Long, BookOrder> active = new LinkedHashMap<>();
    collectBook(active, book.bids(), "BUY", scenarioId, caseId);
    collectBook(active, book.asks(), "SELL", scenarioId, caseId);
    Set<Long> restingIds = new HashSet<>();
    for (LedgerOrder order : ledger.orders.values()) {
      require(
          BigInteger.valueOf(order.original)
              .equals(
                  BigInteger.valueOf(order.filled)
                      .add(BigInteger.valueOf(order.remaining))
                      .add(BigInteger.valueOf(order.canceled))),
          scenarioId,
          caseId,
          "original != filled + remaining + canceled for " + order.orderId);
      counters.quantityPartitionChecks++;
      if (order.state == State.RESTING) {
        restingIds.add(order.orderId);
        BookOrder view = active.get(order.orderId);
        require(
            view != null
                && view.sequence == order.sequence
                && view.side.equals(order.side)
                && view.price == order.price
                && view.remaining == order.remaining,
            scenarioId,
            caseId,
            "active registry order and book view differ");
      } else {
        require(
            !active.containsKey(order.orderId),
            scenarioId,
            caseId,
            "terminal order remains visible in book");
        counters.terminalAbsenceChecks++;
      }
    }
    require(
        active.keySet().equals(restingIds), scenarioId, caseId, "registry-book bijection failed");
    counters.registryBookChecks++;
  }

  private static void collectBook(
      Map<Long, BookOrder> target,
      List<M02ScenarioPack.Level> levels,
      String side,
      String scenarioId,
      String caseId) {
    for (M02ScenarioPack.Level level : levels) {
      for (M02ScenarioPack.RestingOrder order : level.orders()) {
        require(
            target.put(
                    order.orderId(),
                    new BookOrder(
                        order.sequence(), side, level.priceTicks(), order.remainingQuantityLots()))
                == null,
            scenarioId,
            caseId,
            "book contains duplicate active order ID");
      }
    }
  }

  private static void verifyBookStructure(
      String scenarioId, String caseId, M02ScenarioPack.Book book, Counters counters) {
    long previousBid = Long.MAX_VALUE;
    for (M02ScenarioPack.Level level : book.bids()) {
      require(level.priceTicks() < previousBid, scenarioId, caseId, "bids are not descending");
      verifyLevel(scenarioId, caseId, level);
      previousBid = level.priceTicks();
    }
    long previousAsk = Long.MIN_VALUE;
    for (M02ScenarioPack.Level level : book.asks()) {
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
        "book remains crossed");
    counters.bookStructureChecks++;
  }

  private static void verifyLevel(String scenarioId, String caseId, M02ScenarioPack.Level level) {
    require(!level.orders().isEmpty(), scenarioId, caseId, "empty price level remains");
    long previousSequence = 0;
    for (M02ScenarioPack.RestingOrder order : level.orders()) {
      require(
          order.remainingQuantityLots() > 0, scenarioId, caseId, "nonpositive resting quantity");
      require(order.sequence() > previousSequence, scenarioId, caseId, "level is not FIFO");
      previousSequence = order.sequence();
    }
  }

  private static String stable(M02Candidate.Outcome outcome) {
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
      M02RunHistory history,
      Metrics metrics) {}

  record Metrics(
      int commands,
      int placeCommands,
      int cancelCommands,
      int accepted,
      int validationRejected,
      int placeRejected,
      int cancelRejected,
      int canceled,
      int trades,
      int eventBatchChecks,
      int bookTransitionChecks,
      int lifecycleChecks,
      int registryBookChecks,
      int quantityPartitionChecks,
      int terminalAbsenceChecks,
      int bookStructureChecks) {}

  private static final class Counters {
    private int commands;
    private int placeCommands;
    private int cancelCommands;
    private int accepted;
    private int validationRejected;
    private int placeRejected;
    private int cancelRejected;
    private int canceled;
    private int trades;
    private int eventBatchChecks;
    private int bookTransitionChecks;
    private int lifecycleChecks;
    private int registryBookChecks;
    private int quantityPartitionChecks;
    private int terminalAbsenceChecks;
    private int bookStructureChecks;

    private Metrics snapshot() {
      return new Metrics(
          commands,
          placeCommands,
          cancelCommands,
          accepted,
          validationRejected,
          placeRejected,
          cancelRejected,
          canceled,
          trades,
          eventBatchChecks,
          bookTransitionChecks,
          lifecycleChecks,
          registryBookChecks,
          quantityPartitionChecks,
          terminalAbsenceChecks,
          bookStructureChecks);
    }
  }

  private enum State {
    RESTING,
    FILLED,
    CANCELED
  }

  private static final class Ledger {
    private final Map<Long, LedgerOrder> orders = new LinkedHashMap<>();
    private long lastSequence;
  }

  private static final class LedgerOrder {
    private final long sequence;
    private final long orderId;
    private final String side;
    private final long price;
    private final long original;
    private long filled;
    private long remaining;
    private long canceled;
    private State state;

    private LedgerOrder(M02ScenarioPack.Accepted accepted) {
      sequence = accepted.sequence();
      orderId = accepted.orderId();
      side = accepted.side();
      price = accepted.priceTicks();
      original = accepted.quantityLots();
      remaining = original;
      state = State.RESTING;
    }

    private static LedgerOrder accepted(M02ScenarioPack.Accepted accepted) {
      return new LedgerOrder(accepted);
    }

    private void fill(long quantity) {
      remaining = Math.subtractExact(remaining, quantity);
      filled = Math.addExact(filled, quantity);
      if (remaining == 0) {
        state = State.FILLED;
      }
    }

    private void cancel(long quantity) {
      remaining = Math.subtractExact(remaining, quantity);
      canceled = Math.addExact(canceled, quantity);
      state = State.CANCELED;
    }
  }

  private record BookOrder(long sequence, String side, long price, long remaining) {}

  private static final class MutableBook {
    private final NavigableMap<Long, List<MutableOrder>> bids =
        new TreeMap<>(Collections.reverseOrder());
    private final NavigableMap<Long, List<MutableOrder>> asks = new TreeMap<>();

    private MutableBook(M02ScenarioPack.Book source) {
      copy(source.bids(), bids);
      copy(source.asks(), asks);
    }

    private long consumeBest(
        String scenarioId,
        String caseId,
        M02ScenarioPack.Accepted accepted,
        M02ScenarioPack.Trade trade,
        long takerRemaining) {
      NavigableMap<Long, List<MutableOrder>> opposite = "BUY".equals(accepted.side()) ? asks : bids;
      require(!opposite.isEmpty(), scenarioId, caseId, "Trade has no maker");
      long makerPrice = opposite.firstKey();
      boolean crosses =
          "BUY".equals(accepted.side())
              ? accepted.priceTicks() >= makerPrice
              : accepted.priceTicks() <= makerPrice;
      require(crosses, scenarioId, caseId, "Trade does not cross taker limit");
      List<MutableOrder> level = opposite.firstEntry().getValue();
      MutableOrder maker = level.getFirst();
      require(
          trade.makerSequence() == maker.sequence && trade.makerOrderId() == maker.orderId,
          scenarioId,
          caseId,
          "Trade skipped best FIFO maker");
      require(trade.priceTicks() == makerPrice, scenarioId, caseId, "Trade is not at maker price");
      require(
          trade.quantityLots() == Math.min(takerRemaining, maker.remaining),
          scenarioId,
          caseId,
          "Trade quantity is not min remainder");
      maker.remaining -= trade.quantityLots();
      if (maker.remaining == 0) {
        level.removeFirst();
        if (level.isEmpty()) {
          opposite.remove(makerPrice);
        }
      }
      return maker.orderId;
    }

    private void rest(M02ScenarioPack.Rested rested) {
      NavigableMap<Long, List<MutableOrder>> side = "BUY".equals(rested.side()) ? bids : asks;
      side.computeIfAbsent(rested.priceTicks(), ignored -> new ArrayList<>())
          .add(
              new MutableOrder(
                  rested.sequence(), rested.orderId(), rested.remainingQuantityLots()));
    }

    private void cancel(String scenarioId, String caseId, LedgerOrder target) {
      NavigableMap<Long, List<MutableOrder>> side = "BUY".equals(target.side) ? bids : asks;
      List<MutableOrder> level = side.get(target.price);
      require(level != null, scenarioId, caseId, "cancel level is absent");
      int position = -1;
      for (int index = 0; index < level.size(); index++) {
        if (level.get(index).orderId == target.orderId) {
          position = index;
          break;
        }
      }
      require(position >= 0, scenarioId, caseId, "cancel target is absent from its level");
      MutableOrder removed = level.remove(position);
      require(
          removed.sequence == target.sequence && removed.remaining == target.remaining,
          scenarioId,
          caseId,
          "cancel target view differs from registry");
      if (level.isEmpty()) {
        side.remove(target.price);
      }
    }

    private M02ScenarioPack.Book snapshot() {
      return new M02ScenarioPack.Book(snapshot(bids), snapshot(asks));
    }

    private static void copy(
        List<M02ScenarioPack.Level> levels, NavigableMap<Long, List<MutableOrder>> target) {
      for (M02ScenarioPack.Level level : levels) {
        List<MutableOrder> orders = new ArrayList<>();
        for (M02ScenarioPack.RestingOrder order : level.orders()) {
          orders.add(
              new MutableOrder(order.sequence(), order.orderId(), order.remainingQuantityLots()));
        }
        target.put(level.priceTicks(), orders);
      }
    }

    private static List<M02ScenarioPack.Level> snapshot(
        NavigableMap<Long, List<MutableOrder>> source) {
      List<M02ScenarioPack.Level> levels = new ArrayList<>();
      source.forEach(
          (price, queue) -> {
            List<M02ScenarioPack.RestingOrder> orders = new ArrayList<>();
            for (MutableOrder order : queue) {
              orders.add(
                  new M02ScenarioPack.RestingOrder(order.sequence, order.orderId, order.remaining));
            }
            levels.add(new M02ScenarioPack.Level(price, orders));
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
