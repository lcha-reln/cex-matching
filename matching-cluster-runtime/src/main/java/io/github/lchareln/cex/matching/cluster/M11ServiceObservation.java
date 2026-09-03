package io.github.lchareln.cex.matching.cluster;

import java.util.Objects;

/** Non-influencing observation; runtime metadata is kept outside business response/state. */
public record M11ServiceObservation(
    long clusterSessionId,
    long clusterTimestamp,
    long clusterLogPosition,
    M11ApplicationResult applicationResult) {
  public M11ServiceObservation {
    Objects.requireNonNull(applicationResult, "applicationResult");
  }
}
