package io.github.lchareln.cex.matching;

import java.util.Objects;

/** Ordered events emitted by one rejected or atomically completed operator Mass Cancel. */
public sealed interface MassCancelEvent
    permits MassCancelEvent.Started,
        MassCancelEvent.OrderCanceled,
        MassCancelEvent.Completed,
        MassCancelEvent.Rejected {

  ApplicationSequence applicationSequence();

  OperatorId operatorId();

  /** Opens a successful batch and freezes the number of resting orders it will terminate. */
  record Started(
      ApplicationSequence applicationSequence,
      OperatorId operatorId,
      MarketMode marketMode,
      long modeRevision,
      long restingOrderCount)
      implements MassCancelEvent {
    public Started {
      Objects.requireNonNull(applicationSequence, "applicationSequence");
      Objects.requireNonNull(operatorId, "operatorId");
      Objects.requireNonNull(marketMode, "marketMode");
      if (marketMode != MarketMode.HALTED) {
        throw new IllegalArgumentException("Mass Cancel can start only while HALTED");
      }
      if (modeRevision <= 0 || restingOrderCount < 0) {
        throw new IllegalArgumentException(
            "Mass Cancel needs a positive mode revision and non-negative count");
      }
    }
  }

  /** One positive active remainder terminated in global acceptance-sequence order. */
  record OrderCanceled(
      ApplicationSequence applicationSequence,
      OperatorId operatorId,
      AcceptanceSequence sequence,
      OrderId orderId,
      Side side,
      PriceTicks priceTicks,
      QuantityLots canceledQuantityLots,
      RuleSetIdentity admissionRuleSet,
      RuleSetIdentity executionRuleSet)
      implements MassCancelEvent {
    public OrderCanceled {
      Objects.requireNonNull(applicationSequence, "applicationSequence");
      Objects.requireNonNull(operatorId, "operatorId");
      Objects.requireNonNull(sequence, "sequence");
      Objects.requireNonNull(orderId, "orderId");
      Objects.requireNonNull(side, "side");
      Objects.requireNonNull(priceTicks, "priceTicks");
      Objects.requireNonNull(canceledQuantityLots, "canceledQuantityLots");
      Objects.requireNonNull(admissionRuleSet, "admissionRuleSet");
      Objects.requireNonNull(executionRuleSet, "executionRuleSet");
    }
  }

  /** Closes a successful atomic batch after every frozen order has terminated. */
  record Completed(
      ApplicationSequence applicationSequence,
      OperatorId operatorId,
      MarketMode marketMode,
      long modeRevision,
      long canceledOrderCount)
      implements MassCancelEvent {
    public Completed {
      Objects.requireNonNull(applicationSequence, "applicationSequence");
      Objects.requireNonNull(operatorId, "operatorId");
      Objects.requireNonNull(marketMode, "marketMode");
      if (marketMode != MarketMode.HALTED) {
        throw new IllegalArgumentException("Mass Cancel can complete only while HALTED");
      }
      if (modeRevision <= 0 || canceledOrderCount < 0) {
        throw new IllegalArgumentException(
            "Mass Cancel needs a positive mode revision and non-negative count");
      }
    }
  }

  /** A deterministic preflight rejection that leaves mode, book, and registry unchanged. */
  record Rejected(
      ApplicationSequence applicationSequence,
      OperatorId operatorId,
      MarketMode observedMode,
      MassCancelRejectionCode code)
      implements MassCancelEvent {
    public Rejected {
      Objects.requireNonNull(applicationSequence, "applicationSequence");
      Objects.requireNonNull(operatorId, "operatorId");
      Objects.requireNonNull(observedMode, "observedMode");
      Objects.requireNonNull(code, "code");
    }
  }
}
