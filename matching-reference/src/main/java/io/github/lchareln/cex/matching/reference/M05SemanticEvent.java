package io.github.lchareln.cex.matching.reference;

import java.math.BigInteger;
import java.util.Objects;
import java.util.Optional;

/** Neutral M05 business and market-control events with explicit rule attribution. */
public sealed interface M05SemanticEvent
    permits M05SemanticEvent.Rejected,
        M05SemanticEvent.PlaceRejected,
        M05SemanticEvent.CancelRejected,
        M05SemanticEvent.Accepted,
        M05SemanticEvent.Trade,
        M05SemanticEvent.Rested,
        M05SemanticEvent.RemainderCanceled,
        M05SemanticEvent.Canceled,
        M05SemanticEvent.RuleSetPrepared,
        M05SemanticEvent.PrepareRuleSetRejected,
        M05SemanticEvent.RuleSetActivated,
        M05SemanticEvent.ActivateRuleSetRejected {

  enum PrepareStatus {
    PREPARED,
    ALREADY_PREPARED,
    SUPERSEDED
  }

  record Rejected(String code, String field) implements M05SemanticEvent {
    public Rejected {
      Objects.requireNonNull(code, "code");
      Objects.requireNonNull(field, "field");
    }
  }

  record PlaceRejected(BigInteger orderId, String code, M05RuleSetIdentity executionRuleSet)
      implements M05SemanticEvent {
    public PlaceRejected {
      Objects.requireNonNull(orderId, "orderId");
      Objects.requireNonNull(code, "code");
      Objects.requireNonNull(executionRuleSet, "executionRuleSet");
    }
  }

  record CancelRejected(BigInteger orderId, String code, M05RuleSetIdentity executionRuleSet)
      implements M05SemanticEvent {
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
      M05RuleSetIdentity admissionRuleSet,
      M05RuleSetIdentity executionRuleSet)
      implements M05SemanticEvent {
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
      M05RuleSetIdentity makerAdmissionRuleSet,
      M05RuleSetIdentity takerAdmissionRuleSet,
      M05RuleSetIdentity executionRuleSet)
      implements M05SemanticEvent {
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
      M05RuleSetIdentity admissionRuleSet,
      M05RuleSetIdentity executionRuleSet)
      implements M05SemanticEvent {
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
      M05RuleSetIdentity admissionRuleSet,
      M05RuleSetIdentity executionRuleSet)
      implements M05SemanticEvent {
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
      M05RuleSetIdentity admissionRuleSet,
      M05RuleSetIdentity executionRuleSet)
      implements M05SemanticEvent {
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
      M05RuleSetIdentity identity,
      PrepareStatus status,
      Optional<M05RuleSetIdentity> supersededIdentity)
      implements M05SemanticEvent {
    public RuleSetPrepared {
      Objects.requireNonNull(identity, "identity");
      Objects.requireNonNull(status, "status");
      Objects.requireNonNull(supersededIdentity, "supersededIdentity");
    }
  }

  record PrepareRuleSetRejected(String code) implements M05SemanticEvent {
    public PrepareRuleSetRejected {
      Objects.requireNonNull(code, "code");
    }
  }

  record RuleSetActivated(
      M05RuleSetIdentity previousActive,
      M05RuleSetIdentity active,
      M05SemanticMarketState.ActivationFence fence)
      implements M05SemanticEvent {
    public RuleSetActivated {
      Objects.requireNonNull(previousActive, "previousActive");
      Objects.requireNonNull(active, "active");
      Objects.requireNonNull(fence, "fence");
    }
  }

  record ActivateRuleSetRejected(String code) implements M05SemanticEvent {
    public ActivateRuleSetRejected {
      Objects.requireNonNull(code, "code");
    }
  }
}
