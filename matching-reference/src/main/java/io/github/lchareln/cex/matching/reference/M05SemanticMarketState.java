package io.github.lchareln.cex.matching.reference;

import java.math.BigInteger;
import java.util.Objects;
import java.util.Optional;

/** Detached control, sequence, attribution, and book state after an M05 command boundary. */
public record M05SemanticMarketState(
    BigInteger nextApplicationSequence,
    BigInteger nextAcceptanceSequence,
    BigInteger controlRevision,
    M05MarketRuleSetArtifact activeRuleSet,
    Optional<M05MarketRuleSetArtifact> preparedRuleSet,
    Optional<ActivationFence> lastActivationFence,
    M05SemanticBook book) {
  public M05SemanticMarketState {
    Objects.requireNonNull(nextApplicationSequence, "nextApplicationSequence");
    Objects.requireNonNull(nextAcceptanceSequence, "nextAcceptanceSequence");
    Objects.requireNonNull(controlRevision, "controlRevision");
    Objects.requireNonNull(activeRuleSet, "activeRuleSet");
    Objects.requireNonNull(preparedRuleSet, "preparedRuleSet");
    Objects.requireNonNull(lastActivationFence, "lastActivationFence");
    Objects.requireNonNull(book, "book");
  }

  public M05RuleSetIdentity activeIdentity() {
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
}
