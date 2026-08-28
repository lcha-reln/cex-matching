package io.github.lchareln.cex.matching;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

/** Immutable ordered events and the complete book snapshot after one command. */
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
      } else {
        throw new IllegalArgumentException("only Trade or final Rested may follow Accepted");
      }
    }
    if (remaining.signum() > 0 && !restedSeen) {
      throw new IllegalArgumentException("a positive taker remainder must emit Rested");
    }
  }
}
