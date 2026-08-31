package io.github.lchareln.cex.matching;

import java.util.Objects;
import java.util.Optional;

/** Active-rule attribution attached to every returned business execution batch. */
public record MarketExecutionContext(
    RuleSetIdentity activeRuleSet,
    long controlRevision,
    Optional<ApplicationSequence> applicationSequence) {
  public MarketExecutionContext {
    Objects.requireNonNull(activeRuleSet, "activeRuleSet");
    applicationSequence = Objects.requireNonNull(applicationSequence, "applicationSequence");
    if (controlRevision < 0) {
      throw new IllegalArgumentException("controlRevision must be non-negative");
    }
  }

  public MarketExecutionContext(
      RuleSetIdentity activeRuleSet,
      long controlRevision,
      ApplicationSequence applicationSequence) {
    this(activeRuleSet, controlRevision, Optional.of(applicationSequence));
  }

  /** Context used only by the M00-M04 two-argument ExecutionBatch constructor. */
  public static MarketExecutionContext bootstrapCompatibility() {
    return new MarketExecutionContext(
        MarketRuleSetArtifact.bootstrapIdentity(), 0, Optional.empty());
  }
}
