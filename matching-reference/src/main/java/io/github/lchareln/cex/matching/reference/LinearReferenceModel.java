package io.github.lchareln.cex.matching.reference;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Independent M04 semantics backed by one flat order list and complete linear maker scans.
 *
 * <p>This deliberately does not share the production matcher's indexed book representation.
 */
public final class LinearReferenceModel implements ReferenceMatcher {
  private static final String INSTRUMENT = "BTC-USDT";
  private static final String BUY = "BUY";
  private static final String SELL = "SELL";
  private static final String GTC = "GTC";
  private static final String IOC = "IOC";
  private static final String FOK = "FOK";
  private static final String POST_ONLY = "POST_ONLY";
  private static final String IOC_REMAINDER = "IOC_REMAINDER";
  private static final BigInteger MAXIMUM = BigInteger.valueOf(Long.MAX_VALUE);

  private final List<ReferenceOrder> orders = new ArrayList<>();
  private BigInteger nextAcceptanceSequence = BigInteger.ONE;

  @Override
  public SemanticOutcome apply(ReferenceCommand command) {
    Objects.requireNonNull(command, "command");
    assertConsistentState();
    SemanticOutcome outcome =
        switch (command) {
          case ReferenceCommand.Place place -> place(place);
          case ReferenceCommand.Cancel cancel -> cancel(cancel);
        };
    assertConsistentState();
    if (!outcome.bookAfter().equals(deriveBook())) {
      throw new IllegalStateException("outcome book is not the derived reference book");
    }
    return outcome;
  }

  @Override
  public SemanticBook snapshot() {
    assertConsistentState();
    return deriveBook();
  }

  private SemanticOutcome place(ReferenceCommand.Place command) {
    SemanticEvent.Rejected invalid = validate(command);
    if (invalid != null) {
      return singleton(invalid);
    }
    if (find(command.orderId()) != null) {
      return singleton(new SemanticEvent.PlaceRejected(command.orderId(), "DUPLICATE_ORDER_ID"));
    }
    if (POST_ONLY.equals(command.executionPolicy()) && hasCrossingMaker(command)) {
      return singleton(new SemanticEvent.PlaceRejected(command.orderId(), "POST_ONLY_WOULD_TAKE"));
    }
    if (FOK.equals(command.executionPolicy()) && !isFullyExecutable(command)) {
      return singleton(new SemanticEvent.PlaceRejected(command.orderId(), "FOK_NOT_FILLABLE"));
    }
    if (nextAcceptanceSequence.compareTo(MAXIMUM) >= 0) {
      throw new IllegalStateException("acceptance sequence exhausted before state mutation");
    }

    BigInteger sequence = nextAcceptanceSequence;
    nextAcceptanceSequence = nextAcceptanceSequence.add(BigInteger.ONE);
    ReferenceOrder taker =
        new ReferenceOrder(
            sequence,
            command.orderId(),
            command.side(),
            command.priceTicks(),
            command.quantityLots());
    orders.add(taker);

    List<SemanticEvent> events = new ArrayList<>();
    events.add(
        new SemanticEvent.Accepted(
            sequence,
            command.orderId(),
            command.side(),
            command.priceTicks(),
            command.quantityLots(),
            command.executionPolicy()));

    while (taker.remaining.signum() > 0) {
      ReferenceOrder maker = selectMaker(taker);
      if (maker == null) {
        break;
      }
      BigInteger traded = taker.remaining.min(maker.remaining);
      maker.fill(traded);
      taker.fill(traded);
      events.add(
          new SemanticEvent.Trade(
              maker.sequence, maker.orderId, taker.sequence, taker.orderId, maker.price, traded));
    }

    if (taker.remaining.signum() > 0) {
      if (IOC.equals(command.executionPolicy())) {
        BigInteger canceled = taker.remaining;
        taker.cancelAcceptedRemainder(canceled);
        events.add(
            new SemanticEvent.RemainderCanceled(
                taker.sequence, taker.orderId, taker.side, taker.price, canceled, IOC_REMAINDER));
      } else if (FOK.equals(command.executionPolicy())) {
        throw new IllegalStateException("fillable FOK retained an unexpected remainder");
      } else {
        taker.markResting();
        events.add(
            new SemanticEvent.Rested(
                taker.sequence, taker.orderId, taker.side, taker.price, taker.remaining));
      }
    } else {
      taker.markFilled();
    }
    return new SemanticOutcome(events, deriveBook());
  }

