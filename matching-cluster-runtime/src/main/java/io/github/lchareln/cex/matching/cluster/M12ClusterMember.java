package io.github.lchareln.cex.matching.cluster;

import io.aeron.archive.Archive;
import io.aeron.archive.client.AeronArchive;
import io.aeron.cluster.ClusteredMediaDriver;
import io.aeron.cluster.ConsensusModule;
import io.aeron.cluster.ElectionState;
import io.aeron.cluster.VersionValidator;
import io.aeron.cluster.codecs.AdminRequestType;
import io.aeron.cluster.service.ClusteredServiceContainer;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.exceptions.AeronException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Predicate;
import org.agrona.ErrorHandler;
import org.agrona.concurrent.NoOpLock;

/** Owns one voting member's Media Driver, Archive, Consensus Module, and matching service. */
public final class M12ClusterMember implements AutoCloseable {
  private static final String LOCAL_ARCHIVE_CHANNEL = "aeron:ipc?term-length=64k";
  private static final VersionValidator EXACT_APP_VERSION =
      (configured, candidate) -> configured == candidate;
  private static final long MAX_PARK_NANOS = Duration.ofMillis(1).toNanos();
  private static final int MAX_DIAGNOSTIC_WARNINGS = 128;

  private final M12ThreeMemberConfig config;
  private final int memberId;
  private final boolean freshStart;
  private final long processId;
  private final long processStartedAtEpochMillis;
  private final ConcurrentLinkedQueue<Throwable> componentErrors;
  private final ArrayBlockingQueue<Throwable> diagnosticWarnings;
  private final AtomicLong droppedDiagnosticWarnings;
  private final M12ObservedClusteredService observedService;
  private final ClusteredMediaDriver clusteredMediaDriver;
  private final ClusteredServiceContainer serviceContainer;
  private final AtomicLong statusSequence = new AtomicLong();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final M11RuntimeStateCodec runtimeStateCodec = new M11RuntimeStateCodec();

  private M12ClusterMember(
      M12ThreeMemberConfig config,
      int memberId,
      boolean freshStart,
      ConcurrentLinkedQueue<Throwable> componentErrors,
      ArrayBlockingQueue<Throwable> diagnosticWarnings,
      AtomicLong droppedDiagnosticWarnings,
      M12ObservedClusteredService observedService,
      ClusteredMediaDriver clusteredMediaDriver,
      ClusteredServiceContainer serviceContainer) {
    this.config = config;
    this.memberId = memberId;
    this.freshStart = freshStart;
    this.processId = ProcessHandle.current().pid();
    this.processStartedAtEpochMillis =
        ProcessHandle.current().info().startInstant().orElseGet(Instant::now).toEpochMilli();
    this.componentErrors = componentErrors;
    this.diagnosticWarnings = diagnosticWarnings;
    this.droppedDiagnosticWarnings = droppedDiagnosticWarnings;
    this.observedService = observedService;
    this.clusteredMediaDriver = clusteredMediaDriver;
    this.serviceContainer = serviceContainer;
  }

