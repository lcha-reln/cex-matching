package io.github.lchareln.cex.matching;

import java.util.List;
import java.util.Objects;

/** One control result with detached control and book state after its serialized boundary. */
public record MarketControlBatch(
    List<MarketControlEvent> events,
    MarketControlSnapshot controlAfter,
    OrderBookSnapshot bookAfter) {
  public MarketControlBatch {
    events = List.copyOf(events);
    Objects.requireNonNull(controlAfter, "controlAfter");
    Objects.requireNonNull(bookAfter, "bookAfter");
    if (events.size() != 1) {
      throw new IllegalArgumentException("market control batch must contain exactly one event");
    }
    MarketControlEvent event = events.getFirst();
    long expectedNext;
    try {
      expectedNext = Math.incrementExact(event.applicationSequence().value());
    } catch (ArithmeticException failure) {
      throw new IllegalArgumentException("completed control sequence cannot be exhausted", failure);
    }
    if (controlAfter.nextApplicationSequence().value() != expectedNext) {
      throw new IllegalArgumentException("control batch sequence and snapshot must agree");
    }
    RuleSetIdentity activeAfter = controlAfter.activeIdentity();
    switch (event) {
      case MarketControlEvent.RuleSetPrepared prepared -> {
        if (!prepared.activeRuleSet().equals(activeAfter)
            || !controlAfter.preparedIdentity().orElseThrow().equals(prepared.preparedRuleSet())) {
          throw new IllegalArgumentException("prepared event and control snapshot must agree");
        }
      }
      case MarketControlEvent.PrepareRejected ignored -> {
        // The snapshot is the complete retained state for this raw candidate rejection.
      }
      case MarketControlEvent.RuleSetActivated activated -> {
        if (!activated.activeRuleSet().equals(activeAfter)
            || controlAfter.preparedRuleSet().isPresent()
            || !controlAfter
                .lastActivationFence()
                .orElseThrow()
                .equals(activated.activationFence())) {
          throw new IllegalArgumentException("activated event and control snapshot must agree");
        }
      }
      case MarketControlEvent.ActivateRejected rejected -> {
        if (!rejected.activeRuleSet().equals(activeAfter)) {
          throw new IllegalArgumentException("activate rejection and active snapshot must agree");
        }
      }
      case MarketControlEvent.ModeChanged changed -> {
        if (changed.activeMode() != controlAfter.marketMode()
            || changed.transitionFence().modeRevision() != controlAfter.modeRevision()
            || !controlAfter
                .lastModeTransitionFence()
                .orElseThrow()
                .equals(changed.transitionFence())) {
          throw new IllegalArgumentException("mode event and control snapshot must agree");
        }
      }
      case MarketControlEvent.ModeChangeRejected rejected -> {
        if (rejected.observedMode() != controlAfter.marketMode()) {
          throw new IllegalArgumentException("mode rejection and control snapshot must agree");
        }
      }
    }
  }
}
