package io.github.lchareln.cex.matching;

import java.util.Objects;

/** Caller-serialized request to occupy or supersede the single prepared rule-set slot. */
public record PrepareRuleSet(RuleSetIdentity expectedActive, MarketRuleSetArtifact artifact) {
  public PrepareRuleSet {
    Objects.requireNonNull(expectedActive, "expectedActive");
    Objects.requireNonNull(artifact, "artifact");
  }
}
