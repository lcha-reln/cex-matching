package io.github.lchareln.cex.matching.reference;

/** Stateful executable semantics used as the independent side of M03-M04 differential checks. */
public interface ReferenceMatcher {
  SemanticOutcome apply(ReferenceCommand command);

  SemanticBook snapshot();
}
