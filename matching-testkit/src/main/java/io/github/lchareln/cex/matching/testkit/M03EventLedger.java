package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.reference.ReferenceCommand;
import io.github.lchareln.cex.matching.reference.SemanticBook;
import io.github.lchareln.cex.matching.reference.SemanticEvent;
import io.github.lchareln.cex.matching.reference.SemanticOutcome;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Third-path ledger derived only from commands and candidate public outcomes. */
final class M03EventLedger {
  private static final BigInteger ZERO = BigInteger.ZERO;
  private static final BigInteger ONE = BigInteger.ONE;
  private static final BigInteger MAX_LONG = BigInteger.valueOf(Long.MAX_VALUE);

  private final Map<BigInteger, LedgerOrder> orders = new HashMap<>();
  private BigInteger nextSequence = ONE;

  void verifyAndApply(ReferenceCommand command, SemanticOutcome outcome) {
    if (outcome.events().isEmpty()) {
      fail("EVENT_GRAMMAR", "EMPTY_BATCH", "candidate emitted no event");
    }
    if (command instanceof ReferenceCommand.Place place) {
      applyPlace(place, outcome.events());
    } else if (command instanceof ReferenceCommand.Cancel cancel) {
      applyCancel(cancel, outcome.events());
    } else {
      throw new IllegalArgumentException("unsupported ledger command");
    }
    verifyQuantityPartitions();
    verifyBook(outcome.bookAfter());
  }

  private void applyPlace(ReferenceCommand.Place command, List<SemanticEvent> events) {
    SemanticEvent first = events.getFirst();
    if (first instanceof SemanticEvent.Rejected) {
      singleton(events, "rejected PLACE");
      require(
          !validPlace(command),
          "VALIDATION_PRIORITY_AND_NO_MUTATION",
          "INVALID_PLACE_ACCEPTANCE",
          "valid PLACE was reported as validation failure");
      return;
    }
    if (first instanceof SemanticEvent.PlaceRejected rejected) {
      singleton(events, "business-rejected PLACE");
      require(
          validPlace(command),
          "EVENT_GRAMMAR",
          "INVALID_PLACE_BUSINESS_REJECTION",
          "invalid PLACE bypassed validation");
      require(
          rejected.orderId().equals(command.orderId())
              && "DUPLICATE_ORDER_ID".equals(rejected.code()),
          "EVENT_GRAMMAR",
          "PLACE_REJECTION_FIELDS",
          "PLACE rejection fields differ from its command");
      require(
          orders.containsKey(command.orderId()),
          "LIFECYCLE_IRREVERSIBILITY",
          "UNKNOWN_DUPLICATE",
          "unseen order was reported as duplicate");
      return;
    }
    if (!(first instanceof SemanticEvent.Accepted accepted)) {
      fail("EVENT_GRAMMAR", "PLACE_FIRST_EVENT", "PLACE did not start with a legal result");
      return;
    }
    require(
        validPlace(command),
        "VALIDATION_PRIORITY_AND_NO_MUTATION",
        "INVALID_PLACE_ACCEPTED",
        "invalid PLACE was accepted");
    require(
        !orders.containsKey(command.orderId()),
        "LIFECYCLE_IRREVERSIBILITY",
        "TERMINAL_OR_ACTIVE_ID_REUSED",
        "an accepted order identity was reused");
    require(
        accepted.sequence().equals(nextSequence),
        "ACCEPTANCE_SEQUENCE_CONTIGUITY",
        "SEQUENCE_GAP",
        "accepted sequence is not contiguous");
    require(
        accepted.orderId().equals(command.orderId())
            && accepted.side().equals(command.side())
            && accepted.priceTicks().equals(command.priceTicks())
            && accepted.quantityLots().equals(command.quantityLots()),
        "EVENT_GRAMMAR",
        "ACCEPTED_FIELDS",
        "Accepted fields differ from the PLACE command");

    LedgerOrder taker =
        new LedgerOrder(
            accepted.sequence(),
            accepted.orderId(),
            accepted.side(),
            accepted.priceTicks(),
            accepted.quantityLots());
    orders.put(taker.orderId, taker);
    nextSequence = nextSequence.add(ONE);

    boolean rested = false;
    for (int index = 1; index < events.size(); index++) {
      SemanticEvent event = events.get(index);
      if (event instanceof SemanticEvent.Trade trade) {
        require(!rested, "EVENT_GRAMMAR", "TRADE_AFTER_RESTED", "Trade appeared after Rested");
        applyTrade(taker, trade);
      } else if (event instanceof SemanticEvent.Rested restedEvent) {
        require(
            index == events.size() - 1,
            "EVENT_GRAMMAR",
            "RESTED_NOT_FINAL",
            "Rested is not the final PLACE event");
        require(
            restedEvent.sequence().equals(taker.sequence)
                && restedEvent.orderId().equals(taker.orderId)
                && restedEvent.side().equals(taker.side)
                && restedEvent.priceTicks().equals(taker.price)
                && restedEvent.remainingQuantityLots().equals(taker.remaining),
            "EVENT_GRAMMAR",
            "RESTED_FIELDS",
            "Rested fields differ from the accepted remainder");
        require(
            taker.remaining.signum() > 0,
            "QUANTITY_PARTITION",
            "ZERO_REMAINDER_RESTED",
            "non-positive taker remainder was rested");
        taker.lifecycle = Lifecycle.RESTING;
        rested = true;
      } else {
        fail("EVENT_GRAMMAR", "PLACE_EVENT_UNION", "accepted PLACE has an illegal event");
      }
    }
    if (taker.remaining.signum() == 0) {
      require(!rested, "EVENT_GRAMMAR", "FILLED_TAKER_RESTED", "filled taker emitted Rested");
      taker.lifecycle = Lifecycle.FILLED;
    } else {
      require(
          rested, "EVENT_GRAMMAR", "MISSING_RESTED", "positive taker remainder emitted no Rested");
    }
  }

