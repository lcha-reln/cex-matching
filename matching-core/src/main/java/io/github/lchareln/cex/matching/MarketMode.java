package io.github.lchareln.cex.matching;

/** Replicated-ready operating mode for the single instrument. */
public enum MarketMode {
  OPEN,
  CANCEL_ONLY,
  HALTED;

  /** Returns whether the contract permits this mode to transition directly to the target. */
  public boolean canTransitionTo(MarketMode target) {
    return switch (this) {
      case OPEN -> target == CANCEL_ONLY || target == HALTED;
      case CANCEL_ONLY -> target == OPEN || target == HALTED;
      case HALTED -> target == CANCEL_ONLY;
    };
  }
}
