package io.github.lchareln.cex.matching;

import java.util.Objects;

/** M07 STP request guarded by the caller's exact expected active rule-set identity. */
public record GovernedStpPlaceLimitOrderRequest(
    StpPlaceLimitOrderRequest request, RuleSetIdentity expectedActive) {
  public GovernedStpPlaceLimitOrderRequest {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(expectedActive, "expectedActive");
  }
}
