package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.cluster.M12ArchiveMarkFileLiveness;
import io.github.lchareln.cex.matching.cluster.M12ClusterMemberMain;
import io.github.lchareln.cex.matching.cluster.M12MemberStatus;
import io.github.lchareln.cex.matching.cluster.M12MemberStatusFile;
import io.github.lchareln.cex.matching.cluster.M12ThreeMemberConfig;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URISyntaxException;
import java.nio.channels.DatagramChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** External owner of the three real member JVMs used by the M12 fault schedule. */
final class M12ThreeMemberProcessHarness implements AutoCloseable {
  private static final int MAX_LOG_SAMPLE_BYTES = 16 * 1024;
  private static final int MAX_DIAGNOSTIC_LOG_SAMPLE_BYTES = 1_024;
  private static final long POLL_PARK_NANOS = 250_000L;
  private static final long RESTART_POLL_PARK_NANOS = 1_000_000L;
  private static final int STATUS_FRESHNESS_INTERVALS = 10;
  private static final List<String> CHILD_RUNTIME_CLASSES =
      List.of(
          "io.github.lchareln.cex.matching.cluster.M12ClusterMemberMain",
          "io.github.lchareln.cex.matching.local.M08Command",
          "io.github.lchareln.cex.matching.PlaceLimitOrder",
          "io.aeron.Aeron",
          "io.aeron.driver.MediaDriver",
          "io.aeron.archive.Archive",
          "io.aeron.cluster.ConsensusModule",
          "org.agrona.DirectBuffer");
  private static volatile String lastChildClasspathStrategy = "UNRESOLVED";

  private final M12ThreeMemberConfig config;
  private final Map<Integer, MemberProcess> members = new LinkedHashMap<>();
  private final List<MemberProcess> processStarts = new ArrayList<>();
  private final List<StoppedMember> stopped = new ArrayList<>();
  private final List<RestartSafetyWitness> restartSafetyWitnesses = new ArrayList<>();
  private final List<StableSnapshotWitness> stabilityWitnesses = new ArrayList<>();
  private final long ownerProcessId = ProcessHandle.current().pid();
  private List<M12MemberStatus> lastStableStatuses = List.of();
  private int starts;
  private int forcedStops;
  private boolean closed;
  private boolean teardownComplete;

  M12ThreeMemberProcessHarness(M12ThreeMemberConfig config) {
    this.config = Objects.requireNonNull(config, "config");
  }

  static int selectAvailablePortBase(int preferred, int attempts) {
    if (attempts <= 0) {
      throw new IllegalArgumentException("attempts must be positive");
    }
    for (int attempt = 0; attempt < attempts; attempt++) {
      int candidate = preferred + attempt * 40;
      if (candidate > 65_500) {
        candidate = 20_000 + attempt * 40;
      }
      M12ThreeMemberConfig probe =
          M12ThreeMemberConfig.defaults(Path.of("port-probe"), 1, candidate);
      if (portsAvailable(probe)) {
        return candidate;
      }
    }
    throw new IllegalStateException("no complete M12 UDP port block is available");
  }

  void launchFresh(Duration timeout) {
    requireOpen();
    if (!members.isEmpty()) {
      throw new IllegalStateException("members are already running");
    }
    M12InfrastructurePreconditions.requireStaticLaunchTopology(
        M12ThreeMemberConfig.MEMBER_COUNT, List.of(0, 1, 2), config.allFixedUdpPorts().size());
    deleteOwnedRoot(config.rootDirectory());
    for (int memberId = 0; memberId < M12ThreeMemberConfig.MEMBER_COUNT; memberId++) {
      startMember(memberId, true);
    }
    await(
        "three fresh member processes",
        timeout,
        () -> statusesIfReady(Set.of(0, 1, 2)).isPresent());
  }

