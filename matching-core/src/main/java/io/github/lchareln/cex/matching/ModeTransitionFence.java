package io.github.lchareln.cex.matching;

import java.util.Objects;

/** Deterministic cut proving one successful operating-mode transition. */
public record ModeTransitionFence(
    ApplicationSequence appliedCommandSequence,
    long modeRevision,
    MarketMode previousMode,
    MarketMode activeMode,
    AcceptanceSequence nextAcceptanceSequence) {
  public ModeTransitionFence {
    Objects.requireNonNull(appliedCommandSequence, "appliedCommandSequence");
    Objects.requireNonNull(previousMode, "previousMode");
    Objects.requireNonNull(activeMode, "activeMode");
    Objects.requireNonNull(nextAcceptanceSequence, "nextAcceptanceSequence");
    if (modeRevision <= 0) {
      throw new IllegalArgumentException("mode revision must be positive");
    }
    if (!previousMode.canTransitionTo(activeMode)) {
      throw new IllegalArgumentException("mode transition is not permitted by the contract");
    }
  }
}
