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

/** Independent lifecycle and book ledger derived only from commands and candidate outcomes. */
final class M04EventLedger {
  private static final BigInteger ZERO = BigInteger.ZERO;
  private static final BigInteger ONE = BigInteger.ONE;
  private static final BigInteger MAX_LONG = BigInteger.valueOf(Long.MAX_VALUE);
  private final Map<BigInteger, LedgerOrder> orders = new HashMap<>();
  private BigInteger nextSequence = ONE;
  private String lastPolicyRejection;

  void verifyAndApply(ReferenceCommand command, SemanticOutcome outcome) {
    lastPolicyRejection = null;
    if (outcome.events().isEmpty()) {
      fail("EVENT_GRAMMAR", "EMPTY_BATCH", "candidate emitted no event");
    }
    switch (command) {
      case ReferenceCommand.Place place -> applyPlace(place, outcome.events());
      case ReferenceCommand.Cancel cancel -> applyCancel(cancel, outcome.events());
    }
    verifyPartitions();
    verifyBook(outcome.bookAfter());
  }

  private void applyPlace(ReferenceCommand.Place command, List<SemanticEvent> events) {
    SemanticEvent first = events.getFirst();
    ValidationFailure invalid = invalidPlace(command);
    if (first instanceof SemanticEvent.Rejected rejected) {
      singleton(events, "Rejected PLACE");
      require(
          invalid != null,
          "VALIDATION_PRIORITY_AND_NO_MUTATION",
          "VALID_PLACE_REJECTED",
          "valid PLACE was field-rejected");
      require(
          invalid.code.equals(rejected.code()) && invalid.field.equals(rejected.field()),
          "VALIDATION_PRIORITY_AND_NO_MUTATION",
          "VALIDATION_PRIORITY",
          "Rejected code/field does not match frozen priority");
      return;
    }
    require(
        invalid == null,
        "VALIDATION_PRIORITY_AND_NO_MUTATION",
        invalid != null && "executionPolicy".equals(invalid.field)
            ? "UNKNOWN_POLICY_ACCEPTED"
            : "INVALID_PLACE_ACCEPTED",
        "invalid PLACE bypassed field validation");

    String businessRejection = expectedBusinessRejection(command);
    if (first instanceof SemanticEvent.PlaceRejected rejected) {
      singleton(events, "PlaceRejected PLACE");
      require(
          rejected.orderId().equals(command.orderId()),
          "EVENT_GRAMMAR",
          "PLACE_REJECTION_ORDER",
          "PlaceRejected order differs from command");
      String property =
          "IOC".equals(command.executionPolicy())
              ? "IOC_IMMEDIATE_EXECUTION"
              : businessProperty(rejected.code());
      String divergence =
          "IOC".equals(command.executionPolicy())
              ? "IOC_WAS_PRECHECK_REJECTED"
              : "DUPLICATE_ORDER_ID".equals(rejected.code())
                      && !orders.containsKey(command.orderId())
                  ? "REJECTED_ID_RESERVED"
                  : "FOK_NOT_FILLABLE".equals(rejected.code())
                          && fullyExecutable(command)
                          && !bestLevelFillable(command)
                      ? "MULTI_LEVEL_LIQUIDITY_IGNORED"
                      : businessDivergence(rejected.code());
      require(
          businessRejection != null && businessRejection.equals(rejected.code()),
          "REJECTED_ID_RESERVED".equals(divergence)
              ? "POLICY_REJECTION_ATOMICITY"
              : "MULTI_LEVEL_LIQUIDITY_IGNORED".equals(divergence) ? "FOK_FILLABILITY" : property,
          divergence,
          "PlaceRejected code differs from event-derived pre-Accept decision");
      if ("FOK_NOT_FILLABLE".equals(rejected.code())
          || "POST_ONLY_WOULD_TAKE".equals(rejected.code())) {
        lastPolicyRejection = rejected.code();
      }
      return;
    }

    String acceptedRejectionDivergence = acceptanceDivergence(command, businessRejection);
    require(
        businessRejection == null,
        "OUTSIDE_LIMIT_LIQUIDITY_COUNTED".equals(acceptedRejectionDivergence)
            ? "FOK_FILLABILITY"
            : businessProperty(businessRejection),
        acceptedRejectionDivergence,
        "a policy-rejected PLACE was accepted");
    if (!(first instanceof SemanticEvent.Accepted accepted)) {
      fail("EVENT_GRAMMAR", "PLACE_FIRST_EVENT", "PLACE did not start with a legal event");
      return;
    }
    require(
        accepted.sequence().equals(nextSequence),
        "ACCEPTANCE_SEQUENCE_CONTIGUITY",
        "SEQUENCE_GAP",
        "accepted sequence is not contiguous");
    require(
        accepted.orderId().equals(command.orderId())
            && accepted.side().equals(command.side())
            && accepted.priceTicks().equals(command.priceTicks())
            && accepted.quantityLots().equals(command.quantityLots())
            && accepted.executionPolicy().equals(command.executionPolicy()),
        "EVENT_GRAMMAR",
        "ACCEPTED_FIELDS",
        "Accepted fields differ from request");
    LedgerOrder taker =
        new LedgerOrder(
            accepted.sequence(),
            accepted.orderId(),
            accepted.side(),
            accepted.priceTicks(),
            accepted.quantityLots(),
            accepted.executionPolicy());
    orders.put(taker.orderId, taker);
    nextSequence = nextSequence.add(ONE);

    boolean rested = false;
    boolean remainderCanceled = false;
    int trades = 0;
    for (int index = 1; index < events.size(); index++) {
      SemanticEvent event = events.get(index);
      if (event instanceof SemanticEvent.Trade trade) {
        require(
            !rested && !remainderCanceled,
            "EVENT_GRAMMAR",
            "TRADE_AFTER_TERMINAL",
            "Trade appeared after final disposition");
        applyTrade(taker, trade);
        trades++;
      } else if (event instanceof SemanticEvent.Rested rest) {
        require(
            index == events.size() - 1, "EVENT_GRAMMAR", "RESTED_NOT_FINAL", "Rested is not final");
        require(
            "GTC".equals(taker.policy) || "POST_ONLY".equals(taker.policy),
            "EXECUTION_POLICY_GRAMMAR",
            "IOC_REMAINDER_RESTED",
            "IOC/FOK remainder entered the book");
        requireRested(taker, rest);
        taker.lifecycle = Lifecycle.RESTING;
        rested = true;
      } else if (event instanceof SemanticEvent.RemainderCanceled canceled) {
        require(
            index == events.size() - 1,
            "EVENT_GRAMMAR",
            "REMAINDER_NOT_FINAL",
            "RemainderCanceled is not final");
        require(
            "IOC".equals(taker.policy),
            "IOC_REMAINDER_DISPOSITION",
            "NON_IOC_REMAINDER_CANCELED",
            "non-IOC emitted RemainderCanceled");
        requireRemainderCanceled(taker, canceled);
        taker.policyCanceled = taker.remaining;
        taker.remaining = ZERO;
        taker.lifecycle = Lifecycle.CANCELED;
        remainderCanceled = true;
      } else {
        fail("EVENT_GRAMMAR", "PLACE_EVENT_UNION", "accepted PLACE emitted illegal event");
      }
    }

    switch (taker.policy) {
      case "GTC" -> {
        if (taker.remaining.signum() == 0) {
          require(!rested, "EVENT_GRAMMAR", "FILLED_GTC_RESTED", "filled GTC rested");
          taker.lifecycle = Lifecycle.FILLED;
        } else {
          require(rested, "EVENT_GRAMMAR", "GTC_MISSING_RESTED", "GTC remainder did not rest");
        }
      }
      case "IOC" -> {
        require(!rested, "IOC_REMAINDER_DISPOSITION", "IOC_REMAINDER_RESTED", "IOC rested");
        if (remainderCanceled) {
          require(
              taker.policyCanceled.signum() > 0
                  && taker.remaining.signum() == 0
                  && taker.lifecycle == Lifecycle.CANCELED,
              "IOC_REMAINDER_DISPOSITION",
              "IOC_REMAINDER_EVENT",
              "IOC remainder disposition was not a positive terminal cancellation");
        } else {
          require(
              taker.remaining.signum() == 0,
              "IOC_REMAINDER_DISPOSITION",
              "IOC_MISSING_REMAINDER_EVENT",
              "positive IOC remainder was not explicitly canceled");
          taker.lifecycle = Lifecycle.FILLED;
        }
      }
      case "FOK" -> {
        require(
            taker.remaining.signum() == 0 && !rested && !remainderCanceled && trades > 0,
            "FOK_ATOMICITY",
            "FOK_PARTIAL_OR_RESTING",
            "accepted FOK was not completely filled");
        taker.lifecycle = Lifecycle.FILLED;
      }
      case "POST_ONLY" -> {
        require(
            trades == 0 && rested && taker.remaining.equals(taker.original),
            "POST_ONLY_ADMISSION",
            "POST_ONLY_TRADED",
            "accepted Post-only did not rest its full quantity");
      }
      default ->
          fail(
              "VALIDATION_PRIORITY_AND_NO_MUTATION",
              "UNKNOWN_POLICY_ACCEPTED",
              "unknown policy accepted");
    }
  }

