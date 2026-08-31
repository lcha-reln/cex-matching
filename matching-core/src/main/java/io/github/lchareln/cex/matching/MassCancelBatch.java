package io.github.lchareln.cex.matching;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** One rejected or atomically completed Mass Cancel with detached state after its boundary. */
public record MassCancelBatch(
    List<MassCancelEvent> events, MarketControlSnapshot controlAfter, OrderBookSnapshot bookAfter) {
  public MassCancelBatch {
    events = List.copyOf(events);
    Objects.requireNonNull(controlAfter, "controlAfter");
    Objects.requireNonNull(bookAfter, "bookAfter");
    if (events.isEmpty()) {
      throw new IllegalArgumentException("Mass Cancel batch must contain at least one event");
    }

    MassCancelEvent first = events.getFirst();
    validateNextApplication(first.applicationSequence(), controlAfter);
    if (first instanceof MassCancelEvent.Rejected rejected) {
      if (events.size() != 1 || rejected.observedMode() != controlAfter.marketMode()) {
        throw new IllegalArgumentException("rejected Mass Cancel must retain its observed mode");
      }
    } else {
      if (!(first instanceof MassCancelEvent.Started started)) {
        throw new IllegalArgumentException("successful Mass Cancel must start with Started");
      }
      if (events.size() < 2 || !(events.getLast() instanceof MassCancelEvent.Completed completed)) {
        throw new IllegalArgumentException("successful Mass Cancel must end with Completed");
      }
      if (!started.applicationSequence().equals(completed.applicationSequence())
          || !started.operatorId().equals(completed.operatorId())
          || started.marketMode() != completed.marketMode()
          || started.modeRevision() != completed.modeRevision()
          || started.restingOrderCount() != completed.canceledOrderCount()
          || completed.canceledOrderCount() != events.size() - 2L) {
        throw new IllegalArgumentException("Mass Cancel terminal markers disagree");
      }
      if (controlAfter.marketMode() != MarketMode.HALTED
          || controlAfter.modeRevision() != completed.modeRevision()
          || !bookAfter.bids().isEmpty()
          || !bookAfter.asks().isEmpty()) {
        throw new IllegalArgumentException(
            "successful Mass Cancel must retain HALTED and empty book");
      }

      long previousSequence = 0;
      Set<OrderId> canceledOrderIds = new HashSet<>();
      for (int index = 1; index < events.size() - 1; index++) {
        if (!(events.get(index) instanceof MassCancelEvent.OrderCanceled canceled)) {
          throw new IllegalArgumentException("only OrderCanceled may occur inside Mass Cancel");
        }
        if (!canceled.applicationSequence().equals(started.applicationSequence())
            || !canceled.operatorId().equals(started.operatorId())
            || canceled.sequence().value() <= previousSequence
            || !canceledOrderIds.add(canceled.orderId())
            || !canceled.executionRuleSet().equals(controlAfter.activeIdentity())) {
          throw new IllegalArgumentException(
              "Mass Cancel order event lost order or rule attribution");
        }
        previousSequence = canceled.sequence().value();
      }

      MassCancelFence fence = controlAfter.lastMassCancelFence().orElseThrow();
      if (!fence.appliedCommandSequence().equals(started.applicationSequence())
          || !fence.operatorId().equals(started.operatorId())
          || fence.modeRevision() != started.modeRevision()
          || fence.canceledOrderCount() != completed.canceledOrderCount()) {
        throw new IllegalArgumentException("Mass Cancel batch and retained fence disagree");
      }
      if (completed.canceledOrderCount() == 0) {
        if (fence.firstCanceledSequence().isPresent() || fence.lastCanceledSequence().isPresent()) {
          throw new IllegalArgumentException("empty Mass Cancel cannot retain sequence bounds");
        }
      } else {
        AcceptanceSequence firstCanceled =
            ((MassCancelEvent.OrderCanceled) events.get(1)).sequence();
        AcceptanceSequence lastCanceled =
            ((MassCancelEvent.OrderCanceled) events.get(events.size() - 2)).sequence();
        if (!fence.firstCanceledSequence().orElseThrow().equals(firstCanceled)
            || !fence.lastCanceledSequence().orElseThrow().equals(lastCanceled)) {
          throw new IllegalArgumentException("Mass Cancel fence sequence bounds changed");
        }
      }
    }
  }

  private static void validateNextApplication(
      ApplicationSequence applied, MarketControlSnapshot controlAfter) {
    final long expectedNext;
    try {
      expectedNext = Math.incrementExact(applied.value());
    } catch (ArithmeticException failure) {
      throw new IllegalArgumentException(
          "completed Mass Cancel sequence cannot be exhausted", failure);
    }
    if (controlAfter.nextApplicationSequence().value() != expectedNext) {
      throw new IllegalArgumentException("Mass Cancel batch sequence and snapshot disagree");
    }
  }
}
