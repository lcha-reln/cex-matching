package io.github.lchareln.cex.matching;

import java.util.Objects;

/** Exact serialized boundary at which one prepared rule set is requested to become active. */
public record ActivateRuleSet(
    ApplicationSequence expectedApplicationSequence,
    RuleSetIdentity expectedActive,
    RuleSetIdentity target) {
  public ActivateRuleSet {
    Objects.requireNonNull(expectedApplicationSequence, "expectedApplicationSequence");
    Objects.requireNonNull(expectedActive, "expectedActive");
    Objects.requireNonNull(target, "target");
  }
}