  private SemanticOutcome cancel(ReferenceCommand.Cancel command) {
    SemanticEvent.Rejected invalid = validate(command);
    if (invalid != null) {
      return singleton(invalid);
    }

    ReferenceOrder order = find(command.orderId());
    if (order == null) {
      return singleton(new SemanticEvent.CancelRejected(command.orderId(), "ORDER_NOT_FOUND"));
    }
    if (order.lifecycle == Lifecycle.FILLED) {
      return singleton(new SemanticEvent.CancelRejected(command.orderId(), "ORDER_ALREADY_FILLED"));
    }
    if (order.lifecycle == Lifecycle.CANCELED) {
      return singleton(
          new SemanticEvent.CancelRejected(command.orderId(), "ORDER_ALREADY_CANCELED"));
    }
    if (order.lifecycle != Lifecycle.RESTING) {
      throw new IllegalStateException("cancel observed a transient reference lifecycle");
    }

    BigInteger canceled = order.remaining;
    SemanticEvent event =
        new SemanticEvent.Canceled(
            order.sequence, order.orderId, order.side, order.price, canceled);
    order.cancel(canceled);
    return singleton(event);
  }

  private SemanticOutcome singleton(SemanticEvent event) {
    return new SemanticOutcome(List.of(event), deriveBook());
  }

  private ReferenceOrder selectMaker(ReferenceOrder taker) {
    ReferenceOrder best = null;
    for (ReferenceOrder candidate : orders) {
      if (candidate.lifecycle != Lifecycle.RESTING
          || candidate.side.equals(taker.side)
          || !crosses(taker, candidate)) {
        continue;
      }
      if (best == null || isHigherPriority(candidate, best, taker.side)) {
        best = candidate;
      }
    }
    return best;
  }

