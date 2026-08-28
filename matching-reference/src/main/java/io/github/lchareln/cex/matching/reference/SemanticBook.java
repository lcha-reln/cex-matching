package io.github.lchareln.cex.matching.reference;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

/** Detached full-depth book represented only by neutral reference-owned records. */
public record SemanticBook(List<PriceLevel> bids, List<PriceLevel> asks) {
  public SemanticBook {
    bids = List.copyOf(bids);
    asks = List.copyOf(asks);
  }

  public static SemanticBook empty() {
    return new SemanticBook(List.of(), List.of());
  }

  /** One non-empty price level in execution-priority order. */
  public record PriceLevel(String side, BigInteger priceTicks, List<RestingOrder> orders) {
    public PriceLevel {
      Objects.requireNonNull(side, "side");
      Objects.requireNonNull(priceTicks, "priceTicks");
      orders = List.copyOf(orders);
    }
  }

  /** The active remainder of one accepted order. */
  public record RestingOrder(
      BigInteger sequence, BigInteger orderId, BigInteger remainingQuantityLots) {
    public RestingOrder {
      Objects.requireNonNull(sequence, "sequence");
      Objects.requireNonNull(orderId, "orderId");
      Objects.requireNonNull(remainingQuantityLots, "remainingQuantityLots");
    }
  }
}
