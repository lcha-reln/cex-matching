package io.github.lchareln.cex.matching;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Single-writer, in-memory price-time matcher with an addressable M02 order lifecycle. */
public final class SingleInstrumentMatchingEngine {
  private final PlaceLimitOrderValidator placeValidator = new PlaceLimitOrderValidator();
  private final CancelOrderValidator cancelValidator = new CancelOrderValidator();
  private final NavigableMap<Long, PriceLevelState> bids =
      new TreeMap<>(Collections.reverseOrder());
  private final NavigableMap<Long, PriceLevelState> asks = new TreeMap<>();
  private final Map<OrderId, OrderState> ordersById = new HashMap<>();

  private long nextAcceptanceSequence;

  public SingleInstrumentMatchingEngine() {
    this(1);
  }

  SingleInstrumentMatchingEngine(long nextAcceptanceSequence) {
    if (nextAcceptanceSequence <= 0) {
      throw new IllegalArgumentException("next acceptance sequence must be positive");
    }
    this.nextAcceptanceSequence = nextAcceptanceSequence;
  }

  /** Applies one GTC limit command. The caller must serialize calls to this method. */
  public ExecutionBatch place(PlaceLimitOrderInput input) {
    Objects.requireNonNull(input, "input");
    assertConsistentState();
    ValidationResult validation = placeValidator.validate(input);
    if (validation instanceof ValidationResult.Invalid invalid) {
      return singleton(new MatchingEvent.Rejected(invalid.code()));
    }

    PlaceLimitOrder command = placeValidator.normalize(input);
    if (ordersById.containsKey(command.orderId())) {
      return singleton(
          new MatchingEvent.PlaceRejected(
              command.orderId(), PlaceRejectionCode.DUPLICATE_ORDER_ID));
    }

    long sequenceValue = nextAcceptanceSequence;
    final long followingSequence;
    try {
      followingSequence = Math.incrementExact(sequenceValue);
    } catch (ArithmeticException exception) {
      throw new IllegalStateException(
          "acceptance sequence exhausted before state mutation", exception);
    }

    AcceptanceSequence sequence = new AcceptanceSequence(sequenceValue);
    OrderState taker = new OrderState(sequence, command);
    MatchingEvent.Accepted accepted =
        new MatchingEvent.Accepted(
            sequence,
            command.orderId(),
            command.side(),
            command.priceTicks(),
            command.quantityLots());
    if (ordersById.putIfAbsent(command.orderId(), taker) != null) {
      throw new IllegalStateException(
          "duplicate order identity appeared during single-writer apply");
    }

    List<MatchingEvent> events = new ArrayList<>();
    events.add(accepted);
    if (command.side() == Side.BUY) {
      match(taker, asks, events, true);
    } else {
      match(taker, bids, events, false);
    }

    if (taker.remainingQuantityLots > 0) {
      rest(taker);
      events.add(
          new MatchingEvent.Rested(
              taker.sequence,
              taker.orderId,
              taker.side,
              taker.priceTicks,
              new QuantityLots(taker.remainingQuantityLots)));
    } else {
      taker.markFilled();
    }

    nextAcceptanceSequence = followingSequence;
    assertConsistentState();
    return new ExecutionBatch(events, detachedSnapshot());
  }

