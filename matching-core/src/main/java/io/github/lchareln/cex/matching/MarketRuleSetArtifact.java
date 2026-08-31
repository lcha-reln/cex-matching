package io.github.lchareln.cex.matching;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Immutable M05 order-entry price-band artifact with repository-owned canonical hashing. */
public record MarketRuleSetArtifact(
    String schemaVersion,
    String instrumentId,
    RuleSetVersion version,
    PriceTicks lowerInclusive,
    PriceTicks upperInclusive,
    String contentHash) {
  public static final String SCHEMA_VERSION = "matching.market-rule-set.v1";
  public static final String CANONICAL_FORMAT = "M05RS1";

  private static final MarketRuleSetArtifact BOOTSTRAP = createBootstrap();

  public MarketRuleSetArtifact {
    Objects.requireNonNull(schemaVersion, "schemaVersion");
    Objects.requireNonNull(instrumentId, "instrumentId");
    Objects.requireNonNull(version, "version");
    Objects.requireNonNull(lowerInclusive, "lowerInclusive");
    Objects.requireNonNull(upperInclusive, "upperInclusive");
    Objects.requireNonNull(contentHash, "contentHash");
    if (!SCHEMA_VERSION.equals(schemaVersion)) {
      throw new IllegalArgumentException("unsupported market rule-set schema");
    }
    if (!PlaceLimitOrderValidator.INSTRUMENT_ID.equals(instrumentId)) {
      throw new IllegalArgumentException("market rule set must target BTC-USDT");
    }
    if (lowerInclusive.value() > upperInclusive.value()) {
      throw new IllegalArgumentException("lowerInclusive must not exceed upperInclusive");
    }
  }

  public MarketRuleSetArtifact(
      long version, long lowerInclusive, long upperInclusive, String contentHash) {
    this(
        SCHEMA_VERSION,
        PlaceLimitOrderValidator.INSTRUMENT_ID,
        new RuleSetVersion(version),
        new PriceTicks(lowerInclusive),
        new PriceTicks(upperInclusive),
        contentHash);
  }

  /** The version-zero unbounded artifact that preserves the M00-M04 admission decision. */
  public static MarketRuleSetArtifact bootstrap() {
    return BOOTSTRAP;
  }

  public static RuleSetIdentity bootstrapIdentity() {
    return BOOTSTRAP.identity();
  }

  /** Exact M05RS1 UTF-8 bytes; the claimed content hash is deliberately excluded. */
  public byte[] canonicalBytes() {
    String canonical =
        CANONICAL_FORMAT
            + "\n"
            + "schemaVersion="
            + schemaVersion
            + "\n"
            + "instrumentId="
            + instrumentId
            + "\n"
            + "version="
            + version.value()
            + "\n"
            + "lowerInclusive="
            + lowerInclusive.value()
            + "\n"
            + "upperInclusive="
            + upperInclusive.value()
            + "\n";
    return canonical.getBytes(StandardCharsets.UTF_8);
  }

  /** Recomputes the canonical lowercase SHA-256 identity without trusting the claim. */
  public String computedContentHash() {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonicalBytes());
      return "sha256:" + HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("the Java runtime does not provide SHA-256", failure);
    }
  }

  public boolean hasCanonicalContentHash() {
    return RuleSetIdentity.isCanonicalContentHash(contentHash);
  }

  public boolean contentHashMatches() {
    return hasCanonicalContentHash() && contentHash.equals(computedContentHash());
  }

  /** Returns the claimed identity after its strict lexical form has been validated. */
  public RuleSetIdentity identity() {
    return new RuleSetIdentity(version, contentHash);
  }

  public boolean admits(PriceTicks priceTicks) {
    Objects.requireNonNull(priceTicks, "priceTicks");
    return priceTicks.value() >= lowerInclusive.value()
        && priceTicks.value() <= upperInclusive.value();
  }

  private static MarketRuleSetArtifact createBootstrap() {
    MarketRuleSetArtifact unhashed =
        new MarketRuleSetArtifact(
            SCHEMA_VERSION,
            PlaceLimitOrderValidator.INSTRUMENT_ID,
            new RuleSetVersion(0),
            new PriceTicks(1),
            new PriceTicks(Long.MAX_VALUE),
            "sha256:0000000000000000000000000000000000000000000000000000000000000000");
    return new MarketRuleSetArtifact(
        unhashed.schemaVersion,
        unhashed.instrumentId,
        unhashed.version,
        unhashed.lowerInclusive,
        unhashed.upperInclusive,
        unhashed.computedContentHash());
  }
}
