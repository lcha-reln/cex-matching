package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.reference.M06ReferenceCommand;
import io.github.lchareln.cex.matching.reference.M06SemanticMarketState;
import io.github.lchareln.cex.matching.reference.M06SemanticOutcome;

/** Stateful semantic boundary shared by the M06 fixed, generated, mutation, and replay judges. */
interface M06Candidate {
  M06SemanticOutcome apply(M06ReferenceCommand command);

  M06SemanticMarketState snapshot();

  @FunctionalInterface
  interface Factory {
    M06Candidate create();
  }
}
