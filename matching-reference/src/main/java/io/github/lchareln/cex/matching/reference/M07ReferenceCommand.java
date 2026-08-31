package io.github.lchareln.cex.matching.reference;

import java.math.BigInteger;
import java.util.Objects;

/** Raw commands consumed only by the JDK-only independent M07 linear reference. */
public sealed interface M07ReferenceCommand
    permits M07ReferenceCommand.Place,
        M07ReferenceCommand.Cancel,
        M07ReferenceCommand.PrepareRuleSet,
        M07ReferenceCommand.ActivateRuleSet,
        M07ReferenceCommand.ChangeMarketMode,
        M07ReferenceCommand.MassCancel {

  enum PlaceEntrypoint {
    LEGACY,
    GOVERNED,
    STP,
    GOVERNED_STP
  }

  record Place(
      PlaceEntrypoint entrypoint,
      M06RuleSetIdentity expectedRuleSet,
      String instrumentId,
      BigInteger orderId,
      String side,
      BigInteger priceTicks,
      BigInteger quantityLots,
      String executionPolicy,
      BigInteger participantGroupId,
      String stpPolicy)
      implements M07ReferenceCommand {
    public Place {
      Objects.requireNonNull(entrypoint, "entrypoint");
      Objects.requireNonNull(instrumentId, "instrumentId");
      Objects.requireNonNull(orderId, "orderId");
      Objects.requireNonNull(side, "side");
      Objects.requireNonNull(priceTicks, "priceTicks");
      Objects.requireNonNull(quantityLots, "quantityLots");
      Objects.requireNonNull(executionPolicy, "executionPolicy");
      Objects.requireNonNull(participantGroupId, "participantGroupId");
      Objects.requireNonNull(stpPolicy, "stpPolicy");
      boolean governed =
          entrypoint == PlaceEntrypoint.GOVERNED || entrypoint == PlaceEntrypoint.GOVERNED_STP;
      if (governed != (expectedRuleSet != null)) {
        throw new IllegalArgumentException(
            "only a governed Place may carry an expected rule identity");
      }
      boolean stp = entrypoint == PlaceEntrypoint.STP || entrypoint == PlaceEntrypoint.GOVERNED_STP;
      if (!stp && (participantGroupId.signum() != 0 || !"NONE".equals(stpPolicy))) {
        throw new IllegalArgumentException("legacy Place must map to group zero and NONE");
      }
    }

    public static Place legacy(
        String instrumentId,
        BigInteger orderId,
        String side,
        BigInteger priceTicks,
        BigInteger quantityLots,
        String executionPolicy) {
      return create(
          PlaceEntrypoint.LEGACY,
          null,
          instrumentId,
          orderId,
          side,
          priceTicks,
          quantityLots,
          executionPolicy,
          BigInteger.ZERO,
          "NONE");
    }

    public static Place governed(
        M06RuleSetIdentity expectedRuleSet,
        String instrumentId,
        BigInteger orderId,
        String side,
        BigInteger priceTicks,
        BigInteger quantityLots,
        String executionPolicy) {
      return create(
          PlaceEntrypoint.GOVERNED,
          expectedRuleSet,
          instrumentId,
          orderId,
          side,
          priceTicks,
          quantityLots,
          executionPolicy,
          BigInteger.ZERO,
          "NONE");
    }

    public static Place stp(
        String instrumentId,
        BigInteger orderId,
        String side,
        BigInteger priceTicks,
        BigInteger quantityLots,
        String executionPolicy,
        BigInteger participantGroupId,
        String stpPolicy) {
      return create(
          PlaceEntrypoint.STP,
          null,
          instrumentId,
          orderId,
          side,
          priceTicks,
          quantityLots,
          executionPolicy,
          participantGroupId,
          stpPolicy);
    }

    public static Place governedStp(
        M06RuleSetIdentity expectedRuleSet,
        String instrumentId,
        BigInteger orderId,
        String side,
        BigInteger priceTicks,
        BigInteger quantityLots,
        String executionPolicy,
        BigInteger participantGroupId,
        String stpPolicy) {
      return create(
          PlaceEntrypoint.GOVERNED_STP,
          expectedRuleSet,
          instrumentId,
          orderId,
          side,
          priceTicks,
          quantityLots,
          executionPolicy,
          participantGroupId,
          stpPolicy);
    }

    private static Place create(
        PlaceEntrypoint entrypoint,
        M06RuleSetIdentity expectedRuleSet,
        String instrumentId,
        BigInteger orderId,
        String side,
        BigInteger priceTicks,
        BigInteger quantityLots,
        String executionPolicy,
        BigInteger participantGroupId,
        String stpPolicy) {
      return new Place(
          entrypoint,
          expectedRuleSet,
          instrumentId,
          orderId,
          side,
          priceTicks,
          quantityLots,
          executionPolicy,
          participantGroupId,
          stpPolicy);
    }
  }

  record Cancel(String instrumentId, BigInteger orderId) implements M07ReferenceCommand {
    public Cancel {
      Objects.requireNonNull(instrumentId, "instrumentId");
      Objects.requireNonNull(orderId, "orderId");
    }
  }

  record PrepareRuleSet(M06RuleSetIdentity expectedActive, M06MarketRuleSetArtifact artifact)
      implements M07ReferenceCommand {
    public PrepareRuleSet {
      Objects.requireNonNull(expectedActive, "expectedActive");
      Objects.requireNonNull(artifact, "artifact");
    }
  }

  record ActivateRuleSet(
      BigInteger expectedApplicationSequence,
      M06RuleSetIdentity expectedActive,
      M06RuleSetIdentity target)
      implements M07ReferenceCommand {
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
      implements M07ReferenceCommand {
    public ChangeMarketMode {
      Objects.requireNonNull(expectedApplicationSequence, "expectedApplicationSequence");
      requireMode(expectedMode);
      requireMode(targetMode);
      requireOperatorId(operatorId);
    }
  }

  record MassCancel(BigInteger expectedApplicationSequence, String expectedMode, String operatorId)
      implements M07ReferenceCommand {
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
