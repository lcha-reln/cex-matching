package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.reference.M05LinearReferenceModel;
import io.github.lchareln.cex.matching.reference.M05MarketRuleSetArtifact;
import io.github.lchareln.cex.matching.reference.M05ReferenceCommand;
import io.github.lchareln.cex.matching.reference.M05RuleSetIdentity;
import io.github.lchareln.cex.matching.reference.M05SemanticMarketState;
import io.github.lchareln.cex.matching.reference.M05SemanticOutcome;

/** Thin command-shape adapter around the independent flat-list reference implementation. */
final class M05ReferenceCandidate implements M05Candidate {
  private final M05LinearReferenceModel model = new M05LinearReferenceModel();

  @Override
  public M05SemanticOutcome apply(M05Command command) {
    return model.apply(referenceCommand(command));
  }

  @Override
  public M05SemanticMarketState snapshot() {
    return model.snapshot();
  }

  static M05ReferenceCommand referenceCommand(M05Command command) {
    return switch (command) {
      case M05Command.Place place -> place(place);
      case M05Command.Cancel cancel ->
          new M05ReferenceCommand.Cancel(cancel.instrumentId(), cancel.orderId());
      case M05Command.PrepareRuleSet prepare ->
          new M05ReferenceCommand.PrepareRuleSet(
              identity(prepare.expectedActive()), artifact(prepare.artifact()));
      case M05Command.ActivateRuleSet activate ->
          new M05ReferenceCommand.ActivateRuleSet(
              activate.expectedApplicationSequence(),
              identity(activate.expectedActive()),
              identity(activate.target()));
    };
  }

  private static M05ReferenceCommand.Place place(M05Command.Place place) {
    if ("LEGACY".equals(place.entrypoint())) {
      return M05ReferenceCommand.Place.legacy(
          place.instrumentId(),
          place.orderId(),
          place.side(),
          place.priceTicks(),
          place.quantityLots(),
          place.executionPolicy());
    }
    return M05ReferenceCommand.Place.governed(
        identity(place.expectedRuleSet()),
        place.instrumentId(),
        place.orderId(),
        place.side(),
        place.priceTicks(),
        place.quantityLots(),
        place.executionPolicy());
  }

  private static M05MarketRuleSetArtifact artifact(M05Command.Artifact artifact) {
    return new M05MarketRuleSetArtifact(
        artifact.schemaVersion(),
        artifact.instrumentId(),
        artifact.version(),
        artifact.lowerInclusive(),
        artifact.upperInclusive(),
        artifact.contentHash());
  }

  private static M05RuleSetIdentity identity(M05Command.Identity identity) {
    return new M05RuleSetIdentity(identity.version(), identity.contentHash());
  }
}