  private boolean hasCrossingMaker(ReferenceCommand.Place taker) {
    for (ReferenceOrder candidate : orders) {
      if (candidate.lifecycle == Lifecycle.RESTING
          && !candidate.side.equals(taker.side())
          && crosses(taker.side(), taker.priceTicks(), candidate)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Read-only per-order demand deduction. It never sums book quantity, so cumulative depth cannot
   * overflow a bounded production representation.
   */
  private boolean isFullyExecutable(ReferenceCommand.Place taker) {
    BigInteger required = taker.quantityLots();
    for (ReferenceOrder candidate : orders) {
      if (candidate.lifecycle != Lifecycle.RESTING
          || candidate.side.equals(taker.side())
          || !crosses(taker.side(), taker.priceTicks(), candidate)) {
        continue;
      }
      if (candidate.remaining.compareTo(required) >= 0) {
        return true;
      }
      required = required.subtract(candidate.remaining);
    }
    return false;
  }

  private static boolean crosses(ReferenceOrder taker, ReferenceOrder maker) {
    return crosses(taker.side, taker.price, maker);
  }

  private static boolean crosses(String takerSide, BigInteger takerPrice, ReferenceOrder maker) {
    return BUY.equals(takerSide)
        ? takerPrice.compareTo(maker.price) >= 0
        : takerPrice.compareTo(maker.price) <= 0;
  }

  private static boolean isHigherPriority(
      ReferenceOrder candidate, ReferenceOrder incumbent, String takerSide) {
    int priceComparison = candidate.price.compareTo(incumbent.price);
    if (priceComparison != 0) {
      return BUY.equals(takerSide) ? priceComparison < 0 : priceComparison > 0;
    }
    return candidate.sequence.compareTo(incumbent.sequence) < 0;
  }

  private ReferenceOrder find(BigInteger orderId) {
    for (ReferenceOrder order : orders) {
      if (order.orderId.equals(orderId)) {
        return order;
      }
    }
    return null;
  }

  private SemanticBook deriveBook() {
    return new SemanticBook(deriveSide(BUY), deriveSide(SELL));
  }

  private List<SemanticBook.PriceLevel> deriveSide(String side) {
    List<ReferenceOrder> active = new ArrayList<>();
    for (ReferenceOrder order : orders) {
      if (order.lifecycle == Lifecycle.RESTING && side.equals(order.side)) {
        active.add(order);
      }
    }

    Comparator<ReferenceOrder> comparator = Comparator.comparing(order -> order.price);
    if (BUY.equals(side)) {
      comparator = comparator.reversed();
    }
    comparator = comparator.thenComparing(order -> order.sequence);
    active.sort(comparator);

    List<SemanticBook.PriceLevel> levels = new ArrayList<>();
    BigInteger currentPrice = null;
    List<SemanticBook.RestingOrder> currentOrders = new ArrayList<>();
    for (ReferenceOrder order : active) {
      if (currentPrice != null && !currentPrice.equals(order.price)) {
        levels.add(new SemanticBook.PriceLevel(side, currentPrice, currentOrders));
        currentOrders = new ArrayList<>();
      }
      currentPrice = order.price;
      currentOrders.add(
          new SemanticBook.RestingOrder(order.sequence, order.orderId, order.remaining));
    }
    if (currentPrice != null) {
      levels.add(new SemanticBook.PriceLevel(side, currentPrice, currentOrders));
    }
    return List.copyOf(levels);
  }

  private static SemanticEvent.Rejected validate(ReferenceCommand.Place command) {
    if (!INSTRUMENT.equals(command.instrumentId())) {
      return new SemanticEvent.Rejected("UNKNOWN_INSTRUMENT", "instrumentId");
    }
    if (!isPositiveLong(command.orderId())) {
      return new SemanticEvent.Rejected("INVALID_ORDER_ID", "orderId");
    }
    if (!BUY.equals(command.side()) && !SELL.equals(command.side())) {
      return new SemanticEvent.Rejected("INVALID_SIDE", "side");
    }
    if (!isPositiveLong(command.priceTicks())) {
      return new SemanticEvent.Rejected("INVALID_PRICE", "priceTicks");
    }
    if (!isPositiveLong(command.quantityLots())) {
      return new SemanticEvent.Rejected("INVALID_QUANTITY", "quantityLots");
    }
    if (!isExecutionPolicy(command.executionPolicy())) {
      return new SemanticEvent.Rejected("INVALID_EXECUTION_POLICY", "executionPolicy");
    }
    return null;
  }

  private static SemanticEvent.Rejected validate(ReferenceCommand.Cancel command) {
    if (!INSTRUMENT.equals(command.instrumentId())) {
      return new SemanticEvent.Rejected("UNKNOWN_INSTRUMENT", "instrumentId");
    }
    if (!isPositiveLong(command.orderId())) {
      return new SemanticEvent.Rejected("INVALID_ORDER_ID", "orderId");
    }
    return null;
  }

  private static boolean isPositiveLong(BigInteger value) {
    return value.signum() > 0 && value.compareTo(MAXIMUM) <= 0;
  }

  private static boolean isExecutionPolicy(String value) {
    return GTC.equals(value) || IOC.equals(value) || FOK.equals(value) || POST_ONLY.equals(value);
  }

  private void assertConsistentState() {
    if (nextAcceptanceSequence.signum() <= 0) {
      throw new IllegalStateException("next reference acceptance sequence is not positive");
    }
    for (int left = 0; left < orders.size(); left++) {
      ReferenceOrder order = orders.get(left);
      order.assertQuantityPartition();
      if (order.sequence.signum() <= 0 || order.sequence.compareTo(nextAcceptanceSequence) >= 0) {
        throw new IllegalStateException("reference acceptance sequence is outside history");
      }
      for (int right = left + 1; right < orders.size(); right++) {
        ReferenceOrder other = orders.get(right);
        if (order.orderId.equals(other.orderId)) {
          throw new IllegalStateException("reference order identity is not unique");
        }
        if (order.sequence.equals(other.sequence)) {
          throw new IllegalStateException("reference acceptance sequence is not unique");
        }
      }
    }

    ReferenceOrder bestBid = bestResting(BUY);
    ReferenceOrder bestAsk = bestResting(SELL);
    if (bestBid != null && bestAsk != null && bestBid.price.compareTo(bestAsk.price) >= 0) {
      throw new IllegalStateException("reference book is crossed");
    }
  }

  private ReferenceOrder bestResting(String side) {
    ReferenceOrder best = null;
    for (ReferenceOrder order : orders) {
      if (order.lifecycle != Lifecycle.RESTING || !side.equals(order.side)) {
        continue;
      }
      if (best == null
          || (BUY.equals(side)
              ? order.price.compareTo(best.price) > 0
              : order.price.compareTo(best.price) < 0)) {
        best = order;
      }
    }
    return best;
  }

  private enum Lifecycle {
    ACCEPTED,
    RESTING,
    FILLED,
    CANCELED
  }

  private static final class ReferenceOrder {
    private final BigInteger sequence;
    private final BigInteger orderId;
    private final String side;
    private final BigInteger price;
    private final BigInteger original;

    private BigInteger remaining;
    private BigInteger filled = BigInteger.ZERO;
    private BigInteger canceled = BigInteger.ZERO;
    private Lifecycle lifecycle = Lifecycle.ACCEPTED;

    private ReferenceOrder(
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
      if ((lifecycle != Lifecycle.ACCEPTED && lifecycle != Lifecycle.RESTING)
          || quantity.signum() <= 0
          || quantity.compareTo(remaining) > 0) {
        throw new IllegalStateException("invalid reference fill");
      }
      remaining = remaining.subtract(quantity);
      filled = filled.add(quantity);
      if (remaining.signum() == 0) {
        lifecycle = Lifecycle.FILLED;
      }
    }

    private void markResting() {
      if (lifecycle != Lifecycle.ACCEPTED || remaining.signum() <= 0) {
        throw new IllegalStateException("invalid reference rest transition");
      }
      lifecycle = Lifecycle.RESTING;
    }

    private void markFilled() {
      if (lifecycle != Lifecycle.FILLED || remaining.signum() != 0) {
        throw new IllegalStateException("invalid reference filled transition");
      }
    }

    private void cancel(BigInteger quantity) {
      if (lifecycle != Lifecycle.RESTING || quantity.signum() <= 0 || !quantity.equals(remaining)) {
        throw new IllegalStateException("invalid reference cancel transition");
      }
      remaining = BigInteger.ZERO;
      canceled = canceled.add(quantity);
      lifecycle = Lifecycle.CANCELED;
    }

    private void cancelAcceptedRemainder(BigInteger quantity) {
      if (lifecycle != Lifecycle.ACCEPTED
          || quantity.signum() <= 0
          || !quantity.equals(remaining)) {
        throw new IllegalStateException("invalid reference accepted-remainder cancellation");
      }
      remaining = BigInteger.ZERO;
      canceled = canceled.add(quantity);
      lifecycle = Lifecycle.CANCELED;
    }

    private void assertQuantityPartition() {
      if (!original.equals(filled.add(remaining).add(canceled))) {
        throw new IllegalStateException("reference order quantity partition is invalid");
      }
      boolean lifecycleValid =
          switch (lifecycle) {
            case ACCEPTED -> false;
            case RESTING -> remaining.signum() > 0 && canceled.signum() == 0;
            case FILLED -> remaining.signum() == 0 && canceled.signum() == 0;
            case CANCELED -> remaining.signum() == 0 && canceled.signum() > 0;
          };
      if (!lifecycleValid) {
        throw new IllegalStateException("reference order lifecycle is inconsistent");
      }
    }
  }
}
