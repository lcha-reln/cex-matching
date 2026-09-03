package io.github.lchareln.cex.matching.cluster;

import io.aeron.archive.Archive;
import io.aeron.archive.client.AeronArchive;
import io.aeron.cluster.ClusterControl;
import io.aeron.cluster.ClusteredMediaDriver;
import io.aeron.cluster.ConsensusModule;
import io.aeron.cluster.RecordingLog;
import io.aeron.cluster.VersionValidator;
import io.aeron.cluster.codecs.AdminRequestType;
import io.aeron.cluster.service.ClusteredServiceContainer;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.agrona.concurrent.NoOpLock;

/** Owns a real Media Driver, Archive, Consensus Module, and service container. */
public final class M11SingleNodeCluster implements AutoCloseable {
  private static final String LOCAL_ARCHIVE_CHANNEL = "aeron:ipc?term-length=64k";
  private static final VersionValidator EXACT_APP_VERSION =
      (configured, candidate) -> configured == candidate;

  private final M11SingleNodeConfig config;
  private final ConcurrentLinkedQueue<Throwable> componentErrors;
  private final M11ClusteredMatchingService service;
  private final ClusteredMediaDriver clusteredMediaDriver;
  private final ClusteredServiceContainer serviceContainer;

  private M11SingleNodeCluster(
      M11SingleNodeConfig config,
      ConcurrentLinkedQueue<Throwable> componentErrors,
      M11ClusteredMatchingService service,
      ClusteredMediaDriver clusteredMediaDriver,
      ClusteredServiceContainer serviceContainer) {
    this.config = config;
    this.componentErrors = componentErrors;
    this.service = service;
    this.clusteredMediaDriver = clusteredMediaDriver;
    this.serviceContainer = serviceContainer;
  }

  public static M11SingleNodeCluster launch(M11SingleNodeConfig config, boolean freshStart) {
    return launch(config, freshStart, M11ApplicationObserver.NO_OP);
  }

  public static M11SingleNodeCluster launch(
      M11SingleNodeConfig config, boolean freshStart, M11ApplicationObserver observer) {
    Objects.requireNonNull(config, "config");
    ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
    M11ClusteredMatchingService service =
        new M11ClusteredMatchingService(config.shardId(), observer);
    String nodeAeronDirectory = config.nodeAeronDirectory().toString();

    MediaDriver.Context mediaContext =
        new MediaDriver.Context()
            .aeronDirectoryName(nodeAeronDirectory)
            .dirDeleteOnStart(true)
            .dirDeleteOnShutdown(true)
            .threadingMode(ThreadingMode.SHARED)
            .errorHandler(errors::add);
    AeronArchive.Context replicationArchiveContext =
        new AeronArchive.Context().controlResponseChannel("aeron:udp?endpoint=127.0.0.1:0");
    Archive.Context archiveContext =
        new Archive.Context()
            .aeronDirectoryName(nodeAeronDirectory)
            .archiveDir(config.archiveDirectory().toFile())
            .deleteArchiveOnStart(freshStart)
            .controlChannel(config.archiveControlChannel())
            .archiveClientContext(replicationArchiveContext)
            .localControlChannel(LOCAL_ARCHIVE_CHANNEL)
            .replicationChannel("aeron:udp?endpoint=127.0.0.1:0")
            .recordingEventsEnabled(false)
            .errorHandler(errors::add);
    AeronArchive.Context localArchiveContext =
        new AeronArchive.Context()
            .lock(NoOpLock.INSTANCE)
            .controlRequestChannel(LOCAL_ARCHIVE_CHANNEL)
            .controlRequestStreamId(archiveContext.localControlStreamId())
            .controlResponseChannel(LOCAL_ARCHIVE_CHANNEL)
            .aeronDirectoryName(nodeAeronDirectory)
            .errorHandler(errors::add);
    ConsensusModule.Context consensusContext =
        new ConsensusModule.Context()
            .aeronDirectoryName(nodeAeronDirectory)
            .clusterId(config.clusterId())
            .clusterMemberId(0)
            .appointedLeaderId(0)
            .clusterMembers(config.clusterMembers())
            .clusterDir(config.clusterDirectory().toFile())
            .deleteDirOnStart(freshStart)
            .ingressChannel("aeron:udp?term-length=64k")
            .replicationChannel("aeron:udp?endpoint=127.0.0.1:0")
            .archiveContext(localArchiveContext.clone())
            .serviceCount(1)
            .appVersion(config.appVersion())
            .appVersionValidator(EXACT_APP_VERSION)
            .authorisationServiceSupplier(
                () ->
                    (protocolId, actionId, type, encodedPrincipal) ->
                        type == AdminRequestType.SNAPSHOT)
            .errorHandler(errors::add);
    ClusteredServiceContainer.Context serviceContext =
        new ClusteredServiceContainer.Context()
            .aeronDirectoryName(nodeAeronDirectory)
            .clusterId(config.clusterId())
            .serviceId(0)
            .clusterDir(config.clusterDirectory().toFile())
            .archiveContext(localArchiveContext.clone())
            .clusteredService(service)
            .appVersion(config.appVersion())
            .appVersionValidator(EXACT_APP_VERSION)
            .errorHandler(errors::add);

    ClusteredMediaDriver driver =
        ClusteredMediaDriver.launch(mediaContext, archiveContext, consensusContext);
    try {
      ClusteredServiceContainer container = ClusteredServiceContainer.launch(serviceContext);
      return new M11SingleNodeCluster(config, errors, service, driver, container);
    } catch (RuntimeException | Error failure) {
      driver.close();
      throw failure;
    }
  }

