package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.reference.M05SemanticMarketState;
import io.github.lchareln.cex.matching.reference.M05SemanticOutcome;

/** Stateful M05 candidate boundary used by fixed, generated, mutant, and replay judges. */
interface M05Candidate {
  M05SemanticOutcome apply(M05Command command);

  M05SemanticMarketState snapshot();

  @FunctionalInterface
  interface Factory {
    M05Candidate create();
  }
}