  void startMember(int memberId, boolean freshStart) {
    requireOpen();
    config.requireMemberId(memberId);
    MemberProcess existing = members.get(memberId);
    if (existing != null && existing.process().isAlive()) {
      throw new IllegalStateException("member is already running: " + memberId);
    }
    if (!freshStart) {
      awaitArchiveMarkFileInactive(memberId);
    }
    Path diagnostics = config.memberRootDirectory(memberId).resolve("diagnostics");
    try {
      Files.createDirectories(diagnostics);
      Files.deleteIfExists(config.memberStatusFile(memberId));
      List<String> command = new ArrayList<>();
      command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
      command.add("--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED");
      command.add("-cp");
      command.add(childClasspath());
      command.add(M12ClusterMemberMain.class.getName());
      command.addAll(config.memberProcessArguments(memberId, freshStart));
      Path stdout = diagnostics.resolve("stdout-" + starts + ".log");
      Path stderr = diagnostics.resolve("stderr-" + starts + ".log");
      Process process =
          new ProcessBuilder(command)
              .directory(config.rootDirectory().toFile())
              .redirectOutput(stdout.toFile())
              .redirectError(stderr.toFile())
              .start();
      starts++;
      MemberProcess started =
          new MemberProcess(memberId, process, freshStart, stdout, stderr, System.nanoTime());
      members.put(memberId, started);
      processStarts.add(started);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot start M12 member " + memberId, failure);
    }
  }

  StoppedMember forceStop(int memberId, M12MemberStatus stablePreStopWitness, Duration timeout) {
    requireOpen();
    Objects.requireNonNull(stablePreStopWitness, "stablePreStopWitness");
    MemberProcess member = requireRunning(memberId);
    M12MemberStatus preStop = statusOrThrow(memberId);
    if (stablePreStopWitness.memberId() != memberId
        || stablePreStopWitness.processId() != member.process().pid()
        || preStop.memberId() != memberId
        || preStop.processId() != member.process().pid()
        || preStop.statusSequence() < stablePreStopWitness.statusSequence()
        || !preStop.role().equals(stablePreStopWitness.role())
        || !preStop.electionState().equals(stablePreStopWitness.electionState())
        || preStop.leadershipTermId() != stablePreStopWitness.leadershipTermId()
        || preStop.commitPosition() != stablePreStopWitness.commitPosition()
        || preStop.logPosition() != stablePreStopWitness.logPosition()
        || preStop.nextApplicationSequence() != stablePreStopWitness.nextApplicationSequence()
        || preStop.identityResultCount() != stablePreStopWitness.identityResultCount()
        || !preStop.semanticStateDigest().equals(stablePreStopWitness.semanticStateDigest())
        || !preStop.identityResultDigest().equals(stablePreStopWitness.identityResultDigest())
        || !preStop.healthy()
        || !"CLOSED".equals(preStop.electionState())) {
      throw new IllegalStateException(
          "member authority changed between the stable pre-stop witness and fault injection: "
              + memberId);
    }
    member.intentionalStop(true);
    ProcessHandle handle = member.process().toHandle();
    boolean requested = handle.destroyForcibly();
    try {
      if (!member.process().waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
        throw new IllegalStateException(
            "member did not exit after forced stop: "
                + memberId
                + "; diagnostics="
                + diagnosticSummary(member));
      }
      if (member.process().isAlive()) {
        throw new IllegalStateException(
            "member remained alive after forced-stop wait: "
                + memberId
                + "; diagnostics="
                + diagnosticSummary(member));
      }
      int exit = member.process().exitValue();
      members.remove(memberId);
      forcedStops++;
      StoppedMember observation =
          new StoppedMember(
              memberId, handle.pid(), requested, exit, true, System.nanoTime(), preStop);
      stopped.add(observation);
      return observation;
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("member stop interrupted", failure);
    }
  }

  M12MemberStatus awaitInitialTopology(Duration timeout) {
    return awaitSingleLeader(Set.of(0, 1, 2), -1, -1, timeout);
  }

  M12MemberStatus awaitReplacementLeader(
      Set<Integer> activeMemberIds, int previousLeader, long previousTerm, Duration timeout) {
    return awaitSingleLeader(activeMemberIds, previousLeader, previousTerm, timeout);
  }

  M12MemberStatus awaitStableLeader(Set<Integer> activeMemberIds, Duration timeout) {
    return awaitSingleLeader(activeMemberIds, -1, -2, timeout);
  }

  List<M12MemberStatus> awaitConvergence(Set<Integer> activeMemberIds, Duration timeout) {
    return awaitStableStatuses(
        "member business-state convergence",
        activeMemberIds,
        timeout,
        statuses -> {
          M12MemberStatus first = statuses.getFirst();
          return closedSingleLeaderTopology(statuses)
              && statuses.stream()
                  .allMatch(
                      status ->
                          status.nextApplicationSequence() == first.nextApplicationSequence()
                              && status.identityResultCount() == first.identityResultCount()
                              && status.semanticStateDigest().equals(first.semanticStateDigest())
                              && status.identityResultDigest().equals(first.identityResultDigest())
                              && status.commitPosition() == first.commitPosition()
                              && status.logPosition() == first.logPosition());
        });
  }