  public M11MatchingClusterClient connectClient() {
    return M11MatchingClusterClient.connect(config);
  }

  public M11ClusteredMatchingService service() {
    return service;
  }

  public List<Throwable> componentErrors() {
    List<Throwable> errors = new ArrayList<>(componentErrors);
    RuntimeException observerFailure = service.observerFailure();
    if (observerFailure != null) {
      errors.add(observerFailure);
    }
    return List.copyOf(errors);
  }

  public M11SingleNodeConfig config() {
    return config;
  }

  public M11SnapshotBaseline snapshotBaseline() {
    ConsensusModule.Context context = clusteredMediaDriver.consensusModule().context();
    RecordingLog.Entry service = context.recordingLog().getLatestSnapshot(0);
    RecordingLog.Entry consensus =
        context.recordingLog().getLatestSnapshot(ConsensusModule.Configuration.SERVICE_ID);
    return new M11SnapshotBaseline(
        context.snapshotCounter().get(),
        service == null ? -1 : service.recordingId,
        consensus == null ? -1 : consensus.recordingId);
  }

  public M11SnapshotCompletion awaitSnapshotCompletion(
      M11SnapshotBaseline baseline, Duration timeout) {
    Objects.requireNonNull(baseline, "baseline");
    Objects.requireNonNull(timeout, "timeout");
    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must be positive");
    }
    long deadline = Math.addExact(System.nanoTime(), timeout.toNanos());
    ConsensusModule.Context context = clusteredMediaDriver.consensusModule().context();
    while (true) {
      Throwable componentFailure = componentErrors.peek();
      if (componentFailure != null) {
        throw new IllegalStateException(
            "M11 cluster failed while taking snapshot", componentFailure);
      }
      long completionCount = context.snapshotCounter().get();
      ClusterControl.ToggleState toggle =
          ClusterControl.ToggleState.get(context.controlToggleCounter());
      if (completionCount > baseline.completionCount()
          && toggle == ClusterControl.ToggleState.NEUTRAL) {
        RecordingLog.Entry serviceEntry = context.recordingLog().getLatestSnapshot(0);
        RecordingLog.Entry consensusEntry =
            context.recordingLog().getLatestSnapshot(ConsensusModule.Configuration.SERVICE_ID);
        if (serviceEntry != null
            && consensusEntry != null
            && serviceEntry.recordingId != baseline.serviceRecordingId()
            && consensusEntry.recordingId != baseline.consensusRecordingId()
            && serviceEntry.leadershipTermId == consensusEntry.leadershipTermId
            && serviceEntry.logPosition == consensusEntry.logPosition) {
          return new M11SnapshotCompletion(
              baseline.completionCount(),
              completionCount,
              toggle,
              baseline.serviceRecordingId(),
              baseline.consensusRecordingId(),
              serviceEntry.leadershipTermId,
              consensusEntry.leadershipTermId,
              serviceEntry.logPosition,
              consensusEntry.logPosition,
              serviceEntry.recordingId,
              consensusEntry.recordingId);
        }
      }
      if (System.nanoTime() >= deadline) {
        throw new IllegalStateException("M11 snapshot did not reach durable completion");
      }
      Thread.onSpinWait();
    }
  }

  @Override
  public void close() {
    try {
      serviceContainer.close();
    } finally {
      clusteredMediaDriver.close();
    }
  }
}