  public static M12ClusterMember launch(
      M12ThreeMemberConfig config, int memberId, boolean freshStart) {
    Objects.requireNonNull(config, "config");
    config.requireMemberId(memberId);
    try {
      Files.createDirectories(config.memberRootDirectory(memberId));
    } catch (IOException failure) {
      throw new UncheckedIOException("cannot create M12 member root", failure);
    }

    ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
    ArrayBlockingQueue<Throwable> warnings = new ArrayBlockingQueue<>(MAX_DIAGNOSTIC_WARNINGS);
    AtomicLong droppedWarnings = new AtomicLong();
    ErrorHandler componentErrorHandler =
        failure -> {
          if (failure instanceof AeronException aeronFailure
              && aeronFailure.category() == AeronException.Category.WARN) {
            if (!warnings.offer(failure)) {
              droppedWarnings.incrementAndGet();
            }
          } else {
            errors.add(failure);
          }
        };
    M11ClusteredMatchingService matchingService =
        new M11ClusteredMatchingService(
            config.shardId(),
            M11ApplicationObserver.NO_OP,
            config.clientMessageTimeout(),
            errors::peek);
    M12ObservedClusteredService observedService = new M12ObservedClusteredService(matchingService);
    String aeronDirectory = config.memberAeronDirectory(memberId).toString();

    MediaDriver.Context mediaContext =
        new MediaDriver.Context()
            .aeronDirectoryName(aeronDirectory)
            .dirDeleteOnStart(true)
            .dirDeleteOnShutdown(true)
            .threadingMode(ThreadingMode.SHARED)
            .errorHandler(componentErrorHandler);
    AeronArchive.Context replicationArchiveContext =
        new AeronArchive.Context()
            .controlResponseChannel(config.replicationChannel())
            .errorHandler(componentErrorHandler);
    Archive.Context archiveContext =
        new Archive.Context()
            .aeronDirectoryName(aeronDirectory)
            .archiveDir(config.memberArchiveDirectory(memberId).toFile())
            .deleteArchiveOnStart(freshStart)
            .controlChannel(config.archiveControlChannel(memberId))
            .archiveClientContext(replicationArchiveContext)
            .localControlChannel(LOCAL_ARCHIVE_CHANNEL)
            .replicationChannel(config.replicationChannel())
            .recordingEventsEnabled(false)
            .errorHandler(componentErrorHandler);
    AeronArchive.Context localArchiveContext =
        new AeronArchive.Context()
            .lock(NoOpLock.INSTANCE)
            .controlRequestChannel(LOCAL_ARCHIVE_CHANNEL)
            .controlRequestStreamId(archiveContext.localControlStreamId())
            .controlResponseChannel(LOCAL_ARCHIVE_CHANNEL)
            .aeronDirectoryName(aeronDirectory)
            .errorHandler(componentErrorHandler);
    ConsensusModule.Context consensusContext =
        new ConsensusModule.Context()
            .aeronDirectoryName(aeronDirectory)
            .clusterId(config.clusterId())
            .clusterMemberId(memberId)
            .appointedLeaderId(M12ThreeMemberConfig.APPOINTED_LEADER_ID)
            .clusterMembers(config.clusterMembers())
            .clusterDir(config.memberClusterDirectory(memberId).toFile())
            .deleteDirOnStart(freshStart)
            .ingressChannel(config.ingressChannel())
            .consensusChannel(config.consensusChannel())
            .logChannel(config.logChannel())
            .replicationChannel(config.replicationChannel())
            .followerCatchupChannel(config.followerCatchupChannel())
            .leaderArchiveControlChannel(config.leaderArchiveControlChannel())
            .leaderHeartbeatIntervalNs(config.leaderHeartbeatInterval().toNanos())
            .leaderHeartbeatTimeoutNs(config.leaderHeartbeatTimeout().toNanos())
            .electionTimeoutNs(config.electionTimeout().toNanos())
            .startupCanvassTimeoutNs(config.startupCanvassTimeout().toNanos())
            .archiveContext(localArchiveContext.clone())
            .serviceCount(1)
            .appVersion(config.appVersion())
            .appVersionValidator(EXACT_APP_VERSION)
            .authorisationServiceSupplier(
                () ->
                    (protocolId, actionId, type, encodedPrincipal) ->
                        type == AdminRequestType.SNAPSHOT)
            .errorHandler(componentErrorHandler);
    ClusteredServiceContainer.Context serviceContext =
        new ClusteredServiceContainer.Context()
            .aeronDirectoryName(aeronDirectory)
            .clusterId(config.clusterId())
            .serviceId(0)
            .clusterDir(config.memberClusterDirectory(memberId).toFile())
            .archiveContext(localArchiveContext.clone())
            .clusteredService(observedService)
            .appVersion(config.appVersion())
            .appVersionValidator(EXACT_APP_VERSION)
            .errorHandler(componentErrorHandler);

    ClusteredMediaDriver driver =
        ClusteredMediaDriver.launch(mediaContext, archiveContext, consensusContext);
    try {
      ClusteredServiceContainer container = ClusteredServiceContainer.launch(serviceContext);
      return new M12ClusterMember(
          config,
          memberId,
          freshStart,
          errors,
          warnings,
          droppedWarnings,
          observedService,
          driver,
          container);
    } catch (RuntimeException | Error failure) {
      driver.close();
      throw failure;
    }
  }

  public M12ThreeMemberConfig config() {
    return config;
  }

  public int memberId() {
    return memberId;
  }

  public boolean freshStart() {
    return freshStart;
  }

