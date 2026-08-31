package io.github.lchareln.cex.matching;

import java.util.Objects;
import java.util.Optional;

/** Last successful atomic Mass Cancel boundary retained in market-control state. */
public record MassCancelFence(
    ApplicationSequence appliedCommandSequence,
    long modeRevision,
    OperatorId operatorId,
    long canceledOrderCount,
    Optional<AcceptanceSequence> firstCanceledSequence,
    Optional<AcceptanceSequence> lastCanceledSequence) {
  public MassCancelFence {
    Objects.requireNonNull(appliedCommandSequence, "appliedCommandSequence");
    Objects.requireNonNull(operatorId, "operatorId");
    firstCanceledSequence = Objects.requireNonNull(firstCanceledSequence, "firstCanceledSequence");
    lastCanceledSequence = Objects.requireNonNull(lastCanceledSequence, "lastCanceledSequence");
    if (modeRevision <= 0) {
      throw new IllegalArgumentException("Mass Cancel mode revision must be positive");
    }
    if (canceledOrderCount < 0) {
      throw new IllegalArgumentException("canceled order count must be non-negative");
    }
    if ((canceledOrderCount == 0) != firstCanceledSequence.isEmpty()
        || firstCanceledSequence.isEmpty() != lastCanceledSequence.isEmpty()) {
      throw new IllegalArgumentException("canceled order count and sequence bounds must agree");
    }
    if (firstCanceledSequence.isPresent()
        && firstCanceledSequence.get().value() > lastCanceledSequence.orElseThrow().value()) {
      throw new IllegalArgumentException("Mass Cancel sequence bounds are reversed");
    }
  }
}
