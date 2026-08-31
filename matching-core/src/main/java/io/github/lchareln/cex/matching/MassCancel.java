package io.github.lchareln.cex.matching;

import java.util.Objects;

/** Explicit operator request to terminate every resting order while the market is halted. */
public record MassCancel(
    ApplicationSequence expectedApplicationSequence,
    MarketMode expectedMode,
    OperatorId operatorId) {
  public MassCancel {
    Objects.requireNonNull(expectedApplicationSequence, "expectedApplicationSequence");
    Objects.requireNonNull(expectedMode, "expectedMode");
    Objects.requireNonNull(operatorId, "operatorId");
  }
}
