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
public record ExecutionBatch(
    List<MatchingEvent> events, OrderBookSnapshot bookAfter, MarketExecutionContext context) {
  public ExecutionBatch {
    events = List.copyOf(events);
    Objects.requireNonNull(bookAfter, "bookAfter");
    Objects.requireNonNull(context, "context");
    validateGrammar(events, context);
  }

  /** Preserves the M00-M04 value constructor with bootstrap rule attribution. */
  public ExecutionBatch(List<MatchingEvent> events, OrderBookSnapshot bookAfter) {
    this(events, bookAfter, MarketExecutionContext.bootstrapCompatibility());
  }

  private static void validateGrammar(List<MatchingEvent> events, MarketExecutionContext context) {
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
      validateModeGrammar(first, context.marketMode());
      if (first instanceof MatchingEvent.Canceled canceled
          && !canceled.executionRuleSet().equals(context.activeRuleSet())) {
        throw new IllegalArgumentException(
            "canceled event must carry the batch active execution rule set");
      }
      return;
    }
    if (!(first instanceof MatchingEvent.Accepted accepted)) {
      throw new IllegalArgumentException("a valid batch must start with Accepted");
    }
    if (context.marketMode() != MarketMode.OPEN) {
      throw new IllegalArgumentException("an accepted place batch requires OPEN mode");
    }
    if (!accepted.admissionRuleSet().equals(context.activeRuleSet())) {
      throw new IllegalArgumentException(
          "accepted order must carry the batch active admission rule set");
    }
    BigInteger remaining = BigInteger.valueOf(accepted.quantityLots().value());
    boolean restedSeen = false;
    boolean remainderCanceledSeen = false;
    boolean stpTakerCanceledSeen = false;
    int trades = 0;
    int stpEvents = 0;
    for (int index = 1; index < events.size(); index++) {
      MatchingEvent event = events.get(index);
      if (event instanceof MatchingEvent.Trade trade) {
        if (!trade.takerSequence().equals(accepted.sequence())
            || !trade.takerOrderId().equals(accepted.orderId())) {
          throw new IllegalArgumentException("trade taker must be the accepted order");
        }
        if (!trade.takerAdmissionRuleSet().equals(accepted.admissionRuleSet())
            || !trade.executionRuleSet().equals(context.activeRuleSet())) {
          throw new IllegalArgumentException("trade rule-set attribution changed");
        }
        remaining = remaining.subtract(BigInteger.valueOf(trade.quantityLots().value()));
        if (remaining.signum() < 0) {
          throw new IllegalArgumentException("trade quantity exceeds the accepted quantity");
        }
        trades++;
      } else if (event instanceof MatchingEvent.SelfTradePrevented prevented) {
        if (!prevented.takerSequence().equals(accepted.sequence())
            || !prevented.takerOrderId().equals(accepted.orderId())) {
          throw new IllegalArgumentException("STP taker must be the accepted order");
        }
        if (prevented.participantGroupId() != accepted.participantGroupId()
            || prevented.policy() != accepted.selfTradePreventionPolicy()
            || !prevented.takerAdmissionRuleSet().equals(accepted.admissionRuleSet())
            || !prevented.executionRuleSet().equals(context.activeRuleSet())) {
          throw new IllegalArgumentException("STP instruction or rule-set attribution changed");
        }
        if (BigInteger.valueOf(prevented.wouldTradeQuantityLots().value()).compareTo(remaining)
            > 0) {
          throw new IllegalArgumentException("STP would-trade quantity exceeds taker remainder");
        }
        if (prevented.policy() == SelfTradePreventionPolicy.CANCEL_TAKER
            || prevented.policy() == SelfTradePreventionPolicy.CANCEL_BOTH) {
          if (index != events.size() - 1
              || prevented.takerCanceledQuantityLots() != remaining.longValueExact()) {
            throw new IllegalArgumentException(
                "taker-canceling STP must terminate with the complete taker remainder");
          }
          remaining = BigInteger.ZERO;
          stpTakerCanceledSeen = true;
        }
        stpEvents++;
      } else if (event instanceof MatchingEvent.Rested rested) {
        if (index != events.size() - 1) {
          throw new IllegalArgumentException("Rested must be the final event");
        }
        if (!rested.sequence().equals(accepted.sequence())
            || !rested.orderId().equals(accepted.orderId())
            || rested.side() != accepted.side()
            || !rested.priceTicks().equals(accepted.priceTicks())
            || !remaining.equals(BigInteger.valueOf(rested.remainingQuantityLots().value()))
            || !rested.admissionRuleSet().equals(accepted.admissionRuleSet())
            || rested.participantGroupId() != accepted.participantGroupId()
            || rested.selfTradePreventionPolicy() != accepted.selfTradePreventionPolicy()) {
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
            || canceled.reason() != RemainderCancelReason.IOC_REMAINDER
            || !canceled.admissionRuleSet().equals(accepted.admissionRuleSet())) {
          throw new IllegalArgumentException("canceled remainder must belong to the accepted IOC");
        }
        remainderCanceledSeen = true;
      } else {
        throw new IllegalArgumentException(
            "only Trade, SelfTradePrevented, final Rested, or final RemainderCanceled may follow Accepted");
      }
    }
    switch (accepted.executionPolicy()) {
      case GTC -> {
        if ((remaining.signum() > 0) != restedSeen
            || remainderCanceledSeen
            || (stpTakerCanceledSeen && restedSeen)) {
          throw new IllegalArgumentException("GTC remainder must rest exactly once");
        }
      }
      case IOC -> {
        if (restedSeen
            || (remaining.signum() > 0) != remainderCanceledSeen
            || (stpTakerCanceledSeen && remainderCanceledSeen)) {
          throw new IllegalArgumentException("IOC remainder must be canceled exactly once");
        }
      }
      case FOK -> {
        if (remaining.signum() != 0
            || restedSeen
            || remainderCanceledSeen
            || stpTakerCanceledSeen
            || trades == 0) {
          throw new IllegalArgumentException("accepted FOK must fill completely");
        }
      }
      case POST_ONLY -> {
        if (trades != 0 || stpEvents != 0 || !restedSeen || remainderCanceledSeen) {
          throw new IllegalArgumentException("accepted Post-only must rest without trading");
        }
      }
    }
  }

  private static void validateModeGrammar(MatchingEvent event, MarketMode mode) {
    if (event instanceof MatchingEvent.PlaceRejected rejected) {
      if (rejected.code() == PlaceRejectionCode.MARKET_NOT_OPEN && mode == MarketMode.OPEN) {
        throw new IllegalArgumentException("place rejection and market mode disagree");
      }
      if ((rejected.code() == PlaceRejectionCode.FOK_NOT_FILLABLE
              || rejected.code() == PlaceRejectionCode.POST_ONLY_WOULD_TAKE)
          && mode != MarketMode.OPEN) {
        throw new IllegalArgumentException("policy-state rejection requires OPEN mode");
      }
    } else if (event instanceof MatchingEvent.CancelRejected rejected) {
      if ((rejected.code() == CancelRejectionCode.MARKET_NOT_CANCELABLE)
          != (mode == MarketMode.HALTED)) {
        throw new IllegalArgumentException("cancel rejection and market mode disagree");
      }
    } else if (event instanceof MatchingEvent.Canceled && mode == MarketMode.HALTED) {
      throw new IllegalArgumentException("customer cancel success is forbidden while HALTED");
    }
  }
}