  List<M12MemberStatus> statuses(Set<Integer> activeMemberIds) {
    return statusesIfReady(activeMemberIds)
        .orElseThrow(() -> new IllegalStateException("member statuses are not ready"));
  }

  List<M12MemberStatus> lastStableStatuses() {
    requireOpen();
    if (lastStableStatuses.isEmpty()) {
      throw new IllegalStateException("no stable M12 member status snapshot has been observed");
    }
    return List.copyOf(lastStableStatuses);
  }

  M12MemberStatus status(int memberId) {
    requireOpen();
    requireRunning(memberId);
    return statusOrThrow(memberId);
  }

  Set<Integer> activeMemberIds() {
    requireOpen();
    return Set.copyOf(members.keySet());
  }

  boolean isAlive(int memberId) {
    MemberProcess process = members.get(memberId);
    return process != null && process.process().isAlive();
  }

  Throwable firstUnexpectedProcessFailure() {
    for (MemberProcess member : members.values()) {
      if (!member.process().isAlive() && !member.intentionalStop()) {
        return new IllegalStateException(
            "M12 member "
                + member.memberId()
                + " exited unexpectedly with "
                + safeExitValue(member.process())
                + ": "
                + logSample(member.stderr()));
      }
    }
    return null;
  }

  M12ThreeMemberConfig config() {
    return config;
  }

  long ownerProcessId() {
    return ownerProcessId;
  }

  int starts() {
    return starts;
  }

  int forcedStops() {
    return forcedStops;
  }

  static String childClasspathStrategy() {
    return lastChildClasspathStrategy;
  }

  static long archiveMarkFileLivenessTimeoutMillis() {
    return M12ArchiveMarkFileLiveness.LIVENESS_TIMEOUT_MILLIS;
  }

  static String restartSafetyPredicate() {
    return M12ArchiveMarkFileLiveness.PREDICATE;
  }

  List<StoppedMember> stoppedMembers() {
    return List.copyOf(stopped);
  }

  List<RestartSafetyWitness> restartSafetyWitnesses() {
    return List.copyOf(restartSafetyWitnesses);
  }

  List<StableSnapshotWitness> stabilityWitnesses() {
    return List.copyOf(stabilityWitnesses);
  }

  boolean teardownComplete() {
    return closed
        && teardownComplete
        && processStarts.stream().noneMatch(p -> p.process().isAlive());
  }

  List<MemberProcessView> memberProcesses() {
    return processStarts.stream()
        .map(
            member ->
                new MemberProcessView(
                    member.memberId(),
                    member.process().pid(),
                    member.freshStart(),
                    member.process().isAlive()))
        .toList();
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    RuntimeException failure = null;
    boolean interrupted = false;
    for (MemberProcess member : List.copyOf(members.values())) {
      member.intentionalStop(true);
      try {
        member.process().destroy();
        WaitResult graceful = waitForTermination(member.process(), Duration.ofSeconds(5));
        interrupted |= graceful.interrupted();
        if (!graceful.exited()) {
          boolean forceRequested = member.process().toHandle().destroyForcibly();
          WaitResult forced = waitForTermination(member.process(), Duration.ofSeconds(5));
          interrupted |= forced.interrupted();
          if (!forced.exited() || member.process().isAlive()) {
            throw new IllegalStateException(
                "M12 child did not terminate after the second bounded wait: member="
                    + member.memberId()
                    + ", forceRequested="
                    + forceRequested
                    + "; diagnostics="
                    + diagnosticSummary(member));
          }
        }
        if (member.process().isAlive()) {
          throw new IllegalStateException(
              "M12 child remained alive after teardown: member="
                  + member.memberId()
                  + "; diagnostics="
                  + diagnosticSummary(member));
        }
      } catch (RuntimeException closeFailure) {
        if (failure == null) {
          failure = closeFailure;
        } else {
          failure.addSuppressed(closeFailure);
        }
      }
    }
    teardownComplete = processStarts.stream().noneMatch(member -> member.process().isAlive());
    if (!teardownComplete) {
      RuntimeException incomplete =
          new IllegalStateException("M12 teardown left live children: " + activeDiagnostics());
      if (failure == null) {
        failure = incomplete;
      } else {
        failure.addSuppressed(incomplete);
      }
    }
    members.clear();
    if (interrupted) {
      RuntimeException interruption =
          new IllegalStateException(
              "M12 teardown was interrupted but continued until every child was checked");
      if (failure == null) {
        failure = interruption;
      } else {
        failure.addSuppressed(interruption);
      }
      Thread.currentThread().interrupt();
    }
    if (failure != null) {
      throw failure;
    }
  }