  private void applyTrade(LedgerOrder taker, SemanticEvent.Trade trade) {
    require(
        trade.takerSequence().equals(taker.sequence) && trade.takerOrderId().equals(taker.orderId),
        "EVENT_GRAMMAR",
        "TRADE_TAKER_IDENTITY",
        "Trade taker differs from Accepted");
    require(
        trade.quantityLots().signum() > 0,
        "QUANTITY_PARTITION",
        "NONPOSITIVE_TRADE",
        "Trade quantity is not positive");
    LedgerOrder maker = bestMaker(taker.side, taker.price);
    require(
        maker != null,
        "PRICE_TIME_PRIORITY",
        "TRADE_WITHOUT_CROSSING_MAKER",
        "Trade has no eligible maker");
    require(
        maker.orderId.equals(trade.makerOrderId()) && maker.sequence.equals(trade.makerSequence()),
        "PRICE_TIME_PRIORITY",
        "WRONG_MAKER_ORDER",
        "Trade did not select the best price-time maker");
    require(
        maker.price.equals(trade.priceTicks()),
        "MAKER_PRICE",
        "TRADE_PRICE",
        "Trade price differs from maker price");
    require(
        trade.quantityLots().compareTo(maker.remaining) <= 0
            && trade.quantityLots().compareTo(taker.remaining) <= 0,
        "QUANTITY_PARTITION",
        "TRADE_EXCEEDS_REMAINDER",
        "Trade quantity exceeds maker or taker remainder");
    maker.fill(trade.quantityLots());
    taker.fill(trade.quantityLots());
    if (maker.remaining.signum() == 0) {
      maker.lifecycle = Lifecycle.FILLED;
    }
  }