  private String expectedBusinessRejection(ReferenceCommand.Place command) {
    if (orders.containsKey(command.orderId())) {
      return "DUPLICATE_ORDER_ID";
    }
    if ("FOK".equals(command.executionPolicy()) && !fullyExecutable(command)) {
      return "FOK_NOT_FILLABLE";
    }
    if ("POST_ONLY".equals(command.executionPolicy()) && wouldTake(command)) {
      return "POST_ONLY_WOULD_TAKE";
    }
    return null;
  }

  private static String businessProperty(String rejection) {
    if ("FOK_NOT_FILLABLE".equals(rejection)) {
      return "FOK_ATOMICITY";
    }
    if ("POST_ONLY_WOULD_TAKE".equals(rejection)) {
      return "POST_ONLY_ADMISSION";
    }
    return "POLICY_REJECTION_NO_SIDE_EFFECT";
  }

  private String businessDivergence(String rejection) {
    if (rejection == null) {
      return "POLICY_REJECTION_ACCEPTED";
    }
    if ("FOK_NOT_FILLABLE".equals(rejection)) {
      return "FOK_FILLABLE_REJECTED";
    }
    if ("POST_ONLY_WOULD_TAKE".equals(rejection)) {
      return "POST_ONLY_REJECTION_MISMATCH";
    }
    return "PLACE_REJECTION_MISMATCH";
  }

