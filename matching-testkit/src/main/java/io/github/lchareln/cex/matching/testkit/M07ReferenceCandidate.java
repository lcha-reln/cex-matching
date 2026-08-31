package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.reference.M07LinearReferenceModel;
import io.github.lchareln.cex.matching.reference.M07ReferenceCommand;
import io.github.lchareln.cex.matching.reference.M07SemanticMarketState;
import io.github.lchareln.cex.matching.reference.M07SemanticOutcome;

/** Thin testkit adapter around the infrastructure-free, flat-list M07 reference model. */
final class M07ReferenceCandidate implements M07Candidate {
  private final M07LinearReferenceModel model = new M07LinearReferenceModel();

  @Override
  public M07SemanticOutcome apply(M07ReferenceCommand command) {
    return model.apply(command);
  }

  @Override
  public M07SemanticMarketState snapshot() {
    return model.snapshot();
  }
}
