package io.github.lchareln.cex.matching;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

/**
 * Immutable ordered events and the complete book snapshot after one command.
 *
 * <p>The constructor closes the event grammar. Cross-checking those events against the supplied
 * book is an engine invariant and judge responsibility.
 */
public record ExecutionBatch(List<MatchingEvent> events, OrderBookSnapshot bookAfter) {
  public ExecutionBatch {
    events = List.copyOf(events);
    Objects.requireNonNull(bookAfter, "bookAfter");
    validateGrammar(events);
  }

  private static void validateGrammar(List<MatchingEvent> events) {
    if (events.isEmpty()) {
      throw new IllegalArgumentException("execution batch must contain at least one event");
    }
    MatchingEvent first = events.getFirst();
    if (first instanceof MatchingEvent.Rejected
        || first instanceof MatchingEvent.PlaceRejected
        || first instanceof MatchingEvent.CancelRejected
        || first instanceof MatchingEvent.Canceled) {
      if (events.size() != 1) {
        throw new IllegalArgumentException("a singleton batch must contain exactly one event");
      }
      return;
    }
    if (!(first instanceof MatchingEvent.Accepted accepted)) {
      throw new IllegalArgumentException("a valid batch must start with Accepted");
    }
    BigInteger remaining = BigInteger.valueOf(accepted.quantityLots().value());
    boolean restedSeen = false;
    boolean remainderCanceledSeen = false;
    int trades = 0;
    for (int index = 1; index < events.size(); index++) {
      MatchingEvent event = events.get(index);
      if (event instanceof MatchingEvent.Trade trade) {
        if (!trade.takerSequence().equals(accepted.sequence())
            || !trade.takerOrderId().equals(accepted.orderId())) {
          throw new IllegalArgumentException("trade taker must be the accepted order");
        }
        remaining = remaining.subtract(BigInteger.valueOf(trade.quantityLots().value()));
        if (remaining.signum() < 0) {
          throw new IllegalArgumentException("trade quantity exceeds the accepted quantity");
        }
        trades++;
      } else if (event instanceof MatchingEvent.Rested rested) {
        if (index != events.size() - 1) {
          throw new IllegalArgumentException("Rested must be the final event");
        }
        if (!rested.sequence().equals(accepted.sequence())
            || !rested.orderId().equals(accepted.orderId())
            || rested.side() != accepted.side()
            || !rested.priceTicks().equals(accepted.priceTicks())
            || !remaining.equals(BigInteger.valueOf(rested.remainingQuantityLots().value()))) {
          throw new IllegalArgumentException("resting remainder must belong to the accepted order");
        }
        restedSeen = true;
      } else if (event instanceof MatchingEvent.RemainderCanceled canceled) {
        if (index != events.size() - 1) {
          throw new IllegalArgumentException("RemainderCanceled must be the final event");
        }
        if (!canceled.sequence().equals(accepted.sequence())
            || !canceled.orderId().equals(accepted.orderId())
            || canceled.side() != accepted.side()
            || !canceled.priceTicks().equals(accepted.priceTicks())
            || !remaining.equals(BigInteger.valueOf(canceled.canceledQuantityLots().value()))
            || canceled.reason() != RemainderCancelReason.IOC_REMAINDER) {
          throw new IllegalArgumentException("canceled remainder must belong to the accepted IOC");
        }
        remainderCanceledSeen = true;
      } else {
        throw new IllegalArgumentException(
            "only Trade, final Rested, or final RemainderCanceled may follow Accepted");
      }
    }
    switch (accepted.executionPolicy()) {
      case GTC -> {
        if ((remaining.signum() > 0) != restedSeen || remainderCanceledSeen) {
          throw new IllegalArgumentException("GTC remainder must rest exactly once");
        }
      }
      case IOC -> {
        if (restedSeen || (remaining.signum() > 0) != remainderCanceledSeen) {
          throw new IllegalArgumentException("IOC remainder must be canceled exactly once");
        }
      }
      case FOK -> {
        if (remaining.signum() != 0 || restedSeen || remainderCanceledSeen || trades == 0) {
          throw new IllegalArgumentException("accepted FOK must fill completely");
        }
      }
      case POST_ONLY -> {
        if (trades != 0 || !restedSeen || remainderCanceledSeen) {
          throw new IllegalArgumentException("accepted Post-only must rest without trading");
        }
      }
    }
  }
}
