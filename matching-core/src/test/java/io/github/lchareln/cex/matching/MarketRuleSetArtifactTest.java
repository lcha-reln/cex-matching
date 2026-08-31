package io.github.lchareln.cex.matching;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

final class MarketRuleSetArtifactTest {
  @Test
  void m05rs1CanonicalBytesAndAllFrozenVectorsAreExact() {
    MarketRuleSetArtifact versionOne = artifact(1, 90, 110);

    assertArrayEquals(
        ("M05RS1\n"
                + "schemaVersion=matching.market-rule-set.v1\n"
                + "instrumentId=BTC-USDT\n"
                + "version=1\n"
                + "lowerInclusive=90\n"
                + "upperInclusive=110\n")
            .getBytes(StandardCharsets.UTF_8),
        versionOne.canonicalBytes());
    assertEquals(
        "sha256:dbb75b3983480a8ece058736766411f80eb5c62e10eb24de72b74853d5377f91",
        versionOne.contentHash());

    List<HashVector> vectors =
        List.of(
            new HashVector(
                0,
                1,
                Long.MAX_VALUE,
                "sha256:d9928c52e99b8611cb95fb0d2792b6901cf9336825e19a7f593393b0d2b99c04"),
            new HashVector(
                1,
                90,
                110,
                "sha256:dbb75b3983480a8ece058736766411f80eb5c62e10eb24de72b74853d5377f91"),
            new HashVector(
                1,
                95,
                105,
                "sha256:1e5934c44343fe92741732bc5af56c019fc0e785815ff8848ed810ad52247372"),
            new HashVector(
                2,
                80,
                120,
                "sha256:d7d0a8e3a2d1882012f8ba6d7318ecf02e378f4766c26badff272a97e1e21f7d"));
    for (HashVector vector : vectors) {
      MarketRuleSetArtifact value =
          artifact(vector.version(), vector.lowerInclusive(), vector.upperInclusive());
      assertEquals(vector.contentHash(), value.computedContentHash());
      assertTrue(value.contentHashMatches());
      assertEquals(new RuleSetIdentity(vector.version(), vector.contentHash()), value.identity());
    }
    assertEquals(artifact(0, 1, Long.MAX_VALUE), MarketRuleSetArtifact.bootstrap());
  }

  @Test
  void rawClaimIsNotTrustedAndStrictIdentityGrammarIsLowercaseOnly() {
    MarketRuleSetArtifact malformed = new MarketRuleSetArtifact(1, 90, 110, "not-a-hash");
    MarketRuleSetArtifact mismatched =
        new MarketRuleSetArtifact(
            1, 90, 110, "sha256:0000000000000000000000000000000000000000000000000000000000000000");

    assertFalse(malformed.hasCanonicalContentHash());
    assertFalse(malformed.contentHashMatches());
    assertThrows(IllegalArgumentException.class, malformed::identity);
    assertTrue(mismatched.hasCanonicalContentHash());
    assertFalse(mismatched.contentHashMatches());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RuleSetIdentity(
                1, "sha256:DBB75B3983480A8ECE058736766411F80EB5C62E10EB24DE72B74853D5377F91"));
  }

  @Test
  void semanticValuesRejectUnrepresentableArtifactsBeforeAnyEngineCall() {
    assertThrows(IllegalArgumentException.class, () -> new RuleSetVersion(-1));
    assertThrows(IllegalArgumentException.class, () -> new ApplicationSequence(0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MarketRuleSetArtifact(
                MarketRuleSetArtifact.SCHEMA_VERSION,
                PlaceLimitOrderValidator.INSTRUMENT_ID,
                new RuleSetVersion(1),
                new PriceTicks(111),
                new PriceTicks(110),
                "not-a-hash"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MarketRuleSetArtifact(
                "matching.market-rule-set.v0",
                PlaceLimitOrderValidator.INSTRUMENT_ID,
                new RuleSetVersion(1),
                new PriceTicks(90),
                new PriceTicks(110),
                "not-a-hash"));
  }

  private static MarketRuleSetArtifact artifact(long version, long lower, long upper) {
    MarketRuleSetArtifact unhashed =
        new MarketRuleSetArtifact(
            version,
            lower,
            upper,
            "sha256:0000000000000000000000000000000000000000000000000000000000000000");
    return new MarketRuleSetArtifact(version, lower, upper, unhashed.computedContentHash());
  }

  private record HashVector(
      long version, long lowerInclusive, long upperInclusive, String contentHash) {}
}
