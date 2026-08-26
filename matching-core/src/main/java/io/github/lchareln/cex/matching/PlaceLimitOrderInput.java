package io.github.lchareln.cex.matching;

import java.math.BigInteger;
import java.util.Objects;

/** Schema-valid, but not yet business-valid, input for one M00 limit order. */
public record PlaceLimitOrderInput(
    String instrumentId,
    BigInteger orderId,
    String side,
    BigInteger priceTicks,
    BigInteger quantityLots) {

  public PlaceLimitOrderInput {
    Objects.requireNonNull(instrumentId, "instrumentId");
    Objects.requireNonNull(orderId, "orderId");
    Objects.requireNonNull(side, "side");
    Objects.requireNonNull(priceTicks, "priceTicks");
    Objects.requireNonNull(quantityLots, "quantityLots");
  }
}
