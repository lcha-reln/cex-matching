package io.github.lchareln.cex.matching.cluster;

import java.util.Objects;

/**
 * Ephemeral Aeron authority observed by one client generation.
 *
 * <p>None of these fields belongs to durable command identity or semantic matching state.
 */
public record M12TransportAuthority(
    long clientGeneration, long clusterSessionId, long leadershipTermId, int leaderMemberId) {
  public M12TransportAuthority {
    if (clientGeneration <= 0) {
      throw new IllegalArgumentException("clientGeneration must be positive");
    }
    if (clusterSessionId < 0 || leadershipTermId < 0 || leaderMemberId < 0) {
      throw new IllegalArgumentException("Aeron authority values must be non-negative");
    }
  }

  public boolean isLaterTermThan(M12TransportAuthority other) {
    Objects.requireNonNull(other, "other");
    if (clientGeneration != other.clientGeneration) {
      throw new IllegalArgumentException(
          "cannot compare leadership terms across client generations");
    }
    return leadershipTermId > other.leadershipTermId;
  }
}