  public List<Throwable> componentErrors() {
    List<Throwable> errors = new ArrayList<>(componentErrors);
    RuntimeException serviceFailure = observedService.delegate().observerFailure();
    if (serviceFailure != null) {
      errors.add(serviceFailure);
    }
    return List.copyOf(errors);
  }

  public List<Throwable> diagnosticWarnings() {
    return List.copyOf(diagnosticWarnings);
  }

  public long droppedDiagnosticWarnings() {
    return droppedDiagnosticWarnings.get();
  }

  public M12MemberStatus status() {
    requireOpen();
    M11RuntimeState application = observedService.delegate().stateImage();
    ConsensusModule.Context consensus = clusteredMediaDriver.consensusModule().context();
    long term =
        counterValue(consensus.leadershipTermIdCounter(), observedService.leadershipTermId());
    long commitPosition = counterValue(consensus.commitPositionCounter(), -1);
    String electionState = electionState(consensus);
    List<String> errors = componentErrors().stream().map(M12ClusterMember::errorSummary).toList();
    List<String> warnings =
        diagnosticWarnings().stream().map(M12ClusterMember::errorSummary).toList();
    return new M12MemberStatus(
        M12MemberStatus.SCHEMA,
        statusSequence.incrementAndGet(),
        processId,
        processStartedAtEpochMillis,
        System.currentTimeMillis(),
        config.clusterId(),
        memberId,
        M12ThreeMemberConfig.MEMBER_COUNT,
        M12ThreeMemberConfig.QUORUM_SIZE,
        M12ThreeMemberConfig.APPOINTED_LEADER_ID,
        freshStart,
        observedService.role(),
        electionState,
        term,
        commitPosition,
        observedService.logPosition(),
        application.nextApplicationSequence(),
        application.identityBindings().size(),
        application.commandState().semanticStateDigest(),
        runtimeStateCodec.identityTableDigest(application.identityBindings()),
        config.memberPortBase(memberId),
        config.rootDirectory().toString(),
        config.memberAeronDirectory(memberId).toString(),
        config.memberArchiveDirectory(memberId).toString(),
        config.memberClusterDirectory(memberId).toString(),
        errors,
        warnings,
        droppedDiagnosticWarnings());
  }

  public synchronized M12MemberStatus publishStatus() throws IOException {
    M12MemberStatus status = status();
    M12MemberStatusFile.write(config.memberStatusFile(memberId), status);
    return status;
  }

  public M12MemberStatus awaitStatus(Predicate<M12MemberStatus> condition, Duration timeout) {
    Objects.requireNonNull(condition, "condition");
    Objects.requireNonNull(timeout, "timeout");
    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must be positive");
    }
    long deadline = Math.addExact(System.nanoTime(), timeout.toNanos());
    while (true) {
      throwIfFailed();
      M12MemberStatus status = status();
      if (condition.test(status)) {
        return status;
      }
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0) {
        throw new IllegalStateException("M12 member condition was not reached before the deadline");
      }
      LockSupport.parkNanos(Math.min(MAX_PARK_NANOS, remaining));
      if (Thread.interrupted()) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted while awaiting M12 member status");
      }
    }
  }

  public M12MemberStatus awaitRole(String role, Duration timeout) {
    Objects.requireNonNull(role, "role");
    return awaitStatus(status -> role.equals(status.role()), timeout);
  }

  public void throwIfFailed() {
    List<Throwable> failures = componentErrors();
    if (!failures.isEmpty()) {
      throw new IllegalStateException("M12 member component failed closed", failures.getFirst());
    }
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    try {
      serviceContainer.close();
    } finally {
      clusteredMediaDriver.close();
    }
  }

  private void requireOpen() {
    if (closed.get()) {
      throw new IllegalStateException("M12 member is closed");
    }
  }

  private static long counterValue(io.aeron.Counter counter, long fallback) {
    return counter == null ? fallback : counter.get();
  }

  private static String electionState(ConsensusModule.Context consensus) {
    try {
      return consensus.electionStateCounter() == null
          ? "UNKNOWN"
          : ElectionState.get(consensus.electionStateCounter()).name();
    } catch (RuntimeException failure) {
      return "UNKNOWN";
    }
  }

  private static String errorSummary(Throwable failure) {
    String message = failure.getMessage();
    String summary =
        failure.getClass().getName() + (message == null || message.isBlank() ? "" : ": " + message);
    return summary.length() <= 4_096 ? summary : summary.substring(0, 4_096);
  }
}
