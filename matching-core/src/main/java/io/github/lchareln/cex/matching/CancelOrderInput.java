package io.github.lchareln.cex.matching;

import java.math.BigInteger;
import java.util.Objects;

/** Schema-valid, but not yet business-valid, input for one M02 cancellation. */
public record CancelOrderInput(String instrumentId, BigInteger orderId) {
  public CancelOrderInput {
    Objects.requireNonNull(instrumentId, "instrumentId");
    Objects.requireNonNull(orderId, "orderId");
  }
}
