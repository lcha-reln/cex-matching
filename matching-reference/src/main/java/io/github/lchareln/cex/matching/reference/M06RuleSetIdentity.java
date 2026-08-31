package io.github.lchareln.cex.matching.reference;

import java.math.BigInteger;
import java.util.Objects;
import java.util.regex.Pattern;

/** Content-addressed rule identity owned by the independent M06 reference model. */
public record M06RuleSetIdentity(BigInteger version, String contentHash) {
  private static final BigInteger MAXIMUM = BigInteger.valueOf(Long.MAX_VALUE);
  private static final Pattern CONTENT_HASH = Pattern.compile("sha256:[a-f0-9]{64}");

  public M06RuleSetIdentity {
    Objects.requireNonNull(version, "version");
    Objects.requireNonNull(contentHash, "contentHash");
    if (version.signum() < 0 || version.compareTo(MAXIMUM) > 0) {
      throw new IllegalArgumentException("rule-set version must be a non-negative signed long");
    }
    if (!isCanonicalContentHash(contentHash)) {
      throw new IllegalArgumentException(
          "contentHash must be lowercase sha256:<64 hex characters>");
    }
  }

  /**
   * True for every successfully constructed identity; useful to state model invariants directly.
   */
  public boolean hasCanonicalShape() {
    return isCanonicalContentHash(contentHash);
  }

  public static boolean isCanonicalContentHash(String value) {
    return value != null && CONTENT_HASH.matcher(value).matches();
  }
}