  private static WaitResult waitForTermination(Process process, Duration timeout) {
    long deadline = Math.addExact(System.nanoTime(), timeout.toNanos());
    boolean interrupted = Thread.interrupted();
    while (process.isAlive()) {
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0) {
        return new WaitResult(false, interrupted);
      }
      try {
        if (process.waitFor(
            Math.max(1, Math.min(TimeUnit.NANOSECONDS.toMillis(remaining), 100)),
            TimeUnit.MILLISECONDS)) {
          return new WaitResult(true, interrupted);
        }
      } catch (InterruptedException ignored) {
        interrupted = true;
      }
    }
    return new WaitResult(true, interrupted);
  }

  private M12MemberStatus awaitSingleLeader(
      Set<Integer> activeMemberIds,
      int excludedLeader,
      long minimumExclusiveTerm,
      Duration timeout) {
    List<M12MemberStatus> stable =
        awaitStableStatuses(
            "one authoritative M12 Leader",
            activeMemberIds,
            timeout,
            statuses -> {
              List<M12MemberStatus> leaders =
                  statuses.stream().filter(status -> "LEADER".equals(status.role())).toList();
              if (leaders.size() != 1) {
                return false;
              }
              M12MemberStatus candidate = leaders.getFirst();
              if (candidate.memberId() == excludedLeader
                  || candidate.leadershipTermId() <= minimumExclusiveTerm) {
                return false;
              }
              long term = candidate.leadershipTermId();
              boolean sameTerm =
                  statuses.stream().allMatch(status -> status.leadershipTermId() == term);
              boolean followers =
                  statuses.stream()
                      .filter(status -> status.memberId() != candidate.memberId())
                      .allMatch(status -> "FOLLOWER".equals(status.role()));
              boolean electionsClosed =
                  statuses.stream().allMatch(status -> "CLOSED".equals(status.electionState()));
              return sameTerm && followers && electionsClosed;
            });
    return stable.stream()
        .filter(status -> "LEADER".equals(status.role()))
        .findFirst()
        .orElseThrow();
  }

  private List<M12MemberStatus> awaitStableStatuses(
      String label,
      Set<Integer> activeMemberIds,
      Duration timeout,
      Predicate<List<M12MemberStatus>> coherent) {
    Objects.requireNonNull(coherent, "coherent");
    long freshnessBoundNanos =
        config.statusPublishInterval().multipliedBy(STATUS_FRESHNESS_INTERVALS).toNanos();
    StableCandidate[] candidate = new StableCandidate[1];
    AtomicReference<List<M12MemberStatus>> result = new AtomicReference<>();
    await(
        label,
        timeout,
        () -> {
          Optional<List<M12MemberStatus>> ready = statusesIfReady(activeMemberIds);
          if (ready.isEmpty() || !coherent.test(ready.orElseThrow())) {
            candidate[0] = null;
            return false;
          }
          List<M12MemberStatus> statuses = ready.orElseThrow();
          long observedAtNanos = System.nanoTime();
          StableCandidate first = candidate[0];
          if (first == null || observedAtNanos - first.observedAtNanos() > freshnessBoundNanos) {
            candidate[0] = new StableCandidate(statuses, observedAtNanos);
            return false;
          }
          if (!sameProcessAndNondecreasingSequence(first.statuses(), statuses)
              || !stableObservationUnchanged(first.statuses(), statuses)) {
            candidate[0] = new StableCandidate(statuses, observedAtNanos);
            return false;
          }
          if (!allStatusSequencesAdvanced(first.statuses(), statuses)) {
            return false;
          }
          long elapsedNanos = observedAtNanos - first.observedAtNanos();
          StableSnapshotWitness witness =
              new StableSnapshotWitness(
                  stabilityWitnesses.size() + 1,
                  label,
                  config.statusPublishInterval().toMillis(),
                  Duration.ofNanos(freshnessBoundNanos).toMillis(),
                  elapsedNanos,
                  first.statuses(),
                  statuses);
          stabilityWitnesses.add(witness);
          lastStableStatuses = List.copyOf(statuses);
          result.set(statuses);
          return true;
        });
    return List.copyOf(result.get());
  }

  private static boolean allStatusSequencesAdvanced(
      List<M12MemberStatus> first, List<M12MemberStatus> second) {
    if (first.size() != second.size()) {
      return false;
    }
    Map<Integer, M12MemberStatus> firstByMember = new LinkedHashMap<>();
    first.forEach(status -> firstByMember.put(status.memberId(), status));
    return second.stream()
        .allMatch(
            status -> {
              M12MemberStatus previous = firstByMember.get(status.memberId());
              return previous != null
                  && previous.processId() == status.processId()
                  && status.statusSequence() > previous.statusSequence();
            });
  }

  private static boolean sameProcessAndNondecreasingSequence(
      List<M12MemberStatus> first, List<M12MemberStatus> second) {
    if (first.size() != second.size()) {
      return false;
    }
    Map<Integer, M12MemberStatus> firstByMember = new LinkedHashMap<>();
    first.forEach(status -> firstByMember.put(status.memberId(), status));
    return second.stream()
        .allMatch(
            status -> {
              M12MemberStatus previous = firstByMember.get(status.memberId());
              return previous != null
                  && previous.processId() == status.processId()
                  && status.statusSequence() >= previous.statusSequence();
            });
  }

  private static boolean stableObservationUnchanged(
      List<M12MemberStatus> first, List<M12MemberStatus> second) {
    Map<Integer, M12MemberStatus> firstByMember = new LinkedHashMap<>();
    first.forEach(status -> firstByMember.put(status.memberId(), status));
    return second.stream()
        .allMatch(
            status -> {
              M12MemberStatus previous = firstByMember.get(status.memberId());
              return previous != null
                  && previous.role().equals(status.role())
                  && previous.electionState().equals(status.electionState())
                  && previous.leadershipTermId() == status.leadershipTermId()
                  && previous.commitPosition() == status.commitPosition()
                  && previous.logPosition() == status.logPosition()
                  && previous.nextApplicationSequence() == status.nextApplicationSequence()
                  && previous.identityResultCount() == status.identityResultCount()
                  && previous.semanticStateDigest().equals(status.semanticStateDigest())
                  && previous.identityResultDigest().equals(status.identityResultDigest())
                  && previous.componentErrors().equals(status.componentErrors())
                  && previous.diagnosticWarnings().equals(status.diagnosticWarnings())
                  && previous.droppedDiagnosticWarnings() == status.droppedDiagnosticWarnings();
            });
  }

  private static boolean closedSingleLeaderTopology(List<M12MemberStatus> statuses) {
    List<M12MemberStatus> leaders =
        statuses.stream().filter(status -> "LEADER".equals(status.role())).toList();
    if (leaders.size() != 1
        || statuses.stream().anyMatch(status -> !"CLOSED".equals(status.electionState()))) {
      return false;
    }
    long term = leaders.getFirst().leadershipTermId();
    int leaderId = leaders.getFirst().memberId();
    return statuses.stream()
        .allMatch(
            status ->
                status.leadershipTermId() == term
                    && (status.memberId() == leaderId || "FOLLOWER".equals(status.role())));
  }

  private Optional<List<M12MemberStatus>> statusesIfReady(Set<Integer> activeMemberIds) {
    if (activeMemberIds.isEmpty()) {
      throw new IllegalArgumentException("active member set must not be empty");
    }
    List<M12MemberStatus> result = new ArrayList<>();
    for (int memberId : activeMemberIds.stream().sorted().toList()) {
      MemberProcess process = members.get(memberId);
      if (process == null || !process.process().isAlive()) {
        if (process != null && !process.intentionalStop()) {
          throw new IllegalStateException(
              "member " + memberId + " exited: " + logSample(process.stderr()));
        }
        return Optional.empty();
      }
      Path file = config.memberStatusFile(memberId);
      if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
        return Optional.empty();
      }
      final M12MemberStatus status;
      try {
        status = M12MemberStatusFile.read(file);
      } catch (IOException transientStatusWrite) {
        return Optional.empty();
      }
      if (status.memberId() != memberId
          || status.processId() != process.process().pid()
          || !status.healthy()) {
        if (!status.healthy()) {
          throw new IllegalStateException(
              "member " + memberId + " reported component errors: " + status.componentErrors());
        }
        return Optional.empty();
      }
      result.add(status);
    }
    return Optional.of(List.copyOf(result));
  }

  private M12MemberStatus statusOrThrow(int memberId) {
    try {
      return M12MemberStatusFile.read(config.memberStatusFile(memberId));
    } catch (IOException failure) {
      throw new IllegalStateException("cannot read M12 member status " + memberId, failure);
    }
  }

  private MemberProcess requireRunning(int memberId) {
    config.requireMemberId(memberId);
    MemberProcess member = members.get(memberId);
    if (member == null || !member.process().isAlive()) {
      throw new IllegalStateException("member is not running: " + memberId);
    }
    return member;
  }

  private void requireOpen() {
    if (closed) {
      throw new IllegalStateException("M12 member harness is closed");
    }
  }

  private void await(String label, Duration timeout, BooleanSupplier condition) {
    Objects.requireNonNull(timeout, "timeout");
    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must be positive");
    }
    long deadline = Math.addExact(System.nanoTime(), timeout.toNanos());
    while (!condition.getAsBoolean()) {
      Throwable processFailure = firstUnexpectedProcessFailure();
      if (processFailure != null) {
        throw new IllegalStateException(label + " failed", processFailure);
      }
      if (System.nanoTime() >= deadline) {
        throw new IllegalStateException(
            label + " did not complete before the deadline; diagnostics=" + activeDiagnostics());
      }
      LockSupport.parkNanos(POLL_PARK_NANOS);
    }
  }

  private static boolean portsAvailable(M12ThreeMemberConfig config) {
    List<DatagramChannel> reservations = new ArrayList<>();
    try {
      InetAddress loopback = InetAddress.getByName("127.0.0.1");
      for (int port : config.allFixedUdpPorts().stream().sorted().toList()) {
        DatagramChannel channel = DatagramChannel.open();
        channel.bind(new InetSocketAddress(loopback, port));
        reservations.add(channel);
      }
      return true;
    } catch (IOException unavailable) {
      return false;
    } finally {
      for (DatagramChannel reservation : reservations) {
        try {
          reservation.close();
        } catch (IOException ignored) {
          // The probe is advisory; the real component startup remains authoritative.
        }
      }
    }
  }

  private static synchronized String childClasspath() {
    String inherited = System.getProperty("java.class.path", "");
    LinkedHashSet<Path> inheritedEntries = new LinkedHashSet<>();
    for (String entry : inherited.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
      if (!entry.isBlank()) {
        inheritedEntries.add(Path.of(entry).toAbsolutePath().normalize());
      }
    }
    LinkedHashSet<Path> requiredEntries = new LinkedHashSet<>();
    for (String className : CHILD_RUNTIME_CLASSES) {
      requiredEntries.add(codeSourcePath(className));
    }
    if (inheritedEntries.containsAll(requiredEntries)) {
      lastChildClasspathStrategy = "INHERITED_JAVA_CLASS_PATH";
      return inherited;
    }
    if (requiredEntries.isEmpty()) {
      throw new IllegalStateException("M12 child classpath has no production entries");
    }
    lastChildClasspathStrategy = "STRICT_CODE_SOURCE_FALLBACK";
    return requiredEntries.stream()
        .map(Path::toString)
        .collect(java.util.stream.Collectors.joining(File.pathSeparator));
  }

  private void awaitArchiveMarkFileInactive(int memberId) {
    StoppedMember latest =
        stopped.reversed().stream()
            .filter(stop -> stop.memberId() == memberId)
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "freshStart=false requires an observed stopped process: " + memberId));
    Path markFile =
        config
            .memberArchiveDirectory(memberId)
            .resolve(M12ArchiveMarkFileLiveness.MARK_FILE_NAME)
            .toAbsolutePath()
            .normalize();
    long waitStartedAtNanos = System.nanoTime();
    long deadline = Math.addExact(System.nanoTime(), config.clientMessageTimeout().toNanos());
    long probes = 0;
    long lastActivityTimestampMillis = Long.MIN_VALUE;
    long observedAtMillis = Long.MIN_VALUE;
    try (M12ArchiveMarkFileLiveness.Reader archiveMark =
        M12ArchiveMarkFileLiveness.open(config.memberArchiveDirectory(memberId))) {
      while (true) {
        probes++;
        M12ArchiveMarkFileLiveness.Observation observation = archiveMark.observe();
        lastActivityTimestampMillis = observation.lastActivityTimestampMillis();
        observedAtMillis = observation.observedAtMillis();
        if (observation.inactive()) {
          long ageMillis = Math.subtractExact(observedAtMillis, lastActivityTimestampMillis);
          restartSafetyWitnesses.add(
              new RestartSafetyWitness(
                  restartSafetyWitnesses.size() + 1,
                  memberId,
                  latest.processId(),
                  markFile,
                  lastActivityTimestampMillis,
                  observedAtMillis,
                  ageMillis,
                  M12ArchiveMarkFileLiveness.LIVENESS_TIMEOUT_MILLIS,
                  probes,
                  System.nanoTime() - waitStartedAtNanos,
                  observation.aeronVersion(),
                  M12ArchiveMarkFileLiveness.PREDICATE));
          return;
        }
        if (System.nanoTime() >= deadline) {
          throw new IllegalStateException(
              "Aeron Archive mark file remained live past the bounded restart deadline: member="
                  + memberId
                  + ", markFile="
                  + markFile
                  + ", lastActivityTimestampMillis="
                  + lastActivityTimestampMillis
                  + ", observedAtMillis="
                  + observedAtMillis);
        }
        LockSupport.parkNanos(RESTART_POLL_PARK_NANOS);
        if (Thread.interrupted()) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(
              "interrupted while observing Aeron Archive mark-file liveness");
        }
      }
    }
  }

  static boolean isArchiveMarkFileInactive(
      long observedAtMillis, long lastActivityTimestampMillis, long livenessTimeoutMillis) {
    return M12ArchiveMarkFileLiveness.isInactive(
        observedAtMillis, lastActivityTimestampMillis, livenessTimeoutMillis);
  }

  private static Path codeSourcePath(String className) {
    try {
      Class<?> type =
          Class.forName(className, false, M12ThreeMemberProcessHarness.class.getClassLoader());
      if (type.getProtectionDomain() == null
          || type.getProtectionDomain().getCodeSource() == null
          || type.getProtectionDomain().getCodeSource().getLocation() == null) {
        throw new IllegalStateException("M12 child dependency has no CodeSource: " + className);
      }
      return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI())
          .toAbsolutePath()
          .normalize();
    } catch (ClassNotFoundException failure) {
      throw new IllegalStateException(
          "M12 child dependency is not loadable: " + className, failure);
    } catch (URISyntaxException failure) {
      throw new IllegalStateException(
          "M12 child dependency CodeSource is invalid: " + className, failure);
    }
  }

  private static void deleteOwnedRoot(Path root) {
    Path normalized = root.toAbsolutePath().normalize();
    if (!normalized.toString().contains("build/tmp/m12")) {
      throw new IllegalArgumentException("refusing to clear a non-M12 runtime root: " + normalized);
    }
    if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
      try {
        Files.createDirectories(normalized);
        return;
      } catch (IOException failure) {
        throw new IllegalStateException("cannot create M12 runtime root", failure);
      }
    }
    try (var paths = Files.walk(normalized)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
      Files.createDirectories(normalized);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot clear M12 runtime root", failure);
    }
  }

  private static int safeExitValue(Process process) {
    try {
      return process.exitValue();
    } catch (IllegalThreadStateException running) {
      return Integer.MIN_VALUE;
    }
  }

  private static String logSample(Path log) {
    return logSample(log, MAX_LOG_SAMPLE_BYTES);
  }

  private static String logSample(Path log, int maximumBytes) {
    try {
      if (!Files.isRegularFile(log, LinkOption.NOFOLLOW_LINKS)) {
        return "no stderr artifact";
      }
      byte[] bytes = Files.readAllBytes(log);
      int offset = Math.max(0, bytes.length - maximumBytes);
      return new String(bytes, offset, bytes.length - offset, StandardCharsets.UTF_8).strip();
    } catch (IOException failure) {
      return "unreadable stderr artifact: " + failure.getClass().getSimpleName();
    }
  }

  private String activeDiagnostics() {
    return members.values().stream()
        .map(this::diagnosticSummary)
        .collect(java.util.stream.Collectors.joining(" | "));
  }

  private String diagnosticSummary(MemberProcess member) {
    String status;
    try {
      M12MemberStatus latest = M12MemberStatusFile.read(config.memberStatusFile(member.memberId()));
      status =
          "statusSequence="
              + latest.statusSequence()
              + ",processId="
              + latest.processId()
              + ",role="
              + latest.role()
              + ",electionState="
              + latest.electionState()
              + ",term="
              + latest.leadershipTermId()
              + ",commit="
              + latest.commitPosition()
              + ",log="
              + latest.logPosition()
              + ",next="
              + latest.nextApplicationSequence()
              + ",identityCount="
              + latest.identityResultCount()
              + ",errors="
              + latest.componentErrors();
    } catch (IOException unavailable) {
      status = "statusUnavailable=" + unavailable.getClass().getSimpleName();
    }
    return "member="
        + member.memberId()
        + ",pid="
        + member.process().pid()
        + ",alive="
        + member.process().isAlive()
        + ','
        + status
        + ",stdoutTail="
        + logSample(member.stdout(), MAX_DIAGNOSTIC_LOG_SAMPLE_BYTES)
        + ",stderrTail="
        + logSample(member.stderr(), MAX_DIAGNOSTIC_LOG_SAMPLE_BYTES);
  }

  private static final class MemberProcess {
    private final int memberId;
    private final Process process;
    private final boolean freshStart;
    private final Path stdout;
    private final Path stderr;
    private final long launchedAtNanos;
    private boolean intentionalStop;

    MemberProcess(
        int memberId,
        Process process,
        boolean freshStart,
        Path stdout,
        Path stderr,
        long launchedAtNanos) {
      this.memberId = memberId;
      this.process = process;
      this.freshStart = freshStart;
      this.stdout = stdout;
      this.stderr = stderr;
      this.launchedAtNanos = launchedAtNanos;
    }

    int memberId() {
      return memberId;
    }

    Process process() {
      return process;
    }

    boolean freshStart() {
      return freshStart;
    }

    Path stdout() {
      return stdout;
    }

    Path stderr() {
      return stderr;
    }

    long launchedAtNanos() {
      return launchedAtNanos;
    }

    boolean intentionalStop() {
      return intentionalStop;
    }

    void intentionalStop(boolean value) {
      intentionalStop = value;
    }
  }

  record StoppedMember(
      int memberId,
      long processId,
      boolean forcedStopRequested,
      int exitCode,
      boolean externalController,
      long stoppedAtNanos,
      M12MemberStatus lastStatus) {}

  record RestartSafetyWitness(
      int ordinal,
      int memberId,
      long stoppedProcessId,
      Path archiveMarkFile,
      long lastActivityTimestampMillis,
      long observedAtMillis,
      long ageMillis,
      long livenessTimeoutMillis,
      long probeCount,
      long waitElapsedNanos,
      String aeronVersion,
      String predicate) {
    RestartSafetyWitness {
      archiveMarkFile = archiveMarkFile.toAbsolutePath().normalize();
      if (ordinal <= 0
          || memberId < 0
          || memberId >= M12ThreeMemberConfig.MEMBER_COUNT
          || stoppedProcessId <= 0
          || !archiveMarkFile.endsWith(M12ArchiveMarkFileLiveness.MARK_FILE_NAME)
          || lastActivityTimestampMillis <= 0
          || observedAtMillis < lastActivityTimestampMillis
          || ageMillis != observedAtMillis - lastActivityTimestampMillis
          || ageMillis <= livenessTimeoutMillis
          || livenessTimeoutMillis != M12ArchiveMarkFileLiveness.LIVENESS_TIMEOUT_MILLIS
          || probeCount <= 0
          || waitElapsedNanos < 0
          || !M12ArchiveMarkFileLiveness.AERON_VERSION.equals(aeronVersion)
          || !M12ArchiveMarkFileLiveness.PREDICATE.equals(predicate)) {
        throw new IllegalArgumentException("invalid Aeron Archive restart-safety witness");
      }
    }
  }

  record MemberProcessView(int memberId, long processId, boolean freshStart, boolean alive) {}

  record StableSnapshotWitness(
      int ordinal,
      String condition,
      long statusPublishIntervalMillis,
      long freshnessBoundMillis,
      long elapsedNanos,
      List<M12MemberStatus> firstSnapshot,
      List<M12MemberStatus> secondSnapshot) {
    StableSnapshotWitness {
      firstSnapshot = List.copyOf(firstSnapshot);
      secondSnapshot = List.copyOf(secondSnapshot);
      if (ordinal <= 0
          || condition == null
          || condition.isBlank()
          || statusPublishIntervalMillis <= 0
          || freshnessBoundMillis < statusPublishIntervalMillis
          || elapsedNanos < 0
          || elapsedNanos > Duration.ofMillis(freshnessBoundMillis).toNanos()
          || firstSnapshot.isEmpty()
          || !allStatusSequencesAdvanced(firstSnapshot, secondSnapshot)
          || !stableObservationUnchanged(firstSnapshot, secondSnapshot)) {
        throw new IllegalArgumentException("invalid stable M12 snapshot witness");
      }
    }
  }

  private record WaitResult(boolean exited, boolean interrupted) {}

  private record StableCandidate(List<M12MemberStatus> statuses, long observedAtNanos) {
    StableCandidate {
      statuses = List.copyOf(statuses);
    }
  }
}
