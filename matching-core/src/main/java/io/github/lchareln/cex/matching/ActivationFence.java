package io.github.lchareln.cex.matching;

import java.util.Objects;

/** Deterministic cut between commands and accepted-order time priority at one activation. */
public record ActivationFence(
    ApplicationSequence appliedCommandSequence,
    long controlRevision,
    AcceptanceSequence firstAcceptanceSequence) {
  public ActivationFence {
    Objects.requireNonNull(appliedCommandSequence, "appliedCommandSequence");
    Objects.requireNonNull(firstAcceptanceSequence, "firstAcceptanceSequence");
    if (controlRevision <= 0) {
      throw new IllegalArgumentException("activation control revision must be positive");
    }
  }
}
