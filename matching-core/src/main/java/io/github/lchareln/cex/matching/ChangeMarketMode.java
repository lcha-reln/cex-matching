package io.github.lchareln.cex.matching;

import java.util.Objects;

/** Explicit pre-authorized request to change the operating mode at one application boundary. */
public record ChangeMarketMode(
    ApplicationSequence expectedApplicationSequence,
    MarketMode expectedMode,
    MarketMode targetMode,
    OperatorId operatorId) {
  public ChangeMarketMode {
    Objects.requireNonNull(expectedApplicationSequence, "expectedApplicationSequence");
    Objects.requireNonNull(expectedMode, "expectedMode");
    Objects.requireNonNull(targetMode, "targetMode");
    Objects.requireNonNull(operatorId, "operatorId");
  }
}
