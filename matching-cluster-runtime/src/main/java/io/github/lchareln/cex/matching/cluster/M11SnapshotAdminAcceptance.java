package io.github.lchareln.cex.matching.cluster;

/** Evidence that the leader accepted, but has not necessarily completed, a snapshot request. */
public record M11SnapshotAdminAcceptance(
    long correlationId,
    M11SnapshotAdminRequestType requestType,
    M11SnapshotAdminResponseCode responseCode,
    String message) {
  public M11SnapshotAdminAcceptance {
    if (correlationId <= 0
        || requestType != M11SnapshotAdminRequestType.SNAPSHOT
        || responseCode != M11SnapshotAdminResponseCode.OK) {
      throw new IllegalArgumentException("snapshot admin request was not accepted");
    }
  }
}
