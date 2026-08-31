package io.github.lchareln.cex.matching;

import java.util.Objects;

/** M04 place request guarded by the caller's exact expected active rule-set identity. */
public record GovernedPlaceLimitOrderRequest(
    PlaceLimitOrderRequest orderRequest, RuleSetIdentity expectedActive) {
  public GovernedPlaceLimitOrderRequest {
    Objects.requireNonNull(orderRequest, "orderRequest");
    Objects.requireNonNull(expectedActive, "expectedActive");
  }
}
