package io.github.lchareln.cex.matching;

import java.util.Objects;

/** Ordered business events emitted for one place or cancel command. */
public sealed interface MatchingEvent
    permits MatchingEvent.Rejected,
        MatchingEvent.PlaceRejected,
        MatchingEvent.CancelRejected,
        MatchingEvent.Accepted,
        MatchingEvent.Trade,
        MatchingEvent.SelfTradePrevented,
        MatchingEvent.Rested,
        MatchingEvent.RemainderCanceled,
        MatchingEvent.Canceled {

  /** A schema-valid place or cancel input rejected by the frozen field validator. */
  record Rejected(ValidationCode code, String field) implements MatchingEvent {
    public Rejected {
      Objects.requireNonNull(code, "code");
      Objects.requireNonNull(field, "field");
      if (!code.field().equals(field)) {
        throw new IllegalArgumentException("validation code and field do not match");
      }
    }

    public Rejected(ValidationCode code) {
      this(code, code.field());
    }
  }

  /** A business-valid place command rejected before acceptance and state mutation. */
  record PlaceRejected(OrderId orderId, PlaceRejectionCode code) implements MatchingEvent {
    public PlaceRejected {
      Objects.requireNonNull(orderId, "orderId");
      Objects.requireNonNull(code, "code");
    }
  }

  /** A valid cancel command that found no cancellable active remainder. */
  record CancelRejected(OrderId orderId, CancelRejectionCode code) implements MatchingEvent {
    public CancelRejected {
      Objects.requireNonNull(orderId, "orderId");
      Objects.requireNonNull(code, "code");
    }
  }

  /** A valid unique place command assigned its in-memory time-priority sequence. */
  record Accepted(
      AcceptanceSequence sequence,
      OrderId orderId,
      Side side,
      PriceTicks priceTicks,
      QuantityLots quantityLots,
      ExecutionPolicy executionPolicy,
      RuleSetIdentity admissionRuleSet,
      long participantGroupId,
      SelfTradePreventionPolicy selfTradePreventionPolicy)
      implements MatchingEvent {
    public Accepted {
      Objects.requireNonNull(sequence, "sequence");
      Objects.requireNonNull(orderId, "orderId");
      Objects.requireNonNull(side, "side");
      Objects.requireNonNull(priceTicks, "priceTicks");
      Objects.requireNonNull(quantityLots, "quantityLots");
      Objects.requireNonNull(executionPolicy, "executionPolicy");
      Objects.requireNonNull(admissionRuleSet, "admissionRuleSet");
      new SelfTradePreventionInstruction(participantGroupId, selfTradePreventionPolicy);
    }

    public Accepted(
        AcceptanceSequence sequence,
        OrderId orderId,
        Side side,
        PriceTicks priceTicks,
        QuantityLots quantityLots,
        ExecutionPolicy executionPolicy,
        RuleSetIdentity admissionRuleSet) {
      this(
          sequence,
          orderId,
          side,
          priceTicks,
          quantityLots,
          executionPolicy,
          admissionRuleSet,
          0,
          SelfTradePreventionPolicy.NONE);
    }

    public Accepted(
        AcceptanceSequence sequence,
        OrderId orderId,
        Side side,
        PriceTicks priceTicks,
        QuantityLots quantityLots,
        ExecutionPolicy executionPolicy) {
      this(
          sequence,
          orderId,
          side,
          priceTicks,
          quantityLots,
          executionPolicy,
          MarketRuleSetArtifact.bootstrapIdentity());
    }

    public Accepted(
        AcceptanceSequence sequence,
        OrderId orderId,
        Side side,
        PriceTicks priceTicks,
        QuantityLots quantityLots) {
      this(
          sequence,
          orderId,
          side,
          priceTicks,
          quantityLots,
          ExecutionPolicy.GTC,
          MarketRuleSetArtifact.bootstrapIdentity());
    }
  }

  /** One same-group maker/taker encounter resolved without producing a trade. */
  record SelfTradePrevented(
      AcceptanceSequence makerSequence,
      OrderId makerOrderId,
      AcceptanceSequence takerSequence,
      OrderId takerOrderId,
      PriceTicks makerPriceTicks,
      QuantityLots wouldTradeQuantityLots,
      long participantGroupId,
      SelfTradePreventionPolicy policy,
      long makerCanceledQuantityLots,
      long takerCanceledQuantityLots,
      RuleSetIdentity makerAdmissionRuleSet,
      RuleSetIdentity takerAdmissionRuleSet,
      RuleSetIdentity executionRuleSet)
      implements MatchingEvent {
    public SelfTradePrevented {
      Objects.requireNonNull(makerSequence, "makerSequence");
      Objects.requireNonNull(makerOrderId, "makerOrderId");
      Objects.requireNonNull(takerSequence, "takerSequence");
      Objects.requireNonNull(takerOrderId, "takerOrderId");
      Objects.requireNonNull(makerPriceTicks, "makerPriceTicks");
      Objects.requireNonNull(wouldTradeQuantityLots, "wouldTradeQuantityLots");
      Objects.requireNonNull(policy, "policy");
      Objects.requireNonNull(makerAdmissionRuleSet, "makerAdmissionRuleSet");
      Objects.requireNonNull(takerAdmissionRuleSet, "takerAdmissionRuleSet");
      Objects.requireNonNull(executionRuleSet, "executionRuleSet");
      if (makerSequence.value() >= takerSequence.value()) {
        throw new IllegalArgumentException("STP maker must precede its taker");
      }
      if (makerOrderId.equals(takerOrderId)) {
        throw new IllegalArgumentException("STP maker and taker identities must differ");
      }
      if (participantGroupId <= 0 || policy == SelfTradePreventionPolicy.NONE) {
        throw new IllegalArgumentException("STP event requires a positive active instruction");
      }
      if (makerCanceledQuantityLots < 0 || takerCanceledQuantityLots < 0) {
        throw new IllegalArgumentException("STP canceled quantities must be non-negative");
      }
      long wouldTrade = wouldTradeQuantityLots.value();
      if ((makerCanceledQuantityLots > 0 && wouldTrade > makerCanceledQuantityLots)
          || (takerCanceledQuantityLots > 0 && wouldTrade > takerCanceledQuantityLots)) {
        throw new IllegalArgumentException("STP would-trade quantity exceeds a canceled side");
      }
      boolean dispositionMatches =
          switch (policy) {
            case CANCEL_TAKER -> makerCanceledQuantityLots == 0 && takerCanceledQuantityLots > 0;
            case CANCEL_MAKER -> makerCanceledQuantityLots > 0 && takerCanceledQuantityLots == 0;
            case CANCEL_BOTH -> makerCanceledQuantityLots > 0 && takerCanceledQuantityLots > 0;
            case NONE -> false;
          };
      if (!dispositionMatches) {
        throw new IllegalArgumentException("STP canceled quantities disagree with disposition");
      }
    }
  }

  /** One maker-level execution; trades are never aggregated across makers. */
  record Trade(
      AcceptanceSequence makerSequence,
      OrderId makerOrderId,
      AcceptanceSequence takerSequence,
      OrderId takerOrderId,
      PriceTicks priceTicks,
      QuantityLots quantityLots,
      RuleSetIdentity makerAdmissionRuleSet,
      RuleSetIdentity takerAdmissionRuleSet,
      RuleSetIdentity executionRuleSet)
      implements MatchingEvent {
    public Trade {
      Objects.requireNonNull(makerSequence, "makerSequence");
      Objects.requireNonNull(makerOrderId, "makerOrderId");
      Objects.requireNonNull(takerSequence, "takerSequence");
      Objects.requireNonNull(takerOrderId, "takerOrderId");
      Objects.requireNonNull(priceTicks, "priceTicks");
      Objects.requireNonNull(quantityLots, "quantityLots");
      Objects.requireNonNull(makerAdmissionRuleSet, "makerAdmissionRuleSet");
      Objects.requireNonNull(takerAdmissionRuleSet, "takerAdmissionRuleSet");
      Objects.requireNonNull(executionRuleSet, "executionRuleSet");
    }

    public Trade(
        AcceptanceSequence makerSequence,
        OrderId makerOrderId,
        AcceptanceSequence takerSequence,
        OrderId takerOrderId,
        PriceTicks priceTicks,
        QuantityLots quantityLots) {
      this(
          makerSequence,
          makerOrderId,
          takerSequence,
          takerOrderId,
          priceTicks,
          quantityLots,
          MarketRuleSetArtifact.bootstrapIdentity(),
          MarketRuleSetArtifact.bootstrapIdentity(),
          MarketRuleSetArtifact.bootstrapIdentity());
    }
  }

  /** The positive GTC or Post-only remainder appended to its own price level. */
  record Rested(
      AcceptanceSequence sequence,
      OrderId orderId,
      Side side,
      PriceTicks priceTicks,
      QuantityLots remainingQuantityLots,
      RuleSetIdentity admissionRuleSet,
      long participantGroupId,
      SelfTradePreventionPolicy selfTradePreventionPolicy)
      implements MatchingEvent {
    public Rested {
      Objects.requireNonNull(sequence, "sequence");
      Objects.requireNonNull(orderId, "orderId");
      Objects.requireNonNull(side, "side");
      Objects.requireNonNull(priceTicks, "priceTicks");
      Objects.requireNonNull(remainingQuantityLots, "remainingQuantityLots");
      Objects.requireNonNull(admissionRuleSet, "admissionRuleSet");
      new SelfTradePreventionInstruction(participantGroupId, selfTradePreventionPolicy);
    }

    public Rested(
        AcceptanceSequence sequence,
        OrderId orderId,
        Side side,
        PriceTicks priceTicks,
        QuantityLots remainingQuantityLots,
        RuleSetIdentity admissionRuleSet) {
      this(
          sequence,
          orderId,
          side,
          priceTicks,
          remainingQuantityLots,
          admissionRuleSet,
          0,
          SelfTradePreventionPolicy.NONE);
    }

    public Rested(
        AcceptanceSequence sequence,
        OrderId orderId,
        Side side,
        PriceTicks priceTicks,
        QuantityLots remainingQuantityLots) {
      this(
          sequence,
          orderId,
          side,
          priceTicks,
          remainingQuantityLots,
          MarketRuleSetArtifact.bootstrapIdentity());
    }
  }

  /** A positive accepted IOC remainder canceled without ever entering the book. */
  record RemainderCanceled(
      AcceptanceSequence sequence,
      OrderId orderId,
      Side side,
      PriceTicks priceTicks,
      QuantityLots canceledQuantityLots,
      RemainderCancelReason reason,
      RuleSetIdentity admissionRuleSet)
      implements MatchingEvent {
    public RemainderCanceled {
      Objects.requireNonNull(sequence, "sequence");
      Objects.requireNonNull(orderId, "orderId");
      Objects.requireNonNull(side, "side");
      Objects.requireNonNull(priceTicks, "priceTicks");
      Objects.requireNonNull(canceledQuantityLots, "canceledQuantityLots");
      Objects.requireNonNull(reason, "reason");
      Objects.requireNonNull(admissionRuleSet, "admissionRuleSet");
    }

    public RemainderCanceled(
        AcceptanceSequence sequence,
        OrderId orderId,
        Side side,
        PriceTicks priceTicks,
        QuantityLots canceledQuantityLots,
        RemainderCancelReason reason) {
      this(
          sequence,
          orderId,
          side,
          priceTicks,
          canceledQuantityLots,
          reason,
          MarketRuleSetArtifact.bootstrapIdentity());
    }
  }

  /** The positive active remainder removed by one successful cancellation. */
  record Canceled(
      AcceptanceSequence sequence,
      OrderId orderId,
      Side side,
      PriceTicks priceTicks,
      QuantityLots canceledQuantityLots,
      RuleSetIdentity admissionRuleSet,
      RuleSetIdentity executionRuleSet)
      implements MatchingEvent {
    public Canceled {
      Objects.requireNonNull(sequence, "sequence");
      Objects.requireNonNull(orderId, "orderId");
      Objects.requireNonNull(side, "side");
      Objects.requireNonNull(priceTicks, "priceTicks");
      Objects.requireNonNull(canceledQuantityLots, "canceledQuantityLots");
      Objects.requireNonNull(admissionRuleSet, "admissionRuleSet");
      Objects.requireNonNull(executionRuleSet, "executionRuleSet");
    }

    public Canceled(
        AcceptanceSequence sequence,
        OrderId orderId,
        Side side,
        PriceTicks priceTicks,
        QuantityLots canceledQuantityLots) {
      this(
          sequence,
          orderId,
          side,
          priceTicks,
          canceledQuantityLots,
          MarketRuleSetArtifact.bootstrapIdentity(),
          MarketRuleSetArtifact.bootstrapIdentity());
    }
  }
}
