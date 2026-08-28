package io.github.lchareln.cex.matching;

import java.util.Objects;

/** Schema-valid M04 request that composes the frozen five-field input with a raw policy. */
public record PlaceLimitOrderRequest(PlaceLimitOrderInput orderInput, String executionPolicy) {

  public PlaceLimitOrderRequest {
    Objects.requireNonNull(orderInput, "orderInput");
    Objects.requireNonNull(executionPolicy, "executionPolicy");
  }

  /** Preserves the M00-M03 public place surface as an explicit GTC request. */
  public PlaceLimitOrderRequest(PlaceLimitOrderInput orderInput) {
    this(orderInput, ExecutionPolicy.GTC.name());
  }
}
