package io.github.lchareln.cex.matching;

import java.util.Objects;

/** Raw M07 place request with an opaque participant group and exact policy token. */
public record StpPlaceLimitOrderRequest(
    PlaceLimitOrderRequest orderRequest, long participantGroupId, String stpPolicy) {
  public StpPlaceLimitOrderRequest {
    Objects.requireNonNull(orderRequest, "orderRequest");
    Objects.requireNonNull(stpPolicy, "stpPolicy");
  }
}