  /** Cancels the positive active remainder addressed by instrument and order identity. */
  public ExecutionBatch cancel(CancelOrderInput input) {
    Objects.requireNonNull(input, "input");
    assertConsistentState();
    ValidationResult validation = cancelValidator.validate(input);
    if (validation instanceof ValidationResult.Invalid invalid) {
      return singleton(new MatchingEvent.Rejected(invalid.code()));
    }

    CancelOrder command = cancelValidator.normalize(input);
    OrderState order = ordersById.get(command.orderId());
    if (order == null) {
      return singleton(
          new MatchingEvent.CancelRejected(command.orderId(), CancelRejectionCode.ORDER_NOT_FOUND));
    }
    if (order.lifecycle == Lifecycle.FILLED) {
      return singleton(
          new MatchingEvent.CancelRejected(
              command.orderId(), CancelRejectionCode.ORDER_ALREADY_FILLED));
    }
    if (order.lifecycle == Lifecycle.CANCELED) {
      return singleton(
          new MatchingEvent.CancelRejected(
              command.orderId(), CancelRejectionCode.ORDER_ALREADY_CANCELED));
    }
    if (order.lifecycle != Lifecycle.RESTING) {
      throw new IllegalStateException("cancel observed an order in a transient lifecycle state");
    }

    NavigableMap<Long, PriceLevelState> side = order.side == Side.BUY ? bids : asks;
    PriceLevelState level = side.get(order.priceTicks.value());
    if (level == null || level.order(order.orderId) != order) {
      throw new IllegalStateException("active order index and price level disagree");
    }

    MatchingEvent.Canceled canceled =
        new MatchingEvent.Canceled(
            order.sequence,
            order.orderId,
            order.side,
            order.priceTicks,
            new QuantityLots(order.remainingQuantityLots));
    if (!level.remove(order)) {
      throw new IllegalStateException("active order disappeared during single-writer cancel");
    }
    if (level.isEmpty() && !side.remove(order.priceTicks.value(), level)) {
      throw new IllegalStateException("empty price level disappeared during single-writer cancel");
    }
    order.markCanceled();

    assertConsistentState();
    return new ExecutionBatch(List.of(canceled), detachedSnapshot());
  }

  /** Returns a detached immutable full-depth snapshot. */
  public OrderBookSnapshot snapshot() {
    assertConsistentState();
    return detachedSnapshot();
  }

  /** Package-local correctness hook; it exposes no order lifecycle data. */
  void assertConsistentState() {
    if (nextAcceptanceSequence <= 0) {
      throw new IllegalStateException("next acceptance sequence is not positive");
    }

    Set<OrderId> restingIds = new HashSet<>();
    Set<Long> acceptanceSequences = new HashSet<>();
    verifySide(bids, Side.BUY, restingIds);
    verifySide(asks, Side.SELL, restingIds);
    if (!bids.isEmpty() && !asks.isEmpty() && bids.firstKey() >= asks.firstKey()) {
      throw new IllegalStateException("active book is crossed");
    }

    for (Map.Entry<OrderId, OrderState> entry : ordersById.entrySet()) {
      OrderState order = entry.getValue();
      if (!entry.getKey().equals(order.orderId)) {
        throw new IllegalStateException("order registry key and value identity disagree");
      }
      if (!acceptanceSequences.add(order.sequence.value())) {
        throw new IllegalStateException("acceptance sequence is not unique");
      }
      if (order.sequence.value() >= nextAcceptanceSequence) {
        throw new IllegalStateException("accepted order is not behind the next sequence");
      }
      order.assertQuantityPartition();
      boolean inBook = restingIds.contains(order.orderId);
      if ((order.lifecycle == Lifecycle.RESTING) != inBook) {
        throw new IllegalStateException("order lifecycle and book membership disagree");
      }
      if (order.lifecycle == Lifecycle.ACCEPTED) {
        throw new IllegalStateException("transient accepted state escaped a command boundary");
      }
    }
  }

  private ExecutionBatch singleton(MatchingEvent event) {
    assertConsistentState();
    return new ExecutionBatch(List.of(event), detachedSnapshot());
  }

  private void rest(OrderState order) {
    if (order.lifecycle != Lifecycle.ACCEPTED || order.remainingQuantityLots <= 0) {
      throw new IllegalStateException("only a positive accepted remainder can rest");
    }
    NavigableMap<Long, PriceLevelState> side = order.side == Side.BUY ? bids : asks;
    PriceLevelState level =
        side.computeIfAbsent(order.priceTicks.value(), ignored -> new PriceLevelState());
    level.add(order);
    order.markResting();
  }

