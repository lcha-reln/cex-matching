package io.github.lchareln.cex.matching.reference;

import java.math.BigInteger;
import java.util.Objects;
import java.util.Optional;

/** Neutral M07 business and control events; no production matcher type is imported. */
public sealed interface M07SemanticEvent
    permits M07SemanticEvent.Rejected,
        M07SemanticEvent.PlaceRejected,
        M07SemanticEvent.CancelRejected,
        M07SemanticEvent.Accepted,
        M07SemanticEvent.Trade,
        M07SemanticEvent.SelfTradePrevented,
        M07SemanticEvent.Rested,
        M07SemanticEvent.RemainderCanceled,
        M07SemanticEvent.Canceled,
        M07SemanticEvent.RuleSetPrepared,
        M07SemanticEvent.PrepareRuleSetRejected,
        M07SemanticEvent.RuleSetActivated,
        M07SemanticEvent.ActivateRuleSetRejected,
        M07SemanticEvent.ModeChanged,
        M07SemanticEvent.ModeChangeRejected,
        M07SemanticEvent.MassCancelStarted,
        M07SemanticEvent.MassOrderCanceled,
        M07SemanticEvent.MassCancelCompleted,
        M07SemanticEvent.MassCancelRejected {

  enum PrepareStatus {
    PREPARED,
    ALREADY_PREPARED,
    SUPERSEDED
  }

  record Rejected(String code, String field) implements M07SemanticEvent {
    public Rejected {
      requireAll(code, field);
    }
  }

  record PlaceRejected(BigInteger orderId, String code, M06RuleSetIdentity executionRuleSet)
      implements M07SemanticEvent {
    public PlaceRejected {
      requireAll(orderId, code, executionRuleSet);
    }
  }

  record CancelRejected(BigInteger orderId, String code, M06RuleSetIdentity executionRuleSet)
      implements M07SemanticEvent {
    public CancelRejected {
      requireAll(orderId, code, executionRuleSet);
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
      M06RuleSetIdentity executionRuleSet,
      BigInteger participantGroupId,
      String stpPolicy)
      implements M07SemanticEvent {
    public Accepted {
      requireAll(
          acceptanceSequence,
          orderId,
          side,
          priceTicks,
          quantityLots,
          executionPolicy,
          admissionRuleSet,
          executionRuleSet,
          participantGroupId,
          stpPolicy);
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
      implements M07SemanticEvent {
    public Trade {
      requireAll(
          makerSequence,
          makerOrderId,
          takerSequence,
          takerOrderId,
          priceTicks,
          quantityLots,
          makerAdmissionRuleSet,
          takerAdmissionRuleSet,
          executionRuleSet);
    }
  }

  record SelfTradePrevented(
      BigInteger makerSequence,
      BigInteger makerOrderId,
      BigInteger takerSequence,
      BigInteger takerOrderId,
      BigInteger makerPriceTicks,
      BigInteger wouldTradeQuantityLots,
      BigInteger participantGroupId,
      String stpPolicy,
      BigInteger makerCanceledQuantityLots,
      BigInteger takerCanceledQuantityLots,
      M06RuleSetIdentity makerAdmissionRuleSet,
      M06RuleSetIdentity takerAdmissionRuleSet,
      M06RuleSetIdentity executionRuleSet)
      implements M07SemanticEvent {
    public SelfTradePrevented {
      requireAll(
          makerSequence,
          makerOrderId,
          takerSequence,
          takerOrderId,
          makerPriceTicks,
          wouldTradeQuantityLots,
          participantGroupId,
          stpPolicy,
          makerCanceledQuantityLots,
          takerCanceledQuantityLots,
          makerAdmissionRuleSet,
          takerAdmissionRuleSet,
          executionRuleSet);
      if (participantGroupId.signum() <= 0
          || wouldTradeQuantityLots.signum() <= 0
          || makerCanceledQuantityLots.signum() < 0
          || takerCanceledQuantityLots.signum() < 0) {
        throw new IllegalArgumentException("invalid M07 STP event quantities");
      }
      if ((makerCanceledQuantityLots.signum() > 0
              && wouldTradeQuantityLots.compareTo(makerCanceledQuantityLots) > 0)
          || (takerCanceledQuantityLots.signum() > 0
              && wouldTradeQuantityLots.compareTo(takerCanceledQuantityLots) > 0)) {
        throw new IllegalArgumentException("M07 would-trade quantity exceeds a canceled side");
      }
      boolean dispositionMatches =
          switch (stpPolicy) {
            case "CANCEL_TAKER" ->
                makerCanceledQuantityLots.signum() == 0 && takerCanceledQuantityLots.signum() > 0;
            case "CANCEL_MAKER" ->
                makerCanceledQuantityLots.signum() > 0 && takerCanceledQuantityLots.signum() == 0;
            case "CANCEL_BOTH" ->
                makerCanceledQuantityLots.signum() > 0 && takerCanceledQuantityLots.signum() > 0;
            default -> false;
          };
      if (!dispositionMatches) {
        throw new IllegalArgumentException("M07 STP event disposition is inconsistent");
      }
    }
  }

  record Rested(
      BigInteger acceptanceSequence,
      BigInteger orderId,
      String side,
      BigInteger priceTicks,
      BigInteger remainingQuantityLots,
      M06RuleSetIdentity admissionRuleSet,
      M06RuleSetIdentity executionRuleSet,
      BigInteger participantGroupId,
      String stpPolicy)
      implements M07SemanticEvent {
    public Rested {
      requireAll(
          acceptanceSequence,
          orderId,
          side,
          priceTicks,
          remainingQuantityLots,
          admissionRuleSet,
          executionRuleSet,
          participantGroupId,
          stpPolicy);
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
      implements M07SemanticEvent {
    public RemainderCanceled {
      requireAll(
          acceptanceSequence,
          orderId,
          side,
          priceTicks,
          canceledQuantityLots,
          reason,
          admissionRuleSet,
          executionRuleSet);
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
      implements M07SemanticEvent {
    public Canceled {
      requireAll(
          acceptanceSequence,
          orderId,
          side,
          priceTicks,
          canceledQuantityLots,
          admissionRuleSet,
          executionRuleSet);
    }
  }

  record RuleSetPrepared(
      M06RuleSetIdentity identity,
      PrepareStatus status,
      Optional<M06RuleSetIdentity> supersededIdentity)
      implements M07SemanticEvent {
    public RuleSetPrepared {
      requireAll(identity, status, supersededIdentity);
    }
  }

  record PrepareRuleSetRejected(String code) implements M07SemanticEvent {
    public PrepareRuleSetRejected {
      requireAll(code);
    }
  }

  record RuleSetActivated(
      M06RuleSetIdentity previousActive,
      M06RuleSetIdentity active,
      M07SemanticMarketState.ActivationFence fence)
      implements M07SemanticEvent {
    public RuleSetActivated {
      requireAll(previousActive, active, fence);
    }
  }

  record ActivateRuleSetRejected(String code) implements M07SemanticEvent {
    public ActivateRuleSetRejected {
      requireAll(code);
    }
  }

  record ModeChanged(
      String operatorId,
      String previousMode,
      String activeMode,
      M07SemanticMarketState.ModeTransitionFence fence)
      implements M07SemanticEvent {
    public ModeChanged {
      requireAll(operatorId, previousMode, activeMode, fence);
    }
  }

  record ModeChangeRejected(String operatorId, String observedMode, String targetMode, String code)
      implements M07SemanticEvent {
    public ModeChangeRejected {
      requireAll(operatorId, observedMode, targetMode, code);
    }
  }

  record MassCancelStarted(
      String operatorId, String marketMode, BigInteger modeRevision, BigInteger restingOrderCount)
      implements M07SemanticEvent {
    public MassCancelStarted {
      requireAll(operatorId, marketMode, modeRevision, restingOrderCount);
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
      implements M07SemanticEvent {
    public MassOrderCanceled {
      requireAll(
          operatorId,
          acceptanceSequence,
          orderId,
          side,
          priceTicks,
          canceledQuantityLots,
          admissionRuleSet,
          executionRuleSet);
    }
  }

  record MassCancelCompleted(
      String operatorId, String marketMode, BigInteger modeRevision, BigInteger canceledOrderCount)
      implements M07SemanticEvent {
    public MassCancelCompleted {
      requireAll(operatorId, marketMode, modeRevision, canceledOrderCount);
    }
  }

  record MassCancelRejected(String operatorId, String observedMode, String code)
      implements M07SemanticEvent {
    public MassCancelRejected {
      requireAll(operatorId, observedMode, code);
    }
  }

  private static void requireAll(Object... values) {
    for (Object value : values) {
      Objects.requireNonNull(value, "semantic event field");
    }
  }
}
