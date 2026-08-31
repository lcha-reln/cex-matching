package io.github.lchareln.cex.matching;

import java.util.Objects;

/** Content-addressed identity of one immutable market rule set. */
public record RuleSetIdentity(RuleSetVersion version, String contentHash) {
  private static final int PREFIX_LENGTH = "sha256:".length();
  private static final int DIGEST_HEX_LENGTH = 64;

  public RuleSetIdentity {
    Objects.requireNonNull(version, "version");
    Objects.requireNonNull(contentHash, "contentHash");
    if (!isCanonicalContentHash(contentHash)) {
      throw new IllegalArgumentException(
          "contentHash must be lowercase sha256:<64 hex characters>");
    }
  }

  public RuleSetIdentity(long version, String contentHash) {
    this(new RuleSetVersion(version), contentHash);
  }

  /** Returns whether the raw value is the only hash spelling accepted by M05. */
  public static boolean isCanonicalContentHash(String value) {
    if (value == null
        || value.length() != PREFIX_LENGTH + DIGEST_HEX_LENGTH
        || !value.startsWith("sha256:")) {
      return false;
    }
    for (int index = PREFIX_LENGTH; index < value.length(); index++) {
      char character = value.charAt(index);
      if (!((character >= '0' && character <= '9') || (character >= 'a' && character <= 'f'))) {
        return false;
      }
    }
    return true;
  }
}
