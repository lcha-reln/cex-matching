package io.github.lchareln.cex.matching.reference;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

/** One applied M07 command boundary with ordered events and a detached state image. */
public record M07SemanticOutcome(
    BigInteger applicationSequence,
    List<M07SemanticEvent> events,
    M07SemanticMarketState stateAfter) {
  public M07SemanticOutcome {
    Objects.requireNonNull(applicationSequence, "applicationSequence");
    events = List.copyOf(events);
    if (events.isEmpty()) {
      throw new IllegalArgumentException("M07 outcome must contain at least one event");
    }
    Objects.requireNonNull(stateAfter, "stateAfter");
  }

  public M07SemanticBook bookAfter() {
    return stateAfter.book();
  }
}