  private void applyCancel(ReferenceCommand.Cancel command, List<SemanticEvent> events) {
    singleton(events, "CANCEL");
    SemanticEvent first = events.getFirst();
    if (first instanceof SemanticEvent.Rejected) {
      require(
          !validCancel(command),
          "VALIDATION_PRIORITY_AND_NO_MUTATION",
          "VALID_CANCEL_REJECTED",
          "valid CANCEL was reported as validation failure");
      return;
    }
    require(
        validCancel(command),
        "EVENT_GRAMMAR",
        "INVALID_CANCEL_BUSINESS_RESULT",
        "invalid CANCEL bypassed validation");
    LedgerOrder order = orders.get(command.orderId());
    if (first instanceof SemanticEvent.CancelRejected rejected) {
      require(
          rejected.orderId().equals(command.orderId()),
          "EVENT_GRAMMAR",
          "CANCEL_REJECTION_ORDER",
          "CancelRejected order differs from its command");
      String expectedCode =
          order == null
              ? "ORDER_NOT_FOUND"
              : switch (order.lifecycle) {
                case FILLED -> "ORDER_ALREADY_FILLED";
                case CANCELED -> "ORDER_ALREADY_CANCELED";
                case RESTING -> "CANCEL_SHOULD_SUCCEED";
              };
      require(
          expectedCode.equals(rejected.code()),
          "LIFECYCLE_IRREVERSIBILITY",
          "CANCEL_TERMINAL_RESULT",
          "CancelRejected code differs from event-derived lifecycle");
      return;
    }
    if (!(first instanceof SemanticEvent.Canceled canceled)) {
      fail("EVENT_GRAMMAR", "CANCEL_EVENT_UNION", "CANCEL returned an illegal event");
      return;
    }
    require(
        order != null && order.lifecycle == Lifecycle.RESTING,
        "LIFECYCLE_IRREVERSIBILITY",
        "NONRESTING_CANCEL_SUCCEEDED",
        "CANCEL succeeded for a non-resting identity");
    require(
        canceled.sequence().equals(order.sequence)
            && canceled.orderId().equals(order.orderId)
            && canceled.side().equals(order.side)
            && canceled.priceTicks().equals(order.price)
            && canceled.canceledQuantityLots().equals(order.remaining),
        "QUANTITY_PARTITION",
        "CANCELED_FIELDS",
        "Canceled does not report the exact active remainder");
    order.canceled = order.canceled.add(order.remaining);
    order.remaining = ZERO;
    order.lifecycle = Lifecycle.CANCELED;
  }

  private LedgerOrder bestMaker(String takerSide, BigInteger limitPrice) {
    LedgerOrder selected = null;
    for (LedgerOrder candidate : orders.values()) {
      if (candidate.lifecycle != Lifecycle.RESTING || candidate.side.equals(takerSide)) {
        continue;
      }
      boolean crosses =
          "BUY".equals(takerSide)
              ? limitPrice.compareTo(candidate.price) >= 0
              : limitPrice.compareTo(candidate.price) <= 0;
      if (!crosses) {
        continue;
      }
      if (selected == null || betterMaker(candidate, selected, takerSide)) {
        selected = candidate;
      }
    }
    return selected;
  }

  private static boolean betterMaker(
      LedgerOrder candidate, LedgerOrder selected, String takerSide) {
    int priceOrder = candidate.price.compareTo(selected.price);
    if (priceOrder != 0) {
      return "BUY".equals(takerSide) ? priceOrder < 0 : priceOrder > 0;
    }
    return candidate.sequence.compareTo(selected.sequence) < 0;
  }

  private void verifyQuantityPartitions() {
    for (LedgerOrder order : orders.values()) {
      require(
          order.original.equals(order.filled.add(order.remaining).add(order.canceled)),
          "QUANTITY_PARTITION",
          "ORDER_PARTITION",
          "original quantity differs from filled + remaining + canceled");
      require(
          order.original.signum() > 0
              && order.filled.signum() >= 0
              && order.remaining.signum() >= 0
              && order.canceled.signum() >= 0,
          "QUANTITY_PARTITION",
          "NEGATIVE_PARTITION",
          "order quantity partition contains a negative value");
      require(
          (order.lifecycle == Lifecycle.RESTING) == (order.remaining.signum() > 0),
          "LIFECYCLE_IRREVERSIBILITY",
          "LIFECYCLE_REMAINDER",
          "lifecycle and remaining quantity disagree");
    }
  }

  private void verifyBook(SemanticBook book) {
    Set<BigInteger> activeIds = new HashSet<>();
    BigInteger bestBid = verifySide(book.bids(), "BUY", true, activeIds);
    BigInteger bestAsk = verifySide(book.asks(), "SELL", false, activeIds);
    require(
        bestBid == null || bestAsk == null || bestBid.compareTo(bestAsk) < 0,
        "BOOK_ORDER_FIFO_AND_NON_CROSSED",
        "CROSSED_BOOK",
        "returned book remains crossed");

    Set<BigInteger> expectedActive = new HashSet<>();
    for (LedgerOrder order : orders.values()) {
      if (order.lifecycle == Lifecycle.RESTING) {
        expectedActive.add(order.orderId);
      }
    }
    require(
        expectedActive.equals(activeIds),
        "BOOK_LIFECYCLE_BIJECTION",
        "ACTIVE_ID_SET",
        "returned book and event-derived resting identity set differ");
  }

