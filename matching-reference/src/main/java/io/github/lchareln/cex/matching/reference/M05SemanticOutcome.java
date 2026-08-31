package io.github.lchareln.cex.matching.reference;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

/** One applied command boundary, its ordered events, and a detached M05 state image. */
public record M05SemanticOutcome(
    BigInteger applicationSequence,
    List<M05SemanticEvent> events,
    M05SemanticMarketState stateAfter) {
  public M05SemanticOutcome {
    Objects.requireNonNull(applicationSequence, "applicationSequence");
    events = List.copyOf(events);
    if (events.isEmpty()) {
      throw new IllegalArgumentException("M05 outcome must contain at least one event");
    }
    Objects.requireNonNull(stateAfter, "stateAfter");
  }

  public M05RuleSetIdentity activeRuleSet() {
    return stateAfter.activeIdentity();
  }

  public BigInteger controlRevision() {
    return stateAfter.controlRevision();
  }

  public M05SemanticBook bookAfter() {
    return stateAfter.book();
  }
}
