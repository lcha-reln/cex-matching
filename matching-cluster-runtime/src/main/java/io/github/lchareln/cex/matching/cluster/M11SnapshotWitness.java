package io.github.lchareln.cex.matching.cluster;

import java.util.Objects;

/** Accepted request, durable Aeron completion, and exact application bytes as separate facts. */
public record M11SnapshotWitness(
    M11SnapshotAdminAcceptance adminAcceptance,
    M11SnapshotCompletion completion,
    M11ApplicationSnapshotWitness applicationSnapshot) {
  public M11SnapshotWitness {
    Objects.requireNonNull(adminAcceptance, "adminAcceptance");
    Objects.requireNonNull(completion, "completion");
    Objects.requireNonNull(applicationSnapshot, "applicationSnapshot");
  }
}
