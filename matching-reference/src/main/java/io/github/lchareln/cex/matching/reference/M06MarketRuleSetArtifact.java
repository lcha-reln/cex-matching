package io.github.lchareln.cex.matching.reference;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Raw immutable M06 order-entry price-band artifact with repository-owned canonical bytes. */
public record M06MarketRuleSetArtifact(
    String schemaVersion,
    String instrumentId,
    BigInteger version,
    BigInteger lowerInclusive,
    BigInteger upperInclusive,
    String contentHash) {
  public static final String SCHEMA_VERSION = "matching.market-rule-set.v1";
  public static final String INSTRUMENT = "BTC-USDT";
  private static final String HASH_PREFIX = "sha256:";
  private static final BigInteger MAXIMUM = BigInteger.valueOf(Long.MAX_VALUE);

  public M06MarketRuleSetArtifact {
    Objects.requireNonNull(schemaVersion, "schemaVersion");
    Objects.requireNonNull(instrumentId, "instrumentId");
    Objects.requireNonNull(version, "version");
    Objects.requireNonNull(lowerInclusive, "lowerInclusive");
    Objects.requireNonNull(upperInclusive, "upperInclusive");
    Objects.requireNonNull(contentHash, "contentHash");
    if (!SCHEMA_VERSION.equals(schemaVersion)) {
      throw new IllegalArgumentException("unsupported market rule-set schema");
    }
    if (!INSTRUMENT.equals(instrumentId)) {
      throw new IllegalArgumentException("market rule set must target BTC-USDT");
    }
    if (version.signum() < 0 || version.compareTo(MAXIMUM) > 0) {
      throw new IllegalArgumentException("rule-set version must be a non-negative signed long");
    }
    if (lowerInclusive.signum() <= 0
        || lowerInclusive.compareTo(MAXIMUM) > 0
        || upperInclusive.signum() <= 0
        || upperInclusive.compareTo(MAXIMUM) > 0
        || lowerInclusive.compareTo(upperInclusive) > 0) {
      throw new IllegalArgumentException(
          "rule-set bounds must be an ordered positive-long interval");
    }
  }

  /** Creates an artifact whose claimed hash is computed from the inherited M05RS1 bytes. */
  public static M06MarketRuleSetArtifact canonical(
      BigInteger version, BigInteger lowerInclusive, BigInteger upperInclusive) {
    byte[] bytes =
        canonicalBytes(SCHEMA_VERSION, INSTRUMENT, version, lowerInclusive, upperInclusive);
    return new M06MarketRuleSetArtifact(
        SCHEMA_VERSION, INSTRUMENT, version, lowerInclusive, upperInclusive, hash(bytes));
  }

  /** The frozen version-zero unbounded entry band used by every fresh M06 model. */
  public static M06MarketRuleSetArtifact bootstrap() {
    return canonical(BigInteger.ZERO, BigInteger.ONE, BigInteger.valueOf(Long.MAX_VALUE));
  }

  /** Exact LF-terminated, UTF-8 M05RS1 bytes; the claimed content hash is deliberately excluded. */
  public byte[] canonicalBytes() {
    return canonicalBytes(schemaVersion, instrumentId, version, lowerInclusive, upperInclusive);
  }

  /**
   * Recomputes the canonical lowercase SHA-256 identity without trusting {@link #contentHash()}.
   */
  public String computedContentHash() {
    return hash(canonicalBytes());
  }

  public M06RuleSetIdentity identity() {
    return new M06RuleSetIdentity(version, contentHash);
  }

  public boolean contentHashHasCanonicalShape() {
    return M06RuleSetIdentity.isCanonicalContentHash(contentHash);
  }

  private static byte[] canonicalBytes(
      String schemaVersion,
      String instrumentId,
      BigInteger version,
      BigInteger lowerInclusive,
      BigInteger upperInclusive) {
    Objects.requireNonNull(schemaVersion, "schemaVersion");
    Objects.requireNonNull(instrumentId, "instrumentId");
    Objects.requireNonNull(version, "version");
    Objects.requireNonNull(lowerInclusive, "lowerInclusive");
    Objects.requireNonNull(upperInclusive, "upperInclusive");
    String text =
        new StringBuilder()
            .append("M05RS1\n")
            .append("schemaVersion=")
            .append(schemaVersion)
            .append('\n')
            .append("instrumentId=")
            .append(instrumentId)
            .append('\n')
            .append("version=")
            .append(version)
            .append('\n')
            .append("lowerInclusive=")
            .append(lowerInclusive)
            .append('\n')
            .append("upperInclusive=")
            .append(upperInclusive)
            .append('\n')
            .toString();
    return text.getBytes(StandardCharsets.UTF_8);
  }

  private static String hash(byte[] bytes) {
    try {
      return HASH_PREFIX
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}
