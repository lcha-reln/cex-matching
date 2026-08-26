package io.github.lchareln.cex.matching;

import java.util.Objects;

/** A normalized M00 command. It is not an accepted, rested, or traded order. */
public record PlaceLimitOrder(
    String instrumentId,
    OrderId orderId,
    Side side,
    PriceTicks priceTicks,
    QuantityLots quantityLots) {

  public PlaceLimitOrder {
    if (!PlaceLimitOrderValidator.INSTRUMENT_ID.equals(instrumentId)) {
      throw new IllegalArgumentException("instrumentId must be BTC-USDT");
    }
    Objects.requireNonNull(orderId, "orderId");
    Objects.requireNonNull(side, "side");
    Objects.requireNonNull(priceTicks, "priceTicks");
    Objects.requireNonNull(quantityLots, "quantityLots");
  }
}
