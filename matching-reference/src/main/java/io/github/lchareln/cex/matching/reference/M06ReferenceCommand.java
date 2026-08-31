package io.github.lchareln.cex.matching.reference;

import java.math.BigInteger;
import java.util.Objects;

/** Raw schema-valid commands consumed only by the independent M06 reference model. */
public sealed interface M06ReferenceCommand
    permits M06ReferenceCommand.Place,
        M06ReferenceCommand.Cancel,
        M06ReferenceCommand.PrepareRuleSet,
        M06ReferenceCommand.ActivateRuleSet,
        M06ReferenceCommand.ChangeMarketMode,
        M06ReferenceCommand.MassCancel {

  enum PlaceEntrypoint {
    LEGACY,
    GOVERNED
  }

  /** Policy-aware limit Place with an explicit legacy or governed entrypoint. */
  record Place(
      PlaceEntrypoint entrypoint,
      M06RuleSetIdentity expectedRuleSet,
      String instrumentId,
      BigInteger orderId,
      String side,
      BigInteger priceTicks,
      BigInteger quantityLots,
      String executionPolicy)
      implements M06ReferenceCommand {
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
        M06RuleSetIdentity expectedRuleSet,
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

  record Cancel(String instrumentId, BigInteger orderId) implements M06ReferenceCommand {
    public Cancel {
      Objects.requireNonNull(instrumentId, "instrumentId");
      Objects.requireNonNull(orderId, "orderId");
    }
  }

  record PrepareRuleSet(M06RuleSetIdentity expectedActive, M06MarketRuleSetArtifact artifact)
      implements M06ReferenceCommand {
    public PrepareRuleSet {
      Objects.requireNonNull(expectedActive, "expectedActive");
      Objects.requireNonNull(artifact, "artifact");
    }
  }

  record ActivateRuleSet(
      BigInteger expectedApplicationSequence,
      M06RuleSetIdentity expectedActive,
      M06RuleSetIdentity target)
      implements M06ReferenceCommand {
    public ActivateRuleSet {
      Objects.requireNonNull(expectedApplicationSequence, "expectedApplicationSequence");
      Objects.requireNonNull(expectedActive, "expectedActive");
      Objects.requireNonNull(target, "target");
    }
  }

  record ChangeMarketMode(
      BigInteger expectedApplicationSequence,
      String expectedMode,
      String targetMode,
      String operatorId)
      implements M06ReferenceCommand {
    public ChangeMarketMode {
      Objects.requireNonNull(expectedApplicationSequence, "expectedApplicationSequence");
      requireMode(expectedMode);
      requireMode(targetMode);
      requireOperatorId(operatorId);
    }
  }

  record MassCancel(BigInteger expectedApplicationSequence, String expectedMode, String operatorId)
      implements M06ReferenceCommand {
    public MassCancel {
      Objects.requireNonNull(expectedApplicationSequence, "expectedApplicationSequence");
      requireMode(expectedMode);
      requireOperatorId(operatorId);
    }
  }

  private static void requireOperatorId(String operatorId) {
    Objects.requireNonNull(operatorId, "operatorId");
    if (operatorId.isBlank() || operatorId.length() > 128) {
      throw new IllegalArgumentException("operator id must contain 1 to 128 non-blank characters");
    }
  }

  private static void requireMode(String mode) {
    Objects.requireNonNull(mode, "mode");
    if (!"OPEN".equals(mode) && !"CANCEL_ONLY".equals(mode) && !"HALTED".equals(mode)) {
      throw new IllegalArgumentException("unsupported market mode");
    }
  }
}
