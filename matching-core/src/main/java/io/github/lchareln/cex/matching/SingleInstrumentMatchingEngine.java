package io.github.lchareln.cex.matching;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

/** Single-writer, in-memory price-time matcher for the fixed M01 instrument. */
public final class SingleInstrumentMatchingEngine {
  private final PlaceLimitOrderValidator validator = new PlaceLimitOrderValidator();
  private final NavigableMap<Long, ArrayDeque<RestingOrder>> bids =
      new TreeMap<>(Collections.reverseOrder());
  private final NavigableMap<Long, ArrayDeque<RestingOrder>> asks = new TreeMap<>();

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

  /** Applies one command. The caller must serialize calls to this method. */
  public ExecutionBatch place(PlaceLimitOrderInput input) {
    Objects.requireNonNull(input, "input");
    ValidationResult validation = validator.validate(input);
    if (validation instanceof ValidationResult.Invalid invalid) {
      return new ExecutionBatch(List.of(new MatchingEvent.Rejected(invalid.code())), snapshot());
    }

    long sequenceValue = nextAcceptanceSequence;
    final long followingSequence;
    try {
      followingSequence = Math.incrementExact(sequenceValue);
    } catch (ArithmeticException exception) {
      throw new IllegalStateException(
          "acceptance sequence exhausted before state mutation", exception);
    }

    PlaceLimitOrder command = validator.normalize(input);
    AcceptanceSequence sequence = new AcceptanceSequence(sequenceValue);
    List<MatchingEvent> events = new ArrayList<>();
    events.add(
        new MatchingEvent.Accepted(
            sequence,
            command.orderId(),
            command.side(),
            command.priceTicks(),
            command.quantityLots()));

    long remaining = command.quantityLots().value();
    if (command.side() == Side.BUY) {
      remaining = match(command, sequence, remaining, asks, events, true);
    } else {
      remaining = match(command, sequence, remaining, bids, events, false);
    }

    if (remaining > 0) {
      NavigableMap<Long, ArrayDeque<RestingOrder>> ownSide =
          command.side() == Side.BUY ? bids : asks;
      ownSide
          .computeIfAbsent(command.priceTicks().value(), ignored -> new ArrayDeque<>())
          .addLast(new RestingOrder(sequence, command.orderId(), remaining));
      events.add(
          new MatchingEvent.Rested(
              sequence,
              command.orderId(),
              command.side(),
              command.priceTicks(),
              new QuantityLots(remaining)));
    }

    nextAcceptanceSequence = followingSequence;
    return new ExecutionBatch(events, snapshot());
  }

  /** Returns a detached immutable full-depth snapshot. */
  public OrderBookSnapshot snapshot() {
    return new OrderBookSnapshot(snapshotSide(bids, Side.BUY), snapshotSide(asks, Side.SELL));
  }

  private static long match(
      PlaceLimitOrder taker,
      AcceptanceSequence takerSequence,
      long initialRemaining,
      NavigableMap<Long, ArrayDeque<RestingOrder>> oppositeSide,
      List<MatchingEvent> events,
      boolean buying) {
    long remaining = initialRemaining;
    while (remaining > 0 && !oppositeSide.isEmpty()) {
      long makerPrice = oppositeSide.firstKey();
      boolean crosses =
          buying
              ? taker.priceTicks().value() >= makerPrice
              : taker.priceTicks().value() <= makerPrice;
      if (!crosses) {
        break;
      }

      ArrayDeque<RestingOrder> level = oppositeSide.firstEntry().getValue();
      RestingOrder maker = level.getFirst();
      long traded = Math.min(remaining, maker.remainingQuantityLots);
      remaining -= traded;
      maker.remainingQuantityLots -= traded;
      events.add(
          new MatchingEvent.Trade(
              maker.sequence,
              maker.orderId,
              takerSequence,
              taker.orderId(),
              new PriceTicks(makerPrice),
              new QuantityLots(traded)));

      if (maker.remainingQuantityLots == 0) {
        level.removeFirst();
        if (level.isEmpty()) {
          oppositeSide.remove(makerPrice);
        }
      }
    }
    return remaining;
  }

  private static List<OrderBookSnapshot.PriceLevel> snapshotSide(
      NavigableMap<Long, ArrayDeque<RestingOrder>> side, Side sideName) {
    List<OrderBookSnapshot.PriceLevel> levels = new ArrayList<>(side.size());
    side.forEach(
        (price, orders) -> {
          List<OrderBookSnapshot.RestingOrderView> views = new ArrayList<>(orders.size());
          for (RestingOrder order : orders) {
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

  private static final class RestingOrder {
    private final AcceptanceSequence sequence;
    private final OrderId orderId;
    private long remainingQuantityLots;

    private RestingOrder(AcceptanceSequence sequence, OrderId orderId, long remainingQuantityLots) {
      this.sequence = sequence;
      this.orderId = orderId;
      this.remainingQuantityLots = remainingQuantityLots;
    }
  }
}
