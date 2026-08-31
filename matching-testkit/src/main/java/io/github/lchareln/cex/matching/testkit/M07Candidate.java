package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.reference.M07ReferenceCommand;
import io.github.lchareln.cex.matching.reference.M07SemanticMarketState;
import io.github.lchareln.cex.matching.reference.M07SemanticOutcome;

/** Stateful semantic boundary shared by the M07 fixed, generated, mutation, and replay judges. */
interface M07Candidate {
  M07SemanticOutcome apply(M07ReferenceCommand command);

  M07SemanticMarketState snapshot();

  @FunctionalInterface
  interface Factory {
    M07Candidate create();
  }
}
