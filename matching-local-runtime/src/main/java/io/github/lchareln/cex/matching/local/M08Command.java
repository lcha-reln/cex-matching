package io.github.lchareln.cex.matching.local;

import io.github.lchareln.cex.matching.MarketMode;
import io.github.lchareln.cex.matching.MarketRuleSetArtifact;
import io.github.lchareln.cex.matching.RuleSetIdentity;
import java.math.BigInteger;
import java.util.Objects;
import java.util.Optional;

/** M08C1 command union. Records retain raw business fields so core rejections remain replayable. */
public sealed interface M08Command
    permits M08Command.Place,
        M08Command.Cancel,
        M08Command.PrepareRuleSet,
        M08Command.ActivateRuleSet,
        M08Command.ChangeMarketMode,
        M08Command.MassCancel {

  /** Place reserves M07 group/policy fields even before the M07 core types are cherry-picked. */
  record Place(
      String instrumentId,
      BigInteger orderId,
      String side,
      BigInteger priceTicks,
      BigInteger quantityLots,
      String executionPolicy,
      long participantGroupId,
      String stpPolicy,
      Optional<RuleSetIdentity> expectedActive)
      implements M08Command {
    public Place {
      Objects.requireNonNull(instrumentId, "instrumentId");
      Objects.requireNonNull(orderId, "orderId");
      Objects.requireNonNull(side, "side");
      Objects.requireNonNull(priceTicks, "priceTicks");
      Objects.requireNonNull(quantityLots, "quantityLots");
      Objects.requireNonNull(executionPolicy, "executionPolicy");
      Objects.requireNonNull(stpPolicy, "stpPolicy");
      expectedActive = Objects.requireNonNull(expectedActive, "expectedActive");
    }

    public boolean usesLegacyStpMapping() {
      return participantGroupId == 0 && stpPolicy.equals("NONE");
    }
  }

  record Cancel(String instrumentId, BigInteger orderId) implements M08Command {
    public Cancel {
      Objects.requireNonNull(instrumentId, "instrumentId");
      Objects.requireNonNull(orderId, "orderId");
    }
  }

  record PrepareRuleSet(RuleSetIdentity expectedActive, MarketRuleSetArtifact artifact)
      implements M08Command {
    public PrepareRuleSet {
      Objects.requireNonNull(expectedActive, "expectedActive");
      Objects.requireNonNull(artifact, "artifact");
    }
  }

  record ActivateRuleSet(
      long expectedApplicationSequence, RuleSetIdentity expectedActive, RuleSetIdentity target)
      implements M08Command {
    public ActivateRuleSet {
      requirePositive(expectedApplicationSequence, "expectedApplicationSequence");
      Objects.requireNonNull(expectedActive, "expectedActive");
      Objects.requireNonNull(target, "target");
    }
  }

  record ChangeMarketMode(
      long expectedApplicationSequence,
      MarketMode expectedMode,
      MarketMode targetMode,
      String operatorId)
      implements M08Command {
    public ChangeMarketMode {
      requirePositive(expectedApplicationSequence, "expectedApplicationSequence");
      Objects.requireNonNull(expectedMode, "expectedMode");
      Objects.requireNonNull(targetMode, "targetMode");
      validateOperator(operatorId);
    }
  }

  record MassCancel(long expectedApplicationSequence, MarketMode expectedMode, String operatorId)
      implements M08Command {
    public MassCancel {
      requirePositive(expectedApplicationSequence, "expectedApplicationSequence");
      Objects.requireNonNull(expectedMode, "expectedMode");
      validateOperator(operatorId);
    }
  }

  private static void requirePositive(long value, String field) {
    if (value <= 0) {
      throw new IllegalArgumentException(field + " must be positive");
    }
  }

  private static void validateOperator(String value) {
    Objects.requireNonNull(value, "operatorId");
    if (value.isBlank() || value.length() > 128) {
      throw new IllegalArgumentException("operatorId must contain 1 to 128 non-blank characters");
    }
  }
}
