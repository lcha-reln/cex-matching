package io.github.lchareln.cex.matching.local;

import io.github.lchareln.cex.matching.MatchingStateImage;
import java.util.Objects;

/** Complete matching-core adapter state at one applied command boundary. */
record CommandApplierState(
    MatchingStateImage matchingState, String transcriptDigest, String semanticStateDigest) {
  CommandApplierState {
    Objects.requireNonNull(matchingState, "matchingState");
    requireSha256(transcriptDigest, "transcriptDigest");
    requireSha256(semanticStateDigest, "semanticStateDigest");
  }

  private static void requireSha256(String value, String field) {
    Objects.requireNonNull(value, field);
    if (!value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(field + " must be lowercase SHA-256");
    }
  }
}
