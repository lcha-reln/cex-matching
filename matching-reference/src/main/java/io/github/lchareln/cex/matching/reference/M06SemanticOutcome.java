package io.github.lchareln.cex.matching.reference;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

/** One applied command boundary, its ordered events, and a detached M06 state image. */
public record M06SemanticOutcome(
    BigInteger applicationSequence,
    List<M06SemanticEvent> events,
    M06SemanticMarketState stateAfter) {
  public M06SemanticOutcome {
    Objects.requireNonNull(applicationSequence, "applicationSequence");
    events = List.copyOf(events);
    if (events.isEmpty()) {
      throw new IllegalArgumentException("M06 outcome must contain at least one event");
    }
    Objects.requireNonNull(stateAfter, "stateAfter");
  }

  public M06RuleSetIdentity activeRuleSet() {
    return stateAfter.activeIdentity();
  }

  public BigInteger controlRevision() {
    return stateAfter.controlRevision();
  }

  public M06SemanticBook bookAfter() {
    return stateAfter.book();
  }
}
