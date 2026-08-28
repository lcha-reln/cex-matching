package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.reference.ReferenceCommand;
import io.github.lchareln.cex.matching.reference.SemanticOutcome;

/** Neutral stateful subject boundary used by the M04 fixed and generated judges. */
interface M04Candidate {
  SemanticOutcome apply(ReferenceCommand command);

  @FunctionalInterface
  interface Factory {
    M04Candidate create();
  }
}
