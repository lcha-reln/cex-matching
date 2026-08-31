package io.github.lchareln.cex.matching.reference;

import java.math.BigInteger;
import java.util.Objects;
import java.util.Optional;

/** Neutral M06 business and market-control events with explicit rule attribution. */
public sealed interface M06SemanticEvent
    permits M06SemanticEvent.Rejected,
        M06SemanticEvent.PlaceRejected,
        M06SemanticEvent.CancelRejected,
        M06SemanticEvent.Accepted,
        M06SemanticEvent.Trade,
        M06SemanticEvent.Rested,
        M06SemanticEvent.RemainderCanceled,
        M06SemanticEvent.Canceled,
        M06SemanticEvent.RuleSetPrepared,
        M06SemanticEvent.PrepareRuleSetRejected,
        M06SemanticEvent.RuleSetActivated,
        M06SemanticEvent.ActivateRuleSetRejected,
        M06SemanticEvent.ModeChanged,
        M06SemanticEvent.ModeChangeRejected,
        M06SemanticEvent.MassCancelStarted,
        M06SemanticEvent.MassOrderCanceled,
        M06SemanticEvent.MassCancelCompleted,
        M06SemanticEvent.MassCancelRejected {

  enum PrepareStatus {
    PREPARED,
    ALREADY_PREPARED,
    SUPERSEDED
  }

  record Rejected(String code, String field) implements M06SemanticEvent {
    public Rejected {
      Objects.requireNonNull(code, "code");
      Objects.requireNonNull(field, "field");
    }
  }

  record PlaceRejected(BigInteger orderId, String code, M06RuleSetIdentity executionRuleSet)
      implements M06SemanticEvent {
    public PlaceRejected {
      Objects.requireNonNull(orderId, "orderId");
      Objects.requireNonNull(code, "code");
      Objects.requireNonNull(executionRuleSet, "executionRuleSet");
    }
  }

  record CancelRejected(BigInteger orderId, String code, M06RuleSetIdentity executionRuleSet)
      implements M06SemanticEvent {
    public CancelRejected {
      Objects.requireNonNull(orderId, "orderId");
      Objects.requireNonNull(code, "code");
      Objects.requireNonNull(executionRuleSet, "executionRuleSet");
    }
  }

  record Accepted(
      BigInteger acceptanceSequence,
      BigInteger orderId,
      String side,
      BigInteger priceTicks,
      BigInteger quantityLots,
      String executionPolicy,
      M06RuleSetIdentity admissionRuleSet,
      M06RuleSetIdentity executionRuleSet)
      implements M06SemanticEvent {
    public Accepted {
      Objects.requireNonNull(acceptanceSequence, "acceptanceSequence");
      Objects.requireNonNull(orderId, "orderId");
      Objects.requireNonNull(side, "side");
      Objects.requireNonNull(priceTicks, "priceTicks");
      Objects.requireNonNull(quantityLots, "quantityLots");
      Objects.requireNonNull(executionPolicy, "executionPolicy");
      Objects.requireNonNull(admissionRuleSet, "admissionRuleSet");
      Objects.requireNonNull(executionRuleSet, "executionRuleSet");
    }
  }

  record Trade(
      BigInteger makerSequence,
      BigInteger makerOrderId,
      BigInteger takerSequence,
      BigInteger takerOrderId,
      BigInteger priceTicks,
      BigInteger quantityLots,
      M06RuleSetIdentity makerAdmissionRuleSet,
      M06RuleSetIdentity takerAdmissionRuleSet,
      M06RuleSetIdentity executionRuleSet)
      implements M06SemanticEvent {
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
  }

  record Rested(
      BigInteger acceptanceSequence,
      BigInteger orderId,
      String side,
      BigInteger priceTicks,
      BigInteger remainingQuantityLots,
      M06RuleSetIdentity admissionRuleSet,
      M06RuleSetIdentity executionRuleSet)
      implements M06SemanticEvent {
    public Rested {
      Objects.requireNonNull(acceptanceSequence, "acceptanceSequence");
      Objects.requireNonNull(orderId, "orderId");
      Objects.requireNonNull(side, "side");
      Objects.requireNonNull(priceTicks, "priceTicks");
      Objects.requireNonNull(remainingQuantityLots, "remainingQuantityLots");
      Objects.requireNonNull(admissionRuleSet, "admissionRuleSet");
      Objects.requireNonNull(executionRuleSet, "executionRuleSet");
    }
  }

  record RemainderCanceled(
      BigInteger acceptanceSequence,
      BigInteger orderId,
      String side,
      BigInteger priceTicks,
      BigInteger canceledQuantityLots,
      String reason,
      M06RuleSetIdentity admissionRuleSet,
      M06RuleSetIdentity executionRuleSet)
      implements M06SemanticEvent {
    public RemainderCanceled {
      Objects.requireNonNull(acceptanceSequence, "acceptanceSequence");
      Objects.requireNonNull(orderId, "orderId");
      Objects.requireNonNull(side, "side");
      Objects.requireNonNull(priceTicks, "priceTicks");
      Objects.requireNonNull(canceledQuantityLots, "canceledQuantityLots");
      Objects.requireNonNull(reason, "reason");
      Objects.requireNonNull(admissionRuleSet, "admissionRuleSet");
      Objects.requireNonNull(executionRuleSet, "executionRuleSet");
    }
  }

  record Canceled(
      BigInteger acceptanceSequence,
      BigInteger orderId,
      String side,
      BigInteger priceTicks,
      BigInteger canceledQuantityLots,
      M06RuleSetIdentity admissionRuleSet,
      M06RuleSetIdentity executionRuleSet)
      implements M06SemanticEvent {
    public Canceled {
      Objects.requireNonNull(acceptanceSequence, "acceptanceSequence");
      Objects.requireNonNull(orderId, "orderId");
      Objects.requireNonNull(side, "side");
      Objects.requireNonNull(priceTicks, "priceTicks");
      Objects.requireNonNull(canceledQuantityLots, "canceledQuantityLots");
      Objects.requireNonNull(admissionRuleSet, "admissionRuleSet");
      Objects.requireNonNull(executionRuleSet, "executionRuleSet");
    }
  }

  record RuleSetPrepared(
      M06RuleSetIdentity identity,
      PrepareStatus status,
      Optional<M06RuleSetIdentity> supersededIdentity)
      implements M06SemanticEvent {
    public RuleSetPrepared {
      Objects.requireNonNull(identity, "identity");
      Objects.requireNonNull(status, "status");
      Objects.requireNonNull(supersededIdentity, "supersededIdentity");
    }
  }

  record PrepareRuleSetRejected(String code) implements M06SemanticEvent {
    public PrepareRuleSetRejected {
      Objects.requireNonNull(code, "code");
    }
  }

  record RuleSetActivated(
      M06RuleSetIdentity previousActive,
      M06RuleSetIdentity active,
      M06SemanticMarketState.ActivationFence fence)
      implements M06SemanticEvent {
    public RuleSetActivated {
      Objects.requireNonNull(previousActive, "previousActive");
      Objects.requireNonNull(active, "active");
      Objects.requireNonNull(fence, "fence");
    }
  }

  record ActivateRuleSetRejected(String code) implements M06SemanticEvent {
    public ActivateRuleSetRejected {
      Objects.requireNonNull(code, "code");
    }
  }

  record ModeChanged(
      String operatorId,
      String previousMode,
      String activeMode,
      M06SemanticMarketState.ModeTransitionFence fence)
      implements M06SemanticEvent {
    public ModeChanged {
      Objects.requireNonNull(operatorId, "operatorId");
      Objects.requireNonNull(previousMode, "previousMode");
      Objects.requireNonNull(activeMode, "activeMode");
      Objects.requireNonNull(fence, "fence");
    }
  }

  record ModeChangeRejected(String operatorId, String observedMode, String targetMode, String code)
      implements M06SemanticEvent {
    public ModeChangeRejected {
      Objects.requireNonNull(operatorId, "operatorId");
      Objects.requireNonNull(observedMode, "observedMode");
      Objects.requireNonNull(targetMode, "targetMode");
      Objects.requireNonNull(code, "code");
    }
  }

  record MassCancelStarted(
      String operatorId, String marketMode, BigInteger modeRevision, BigInteger restingOrderCount)
      implements M06SemanticEvent {
    public MassCancelStarted {
      Objects.requireNonNull(operatorId, "operatorId");
      Objects.requireNonNull(marketMode, "marketMode");
      Objects.requireNonNull(modeRevision, "modeRevision");
      Objects.requireNonNull(restingOrderCount, "restingOrderCount");
    }
  }

  record MassOrderCanceled(
      String operatorId,
      BigInteger acceptanceSequence,
      BigInteger orderId,
      String side,
      BigInteger priceTicks,
      BigInteger canceledQuantityLots,
      M06RuleSetIdentity admissionRuleSet,
      M06RuleSetIdentity executionRuleSet)
      implements M06SemanticEvent {
    public MassOrderCanceled {
      Objects.requireNonNull(operatorId, "operatorId");
      Objects.requireNonNull(acceptanceSequence, "acceptanceSequence");
      Objects.requireNonNull(orderId, "orderId");
      Objects.requireNonNull(side, "side");
      Objects.requireNonNull(priceTicks, "priceTicks");
      Objects.requireNonNull(canceledQuantityLots, "canceledQuantityLots");
      Objects.requireNonNull(admissionRuleSet, "admissionRuleSet");
      Objects.requireNonNull(executionRuleSet, "executionRuleSet");
    }
  }

  record MassCancelCompleted(
      String operatorId, String marketMode, BigInteger modeRevision, BigInteger canceledOrderCount)
      implements M06SemanticEvent {
    public MassCancelCompleted {
      Objects.requireNonNull(operatorId, "operatorId");
      Objects.requireNonNull(marketMode, "marketMode");
      Objects.requireNonNull(modeRevision, "modeRevision");
      Objects.requireNonNull(canceledOrderCount, "canceledOrderCount");
    }
  }

  record MassCancelRejected(String operatorId, String observedMode, String code)
      implements M06SemanticEvent {
    public MassCancelRejected {
      Objects.requireNonNull(operatorId, "operatorId");
      Objects.requireNonNull(observedMode, "observedMode");
      Objects.requireNonNull(code, "code");
    }
  }
}
