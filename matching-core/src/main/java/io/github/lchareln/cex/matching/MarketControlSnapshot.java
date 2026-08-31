package io.github.lchareln.cex.matching;

import java.util.Objects;
import java.util.Optional;

/** Detached immutable rule-set and M06 operating-mode state for the single instrument. */
public record MarketControlSnapshot(
    MarketRuleSetArtifact activeRuleSet,
    Optional<MarketRuleSetArtifact> preparedRuleSet,
    long controlRevision,
    Optional<ActivationFence> lastActivationFence,
    ApplicationSequence nextApplicationSequence,
    AcceptanceSequence nextAcceptanceSequence,
    MarketMode marketMode,
    long modeRevision,
    Optional<ModeTransitionFence> lastModeTransitionFence,
    Optional<MassCancelFence> lastMassCancelFence) {
  public MarketControlSnapshot {
    Objects.requireNonNull(activeRuleSet, "activeRuleSet");
    preparedRuleSet = Objects.requireNonNull(preparedRuleSet, "preparedRuleSet");
    lastActivationFence = Objects.requireNonNull(lastActivationFence, "lastActivationFence");
    Objects.requireNonNull(nextApplicationSequence, "nextApplicationSequence");
    Objects.requireNonNull(nextAcceptanceSequence, "nextAcceptanceSequence");
    Objects.requireNonNull(marketMode, "marketMode");
    lastModeTransitionFence =
        Objects.requireNonNull(lastModeTransitionFence, "lastModeTransitionFence");
    lastMassCancelFence = Objects.requireNonNull(lastMassCancelFence, "lastMassCancelFence");
    if (!activeRuleSet.contentHashMatches()) {
      throw new IllegalArgumentException("active rule-set content hash must match");
    }
    preparedRuleSet.ifPresent(
        prepared -> {
          if (!prepared.contentHashMatches()
              || prepared.version().compareTo(activeRuleSet.version()) <= 0) {
            throw new IllegalArgumentException(
                "prepared rule set must be valid and newer than active");
          }
        });
    if (controlRevision < 0) {
      throw new IllegalArgumentException("controlRevision must be non-negative");
    }
    if ((controlRevision == 0) != lastActivationFence.isEmpty()) {
      throw new IllegalArgumentException("control revision and activation fence must agree");
    }
    lastActivationFence.ifPresent(
        fence -> {
          if (fence.controlRevision() != controlRevision) {
            throw new IllegalArgumentException("last activation fence revision changed");
          }
        });
    if (modeRevision < 0 || (modeRevision == 0) != lastModeTransitionFence.isEmpty()) {
      throw new IllegalArgumentException("mode revision and transition fence must agree");
    }
    if (modeRevision == 0 && marketMode != MarketMode.OPEN) {
      throw new IllegalArgumentException("untransitioned market mode must be OPEN");
    }
    lastModeTransitionFence.ifPresent(
        fence -> {
          if (fence.modeRevision() != modeRevision || fence.activeMode() != marketMode) {
            throw new IllegalArgumentException("last mode transition and active mode must agree");
          }
          if (fence.appliedCommandSequence().value() >= nextApplicationSequence.value()
              || fence.nextAcceptanceSequence().value() > nextAcceptanceSequence.value()) {
            throw new IllegalArgumentException("last mode transition is ahead of snapshot state");
          }
        });
    if (lastMassCancelFence.isPresent()) {
      MassCancelFence fence = lastMassCancelFence.orElseThrow();
      if (modeRevision == 0
          || fence.modeRevision() > modeRevision
          || fence.appliedCommandSequence().value() >= nextApplicationSequence.value()) {
        throw new IllegalArgumentException("last Mass Cancel is ahead of snapshot state");
      }
      if (fence.modeRevision() == modeRevision) {
        ModeTransitionFence transition = lastModeTransitionFence.orElseThrow();
        if (marketMode != MarketMode.HALTED
            || transition.modeRevision() != fence.modeRevision()
            || transition.appliedCommandSequence().value()
                >= fence.appliedCommandSequence().value()) {
          throw new IllegalArgumentException("current mode and last Mass Cancel boundary disagree");
        }
      }
      fence
          .lastCanceledSequence()
          .ifPresent(
              sequence -> {
                if (sequence.value() >= nextAcceptanceSequence.value()) {
                  throw new IllegalArgumentException(
                      "last Mass Cancel canceled an unaccepted sequence");
                }
              });
    }
  }

  /** Preserves the M05 snapshot constructor with bootstrap operating-mode state. */
  public MarketControlSnapshot(
      MarketRuleSetArtifact activeRuleSet,
      Optional<MarketRuleSetArtifact> preparedRuleSet,
      long controlRevision,
      Optional<ActivationFence> lastActivationFence,
      ApplicationSequence nextApplicationSequence,
      AcceptanceSequence nextAcceptanceSequence) {
    this(
        activeRuleSet,
        preparedRuleSet,
        controlRevision,
        lastActivationFence,
        nextApplicationSequence,
        nextAcceptanceSequence,
        MarketMode.OPEN,
        0,
        Optional.empty(),
        Optional.empty());
  }

  public RuleSetIdentity activeIdentity() {
    return activeRuleSet.identity();
  }

  public Optional<RuleSetIdentity> preparedIdentity() {
    return preparedRuleSet.map(MarketRuleSetArtifact::identity);
  }
}
