package io.github.lchareln.cex.matching.reference;

import java.math.BigInteger;
import java.util.Objects;

/** Raw schema-valid commands consumed only by the independent M05 reference model. */
public sealed interface M05ReferenceCommand
    permits M05ReferenceCommand.Place,
        M05ReferenceCommand.Cancel,
        M05ReferenceCommand.PrepareRuleSet,
        M05ReferenceCommand.ActivateRuleSet {

  enum PlaceEntrypoint {
    LEGACY,
    GOVERNED
  }

  /** Policy-aware limit Place with an explicit legacy or governed entrypoint. */
  record Place(
      PlaceEntrypoint entrypoint,
      M05RuleSetIdentity expectedRuleSet,
      String instrumentId,
      BigInteger orderId,
      String side,
      BigInteger priceTicks,
      BigInteger quantityLots,
      String executionPolicy)
      implements M05ReferenceCommand {
    public Place {
      Objects.requireNonNull(entrypoint, "entrypoint");
      Objects.requireNonNull(instrumentId, "instrumentId");
      Objects.requireNonNull(orderId, "orderId");
      Objects.requireNonNull(side, "side");
      Objects.requireNonNull(priceTicks, "priceTicks");
      Objects.requireNonNull(quantityLots, "quantityLots");
      Objects.requireNonNull(executionPolicy, "executionPolicy");
      if (entrypoint == PlaceEntrypoint.LEGACY && expectedRuleSet != null) {
        throw new IllegalArgumentException("legacy Place cannot carry an expected rule identity");
      }
      if (entrypoint == PlaceEntrypoint.GOVERNED && expectedRuleSet == null) {
        throw new IllegalArgumentException("governed Place requires an expected rule identity");
      }
    }

    public static Place legacy(
        String instrumentId,
        BigInteger orderId,
        String side,
        BigInteger priceTicks,
        BigInteger quantityLots,
        String executionPolicy) {
      return new Place(
          PlaceEntrypoint.LEGACY,
          null,
          instrumentId,
          orderId,
          side,
          priceTicks,
          quantityLots,
          executionPolicy);
    }

    public static Place governed(
        M05RuleSetIdentity expectedRuleSet,
        String instrumentId,
        BigInteger orderId,
        String side,
        BigInteger priceTicks,
        BigInteger quantityLots,
        String executionPolicy) {
      return new Place(
          PlaceEntrypoint.GOVERNED,
          expectedRuleSet,
          instrumentId,
          orderId,
          side,
          priceTicks,
          quantityLots,
          executionPolicy);
    }
  }

  record Cancel(String instrumentId, BigInteger orderId) implements M05ReferenceCommand {
    public Cancel {
      Objects.requireNonNull(instrumentId, "instrumentId");
      Objects.requireNonNull(orderId, "orderId");
    }
  }

  record PrepareRuleSet(M05RuleSetIdentity expectedActive, M05MarketRuleSetArtifact artifact)
      implements M05ReferenceCommand {
    public PrepareRuleSet {
      Objects.requireNonNull(expectedActive, "expectedActive");
      Objects.requireNonNull(artifact, "artifact");
    }
  }

  record ActivateRuleSet(
      BigInteger expectedApplicationSequence,
      M05RuleSetIdentity expectedActive,
      M05RuleSetIdentity target)
      implements M05ReferenceCommand {
    public ActivateRuleSet {
      Objects.requireNonNull(expectedApplicationSequence, "expectedApplicationSequence");
      Objects.requireNonNull(expectedActive, "expectedActive");
      Objects.requireNonNull(target, "target");
    }
  }
}
