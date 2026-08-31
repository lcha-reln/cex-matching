package io.github.lchareln.cex.matching;

import java.util.Objects;
import java.util.Optional;

/** Active-rule attribution attached to every returned business execution batch. */
public record MarketExecutionContext(
    RuleSetIdentity activeRuleSet,
    long controlRevision,
    Optional<ApplicationSequence> applicationSequence,
    MarketMode marketMode) {
  public MarketExecutionContext {
    Objects.requireNonNull(activeRuleSet, "activeRuleSet");
    applicationSequence = Objects.requireNonNull(applicationSequence, "applicationSequence");
    Objects.requireNonNull(marketMode, "marketMode");
    if (controlRevision < 0) {
      throw new IllegalArgumentException("controlRevision must be non-negative");
    }
  }

  public MarketExecutionContext(
      RuleSetIdentity activeRuleSet,
      long controlRevision,
      ApplicationSequence applicationSequence) {
    this(activeRuleSet, controlRevision, Optional.of(applicationSequence), MarketMode.OPEN);
  }

  public MarketExecutionContext(
      RuleSetIdentity activeRuleSet,
      long controlRevision,
      ApplicationSequence applicationSequence,
      MarketMode marketMode) {
    this(activeRuleSet, controlRevision, Optional.of(applicationSequence), marketMode);
  }

  /** Preserves the M05 three-component value constructor with the bootstrap OPEN mode. */
  public MarketExecutionContext(
      RuleSetIdentity activeRuleSet,
      long controlRevision,
      Optional<ApplicationSequence> applicationSequence) {
    this(activeRuleSet, controlRevision, applicationSequence, MarketMode.OPEN);
  }

  /** Context used only by the M00-M04 two-argument ExecutionBatch constructor. */
  public static MarketExecutionContext bootstrapCompatibility() {
    return new MarketExecutionContext(
        MarketRuleSetArtifact.bootstrapIdentity(), 0, Optional.empty(), MarketMode.OPEN);
  }
}
