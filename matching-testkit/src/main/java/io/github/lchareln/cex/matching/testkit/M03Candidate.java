package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.reference.ReferenceCommand;
import io.github.lchareln.cex.matching.reference.SemanticOutcome;

/** Neutral stateful subject boundary used by the M03 generated-property judge. */
interface M03Candidate {
  SemanticOutcome apply(ReferenceCommand command);

  @FunctionalInterface
  interface Factory {
    M03Candidate create();
  }
}