  private void match(
      OrderState taker,
      NavigableMap<Long, PriceLevelState> oppositeSide,
      List<MatchingEvent> events,
      boolean buying) {
    while (taker.remainingQuantityLots > 0 && !oppositeSide.isEmpty()) {
      long makerPrice = oppositeSide.firstKey();
      boolean crosses =
          buying ? taker.priceTicks.value() >= makerPrice : taker.priceTicks.value() <= makerPrice;
      if (!crosses) {
        break;
      }

      PriceLevelState level = oppositeSide.firstEntry().getValue();
      OrderState maker = level.first();
      if (maker.lifecycle != Lifecycle.RESTING
          || maker.priceTicks.value() != makerPrice
          || ordersById.get(maker.orderId) != maker) {
        throw new IllegalStateException("maker index and price level disagree before fill");
      }
      long traded = Math.min(taker.remainingQuantityLots, maker.remainingQuantityLots);
      maker.fill(traded);
      taker.fill(traded);
      events.add(
          new MatchingEvent.Trade(
              maker.sequence,
              maker.orderId,
              taker.sequence,
              taker.orderId,
              maker.priceTicks,
              new QuantityLots(traded)));

      if (maker.remainingQuantityLots == 0) {
        if (!level.remove(maker)) {
          throw new IllegalStateException("filled maker disappeared during single-writer apply");
        }
        maker.markFilled();
        if (level.isEmpty() && !oppositeSide.remove(makerPrice, level)) {
          throw new IllegalStateException(
              "empty maker level disappeared during single-writer apply");
        }
      }
    }
  }

  private void verifySide(
      NavigableMap<Long, PriceLevelState> side, Side expectedSide, Set<OrderId> restingIds) {
    for (Map.Entry<Long, PriceLevelState> levelEntry : side.entrySet()) {
      if (levelEntry.getValue().isEmpty()) {
        throw new IllegalStateException("active book contains an empty price level");
      }
      long previousSequence = 0;
      for (Map.Entry<OrderId, OrderState> orderEntry : levelEntry.getValue().entries()) {
        OrderState order = orderEntry.getValue();
        if (!orderEntry.getKey().equals(order.orderId)
            || ordersById.get(order.orderId) != order
            || order.side != expectedSide
            || order.priceTicks.value() != levelEntry.getKey()
            || order.lifecycle != Lifecycle.RESTING
            || order.remainingQuantityLots <= 0) {
          throw new IllegalStateException("active order index and price level disagree");
        }
        if (order.sequence.value() <= previousSequence) {
          throw new IllegalStateException("price level is not FIFO by acceptance sequence");
        }
        if (!restingIds.add(order.orderId)) {
          throw new IllegalStateException("active order appears more than once in the book");
        }
        previousSequence = order.sequence.value();
      }
    }
  }

  private OrderBookSnapshot detachedSnapshot() {
    return new OrderBookSnapshot(snapshotSide(bids, Side.BUY), snapshotSide(asks, Side.SELL));
  }

  private static List<OrderBookSnapshot.PriceLevel> snapshotSide(
      NavigableMap<Long, PriceLevelState> side, Side sideName) {
    List<OrderBookSnapshot.PriceLevel> levels = new ArrayList<>(side.size());
    side.forEach(
        (price, level) -> {
          List<OrderBookSnapshot.RestingOrderView> views = new ArrayList<>(level.size());
          for (OrderState order : level.values()) {
            views.add(
                new OrderBookSnapshot.RestingOrderView(
                    order.sequence, order.orderId, new QuantityLots(order.remainingQuantityLots)));
          }
          levels.add(
              new OrderBookSnapshot.PriceLevel(
                  sideName, new PriceTicks(price), List.copyOf(views)));
        });
    return List.copyOf(levels);
  }

  private enum Lifecycle {
    ACCEPTED,
    RESTING,
    FILLED,
    CANCELED
  }

  private static final class PriceLevelState {
    private final LinkedHashMap<OrderId, OrderState> orders = new LinkedHashMap<>();

    private void add(OrderState order) {
      if (orders.putIfAbsent(order.orderId, order) != null) {
        throw new IllegalStateException("price level already contains order identity");
      }
    }

