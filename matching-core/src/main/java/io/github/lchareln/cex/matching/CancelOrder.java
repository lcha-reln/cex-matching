package io.github.lchareln.cex.matching;

import java.util.Objects;

/** A normalized M02 cancellation addressed only by instrument and accepted order identity. */
public record CancelOrder(String instrumentId, OrderId orderId) {
  public CancelOrder {
    if (!PlaceLimitOrderValidator.INSTRUMENT_ID.equals(instrumentId)) {
      throw new IllegalArgumentException("instrumentId must be BTC-USDT");
    }
    Objects.requireNonNull(orderId, "orderId");
  }
}
