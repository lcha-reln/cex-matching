package io.github.lchareln.cex.matching.cluster;

import io.github.lchareln.cex.matching.local.CanonicalResult;
import java.util.Objects;
import java.util.Optional;

/** Bounded response plus a non-wire observation of the full deterministic result. */
public record M11ApplicationResult(
    M11CommandResponse response, Optional<CanonicalResult> fullResult) {
  public M11ApplicationResult {
    Objects.requireNonNull(response, "response");
    fullResult = Objects.requireNonNull(fullResult, "fullResult");
    if ((response.status() == M11ResponseStatus.REJECTED) == fullResult.isPresent()) {
      throw new IllegalArgumentException("response and full-result observation disagree");
    }
  }
}
