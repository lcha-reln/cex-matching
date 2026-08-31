package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.reference.M06LinearReferenceModel;
import io.github.lchareln.cex.matching.reference.M06ReferenceCommand;
import io.github.lchareln.cex.matching.reference.M06SemanticMarketState;
import io.github.lchareln.cex.matching.reference.M06SemanticOutcome;

/** Thin testkit adapter around the infrastructure-free, flat-list M06 reference model. */
final class M06ReferenceCandidate implements M06Candidate {
  private final M06LinearReferenceModel model = new M06LinearReferenceModel();

  @Override
  public M06SemanticOutcome apply(M06ReferenceCommand command) {
    return model.apply(command);
  }

  @Override
  public M06SemanticMarketState snapshot() {
    return model.snapshot();
  }
}