  private String acceptanceDivergence(ReferenceCommand.Place command, String rejection) {
    return switch (rejection) {
      case "FOK_NOT_FILLABLE" ->
          liquidityIgnoringLimit(command)
              ? "OUTSIDE_LIMIT_LIQUIDITY_COUNTED"
              : "FOK_INSUFFICIENT_ACCEPTED";
      case "POST_ONLY_WOULD_TAKE" ->
          touchesBest(command) ? "TOUCH_WAS_ACCEPTED" : "CROSS_WAS_ACCEPTED";
      case "DUPLICATE_ORDER_ID" -> "DUPLICATE_ACCEPTED";
      case null -> "POLICY_REJECTION_ACCEPTED";
      default -> "PLACE_REJECTION_ACCEPTED";
    };
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
        "PRICE_PROTECTION",
        "TRADE_OUTSIDE_LIMIT",
        "Trade has no eligible maker within priceTicks");
    require(
        maker.orderId.equals(trade.makerOrderId()) && maker.sequence.equals(trade.makerSequence()),
        "PRICE_TIME_PRIORITY",
        "WRONG_MAKER_ORDER",
        "Trade did not use best price-time maker");
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
    require(
        trade.quantityLots().equals(maker.remaining.min(taker.remaining)),
        "QUANTITY_PARTITION",
        "TRADE_NOT_MAXIMAL",
        "Trade did not consume the full maker/taker minimum");
    maker.fill(trade.quantityLots());
    taker.fill(trade.quantityLots());
    if (maker.remaining.signum() == 0) {
      maker.lifecycle = Lifecycle.FILLED;
    }
  }

  private static void requireRested(LedgerOrder taker, SemanticEvent.Rested rested) {
    require(
        rested.sequence().equals(taker.sequence)
            && rested.orderId().equals(taker.orderId)
            && rested.side().equals(taker.side)
            && rested.priceTicks().equals(taker.price)
            && rested.remainingQuantityLots().equals(taker.remaining)
            && taker.remaining.signum() > 0,
        "EVENT_GRAMMAR",
        "RESTED_FIELDS",
        "Rested fields differ from positive taker remainder");
  }

  private static void requireRemainderCanceled(
      LedgerOrder taker, SemanticEvent.RemainderCanceled canceled) {
    require(
        canceled.sequence().equals(taker.sequence)
            && canceled.orderId().equals(taker.orderId)
            && canceled.side().equals(taker.side)
            && canceled.priceTicks().equals(taker.price)
            && canceled.canceledQuantityLots().equals(taker.remaining)
            && taker.remaining.signum() > 0
            && "IOC_REMAINDER".equals(canceled.reason()),
        "IOC_REMAINDER_DISPOSITION",
        "IOC_REMAINDER_EVENT",
        "RemainderCanceled fields or reason differ from IOC remainder");
  }

  private void applyCancel(ReferenceCommand.Cancel command, List<SemanticEvent> events) {
    singleton(events, "CANCEL");
    SemanticEvent first = events.getFirst();
    ValidationFailure invalid = invalidCancel(command);
    if (first instanceof SemanticEvent.Rejected rejected) {
      require(
          invalid != null,
          "VALIDATION_PRIORITY_AND_NO_MUTATION",
          "VALID_CANCEL_REJECTED",
          "valid CANCEL rejected");
      require(
          invalid.code.equals(rejected.code()) && invalid.field.equals(rejected.field()),
          "VALIDATION_PRIORITY_AND_NO_MUTATION",
          "CANCEL_VALIDATION_PRIORITY",
          "CANCEL validation result differs from priority");
      return;
    }
    require(
        invalid == null,
        "EVENT_GRAMMAR",
        "INVALID_CANCEL_RESULT",
        "invalid CANCEL bypassed validation");
    LedgerOrder order = orders.get(command.orderId());
    if (first instanceof SemanticEvent.CancelRejected rejected) {
      String expected =
          order == null
              ? "ORDER_NOT_FOUND"
              : switch (order.lifecycle) {
                case FILLED -> "ORDER_ALREADY_FILLED";
                case CANCELED -> "ORDER_ALREADY_CANCELED";
                case RESTING -> "CANCEL_SHOULD_SUCCEED";
              };
      require(
          rejected.orderId().equals(command.orderId()) && expected.equals(rejected.code()),
          "LIFECYCLE_IRREVERSIBILITY",
          "CANCEL_TERMINAL_RESULT",
          "CancelRejected differs from event-derived lifecycle");
      return;
    }
    if (!(first instanceof SemanticEvent.Canceled canceled)) {
      fail("EVENT_GRAMMAR", "CANCEL_EVENT_UNION", "CANCEL emitted illegal event");
      return;
    }
    require(
        order != null && order.lifecycle == Lifecycle.RESTING,
        "LIFECYCLE_IRREVERSIBILITY",
        "NONRESTING_CANCEL_SUCCEEDED",
        "CANCEL succeeded for non-resting identity");
    require(
        canceled.sequence().equals(order.sequence)
            && canceled.orderId().equals(order.orderId)
            && canceled.side().equals(order.side)
            && canceled.priceTicks().equals(order.price)
            && canceled.canceledQuantityLots().equals(order.remaining),
        "QUANTITY_PARTITION",
        "CANCELED_FIELDS",
        "Canceled differs from active remainder");
    order.userCanceled = order.remaining;
    order.remaining = ZERO;
    order.lifecycle = Lifecycle.CANCELED;
  }

  private boolean fullyExecutable(ReferenceCommand.Place command) {
    BigInteger need = command.quantityLots();
    for (LedgerOrder maker : orders.values()) {
      if (!eligible(maker, command.side(), command.priceTicks())) {
        continue;
      }
      if (maker.remaining.compareTo(need) >= 0) {
        return true;
      }
      need = need.subtract(maker.remaining);
    }
    return false;
  }

  private boolean bestLevelFillable(ReferenceCommand.Place command) {
    LedgerOrder best = bestMaker(command.side(), command.priceTicks());
    if (best == null) {
      return false;
    }
    BigInteger need = command.quantityLots();
    for (LedgerOrder maker : orders.values()) {
      if (maker.lifecycle != Lifecycle.RESTING
          || maker.side.equals(command.side())
          || !maker.price.equals(best.price)) {
        continue;
      }
      if (maker.remaining.compareTo(need) >= 0) {
        return true;
      }
      need = need.subtract(maker.remaining);
    }
    return false;
  }

  private boolean liquidityIgnoringLimit(ReferenceCommand.Place command) {
    if (fullyExecutable(command)) {
      return false;
    }
    BigInteger need = command.quantityLots();
    for (LedgerOrder maker : orders.values()) {
      if (maker.lifecycle != Lifecycle.RESTING || maker.side.equals(command.side())) {
        continue;
      }
      if (maker.remaining.compareTo(need) >= 0) {
        return true;
      }
      need = need.subtract(maker.remaining);
    }
    return false;
  }

  private boolean touchesBest(ReferenceCommand.Place command) {
    LedgerOrder best = bestMaker(command.side(), command.priceTicks());
    return best != null && best.price.equals(command.priceTicks());
  }

  private boolean wouldTake(ReferenceCommand.Place command) {
    return orders.values().stream()
        .anyMatch(order -> eligible(order, command.side(), command.priceTicks()));
  }

  private LedgerOrder bestMaker(String side, BigInteger price) {
    LedgerOrder selected = null;
    for (LedgerOrder candidate : orders.values()) {
      if (!eligible(candidate, side, price)) {
        continue;
      }
      if (selected == null || better(candidate, selected, side)) {
        selected = candidate;
      }
    }
    return selected;
  }

  private static boolean eligible(LedgerOrder order, String takerSide, BigInteger limit) {
    if (order.lifecycle != Lifecycle.RESTING || order.side.equals(takerSide)) {
      return false;
    }
    return "BUY".equals(takerSide)
        ? limit.compareTo(order.price) >= 0
        : limit.compareTo(order.price) <= 0;
  }

  private static boolean better(LedgerOrder candidate, LedgerOrder selected, String takerSide) {
    int price = candidate.price.compareTo(selected.price);
    if (price != 0) {
      return "BUY".equals(takerSide) ? price < 0 : price > 0;
    }
    return candidate.sequence.compareTo(selected.sequence) < 0;
  }

  private void verifyPartitions() {
    for (LedgerOrder order : orders.values()) {
      require(
          order.original.equals(
              order.filled.add(order.remaining).add(order.userCanceled).add(order.policyCanceled)),
          "QUANTITY_PARTITION",
          "ORDER_PARTITION",
          "original != filled + resting + userCanceled + policyRemainderCanceled");
      require(
          order.filled.signum() >= 0
              && order.remaining.signum() >= 0
              && order.userCanceled.signum() >= 0
              && order.policyCanceled.signum() >= 0,
          "QUANTITY_PARTITION",
          "NEGATIVE_PARTITION",
          "quantity partition is negative");
      require(
          (order.lifecycle == Lifecycle.RESTING) == (order.remaining.signum() > 0),
          "LIFECYCLE_IRREVERSIBILITY",
          "LIFECYCLE_REMAINDER",
          "lifecycle and remainder disagree");
    }
  }

  private void verifyBook(SemanticBook book) {
    Set<BigInteger> active = new HashSet<>();
    BigInteger bestBid = verifySide(book.bids(), "BUY", true, active);
    BigInteger bestAsk = verifySide(book.asks(), "SELL", false, active);
    require(
        bestBid == null || bestAsk == null || bestBid.compareTo(bestAsk) < 0,
        "BOOK_ORDER_FIFO_AND_NON_CROSSED",
        "CROSSED_BOOK",
        "book is crossed");
    Set<BigInteger> expected = new HashSet<>();
    orders.values().stream()
        .filter(order -> order.lifecycle == Lifecycle.RESTING)
        .forEach(order -> expected.add(order.orderId));
    require(
        expected.equals(active),
        bookProperty(),
        bookDivergence("ACTIVE_ID_SET"),
        "book identities differ from ledger");
  }

  private BigInteger verifySide(
      List<SemanticBook.PriceLevel> levels,
      String side,
      boolean descending,
      Set<BigInteger> active) {
    BigInteger previousPrice = null;
    BigInteger best = null;
    for (SemanticBook.PriceLevel level : levels) {
      require(
          side.equals(level.side()),
          "BOOK_ORDER_FIFO_AND_NON_CROSSED",
          "LEVEL_SIDE",
          "wrong level side");
      require(
          !level.orders().isEmpty(),
          "BOOK_ORDER_FIFO_AND_NON_CROSSED",
          "EMPTY_LEVEL",
          "empty level");
      if (previousPrice == null) {
        best = level.priceTicks();
      } else {
        int order = level.priceTicks().compareTo(previousPrice);
        require(
            descending ? order < 0 : order > 0,
            "BOOK_ORDER_FIFO_AND_NON_CROSSED",
            "LEVEL_ORDER",
            "levels out of order");
      }
      previousPrice = level.priceTicks();
      BigInteger previousSequence = ZERO;
      for (SemanticBook.RestingOrder resting : level.orders()) {
        LedgerOrder ledger = orders.get(resting.orderId());
        require(
            active.add(resting.orderId())
                && resting.sequence().compareTo(previousSequence) > 0
                && ledger != null
                && ledger.lifecycle == Lifecycle.RESTING
                && ledger.sequence.equals(resting.sequence())
                && ledger.side.equals(side)
                && ledger.price.equals(level.priceTicks())
                && ledger.remaining.equals(resting.remainingQuantityLots()),
            bookProperty(),
            bookDivergence("RESTING_FIELDS"),
            "book node differs from ledger or FIFO");
        previousSequence = resting.sequence();
      }
    }
    return best;
  }

  private String bookProperty() {
    return "FOK_NOT_FILLABLE".equals(lastPolicyRejection)
        ? "POLICY_REJECTION_ATOMICITY"
        : "BOOK_LIFECYCLE_BIJECTION";
  }

  private String bookDivergence(String fallback) {
    return "FOK_NOT_FILLABLE".equals(lastPolicyRejection) ? "FOK_REJECTION_CHANGED_BOOK" : fallback;
  }

  private static ValidationFailure invalidPlace(ReferenceCommand.Place command) {
    if (!"BTC-USDT".equals(command.instrumentId())) {
      return new ValidationFailure("UNKNOWN_INSTRUMENT", "instrumentId");
    }
    if (!positiveLong(command.orderId())) {
      return new ValidationFailure("INVALID_ORDER_ID", "orderId");
    }
    if (!"BUY".equals(command.side()) && !"SELL".equals(command.side())) {
      return new ValidationFailure("INVALID_SIDE", "side");
    }
    if (!positiveLong(command.priceTicks())) {
      return new ValidationFailure("INVALID_PRICE", "priceTicks");
    }
    if (!positiveLong(command.quantityLots())) {
      return new ValidationFailure("INVALID_QUANTITY", "quantityLots");
    }
    if (!List.of("GTC", "IOC", "FOK", "POST_ONLY").contains(command.executionPolicy())) {
      return new ValidationFailure("INVALID_EXECUTION_POLICY", "executionPolicy");
    }
    return null;
  }

  private static ValidationFailure invalidCancel(ReferenceCommand.Cancel command) {
    if (!"BTC-USDT".equals(command.instrumentId())) {
      return new ValidationFailure("UNKNOWN_INSTRUMENT", "instrumentId");
    }
    if (!positiveLong(command.orderId())) {
      return new ValidationFailure("INVALID_ORDER_ID", "orderId");
    }
    return null;
  }

  private static boolean positiveLong(BigInteger value) {
    return value.compareTo(ONE) >= 0 && value.compareTo(MAX_LONG) <= 0;
  }

  private static void singleton(List<SemanticEvent> events, String label) {
    require(
        events.size() == 1, "EVENT_GRAMMAR", "NON_SINGLETON_RESULT", label + " must be singleton");
  }

  private static void require(
      boolean condition, String property, String divergence, String message) {
    if (!condition) {
      fail(property, divergence, message);
    }
  }

  private static void fail(String property, String divergence, String message) {
    throw new M04PropertyJudge.PropertyFailure(property, divergence, message);
  }

  private record ValidationFailure(String code, String field) {}

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
    private final String policy;
    private BigInteger filled = ZERO;
    private BigInteger remaining;
    private BigInteger userCanceled = ZERO;
    private BigInteger policyCanceled = ZERO;
    private Lifecycle lifecycle = Lifecycle.RESTING;

    private LedgerOrder(
        BigInteger sequence,
        BigInteger orderId,
        String side,
        BigInteger price,
        BigInteger original,
        String policy) {
      this.sequence = sequence;
      this.orderId = orderId;
      this.side = side;
      this.price = price;
      this.original = original;
      this.remaining = original;
      this.policy = policy;
    }

    private void fill(BigInteger quantity) {
      remaining = remaining.subtract(quantity);
      filled = filled.add(quantity);
    }
  }
}
