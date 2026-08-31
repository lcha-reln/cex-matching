package io.github.lchareln.cex.matching.testkit;

import java.math.BigInteger;
import java.util.Objects;

/** Raw deterministic command algebra shared by the M05 generator and both candidate adapters. */
sealed interface M05Command
    permits M05Command.Place,
        M05Command.Cancel,
        M05Command.PrepareRuleSet,
        M05Command.ActivateRuleSet {

  record Identity(BigInteger version, String contentHash) {
    public Identity {
      Objects.requireNonNull(version, "version");
      Objects.requireNonNull(contentHash, "contentHash");
    }
  }

  record Artifact(
      String schemaVersion,
      String instrumentId,
      BigInteger version,
      BigInteger lowerInclusive,
      BigInteger upperInclusive,
      String contentHash) {
    public Artifact {
      Objects.requireNonNull(schemaVersion, "schemaVersion");
      Objects.requireNonNull(instrumentId, "instrumentId");
      Objects.requireNonNull(version, "version");
      Objects.requireNonNull(lowerInclusive, "lowerInclusive");
      Objects.requireNonNull(upperInclusive, "upperInclusive");
      Objects.requireNonNull(contentHash, "contentHash");
    }

    Identity identity() {
      return new Identity(version, contentHash);
    }
  }

  record Place(
      String entrypoint,
      String instrumentId,
      BigInteger orderId,
      String side,
      BigInteger priceTicks,
      BigInteger quantityLots,
      String executionPolicy,
      Identity expectedRuleSet)
      implements M05Command {
    public Place {
      Objects.requireNonNull(entrypoint, "entrypoint");
      Objects.requireNonNull(instrumentId, "instrumentId");
      Objects.requireNonNull(orderId, "orderId");
      Objects.requireNonNull(side, "side");
      Objects.requireNonNull(priceTicks, "priceTicks");
      Objects.requireNonNull(quantityLots, "quantityLots");
      Objects.requireNonNull(executionPolicy, "executionPolicy");
      if (!"LEGACY".equals(entrypoint) && !"GOVERNED".equals(entrypoint)) {
        throw new IllegalArgumentException("unknown M05 Place entrypoint");
      }
      if (("GOVERNED".equals(entrypoint)) != (expectedRuleSet != null)) {
        throw new IllegalArgumentException("only governed Place carries an expected rule set");
      }
    }
  }

  record Cancel(String instrumentId, BigInteger orderId) implements M05Command {
    public Cancel {
      Objects.requireNonNull(instrumentId, "instrumentId");
      Objects.requireNonNull(orderId, "orderId");
    }
  }

  record PrepareRuleSet(Identity expectedActive, Artifact artifact) implements M05Command {
    public PrepareRuleSet {
      Objects.requireNonNull(expectedActive, "expectedActive");
      Objects.requireNonNull(artifact, "artifact");
    }
  }

  record ActivateRuleSet(
      BigInteger expectedApplicationSequence, Identity expectedActive, Identity target)
      implements M05Command {
    public ActivateRuleSet {
      Objects.requireNonNull(expectedApplicationSequence, "expectedApplicationSequence");
      Objects.requireNonNull(expectedActive, "expectedActive");
      Objects.requireNonNull(target, "target");
    }
  }
}
