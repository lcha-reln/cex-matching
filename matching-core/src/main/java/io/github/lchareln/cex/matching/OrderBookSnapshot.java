package io.github.lchareln.cex.matching;

import java.util.List;
import java.util.Objects;

/** Immutable full-depth M01 book view in execution-priority order. */
public record OrderBookSnapshot(List<PriceLevel> bids, List<PriceLevel> asks) {
  public OrderBookSnapshot {
    bids = List.copyOf(bids);
    asks = List.copyOf(asks);
    validateLevels(bids, Side.BUY, true);
    validateLevels(asks, Side.SELL, false);
    if (!bids.isEmpty()
        && !asks.isEmpty()
        && bids.getFirst().priceTicks().value() >= asks.getFirst().priceTicks().value()) {
      throw new IllegalArgumentException("snapshot must not contain a crossed book");
    }
  }

  private static void validateLevels(List<PriceLevel> levels, Side side, boolean descending) {
    long previousPrice = 0;
    boolean first = true;
    for (PriceLevel level : levels) {
      Objects.requireNonNull(level, "level");
      if (level.side() != side) {
        throw new IllegalArgumentException("price level is on the wrong side");
      }
      long price = level.priceTicks().value();
      if (!first
          && ((descending && price >= previousPrice) || (!descending && price <= previousPrice))) {
        throw new IllegalArgumentException("price levels are not in strict book order");
      }
      previousPrice = price;
      first = false;
    }
  }

  /** One non-empty price level. */
  public record PriceLevel(Side side, PriceTicks priceTicks, List<RestingOrderView> orders) {
    public PriceLevel {
      Objects.requireNonNull(side, "side");
      Objects.requireNonNull(priceTicks, "priceTicks");
      orders = List.copyOf(orders);
      if (orders.isEmpty()) {
        throw new IllegalArgumentException("price level must not be empty");
      }
      long previousSequence = 0;
      for (RestingOrderView order : orders) {
        Objects.requireNonNull(order, "order");
        if (order.sequence().value() <= previousSequence) {
          throw new IllegalArgumentException("price level is not FIFO by acceptance sequence");
        }
        previousSequence = order.sequence().value();
      }
    }
  }

  /** The active remainder of one accepted order. */
  public record RestingOrderView(
      AcceptanceSequence sequence,
      OrderId orderId,
      QuantityLots remainingQuantityLots,
      RuleSetIdentity admissionRuleSet,
      long participantGroupId,
      SelfTradePreventionPolicy selfTradePreventionPolicy) {
    public RestingOrderView {
      Objects.requireNonNull(sequence, "sequence");
      Objects.requireNonNull(orderId, "orderId");
      Objects.requireNonNull(remainingQuantityLots, "remainingQuantityLots");
      Objects.requireNonNull(admissionRuleSet, "admissionRuleSet");
      new SelfTradePreventionInstruction(participantGroupId, selfTradePreventionPolicy);
    }

    public RestingOrderView(
        AcceptanceSequence sequence,
        OrderId orderId,
        QuantityLots remainingQuantityLots,
        RuleSetIdentity admissionRuleSet) {
      this(
          sequence,
          orderId,
          remainingQuantityLots,
          admissionRuleSet,
          0,
          SelfTradePreventionPolicy.NONE);
    }

    public RestingOrderView(
        AcceptanceSequence sequence, OrderId orderId, QuantityLots remainingQuantityLots) {
      this(sequence, orderId, remainingQuantityLots, MarketRuleSetArtifact.bootstrapIdentity());
    }
  }
}