    private OrderState first() {
      if (orders.isEmpty()) {
        throw new IllegalStateException("cannot read an empty price level");
      }
      return orders.values().iterator().next();
    }

    private OrderState order(OrderId orderId) {
      return orders.get(orderId);
    }

    private boolean remove(OrderState order) {
      return orders.remove(order.orderId, order);
    }

    private boolean isEmpty() {
      return orders.isEmpty();
    }

    private int size() {
      return orders.size();
    }

    private Iterable<OrderState> values() {
      return orders.values();
    }

    private Set<Map.Entry<OrderId, OrderState>> entries() {
      return orders.entrySet();
    }
  }

  private static final class OrderState {
    private final AcceptanceSequence sequence;
    private final OrderId orderId;
    private final Side side;
    private final PriceTicks priceTicks;
    private final long originalQuantityLots;

    private long remainingQuantityLots;
    private long filledQuantityLots;
    private long canceledQuantityLots;
    private Lifecycle lifecycle = Lifecycle.ACCEPTED;

    private OrderState(AcceptanceSequence sequence, PlaceLimitOrder command) {
      this.sequence = sequence;
      this.orderId = command.orderId();
      this.side = command.side();
      this.priceTicks = command.priceTicks();
      this.originalQuantityLots = command.quantityLots().value();
      this.remainingQuantityLots = originalQuantityLots;
    }

    private void fill(long quantityLots) {
      if ((lifecycle != Lifecycle.ACCEPTED && lifecycle != Lifecycle.RESTING)
          || quantityLots <= 0
          || quantityLots > remainingQuantityLots) {
        throw new IllegalStateException("invalid fill transition");
      }
      remainingQuantityLots -= quantityLots;
      filledQuantityLots = Math.addExact(filledQuantityLots, quantityLots);
    }

    private void markResting() {
      if (lifecycle != Lifecycle.ACCEPTED || remainingQuantityLots <= 0) {
        throw new IllegalStateException("invalid resting transition");
      }
      lifecycle = Lifecycle.RESTING;
    }

    private void markFilled() {
      if ((lifecycle != Lifecycle.ACCEPTED && lifecycle != Lifecycle.RESTING)
          || remainingQuantityLots != 0
          || canceledQuantityLots != 0) {
        throw new IllegalStateException("invalid filled transition");
      }
      lifecycle = Lifecycle.FILLED;
    }

    private void markCanceled() {
      if (lifecycle != Lifecycle.RESTING || remainingQuantityLots <= 0) {
        throw new IllegalStateException("invalid canceled transition");
      }
      canceledQuantityLots = remainingQuantityLots;
      remainingQuantityLots = 0;
      lifecycle = Lifecycle.CANCELED;
    }

    private void assertQuantityPartition() {
      final long total;
      try {
        total =
            Math.addExact(
                Math.addExact(filledQuantityLots, remainingQuantityLots), canceledQuantityLots);
      } catch (ArithmeticException exception) {
        throw new IllegalStateException("order quantity partition overflowed", exception);
      }
      if (originalQuantityLots <= 0
          || filledQuantityLots < 0
          || remainingQuantityLots < 0
          || canceledQuantityLots < 0
          || total != originalQuantityLots) {
        throw new IllegalStateException("order quantity partition is inconsistent");
      }
      if (lifecycle == Lifecycle.RESTING
          && (remainingQuantityLots <= 0 || canceledQuantityLots != 0)) {
        throw new IllegalStateException("resting order has an invalid quantity partition");
      }
      if (lifecycle == Lifecycle.FILLED
          && (remainingQuantityLots != 0
              || canceledQuantityLots != 0
              || filledQuantityLots != originalQuantityLots)) {
        throw new IllegalStateException("filled order has an invalid quantity partition");
      }
      if (lifecycle == Lifecycle.CANCELED
          && (remainingQuantityLots != 0 || canceledQuantityLots <= 0)) {
        throw new IllegalStateException("canceled order has an invalid quantity partition");
      }
    }
  }
}
