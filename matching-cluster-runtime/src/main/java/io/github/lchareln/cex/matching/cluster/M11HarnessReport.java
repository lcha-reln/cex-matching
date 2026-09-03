package io.github.lchareln.cex.matching.cluster;

import java.util.Optional;

/** Explicitly separates ingress acceptance, apply, egress, snapshot acceptance, and completion. */
public record M11HarnessReport(
    long ingressOffersAccepted,
    long correlatedEgressResponses,
    long newBusinessApplications,
    long duplicateReplays,
    long rejectedApplications,
    long snapshotAdminAccepted,
    long snapshotsCompleted,
    long restarts,
    boolean restartDirectoriesPreserved,
    boolean completedSnapshotLoaded,
    Optional<M11SnapshotWitness> lastSnapshot,
    Optional<M11ApplicationSnapshotWitness> lastLoadedSnapshot,
    int componentErrorCount) {}
