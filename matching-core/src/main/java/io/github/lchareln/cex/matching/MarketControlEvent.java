package io.github.lchareln.cex.matching;

import java.util.Objects;

/** Singleton deterministic result emitted by one rule-set or operating-mode control command. */
public sealed interface MarketControlEvent
    permits MarketControlEvent.RuleSetPrepared,
        MarketControlEvent.PrepareRejected,
        MarketControlEvent.RuleSetActivated,
        MarketControlEvent.ActivateRejected,
        MarketControlEvent.ModeChanged,
        MarketControlEvent.ModeChangeRejected {

  ApplicationSequence applicationSequence();

  /** A valid artifact occupied, replayed, or superseded the prepared slot. */
  record RuleSetPrepared(
      ApplicationSequence applicationSequence,
      RuleSetIdentity activeRuleSet,
      RuleSetIdentity preparedRuleSet,
      PrepareRuleSetStatus status)
      implements MarketControlEvent {
    public RuleSetPrepared {
      Objects.requireNonNull(applicationSequence, "applicationSequence");
      Objects.requireNonNull(activeRuleSet, "activeRuleSet");
      Objects.requireNonNull(preparedRuleSet, "preparedRuleSet");
      Objects.requireNonNull(status, "status");
      if (preparedRuleSet.version().compareTo(activeRuleSet.version()) <= 0) {
        throw new IllegalArgumentException("prepared version must be higher than active version");
      }
    }
  }

  /** Prepare failed without changing active, prepared, book, or acceptance state. */
  record PrepareRejected(
      ApplicationSequence applicationSequence,
      RuleSetVersion candidateVersion,
      String candidateContentHash,
      PrepareRuleSetRejectionCode code)
      implements MarketControlEvent {
    public PrepareRejected {
      Objects.requireNonNull(applicationSequence, "applicationSequence");
      Objects.requireNonNull(candidateVersion, "candidateVersion");
      Objects.requireNonNull(candidateContentHash, "candidateContentHash");
      Objects.requireNonNull(code, "code");
    }
  }

  /** The prepared artifact became active at the serialized application boundary. */
  record RuleSetActivated(
      ApplicationSequence applicationSequence,
      RuleSetIdentity previousActiveRuleSet,
      RuleSetIdentity activeRuleSet,
      ActivationFence activationFence)
      implements MarketControlEvent {
    public RuleSetActivated {
      Objects.requireNonNull(applicationSequence, "applicationSequence");
      Objects.requireNonNull(previousActiveRuleSet, "previousActiveRuleSet");
      Objects.requireNonNull(activeRuleSet, "activeRuleSet");
      Objects.requireNonNull(activationFence, "activationFence");
      if (!applicationSequence.equals(activationFence.appliedCommandSequence())) {
        throw new IllegalArgumentException("activated event and fence sequence must agree");
      }
      if (activeRuleSet.version().compareTo(previousActiveRuleSet.version()) <= 0) {
        throw new IllegalArgumentException("activated version must be higher than previous active");
      }
    }
  }

  /** Activate failed at its applied boundary while retaining active and prepared state. */
  record ActivateRejected(
      ApplicationSequence applicationSequence,
      RuleSetIdentity activeRuleSet,
      RuleSetIdentity target,
      ActivateRuleSetRejectionCode code)
      implements MarketControlEvent {
    public ActivateRejected {
      Objects.requireNonNull(applicationSequence, "applicationSequence");
      Objects.requireNonNull(activeRuleSet, "activeRuleSet");
      Objects.requireNonNull(target, "target");
      Objects.requireNonNull(code, "code");
    }
  }

  /** The requested operating mode became active at one serialized application boundary. */
  record ModeChanged(
      ApplicationSequence applicationSequence,
      OperatorId operatorId,
      MarketMode previousMode,
      MarketMode activeMode,
      ModeTransitionFence transitionFence)
      implements MarketControlEvent {
    public ModeChanged {
      Objects.requireNonNull(applicationSequence, "applicationSequence");
      Objects.requireNonNull(operatorId, "operatorId");
      Objects.requireNonNull(previousMode, "previousMode");
      Objects.requireNonNull(activeMode, "activeMode");
      Objects.requireNonNull(transitionFence, "transitionFence");
      if (!applicationSequence.equals(transitionFence.appliedCommandSequence())
          || previousMode != transitionFence.previousMode()
          || activeMode != transitionFence.activeMode()) {
        throw new IllegalArgumentException("mode event and transition fence must agree");
      }
    }
  }

  /** A mode request failed while retaining current mode, book, rules, and acceptance state. */
  record ModeChangeRejected(
      ApplicationSequence applicationSequence,
      OperatorId operatorId,
      MarketMode observedMode,
      MarketMode targetMode,
      ChangeMarketModeRejectionCode code)
      implements MarketControlEvent {
    public ModeChangeRejected {
      Objects.requireNonNull(applicationSequence, "applicationSequence");
      Objects.requireNonNull(operatorId, "operatorId");
      Objects.requireNonNull(observedMode, "observedMode");
      Objects.requireNonNull(targetMode, "targetMode");
      Objects.requireNonNull(code, "code");
      if (code == ChangeMarketModeRejectionCode.NO_MODE_CHANGE && targetMode != observedMode) {
        throw new IllegalArgumentException("NO_MODE_CHANGE requires an unchanged target");
      }
      if (code == ChangeMarketModeRejectionCode.INVALID_TRANSITION
          && (targetMode == observedMode || observedMode.canTransitionTo(targetMode))) {
        throw new IllegalArgumentException(
            "INVALID_TRANSITION requires a distinct forbidden target");
      }
    }
  }
}
