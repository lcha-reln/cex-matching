package io.github.lchareln.cex.matching;

import java.util.Objects;
import java.util.Optional;

/** Detached immutable M05 control-plane state for the single instrument. */
public record MarketControlSnapshot(
    MarketRuleSetArtifact activeRuleSet,
    Optional<MarketRuleSetArtifact> preparedRuleSet,
    long controlRevision,
    Optional<ActivationFence> lastActivationFence,
    ApplicationSequence nextApplicationSequence,
    AcceptanceSequence nextAcceptanceSequence) {
  public MarketControlSnapshot {
    Objects.requireNonNull(activeRuleSet, "activeRuleSet");
    preparedRuleSet = Objects.requireNonNull(preparedRuleSet, "preparedRuleSet");
    lastActivationFence = Objects.requireNonNull(lastActivationFence, "lastActivationFence");
    Objects.requireNonNull(nextApplicationSequence, "nextApplicationSequence");
    Objects.requireNonNull(nextAcceptanceSequence, "nextAcceptanceSequence");
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
  }

  public RuleSetIdentity activeIdentity() {
    return activeRuleSet.identity();
  }

  public Optional<RuleSetIdentity> preparedIdentity() {
    return preparedRuleSet.map(MarketRuleSetArtifact::identity);
  }
}
