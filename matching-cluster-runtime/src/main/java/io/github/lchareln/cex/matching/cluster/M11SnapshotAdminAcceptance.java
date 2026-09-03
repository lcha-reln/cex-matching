package io.github.lchareln.cex.matching.cluster;

import io.aeron.cluster.codecs.AdminRequestType;
import io.aeron.cluster.codecs.AdminResponseCode;

/** Evidence that the leader accepted, but has not necessarily completed, a snapshot request. */
public record M11SnapshotAdminAcceptance(
    long correlationId,
    AdminRequestType requestType,
    AdminResponseCode responseCode,
    String message) {
  public M11SnapshotAdminAcceptance {
    if (correlationId <= 0
        || requestType != AdminRequestType.SNAPSHOT
        || responseCode != AdminResponseCode.OK) {
      throw new IllegalArgumentException("snapshot admin request was not accepted");
    }
  }
}