  private BigInteger verifySide(
      List<SemanticBook.PriceLevel> levels,
      String side,
      boolean descending,
      Set<BigInteger> activeIds) {
    BigInteger previousPrice = null;
    BigInteger best = null;
    for (SemanticBook.PriceLevel level : levels) {
      require(
          side.equals(level.side()),
          "BOOK_ORDER_FIFO_AND_NON_CROSSED",
          "LEVEL_SIDE",
          "book level appears on the wrong side");
      require(
          !level.orders().isEmpty(),
          "BOOK_ORDER_FIFO_AND_NON_CROSSED",
          "EMPTY_LEVEL",
          "book contains an empty level");
      if (previousPrice != null) {
        int order = level.priceTicks().compareTo(previousPrice);
        require(
            descending ? order < 0 : order > 0,
            "BOOK_ORDER_FIFO_AND_NON_CROSSED",
            "LEVEL_ORDER",
            "price levels are not in strict execution order");
      } else {
        best = level.priceTicks();
      }
      previousPrice = level.priceTicks();
      BigInteger previousSequence = ZERO;
      for (SemanticBook.RestingOrder resting : level.orders()) {
        require(
            resting.remainingQuantityLots().signum() > 0,
            "BOOK_ORDER_FIFO_AND_NON_CROSSED",
            "NONPOSITIVE_RESTING",
            "book contains non-positive resting quantity");
        require(
            resting.sequence().compareTo(previousSequence) > 0,
            "BOOK_ORDER_FIFO_AND_NON_CROSSED",
            "LEVEL_FIFO",
            "same-price orders are not FIFO by sequence");
        require(
            activeIds.add(resting.orderId()),
            "BOOK_ORDER_FIFO_AND_NON_CROSSED",
            "DUPLICATE_BOOK_ID",
            "order identity appears more than once in the book");
        LedgerOrder ledger = orders.get(resting.orderId());
        require(
            ledger != null
                && ledger.sequence.equals(resting.sequence())
                && ledger.side.equals(side)
                && ledger.price.equals(level.priceTicks()),
            "BOOK_LIFECYCLE_BIJECTION",
            "RESTING_FIELDS",
            "book node identity differs from event-derived lifecycle state");
        if (ledger.lifecycle == Lifecycle.RESTING) {
          require(
              ledger.remaining.equals(resting.remainingQuantityLots()),
              "BOOK_LIFECYCLE_BIJECTION",
              "RESTING_FIELDS",
              "book node remainder differs from event-derived lifecycle state");
        }
        previousSequence = resting.sequence();
      }
    }
    return best;
  }

  private static boolean validPlace(ReferenceCommand.Place command) {
    return "BTC-USDT".equals(command.instrumentId())
        && inPositiveLong(command.orderId())
        && ("BUY".equals(command.side()) || "SELL".equals(command.side()))
        && inPositiveLong(command.priceTicks())
        && inPositiveLong(command.quantityLots());
  }

  private static boolean validCancel(ReferenceCommand.Cancel command) {
    return "BTC-USDT".equals(command.instrumentId()) && inPositiveLong(command.orderId());
  }

  private static boolean inPositiveLong(BigInteger value) {
    return value.compareTo(ONE) >= 0 && value.compareTo(MAX_LONG) <= 0;
  }

  private static void singleton(List<SemanticEvent> events, String label) {
    require(
        events.size() == 1,
        "EVENT_GRAMMAR",
        "NON_SINGLETON_RESULT",
        label + " must emit exactly one event");
  }

  private static void require(
      boolean condition, String propertyId, String divergenceKind, String message) {
    if (!condition) {
      fail(propertyId, divergenceKind, message);
    }
  }

  private static void fail(String propertyId, String divergenceKind, String message) {
    throw new M03PropertyJudge.PropertyFailure(propertyId, divergenceKind, message);
  }

  private enum Lifecycle {
    RESTING,
    FILLED,
    CANCELED
  }

  private static final class LedgerOrder {
    private final BigInteger sequence;
    private final BigInteger orderId;
    private final String side;
    private final BigInteger price;
    private final BigInteger original;
    private BigInteger filled = ZERO;
    private BigInteger remaining;
    private BigInteger canceled = ZERO;
    private Lifecycle lifecycle = Lifecycle.RESTING;

    private LedgerOrder(
        BigInteger sequence,
        BigInteger orderId,
        String side,
        BigInteger price,
        BigInteger original) {
      this.sequence = sequence;
      this.orderId = orderId;
      this.side = side;
      this.price = price;
      this.original = original;
      remaining = original;
    }

    private void fill(BigInteger quantity) {
      remaining = remaining.subtract(quantity);
      filled = filled.add(quantity);
    }
  }
}
