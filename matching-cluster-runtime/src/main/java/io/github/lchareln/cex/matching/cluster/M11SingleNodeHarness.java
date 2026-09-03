package io.github.lchareln.cex.matching.cluster;

import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Restart-capable production harness used by local teaching and deterministic acceptance. */
public final class M11SingleNodeHarness implements AutoCloseable {
  private final M11SingleNodeConfig config;
  private final M11ApplicationObserver observer;
  private final ConcurrentLinkedQueue<M11ServiceObservation> observations =
      new ConcurrentLinkedQueue<>();
  private final ConcurrentLinkedQueue<Throwable> archivedErrors = new ConcurrentLinkedQueue<>();
  private M11SingleNodeCluster node;
  private M11MatchingClusterClient client;
  private long archivedIngressOffers;
  private long archivedEgressResponses;
  private long snapshotAdminAccepted;
  private long snapshotsCompleted;
  private long restarts;
  private boolean restartDirectoriesPreserved;
  private boolean completedSnapshotLoaded;
  private M11SnapshotWitness lastSnapshot;
  private M11ApplicationSnapshotWitness lastLoadedSnapshot;

  private M11SingleNodeHarness(M11SingleNodeConfig config, M11ApplicationObserver observer) {
    this.config = config;
    this.observer = observer;
  }

  public static M11SingleNodeHarness launchFresh(M11SingleNodeConfig config) {
    return launchFresh(config, M11ApplicationObserver.NO_OP);
  }

  public static M11SingleNodeHarness launchFresh(
      M11SingleNodeConfig config, M11ApplicationObserver observer) {
    M11SingleNodeHarness harness =
        new M11SingleNodeHarness(
            Objects.requireNonNull(config, "config"), Objects.requireNonNull(observer, "observer"));
    harness.start(true);
    return harness;
  }

  public M11CommandResponse submit(M11CommandRequest request, Duration timeout) {
    return requireClient().submit(request, timeout);
  }

