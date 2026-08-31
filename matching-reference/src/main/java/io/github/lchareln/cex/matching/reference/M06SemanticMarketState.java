package io.github.lchareln.cex.matching.reference;

import java.math.BigInteger;
import java.util.Objects;
import java.util.Optional;

/** Detached control, sequence, attribution, and book state after an M06 command boundary. */
public record M06SemanticMarketState(
    BigInteger nextApplicationSequence,
    BigInteger nextAcceptanceSequence,
    BigInteger controlRevision,
    M06MarketRuleSetArtifact activeRuleSet,
    Optional<M06MarketRuleSetArtifact> preparedRuleSet,
    Optional<ActivationFence> lastActivationFence,
    String marketMode,
    BigInteger modeRevision,
    Optional<ModeTransitionFence> lastModeTransitionFence,
    Optional<MassCancelFence> lastMassCancelFence,
    M06SemanticBook book) {
  public M06SemanticMarketState {
    Objects.requireNonNull(nextApplicationSequence, "nextApplicationSequence");
    Objects.requireNonNull(nextAcceptanceSequence, "nextAcceptanceSequence");
    Objects.requireNonNull(controlRevision, "controlRevision");
    Objects.requireNonNull(activeRuleSet, "activeRuleSet");
    Objects.requireNonNull(preparedRuleSet, "preparedRuleSet");
    Objects.requireNonNull(lastActivationFence, "lastActivationFence");
    Objects.requireNonNull(marketMode, "marketMode");
    Objects.requireNonNull(modeRevision, "modeRevision");
    Objects.requireNonNull(lastModeTransitionFence, "lastModeTransitionFence");
    Objects.requireNonNull(lastMassCancelFence, "lastMassCancelFence");
    Objects.requireNonNull(book, "book");
  }

  public M06RuleSetIdentity activeIdentity() {
    return activeRuleSet.identity();
  }

  public record ActivationFence(
      BigInteger applicationSequence,
      BigInteger controlRevision,
      BigInteger firstAcceptanceSequence) {
    public ActivationFence {
      Objects.requireNonNull(applicationSequence, "applicationSequence");
      Objects.requireNonNull(controlRevision, "controlRevision");
      Objects.requireNonNull(firstAcceptanceSequence, "firstAcceptanceSequence");
    }
  }

  public record ModeTransitionFence(
      BigInteger applicationSequence,
      BigInteger modeRevision,
      String previousMode,
      String activeMode,
      BigInteger nextAcceptanceSequence) {
    public ModeTransitionFence {
      Objects.requireNonNull(applicationSequence, "applicationSequence");
      Objects.requireNonNull(modeRevision, "modeRevision");
      Objects.requireNonNull(previousMode, "previousMode");
      Objects.requireNonNull(activeMode, "activeMode");
      Objects.requireNonNull(nextAcceptanceSequence, "nextAcceptanceSequence");
    }
  }

  public record MassCancelFence(
      BigInteger applicationSequence,
      BigInteger modeRevision,
      String operatorId,
      BigInteger canceledOrderCount,
      Optional<BigInteger> firstCanceledSequence,
      Optional<BigInteger> lastCanceledSequence) {
    public MassCancelFence {
      Objects.requireNonNull(applicationSequence, "applicationSequence");
      Objects.requireNonNull(modeRevision, "modeRevision");
      Objects.requireNonNull(operatorId, "operatorId");
      Objects.requireNonNull(canceledOrderCount, "canceledOrderCount");
      Objects.requireNonNull(firstCanceledSequence, "firstCanceledSequence");
      Objects.requireNonNull(lastCanceledSequence, "lastCanceledSequence");
    }
  }
}
