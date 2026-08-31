package io.github.lchareln.cex.matching.testkit;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Testkit-owned M05RS1 encoder used for frozen vectors and generated artifacts. */
final class M05RuleSetCanonical {
  static final String SCHEMA = "matching.market-rule-set.v1";
  static final String INSTRUMENT = "BTC-USDT";
  static final M05Command.Artifact BOOTSTRAP =
      artifact(BigInteger.ZERO, BigInteger.ONE, BigInteger.valueOf(Long.MAX_VALUE));

  private M05RuleSetCanonical() {}

  static M05Command.Artifact artifact(
      BigInteger version, BigInteger lowerInclusive, BigInteger upperInclusive) {
    String hash = contentHash(version, lowerInclusive, upperInclusive);
    return new M05Command.Artifact(
        SCHEMA, INSTRUMENT, version, lowerInclusive, upperInclusive, hash);
  }

  static byte[] bytes(BigInteger version, BigInteger lowerInclusive, BigInteger upperInclusive) {
    String text =
        "M05RS1\n"
            + "schemaVersion="
            + SCHEMA
            + "\n"
            + "instrumentId="
            + INSTRUMENT
            + "\n"
            + "version="
            + version
            + "\n"
            + "lowerInclusive="
            + lowerInclusive
            + "\n"
            + "upperInclusive="
            + upperInclusive
            + "\n";
    return text.getBytes(StandardCharsets.UTF_8);
  }

  static String contentHash(
      BigInteger version, BigInteger lowerInclusive, BigInteger upperInclusive) {
    try {
      return "sha256:"
          + HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(bytes(version, lowerInclusive, upperInclusive)));
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("the Java runtime does not provide SHA-256", failure);
    }
  }

  static boolean matches(M05Command.Artifact artifact) {
    return SCHEMA.equals(artifact.schemaVersion())
        && INSTRUMENT.equals(artifact.instrumentId())
        && artifact.lowerInclusive().signum() > 0
        && artifact.upperInclusive().compareTo(artifact.lowerInclusive()) >= 0
        && artifact
            .contentHash()
            .equals(
                contentHash(
                    artifact.version(), artifact.lowerInclusive(), artifact.upperInclusive()));
  }
}