  public M11SnapshotWitness takeSnapshot(Duration timeout) {
    Objects.requireNonNull(timeout, "timeout");
    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must be positive");
    }
    long deadline = Math.addExact(System.nanoTime(), timeout.toNanos());
    M11SnapshotBaseline baseline = requireNode().snapshotBaseline();
    M11SnapshotAdminAcceptance accepted = requireClient().requestSnapshot(remaining(deadline));
    snapshotAdminAccepted++;
    M11SnapshotCompletion completion =
        requireNode().awaitSnapshotCompletion(baseline, remaining(deadline));
    M11ApplicationSnapshotWitness application = requireNode().service().lastWrittenSnapshot();
    if (application == null) {
      throw new IllegalStateException("snapshot completed without an application write witness");
    }
    snapshotsCompleted++;
    lastSnapshot = new M11SnapshotWitness(accepted, completion, application);
    return lastSnapshot;
  }

  public void restartFromSnapshot() {
    if (lastSnapshot == null) {
      throw new IllegalStateException("a completed snapshot is required before restart");
    }
    boolean existedBefore =
        Files.isDirectory(config.archiveDirectory())
            && Files.isRegularFile(config.clusterDirectory().resolve("recording.log"));
    closeComponents();
    boolean existedAfterClose =
        Files.isDirectory(config.archiveDirectory())
            && Files.isRegularFile(config.clusterDirectory().resolve("recording.log"));
    start(false);
    boolean existedAfterStart =
        Files.isDirectory(config.archiveDirectory())
            && Files.isRegularFile(config.clusterDirectory().resolve("recording.log"));
    restartDirectoriesPreserved = existedBefore && existedAfterClose && existedAfterStart;
    lastLoadedSnapshot = requireNode().service().lastLoadedSnapshot();
    completedSnapshotLoaded =
        lastLoadedSnapshot != null && lastLoadedSnapshot.equals(lastSnapshot.applicationSnapshot());
    if (!restartDirectoriesPreserved || !completedSnapshotLoaded) {
      throw new IllegalStateException("restart did not load the completed M11 snapshot");
    }
    restarts++;
  }

  public M11RuntimeState stateImage() {
    return requireNode().service().stateImage();
  }

  public String semanticStateDigest() {
    return requireNode().service().semanticStateDigest();
  }

  public List<M11ServiceObservation> observations() {
    return List.copyOf(observations);
  }

  public List<Throwable> componentErrors() {
    List<Throwable> errors = new ArrayList<>(archivedErrors);
    if (node != null) {
      errors.addAll(node.componentErrors());
    }
    if (client != null) {
      errors.addAll(client.componentErrors());
    }
    return List.copyOf(errors);
  }

  public M11HarnessReport report() {
    long ingress = archivedIngressOffers + (client == null ? 0 : client.ingressOffersAccepted());
    long egress = archivedEgressResponses + (client == null ? 0 : client.egressResponsesDecoded());
    long applied =
        observations.stream()
            .filter(
                observation ->
                    observation.applicationResult().response().status()
                        == M11ResponseStatus.NEW_APPLIED)
            .count();
    long duplicates =
        observations.stream()
            .filter(
                observation ->
                    observation.applicationResult().response().status()
                        == M11ResponseStatus.DUPLICATE_REPLAYED)
            .count();
    long rejected =
        observations.stream()
            .filter(
                observation ->
                    observation.applicationResult().response().status()
                        == M11ResponseStatus.REJECTED)
            .count();
    return new M11HarnessReport(
        ingress,
        egress,
        applied,
        duplicates,
        rejected,
        snapshotAdminAccepted,
        snapshotsCompleted,
        restarts,
        restartDirectoriesPreserved,
        completedSnapshotLoaded,
        java.util.Optional.ofNullable(lastSnapshot),
        java.util.Optional.ofNullable(lastLoadedSnapshot),
        componentErrors().size());
  }

  @Override
  public void close() {
    closeComponents();
  }

  private void start(boolean freshStart) {
    node =
        M11SingleNodeCluster.launch(
            config,
            freshStart,
            observation -> {
              observations.add(observation);
              observer.onApplication(observation);
            });
    try {
      client = node.connectClient();
    } catch (RuntimeException | Error failure) {
      node.close();
      node = null;
      throw failure;
    }
  }

  private void closeComponents() {
    RuntimeException closeFailure = null;
    if (client != null) {
      M11MatchingClusterClient closingClient = client;
      client = null;
      archivedIngressOffers += closingClient.ingressOffersAccepted();
      archivedEgressResponses += closingClient.egressResponsesDecoded();
      try {
        closingClient.close();
      } catch (RuntimeException failure) {
        archivedErrors.add(failure);
        closeFailure = failure;
      }
      archivedErrors.addAll(closingClient.componentErrors());
    }
    if (node != null) {
      M11SingleNodeCluster closingNode = node;
      node = null;
      try {
        closingNode.close();
      } catch (RuntimeException failure) {
        archivedErrors.add(failure);
        if (closeFailure == null) {
          closeFailure = failure;
        } else {
          closeFailure.addSuppressed(failure);
        }
      }
      archivedErrors.addAll(closingNode.componentErrors());
    }
    if (closeFailure != null) {
      throw closeFailure;
    }
  }

  private M11SingleNodeCluster requireNode() {
    if (node == null) {
      throw new IllegalStateException("M11 harness is closed");
    }
    return node;
  }

  private M11MatchingClusterClient requireClient() {
    if (client == null) {
      throw new IllegalStateException("M11 harness is closed");
    }
    return client;
  }

  private static Duration remaining(long deadline) {
    long remaining = deadline - System.nanoTime();
    if (remaining <= 0) {
      throw new IllegalStateException("M11 harness operation timed out");
    }
    return Duration.ofNanos(remaining);
  }
}
