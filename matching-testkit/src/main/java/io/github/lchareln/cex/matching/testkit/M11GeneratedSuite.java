package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.cluster.DirectM11MatchingRuntime;
import io.github.lchareln.cex.matching.cluster.M11ApplicationResult;
import io.github.lchareln.cex.matching.cluster.M11ApplicationSnapshotWitness;
import io.github.lchareln.cex.matching.cluster.M11ClusterRuntimeWitness;
import io.github.lchareln.cex.matching.cluster.M11CommandRequest;
import io.github.lchareln.cex.matching.cluster.M11CommandResponse;
import io.github.lchareln.cex.matching.cluster.M11HarnessReport;
import io.github.lchareln.cex.matching.cluster.M11ProtocolException;
import io.github.lchareln.cex.matching.cluster.M11RequestCodec;
import io.github.lchareln.cex.matching.cluster.M11ResponseStatus;
import io.github.lchareln.cex.matching.cluster.M11RuntimeState;
import io.github.lchareln.cex.matching.cluster.M11RuntimeStateCodec;
import io.github.lchareln.cex.matching.cluster.M11ServiceObservation;
import io.github.lchareln.cex.matching.cluster.M11SingleNodeConfig;
import io.github.lchareln.cex.matching.cluster.M11SingleNodeHarness;
import io.github.lchareln.cex.matching.cluster.M11SnapshotCodec;
import io.github.lchareln.cex.matching.cluster.M11SnapshotWitness;
import io.github.lchareln.cex.matching.local.M08Command;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** One continuous deterministic corpus executed through direct and two real Cluster paths. */
final class M11GeneratedSuite {
  static final long BASE_SEED = 6111;
  static final int SEGMENTS = 32;
  static final int ACTIONS_PER_SEGMENT = 128;
  static final int ACTIONS_PER_PATH = SEGMENTS * ACTIONS_PER_SEGMENT;
  static final int SNAPSHOT_AFTER_ACTION = 2048;
  static final int CLUSTER_RUNS = 2;
  static final int TOTAL_CLUSTER_INGRESS = ACTIONS_PER_PATH * CLUSTER_RUNS;
  private static final long SHARD = 1;
  private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(15);
  private static final Duration SNAPSHOT_TIMEOUT = Duration.ofSeconds(30);
  private static final int PORT_BLOCK_SIZE = 6;
  private static final int MAX_PORT_PROBE_ATTEMPTS = 2_048;
  private static final int MAX_CLUSTER_LAUNCH_ATTEMPTS = 8;

  Result run(Path workingRoot) {
    M09ScenarioSupport.deleteTree(workingRoot);
    createDirectories(workingRoot);
    Corpus first = generate();
    Corpus second = generate();
    require(
        Arrays.equals(first.canonicalBytes(), second.canonicalBytes()),
        "M11 corpus is not byte-exact");
    require(first.actions().size() == ACTIONS_PER_PATH, "M11 corpus action count changed");

    DirectRun direct = runDirect(first);
    ClusterRun uninterrupted =
        runCluster(
            first,
            direct.results(),
            workingRoot.resolve("uninterrupted"),
            false,
            23_111,
            List.of());
    ClusterRun restarted =
        runCluster(
            first,
            direct.results(),
            workingRoot.resolve("snapshot-restart"),
            true,
            33_111,
            List.of(uninterrupted.portBlock()));
    require(
        uninterrupted.applicationResults().equals(restarted.applicationResults()),
        "the two Cluster paths diverged");
    require(
        direct.finalState().equals(uninterrupted.finalState()),
        "direct and uninterrupted state diverged");
    require(
        direct.finalState().equals(restarted.finalState()), "direct and restarted state diverged");

    M11HarnessReport uninterruptedHarness = uninterrupted.harnessReport();
    M11HarnessReport restartedHarness = restarted.harnessReport();
    requireClusterCounts(uninterruptedHarness, false, direct.counts());
    requireClusterCounts(restartedHarness, true, direct.counts());
    require(uninterrupted.teardownCompleted(), "uninterrupted teardown was not witnessed");
    require(restarted.teardownCompleted(), "restart teardown was not witnessed");
    require(
        uninterrupted.portBlock().disjoint(restarted.portBlock()),
        "Cluster UDP port blocks overlap");
    require(
        uninterrupted.runtimeWitnesses().stream()
            .allMatch(witness -> "LEADER".equals(witness.serviceRole())),
        "uninterrupted service was not leader");
    require(
        restarted.runtimeWitnesses().stream()
            .allMatch(witness -> "LEADER".equals(witness.serviceRole())),
        "restart service was not leader");

    ObjectNode generated = JsonSupport.MAPPER.createObjectNode();
    generated.put("schemaVersion", "matching.m11.generated-differential.v1");
    generated.put("status", M11CheckRunner.PASS);
    generated.put("algorithm", "splitmix64-v1");
    generated.put("seed", Long.toString(BASE_SEED));
    generated.put("histories", SEGMENTS);
    generated.put("continuousCorpus", true);
    generated.put("actionsPerHistory", ACTIONS_PER_SEGMENT);
    generated.put("actionsPerPath", ACTIONS_PER_PATH);
    generated.put("clusterRuns", CLUSTER_RUNS);
    generated.put("totalActualClusterIngress", TOTAL_CLUSTER_INGRESS);
    generated.put("freshGenerations", 2);
    generated.put("byteExactRegeneration", true);
    generated.put("canonicalSha256", Hashing.sha256Hex(first.canonicalBytes()));
    generated.put("newApplied", direct.counts().getOrDefault(Expected.NEW, 0));
    generated.put("duplicateReplayed", direct.counts().getOrDefault(Expected.DUPLICATE, 0));
    generated.put("identityRejected", direct.counts().getOrDefault(Expected.CONFLICT, 0));
    generated.put("commandIdConflicts", direct.commandConflicts());
    generated.put("slotConflicts", direct.slotConflicts());
    generated.put("directClusterComparisons", ACTIONS_PER_PATH * CLUSTER_RUNS);
    generated.put("clusterClusterComparisons", ACTIONS_PER_PATH);
    generated.put("threePathFullBusinessEquivalent", true);
    generated.put("finalIdentityBindings", direct.finalState().identityBindings().size());
    generated.put("finalNextApplicationSequence", direct.finalState().nextApplicationSequence());
    generated.put("newOrdinalsContinuous", direct.newOrdinalsContinuous());
    generated.put("duplicateStateInvariantChecks", direct.duplicateInvariantChecks());
    generated.put("conflictStateInvariantChecks", direct.conflictInvariantChecks());
    ArrayNode schedule = generated.putArray("segmentSchedule");
    first.segmentSchedule().forEach(schedule::add);
    ArrayNode lanes = generated.putArray("lanes");
    for (Lane lane : Lane.values()) {
      ObjectNode value = lanes.addObject();
      value.put("id", lane.name());
      long segments =
          first.actions().stream()
              .map(Action::segment)
              .distinct()
              .filter(segment -> first.actions().get(segment * ACTIONS_PER_SEGMENT).lane() == lane)
              .count();
      long actions = first.actions().stream().filter(action -> action.lane() == lane).count();
      value.put("segments", segments);
      value.put("actions", actions);
    }

    ObjectNode cluster = JsonSupport.MAPPER.createObjectNode();
    M11ClusterRuntimeWitness uninterruptedRuntime = uninterrupted.runtimeWitnesses().getFirst();
    M11ClusterRuntimeWitness restartedRuntime = restarted.runtimeWitnesses().getLast();
    cluster.put("schemaVersion", "matching.m11.cluster-runtime.v1");
    cluster.put("status", M11CheckRunner.PASS);
    cluster.put("implementation", "REAL_AERON_CLUSTER");
    cluster.put("memberCount", uninterruptedRuntime.memberCount());
    cluster.put("memberId", uninterruptedRuntime.memberId());
    cluster.put("appointedLeaderId", uninterruptedRuntime.appointedLeaderId());
    cluster.put("clusterRuns", List.of(uninterrupted, restarted).size());
    cluster.put("actionsPerRun", first.actions().size());
    cluster.put(
        "acceptedIngressOffers",
        uninterruptedHarness.ingressOffersAccepted() + restartedHarness.ingressOffersAccepted());
    cluster.put(
        "correlatedResponses",
        uninterruptedHarness.correlatedEgressResponses()
            + restartedHarness.correlatedEgressResponses());
    cluster.put(
        "serviceObservations", uninterrupted.observationCount() + restarted.observationCount());
    cluster.put(
        "newBusinessApplications",
        uninterruptedHarness.newBusinessApplications()
            + restartedHarness.newBusinessApplications());
    cluster.put(
        "duplicateReplays",
        uninterruptedHarness.duplicateReplays() + restartedHarness.duplicateReplays());
    cluster.put(
        "rejectedApplications",
        uninterruptedHarness.rejectedApplications() + restartedHarness.rejectedApplications());
    cluster.put(
        "snapshotAdminAccepted",
        uninterruptedHarness.snapshotAdminAccepted() + restartedHarness.snapshotAdminAccepted());
    cluster.put(
        "snapshotsCompleted",
        uninterruptedHarness.snapshotsCompleted() + restartedHarness.snapshotsCompleted());
    cluster.put("restarts", uninterruptedHarness.restarts() + restartedHarness.restarts());
    cluster.put(
        "componentErrors",
        uninterruptedHarness.componentErrorCount() + restartedHarness.componentErrorCount());
    cluster.put(
        "componentErrorsSampledAfterTeardown",
        uninterrupted.teardownCompleted() && restarted.teardownCompleted());
    cluster.put(
        "teardownsCompleted",
        (uninterrupted.teardownCompleted() ? 1 : 0) + (restarted.teardownCompleted() ? 1 : 0));
    cluster.put("serviceRole", restartedRuntime.serviceRole());
    cluster.put("aeronConfiguredVersion", "1.52.2");
    cluster.put("agronaConfiguredVersion", "2.5.0");
    cluster.put(
        "aeronRuntimeVersion",
        uninterrupted.runtimeWitnesses().getFirst().aeronImplementationVersion());
    cluster.put(
        "agronaRuntimeVersion",
        uninterrupted.runtimeWitnesses().getFirst().agronaImplementationVersion());
    cluster.put("udpPortProbe", true);
    cluster.put("portProbeMaxAttempts", MAX_PORT_PROBE_ATTEMPTS);
    cluster.put(
        "portProbeAttempts",
        uninterrupted.portBlock().probeAttempts() + restarted.portBlock().probeAttempts());
    cluster.put("portBlockSize", PORT_BLOCK_SIZE);
    cluster.put("clusterLaunchRetryBound", MAX_CLUSTER_LAUNCH_ATTEMPTS);
    cluster.put(
        "clusterLaunchAttempts", uninterrupted.launchAttempts() + restarted.launchAttempts());
    cluster.put("portBlocksDisjoint", uninterrupted.portBlock().disjoint(restarted.portBlock()));
    cluster.put(
        "ownedRootsDisjoint",
        !uninterrupted
            .runtimeWitnesses()
            .getFirst()
            .rootDirectory()
            .equals(restarted.runtimeWitnesses().getFirst().rootDirectory()));
    ArrayNode portBlocks = cluster.putArray("portBlocks");
    addPortBlock(portBlocks, "UNINTERRUPTED", uninterrupted);
    addPortBlock(portBlocks, "SNAPSHOT_RESTART", restarted);
    ArrayNode runtimeWitnesses = cluster.putArray("runtimeWitnesses");
    addRuntimeWitness(
        runtimeWitnesses, "UNINTERRUPTED", uninterrupted.runtimeWitnesses().getFirst());
    addRuntimeWitness(
        runtimeWitnesses, "SNAPSHOT_RESTART_BEFORE", restarted.runtimeWitnesses().getFirst());
    addRuntimeWitness(
        runtimeWitnesses, "SNAPSHOT_RESTART_AFTER", restarted.runtimeWitnesses().getLast());
    cluster.put(
        "correlationRoundTrips",
        uninterruptedHarness.correlatedEgressResponses()
            + restartedHarness.correlatedEgressResponses());
    cluster.put(
        "allBusinessOutcomesFromCorrelatedEgress",
        uninterruptedHarness.ingressOffersAccepted()
                == uninterruptedHarness.correlatedEgressResponses()
            && restartedHarness.ingressOffersAccepted()
                == restartedHarness.correlatedEgressResponses());
    cluster.put(
        "singleMemberOnly",
        uninterruptedRuntime.memberCount() == 1 && restartedRuntime.memberCount() == 1);
    cluster.put("highAvailabilityClaim", false);
    cluster.put("performanceClaim", false);
    cluster.put("dockerRequired", false);
    cluster.put("externalServices", false);

    return new Result(
        generated,
        cluster,
        restarted.snapshotReport(),
        first.canonicalBytes(),
        direct.results(),
        uninterrupted.applicationResults(),
        restarted.applicationResults(),
        direct.finalState(),
        workingRoot);
  }

  static Corpus generate() {
    M11RequestCodec codec = new M11RequestCodec();
    List<Action> actions = new ArrayList<>(ACTIONS_PER_PATH);
    List<Original> originals = new ArrayList<>(2048);
    List<List<Original>> currentBySegment = new ArrayList<>();
    List<List<Original>> previousBySegment = new ArrayList<>();
    for (int segment = 0; segment < 8; segment++) {
      currentBySegment.add(new ArrayList<>(ACTIONS_PER_SEGMENT));
      previousBySegment.add(new ArrayList<>(ACTIONS_PER_SEGMENT));
    }
    M03SplitMix64V1 random = new M03SplitMix64V1(BASE_SEED);
    List<SegmentSpec> schedule = segmentSchedule();
    long nextNewOrdinal = 1;
    for (int segment = 0; segment < schedule.size(); segment++) {
      SegmentSpec spec = schedule.get(segment);
      for (int action = 0; action < ACTIONS_PER_SEGMENT; action++) {
        int global = segment * ACTIONS_PER_SEGMENT + action;
        switch (spec.lane()) {
          case CURRENT_NEW, PREVIOUS_NEW -> {
            int requestVersion = spec.lane() == Lane.CURRENT_NEW ? 2 : 1;
            long newOrdinal = nextNewOrdinal++;
            M11CommandRequest request =
                create(
                    codec,
                    requestVersion,
                    requestVersion,
                    uuid(random),
                    "m11-generated",
                    1,
                    newOrdinal,
                    uuid(random),
                    place(newOrdinal, random));
            Original original = new Original(global, newOrdinal, request);
            originals.add(original);
            (spec.lane() == Lane.CURRENT_NEW ? currentBySegment : previousBySegment)
                .get(spec.laneIndex())
                .add(original);
            actions.add(
                new Action(
                    global,
                    segment,
                    action,
                    spec.lane(),
                    spec.laneIndex(),
                    Expected.NEW,
                    request,
                    newOrdinal,
                    -1,
                    false,
                    "NEW_" + newOrdinal));
          }
          case DUPLICATE_REPLAY -> {
            Original source =
                spec.laneIndex() < 4
                    ? currentBySegment.get(spec.laneIndex()).get(action)
                    : previousBySegment.get(spec.laneIndex() - 4).get(action);
            boolean crossSnapshot = spec.laneIndex() >= 4;
            M11CommandRequest duplicate = source.request().withCorrelationId(uuid(random));
            actions.add(
                new Action(
                    global,
                    segment,
                    action,
                    spec.lane(),
                    spec.laneIndex(),
                    Expected.DUPLICATE,
                    duplicate,
                    0,
                    source.actionIndex(),
                    crossSnapshot,
                    "SOURCE_NEW_" + source.newOrdinal()));
          }
          case IDENTITY_CONFLICT -> {
            int conflictOrdinal = spec.laneIndex() * ACTIONS_PER_SEGMENT + action;
            Original source = originals.get(conflictOrdinal);
            M11CommandRequest original = source.request();
            boolean commandConflict = (conflictOrdinal & 1) == 0;
            M11CommandRequest conflict =
                create(
                    codec,
                    original.protocolVersion(),
                    original.requestedResponseVersion(),
                    uuid(random),
                    commandConflict ? "m11-conflict-" + global : original.slot().producerId(),
                    original.slot().producerEpoch(),
                    commandConflict ? 1 : original.slot().producerSequence(),
                    commandConflict ? original.commandId() : uuid(random),
                    conflictingPlace(global + 1L, random));
            String detail = commandConflict ? "COMMAND_ID_SLOT_CONFLICT" : "SLOT_IDENTITY_CONFLICT";
            actions.add(
                new Action(
                    global,
                    segment,
                    action,
                    spec.lane(),
                    spec.laneIndex(),
                    Expected.CONFLICT,
                    conflict,
                    0,
                    source.actionIndex(),
                    false,
                    detail));
          }
        }
      }
    }
    require(actions.size() == ACTIONS_PER_PATH, "M11 generator did not fill the corpus");
    require(nextNewOrdinal == 2049, "M11 generator NEW ordinal count changed");
    return new Corpus(
        List.copyOf(actions), canonical(actions), schedule.stream().map(SegmentSpec::id).toList());
  }

  static DirectRun runDirect(Corpus corpus) {
    DirectM11MatchingRuntime runtime = new DirectM11MatchingRuntime();
    List<M11ApplicationResult> results = new ArrayList<>(ACTIONS_PER_PATH);
    Map<Expected, Integer> counts = new EnumMap<>(Expected.class);
    int commandConflicts = 0;
    int slotConflicts = 0;
    int duplicateInvariantChecks = 0;
    int conflictInvariantChecks = 0;
    long expectedNewOrdinal = 1;
    for (Action action : corpus.actions()) {
      M11RuntimeState before = action.expected() == Expected.NEW ? null : runtime.stateImage();
      String cursorBefore = before == null ? null : producerCursorWitness(before);
      M11ApplicationResult result = runtime.submit(action.request());
      verifyExpected(action, result.response());
      if (action.expected() == Expected.NEW) {
        require(action.newOrdinal() == expectedNewOrdinal, "NEW ordinal is discontinuous");
        require(
            action.request().slot().producerSequence() == expectedNewOrdinal,
            "producer sequence is discontinuous");
        require(
            result.response().applicationSequence().orElseThrow() == expectedNewOrdinal,
            "application sequence is discontinuous");
        expectedNewOrdinal++;
      } else {
        M11RuntimeState after = runtime.stateImage();
        require(before.equals(after), "non-mutating action changed direct state");
        require(
            cursorBefore.equals(producerCursorWitness(after)),
            "non-mutating action changed producer cursors");
        if (action.expected() == Expected.DUPLICATE) {
          M11ApplicationResult original = results.get(action.sourceActionIndex());
          require(
              result.fullResult().equals(original.fullResult()),
              "duplicate did not replay its original full result");
          duplicateInvariantChecks++;
        } else {
          conflictInvariantChecks++;
        }
      }
      results.add(result);
      counts.merge(action.expected(), 1, Integer::sum);
      if ("COMMAND_ID_SLOT_CONFLICT".equals(action.detail())) {
        commandConflicts++;
      } else if ("SLOT_IDENTITY_CONFLICT".equals(action.detail())) {
        slotConflicts++;
      }
    }
    int newApplications = counts.getOrDefault(Expected.NEW, 0);
    require(
        runtime.nextApplicationSequence() == newApplications + 1L,
        "direct application sequence changed");
    require(
        runtime.stateImage().identityBindings().size() == newApplications,
        "direct identity size changed");
    return new DirectRun(
        List.copyOf(results),
        runtime.stateImage(),
        Map.copyOf(counts),
        commandConflicts,
        slotConflicts,
        duplicateInvariantChecks,
        conflictInvariantChecks,
        expectedNewOrdinal == 2049);
  }

  private static ClusterRun runCluster(
      Corpus corpus,
      List<M11ApplicationResult> expected,
      Path root,
      boolean restart,
      int preferredPort,
      List<PortBlock> excludedPortBlocks) {
    createDirectories(root);
    LaunchedHarness launched = launchHarness(root, preferredPort, excludedPortBlocks);
    PortBlock portBlock = launched.portBlock();
    M11SingleNodeConfig config = launched.config();
    List<M11CommandResponse> responses = new ArrayList<>(ACTIONS_PER_PATH);
    M11RuntimeState beforeSnapshot = null;
    String beforeIdentityDigest = null;
    String beforeSnapshotDigest = null;
    String beforeSemanticDigest = null;
    long beforeNextSequence = 0;
    boolean directoriesPresentBefore = false;
    boolean directoriesPresentAfter = false;
    M11SnapshotWitness completedSnapshot = null;
    M11RuntimeState finalState = null;
    List<M11ApplicationResult> applications = List.of();
    List<Long> sessionIds = List.of();
    List<M11ClusterRuntimeWitness> runtimeWitnesses = new ArrayList<>();
    int crossSnapshotFullResultChecks = 0;
    int crossSnapshotStateChecks = 0;
    int crossSnapshotSequenceChecks = 0;
    int crossSnapshotIdentityChecks = 0;
    int crossSnapshotCursorChecks = 0;
    int conflictStateChecks = 0;
    int conflictSequenceChecks = 0;
    int conflictIdentityChecks = 0;
    int conflictCursorChecks = 0;
    long firstPostRestartApplicationSequence = -1;
    long firstPostRestartProducerSequence = -1;
    String firstPostRestartLane = "";
    String firstPostRestartStatus = "";
    long preSnapshotSessionId = -1;
    long postRestartSessionId = -1;
    long replayedDuplicateSessionId = -1;
    boolean identityReplayedAcrossSessions = false;
    int prefixNew =
        (int)
            corpus.actions().subList(0, SNAPSHOT_AFTER_ACTION).stream()
                .filter(action -> action.expected() == Expected.NEW)
                .count();
    int prefixDuplicate =
        (int)
            corpus.actions().subList(0, SNAPSHOT_AFTER_ACTION).stream()
                .filter(action -> action.expected() == Expected.DUPLICATE)
                .count();
    ObjectNode snapshot = JsonSupport.MAPPER.createObjectNode();
    M11SingleNodeHarness harness = launched.harness();
    Throwable executionFailure = null;
    boolean teardownCompleted = false;
    try {
      runtimeWitnesses.add(harness.runtimeWitness(RESPONSE_TIMEOUT));
      verifyRuntimeWitness(runtimeWitnesses.getFirst(), config);
      for (int index = 0; index < corpus.actions().size(); index++) {
        if (restart && index == SNAPSHOT_AFTER_ACTION) {
          beforeSnapshot = harness.stateImage();
          beforeIdentityDigest =
              new M11RuntimeStateCodec().identityTableDigest(beforeSnapshot.identityBindings());
          beforeSnapshotDigest =
              Hashing.sha256Hex(new M11SnapshotCodec().encodeCurrent(beforeSnapshot));
          beforeSemanticDigest = harness.semanticStateDigest();
          beforeNextSequence = beforeSnapshot.nextApplicationSequence();
          completedSnapshot = harness.takeSnapshot(SNAPSHOT_TIMEOUT);
          verifyCompletedSnapshot(
              completedSnapshot,
              beforeSnapshotDigest,
              beforeIdentityDigest,
              beforeSemanticDigest,
              beforeNextSequence);
          directoriesPresentBefore =
              Files.isRegularFile(config.clusterDirectory().resolve("recording.log"))
                  && Files.isDirectory(config.archiveDirectory());
          infrastructureRequire(
              harness.componentErrors().isEmpty(), "component error before Cluster restart");
          harness.restartFromSnapshot();
          M11ClusterRuntimeWitness restartedWitness = harness.runtimeWitness(RESPONSE_TIMEOUT);
          verifyRuntimeWitness(restartedWitness, config);
          runtimeWitnesses.add(restartedWitness);
          directoriesPresentAfter =
              Files.isRegularFile(config.clusterDirectory().resolve("recording.log"))
                  && Files.isDirectory(config.archiveDirectory());
          M11RuntimeState restored = harness.stateImage();
          require(beforeSnapshot.equals(restored), "Cluster restart did not restore exact state");
          require(
              beforeIdentityDigest.equals(
                  new M11RuntimeStateCodec().identityTableDigest(restored.identityBindings())),
              "Cluster restart identity digest changed");
          require(
              beforeSnapshotDigest.equals(
                  Hashing.sha256Hex(new M11SnapshotCodec().encodeCurrent(restored))),
              "Cluster restart snapshot digest changed");
          require(
              beforeSemanticDigest.equals(harness.semanticStateDigest()),
              "Cluster restart semantic digest changed");
          require(
              beforeNextSequence == restored.nextApplicationSequence(),
              "Cluster restart sequence changed");
          M11HarnessReport afterRestart = harness.report();
          require(
              afterRestart.restartDirectoriesPreserved(), "Aeron directories were not preserved");
          require(afterRestart.completedSnapshotLoaded(), "completed snapshot was not loaded");
          require(afterRestart.restarts() == 1, "Cluster restart count changed");
          require(
              afterRestart
                  .lastLoadedSnapshot()
                  .orElseThrow()
                  .equals(completedSnapshot.applicationSnapshot()),
              "restart loaded a different application snapshot");
        }
        Action action = corpus.actions().get(index);
        boolean crossSnapshotDuplicate = restart && action.crossSnapshotDuplicate();
        boolean conflict = restart && action.expected() == Expected.CONFLICT;
        M11RuntimeState stateBefore =
            crossSnapshotDuplicate || conflict ? harness.stateImage() : null;
        long sequenceBefore = stateBefore == null ? -1 : stateBefore.nextApplicationSequence();
        List<io.github.lchareln.cex.matching.cluster.M11IdentityBinding> identitiesBefore =
            stateBefore == null ? List.of() : stateBefore.identityBindings();
        String cursorBefore = stateBefore == null ? "" : producerCursorWitness(stateBefore);
        M11CommandResponse response = harness.submit(action.request(), RESPONSE_TIMEOUT);
        verifyExpected(action, response);
        require(
            response.equals(expected.get(index).response()),
            "Cluster response diverged at action " + index);
        responses.add(response);
        if (restart && index == SNAPSHOT_AFTER_ACTION) {
          firstPostRestartApplicationSequence = response.applicationSequence().orElseThrow();
          firstPostRestartProducerSequence = action.request().slot().producerSequence();
          firstPostRestartLane = action.lane().name();
          firstPostRestartStatus = response.status().name();
          require(action.expected() == Expected.NEW, "first post-restart action is not NEW");
          require(
              firstPostRestartApplicationSequence == beforeNextSequence,
              "first post-restart application sequence did not continue");
          require(
              firstPostRestartProducerSequence == beforeNextSequence,
              "first post-restart producer sequence did not continue");
        }
        if (stateBefore != null) {
          M11RuntimeState stateAfter = harness.stateImage();
          require(stateBefore.equals(stateAfter), "non-mutating Cluster action changed state");
          require(
              sequenceBefore == stateAfter.nextApplicationSequence(),
              "non-mutating Cluster action advanced application sequence");
          require(
              identitiesBefore.equals(stateAfter.identityBindings()),
              "non-mutating Cluster action changed identity bindings");
          require(
              cursorBefore.equals(producerCursorWitness(stateAfter)),
              "non-mutating Cluster action changed producer cursors");
          if (crossSnapshotDuplicate) {
            crossSnapshotStateChecks++;
            crossSnapshotSequenceChecks++;
            crossSnapshotIdentityChecks++;
            crossSnapshotCursorChecks++;
          } else {
            conflictStateChecks++;
            conflictSequenceChecks++;
            conflictIdentityChecks++;
            conflictCursorChecks++;
          }
        }
      }
      List<M11ServiceObservation> observations = harness.observations();
      require(observations.size() == ACTIONS_PER_PATH, "Cluster observation count changed");
      sessionIds =
          observations.stream().map(M11ServiceObservation::clusterSessionId).distinct().toList();
      preSnapshotSessionId = observations.getFirst().clusterSessionId();
      postRestartSessionId =
          restart
              ? observations.get(SNAPSHOT_AFTER_ACTION).clusterSessionId()
              : preSnapshotSessionId;
      applications = observations.stream().map(M11ServiceObservation::applicationResult).toList();
      if (restart) {
        for (int index = SNAPSHOT_AFTER_ACTION; index < corpus.actions().size(); index++) {
          Action action = corpus.actions().get(index);
          if (!action.crossSnapshotDuplicate()) {
            continue;
          }
          require(
              action.sourceActionIndex() < SNAPSHOT_AFTER_ACTION,
              "duplicate source is not snapshotted");
          M11ApplicationResult original = applications.get(action.sourceActionIndex());
          M11ApplicationResult replay = applications.get(index);
          require(
              replay.fullResult().equals(original.fullResult()),
              "cross-snapshot duplicate lost its original full result");
          require(
              replay.fullResult().equals(expected.get(action.sourceActionIndex()).fullResult()),
              "cross-snapshot duplicate disagreed with the direct original result");
          require(
              replay
                  .response()
                  .applicationSequence()
                  .equals(original.response().applicationSequence()),
              "cross-snapshot duplicate changed the original application sequence");
          crossSnapshotFullResultChecks++;
          if (replayedDuplicateSessionId < 0) {
            preSnapshotSessionId = observations.get(action.sourceActionIndex()).clusterSessionId();
            replayedDuplicateSessionId = observations.get(index).clusterSessionId();
          }
        }
        identityReplayedAcrossSessions =
            crossSnapshotFullResultChecks > 0 && preSnapshotSessionId != replayedDuplicateSessionId;
        require(sessionIds.size() >= 2, "restart path did not observe a new client session");
        require(
            identityReplayedAcrossSessions,
            "same durable identity was not replayed across client sessions");
      }
      require(applications.equals(expected), "Cluster full business observations diverged");
      infrastructureRequire(
          harness.componentErrors().isEmpty(), "Aeron component errors were observed");
      finalState = harness.stateImage();
    } catch (RuntimeException | Error failure) {
      executionFailure = failure;
    } finally {
      try {
        harness.close();
        teardownCompleted = true;
      } catch (RuntimeException | Error closeFailure) {
        if (executionFailure == null) {
          executionFailure = closeFailure;
        } else {
          executionFailure.addSuppressed(closeFailure);
        }
      }
    }
    M11HarnessReport harnessReport = harness.report();
    if (executionFailure != null) {
      if (executionFailure instanceof RuntimeException runtimeFailure) {
        throw runtimeFailure;
      }
      throw (Error) executionFailure;
    }
    infrastructureRequire(teardownCompleted, "Cluster teardown did not complete");
    infrastructureRequire(
        harnessReport.componentErrorCount() == 0,
        "Aeron component errors were observed during or after teardown");
    if (restart) {
      int expectedCrossSnapshotDuplicates =
          (int) corpus.actions().stream().filter(Action::crossSnapshotDuplicate).count();
      int expectedConflicts =
          (int)
              corpus.actions().stream()
                  .filter(action -> action.expected() == Expected.CONFLICT)
                  .count();
      require(prefixNew == 1536, "snapshot prefix NEW count differs from the frozen schedule");
      require(
          prefixDuplicate == 512,
          "snapshot prefix duplicate count differs from the frozen schedule");
      require(
          crossSnapshotFullResultChecks == expectedCrossSnapshotDuplicates,
          "not every cross-snapshot duplicate replayed its full original result");
      require(
          crossSnapshotStateChecks == expectedCrossSnapshotDuplicates
              && crossSnapshotSequenceChecks == expectedCrossSnapshotDuplicates
              && crossSnapshotIdentityChecks == expectedCrossSnapshotDuplicates
              && crossSnapshotCursorChecks == expectedCrossSnapshotDuplicates,
          "not every cross-snapshot duplicate preserved all state witnesses");
      require(
          conflictStateChecks == expectedConflicts
              && conflictSequenceChecks == expectedConflicts
              && conflictIdentityChecks == expectedConflicts
              && conflictCursorChecks == expectedConflicts,
          "not every identity conflict preserved all state witnesses");
      require(beforeSnapshot != null, "snapshot cut was not executed");
      require(completedSnapshot != null, "snapshot completion witness is missing");
      M11ApplicationSnapshotWitness application = completedSnapshot.applicationSnapshot();
      snapshot.put("schemaVersion", "matching.m11.snapshot-restart.v1");
      snapshot.put("status", M11CheckRunner.PASS);
      snapshot.put("requestedAfterAction", SNAPSHOT_AFTER_ACTION);
      snapshot.put("snapshotPrefixNewApplied", prefixNew);
      snapshot.put("snapshotPrefixDuplicateReplayed", prefixDuplicate);
      snapshot.put("snapshotIdentityBindings", beforeSnapshot.identityBindings().size());
      snapshot.put("snapshotNextApplicationSequence", beforeNextSequence);
      snapshot.put("firstPostRestartLane", firstPostRestartLane);
      snapshot.put("firstPostRestartStatus", firstPostRestartStatus);
      snapshot.put("firstPostRestartApplicationSequence", firstPostRestartApplicationSequence);
      snapshot.put("firstPostRestartProducerSequence", firstPostRestartProducerSequence);
      snapshot.put("postRestartCrossSnapshotDuplicates", crossSnapshotFullResultChecks);
      snapshot.put("postRestartDuplicateFullResultExact", crossSnapshotFullResultChecks > 0);
      snapshot.put("postRestartDuplicateStateInvariantChecks", crossSnapshotStateChecks);
      snapshot.put("postRestartDuplicateSequenceInvariantChecks", crossSnapshotSequenceChecks);
      snapshot.put("postRestartDuplicateIdentityInvariantChecks", crossSnapshotIdentityChecks);
      snapshot.put("postRestartDuplicateCursorInvariantChecks", crossSnapshotCursorChecks);
      snapshot.put("postRestartConflictStateInvariantChecks", conflictStateChecks);
      snapshot.put("postRestartConflictSequenceInvariantChecks", conflictSequenceChecks);
      snapshot.put("postRestartConflictIdentityInvariantChecks", conflictIdentityChecks);
      snapshot.put("postRestartConflictCursorInvariantChecks", conflictCursorChecks);
      snapshot.put(
          "adminRequestAccepted",
          completedSnapshot.adminAcceptance().correlationId() > 0
              && "SNAPSHOT".equals(completedSnapshot.adminAcceptance().requestType().name())
              && "OK".equals(completedSnapshot.adminAcceptance().responseCode().name()));
      snapshot.put(
          "completionBounded",
          completedSnapshot.completion().completionCountAfter()
              > completedSnapshot.completion().completionCountBefore());
      snapshot.put(
          "controlToggleResetToNeutral",
          "NEUTRAL".equals(completedSnapshot.completion().controlToggleState().name()));
      snapshot.put(
          "recordingLogNewSnapshotEntry", completedSnapshot.completion().recordingIdsChanged());
      snapshot.put(
          "acceptanceDistinctFromCompletion",
          completedSnapshot.adminAcceptance().correlationId() > 0
              && completedSnapshot.completion().completionCountAfter()
                  > completedSnapshot.completion().completionCountBefore());
      snapshot.put(
          "closedOnlyAfterCompletion",
          teardownCompleted && harnessReport.snapshotsCompleted() == 1);
      snapshot.put("componentErrorsAfterTeardown", harnessReport.componentErrorCount());
      snapshot.put("restartCount", harnessReport.restarts());
      snapshot.put(
          "directoriesPreserved",
          directoriesPresentBefore
              && directoriesPresentAfter
              && harnessReport.restartDirectoriesPreserved());
      snapshot.put("loadedSnapshot", harnessReport.completedSnapshotLoaded());
      snapshot.put(
          "identityDigestExact", application.identityTableDigest().equals(beforeIdentityDigest));
      snapshot.put(
          "semanticDigestExact", application.semanticStateDigest().equals(beforeSemanticDigest));
      snapshot.put(
          "nextApplicationSequenceExact",
          application.nextApplicationSequence() == beforeNextSequence);
      snapshot.put("duplicateOriginalResultsSurvived", crossSnapshotFullResultChecks);
      snapshot.put("conflictsRemainNonMutating", conflictStateChecks);
      snapshot.put("distinctClientSessionIds", sessionIds.size());
      snapshot.put("preSnapshotSessionId", preSnapshotSessionId);
      snapshot.put("postRestartSessionId", postRestartSessionId);
      snapshot.put("replayedDuplicateSessionId", replayedDuplicateSessionId);
      snapshot.put("identityReplayedAcrossSessions", identityReplayedAcrossSessions);
      snapshot.put("adminCorrelationId", completedSnapshot.adminAcceptance().correlationId());
      snapshot.put("completionCountBefore", completedSnapshot.completion().completionCountBefore());
      snapshot.put("completionCountAfter", completedSnapshot.completion().completionCountAfter());
      snapshot.put("controlToggleState", "NEUTRAL");
      snapshot.put(
          "previousServiceRecordingId",
          completedSnapshot.completion().previousServiceRecordingId());
      snapshot.put(
          "previousConsensusRecordingId",
          completedSnapshot.completion().previousConsensusRecordingId());
      snapshot.put(
          "serviceLeadershipTermId", completedSnapshot.completion().serviceLeadershipTermId());
      snapshot.put(
          "consensusLeadershipTermId", completedSnapshot.completion().consensusLeadershipTermId());
      snapshot.put("serviceLogPosition", completedSnapshot.completion().serviceLogPosition());
      snapshot.put("consensusLogPosition", completedSnapshot.completion().consensusLogPosition());
      snapshot.put("serviceRecordingId", completedSnapshot.completion().serviceRecordingId());
      snapshot.put("consensusRecordingId", completedSnapshot.completion().consensusRecordingId());
      snapshot.put("recordingIdsChanged", completedSnapshot.completion().recordingIdsChanged());
      snapshot.put(
          "sameTermAndLogPosition", completedSnapshot.completion().sameTermAndLogPosition());
      snapshot.put("snapshotSequence", application.snapshotSequence());
      snapshot.put("snapshotStateSha256", beforeSnapshotDigest);
      snapshot.put("identityTableSha256", beforeIdentityDigest);
      snapshot.put("semanticStateSha256", beforeSemanticDigest);
      snapshot.put("restoredNextApplicationSequence", beforeNextSequence);
      snapshot.put("finalNextApplicationSequence", finalState.nextApplicationSequence());
    }
    return new ClusterRun(
        List.copyOf(responses),
        applications,
        finalState,
        snapshot,
        harnessReport,
        applications.size(),
        portBlock,
        List.copyOf(runtimeWitnesses),
        teardownCompleted,
        launched.launchAttempts());
  }

  private static void verifyCompletedSnapshot(
      M11SnapshotWitness witness,
      String expectedSnapshotDigest,
      String expectedIdentityDigest,
      String expectedSemanticDigest,
      long expectedNextSequence) {
    require(witness.adminAcceptance().correlationId() > 0, "snapshot admin correlation is missing");
    require(
        "SNAPSHOT".equals(witness.adminAcceptance().requestType().name()),
        "wrong snapshot admin request type");
    require(
        "OK".equals(witness.adminAcceptance().responseCode().name()), "snapshot was not accepted");
    require(
        witness.completion().completionCountAfter() > witness.completion().completionCountBefore(),
        "snapshot completion counter did not advance");
    require(
        "NEUTRAL".equals(witness.completion().controlToggleState().name()),
        "snapshot control toggle did not reset");
    require(witness.completion().leadershipTermId() >= 0, "snapshot term witness is missing");
    require(witness.completion().logPosition() >= 0, "snapshot log position is missing");
    require(
        witness.completion().serviceRecordingId() >= 0, "service snapshot recording is missing");
    require(
        witness.completion().consensusRecordingId() >= 0,
        "consensus snapshot recording is missing");
    require(witness.completion().recordingIdsChanged(), "snapshot recording IDs did not change");
    require(
        witness.completion().sameTermAndLogPosition(),
        "service and consensus snapshots do not share term/log position");
    M11ApplicationSnapshotWitness application = witness.applicationSnapshot();
    require(
        application.snapshotSequence() == expectedNextSequence - 1,
        "application snapshot sequence changed");
    require(application.snapshotDigest().equals(expectedSnapshotDigest), "snapshot digest changed");
    require(
        application.identityTableDigest().equals(expectedIdentityDigest),
        "identity digest changed");
    require(
        application.semanticStateDigest().equals(expectedSemanticDigest),
        "semantic digest changed");
    require(
        application.nextApplicationSequence() == expectedNextSequence,
        "snapshot next application sequence changed");
  }

  private static void requireClusterCounts(
      M11HarnessReport report, boolean restarted, Map<Expected, Integer> expectedCounts) {
    require(report.ingressOffersAccepted() == ACTIONS_PER_PATH, "Cluster ingress count changed");
    require(report.correlatedEgressResponses() == ACTIONS_PER_PATH, "Cluster egress count changed");
    require(
        report.newBusinessApplications() == expectedCounts.getOrDefault(Expected.NEW, 0),
        "Cluster NEW count changed");
    require(
        report.duplicateReplays() == expectedCounts.getOrDefault(Expected.DUPLICATE, 0),
        "Cluster duplicate count changed");
    require(
        report.rejectedApplications() == expectedCounts.getOrDefault(Expected.CONFLICT, 0),
        "Cluster rejection count changed");
    infrastructureRequire(
        report.componentErrorCount() == 0, "Cluster component error count changed");
    require(
        report.snapshotAdminAccepted() == (restarted ? 1 : 0), "snapshot acceptance count changed");
    require(
        report.snapshotsCompleted() == (restarted ? 1 : 0), "snapshot completion count changed");
    require(report.restarts() == (restarted ? 1 : 0), "restart count changed");
    if (restarted) {
      require(report.restartDirectoriesPreserved(), "restart did not preserve directories");
      require(report.completedSnapshotLoaded(), "restart did not load completed snapshot");
      require(report.lastSnapshot().isPresent(), "completed snapshot report is missing");
      require(report.lastLoadedSnapshot().isPresent(), "loaded snapshot report is missing");
      require(
          report
              .lastSnapshot()
              .orElseThrow()
              .applicationSnapshot()
              .equals(report.lastLoadedSnapshot().orElseThrow()),
          "loaded snapshot witness differs from completed snapshot");
    }
  }

  private static void verifyExpected(Action action, M11CommandResponse response) {
    switch (action.expected()) {
      case NEW ->
          require(response.status() == M11ResponseStatus.NEW_APPLIED, "new action was not applied");
      case DUPLICATE ->
          require(
              response.status() == M11ResponseStatus.DUPLICATE_REPLAYED,
              "duplicate was not replayed");
      case CONFLICT -> {
        require(
            response.status() == M11ResponseStatus.REJECTED, "identity conflict was not rejected");
        require(
            response.rejectionCode().orElseThrow().equals(action.detail()),
            "wrong identity rejection");
      }
    }
    require(
        response.correlationId().equals(action.request().correlationId()), "correlation changed");
  }

  private static void verifyRuntimeWitness(
      M11ClusterRuntimeWitness witness, M11SingleNodeConfig config) {
    require(witness.clusterId() == config.clusterId(), "runtime cluster ID differs from config");
    require(witness.memberCount() == 1, "runtime member count is not one");
    require(witness.memberId() == 0, "runtime member ID is not zero");
    require(witness.appointedLeaderId() == 0, "runtime appointed leader is not zero");
    require(
        witness.clusterMembers().equals(config.clusterMembers()),
        "runtime cluster member string differs from config");
    require("LEADER".equals(witness.serviceRole()), "ClusteredService role is not LEADER");
    require(
        "1.52.2".equals(witness.aeronImplementationVersion()),
        "runtime Aeron version is not 1.52.2");
    require(
        "2.5.0".equals(witness.agronaImplementationVersion()),
        "runtime Agrona version is not 2.5.0");
    require(
        witness.rootDirectory().equals(config.rootDirectory().toString()),
        "runtime root differs from config");
    require(
        witness.udpPortBlockBase() == config.portBase(),
        "runtime UDP port block differs from config");
  }

  private static List<SegmentSpec> segmentSchedule() {
    List<SegmentSpec> result = new ArrayList<>(SEGMENTS);
    for (int index = 0; index < 8; index++) {
      result.add(new SegmentSpec(Lane.CURRENT_NEW, index));
    }
    for (int index = 0; index < 4; index++) {
      result.add(new SegmentSpec(Lane.DUPLICATE_REPLAY, index));
    }
    for (int index = 0; index < 8; index++) {
      result.add(new SegmentSpec(Lane.PREVIOUS_NEW, index));
    }
    for (int index = 4; index < 8; index++) {
      result.add(new SegmentSpec(Lane.DUPLICATE_REPLAY, index));
    }
    for (int index = 0; index < 8; index++) {
      result.add(new SegmentSpec(Lane.IDENTITY_CONFLICT, index));
    }
    require(result.size() == SEGMENTS, "M11 segment schedule changed");
    return List.copyOf(result);
  }

  private static String producerCursorWitness(M11RuntimeState state) {
    java.util.SortedMap<String, String> cursors = new java.util.TreeMap<>();
    for (io.github.lchareln.cex.matching.cluster.M11IdentityBinding binding :
        state.identityBindings()) {
      String producer = binding.slot().producerId() + '\u0000' + binding.slot().shardId();
      cursors.put(
          producer,
          binding.slot().producerEpoch()
              + ":"
              + Math.incrementExact(binding.slot().producerSequence()));
    }
    return cursors.toString();
  }

  private static void addPortBlock(ArrayNode target, String run, ClusterRun clusterRun) {
    ObjectNode block = target.addObject();
    block.put("run", run);
    block.put("root", clusterRun.runtimeWitnesses().getFirst().rootDirectory());
    block.put("protocol", "UDP");
    block.put("base", clusterRun.portBlock().base());
    block.put("last", clusterRun.portBlock().last());
    block.put("size", PORT_BLOCK_SIZE);
    block.put("probeAttempts", clusterRun.portBlock().probeAttempts());
  }

  private static void addRuntimeWitness(
      ArrayNode target, String phase, M11ClusterRuntimeWitness witness) {
    ObjectNode value = target.addObject();
    value.put("phase", phase);
    value.put("clusterId", witness.clusterId());
    value.put("memberCount", witness.memberCount());
    value.put("memberId", witness.memberId());
    value.put("appointedLeaderId", witness.appointedLeaderId());
    value.put("clusterMembers", witness.clusterMembers());
    value.put("serviceRole", witness.serviceRole());
    value.put("aeronImplementationVersion", witness.aeronImplementationVersion());
    value.put("agronaImplementationVersion", witness.agronaImplementationVersion());
    value.put("rootDirectory", witness.rootDirectory());
    value.put("udpPortBlockBase", witness.udpPortBlockBase());
  }

  private static M11CommandRequest create(
      M11RequestCodec codec,
      int requestVersion,
      int responseVersion,
      UUID correlation,
      String producer,
      long epoch,
      long producerSequence,
      UUID command,
      M08Command payload) {
    try {
      return codec.create(
          requestVersion,
          responseVersion,
          correlation,
          producer,
          epoch,
          SHARD,
          producerSequence,
          command,
          payload);
    } catch (M11ProtocolException failure) {
      throw new IllegalStateException("generated M11 request is invalid", failure);
    }
  }

  private static M08Command.Place place(long orderId, M03SplitMix64V1 random) {
    return new M08Command.Place(
        "BTC-USDT",
        BigInteger.valueOf(orderId),
        "BUY",
        BigInteger.valueOf(1_000_000L + random.nextInt(5_000_000)),
        BigInteger.valueOf(1L + random.nextInt(10)),
        "GTC",
        0,
        "NONE",
        Optional.empty());
  }

  private static M08Command.Place conflictingPlace(long token, M03SplitMix64V1 random) {
    return new M08Command.Place(
        "BTC-USDT",
        BigInteger.valueOf(10_000_000L + token),
        "BUY",
        BigInteger.valueOf(7_000_000L + random.nextInt(1_000_000)),
        BigInteger.ONE,
        "GTC",
        0,
        "NONE",
        Optional.empty());
  }

  private static UUID uuid(M03SplitMix64V1 random) {
    return new UUID(random.nextLong(), random.nextLong());
  }

  private static byte[] canonical(List<Action> actions) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream out = new DataOutputStream(bytes)) {
        for (Action action : actions) {
          byte[] encoded = new M11RequestCodec().encode(action.request());
          out.writeInt(action.globalIndex());
          out.writeInt(action.segment());
          out.writeInt(action.action());
          out.writeByte(action.lane().ordinal());
          out.writeInt(action.laneIndex());
          out.writeByte(action.expected().ordinal());
          out.writeLong(action.newOrdinal());
          out.writeInt(action.sourceActionIndex());
          out.writeBoolean(action.crossSnapshotDuplicate());
          out.writeInt(encoded.length);
          out.write(encoded);
        }
      }
      return bytes.toByteArray();
    } catch (IOException impossible) {
      throw new IllegalStateException("cannot encode M11 corpus", impossible);
    }
  }

  private static LaunchedHarness launchHarness(
      Path root, int preferredPort, List<PortBlock> excludedPortBlocks) {
    List<PortBlock> unavailable = new ArrayList<>(excludedPortBlocks);
    RuntimeException lastBindFailure = null;
    for (int launchAttempt = 1; launchAttempt <= MAX_CLUSTER_LAUNCH_ATTEMPTS; launchAttempt++) {
      PortBlock portBlock = findUdpPortBlock(preferredPort, unavailable);
      M11SingleNodeConfig config = M11SingleNodeConfig.defaults(root, SHARD, portBlock.base());
      try {
        return new LaunchedHarness(
            config, portBlock, M11SingleNodeHarness.launchFresh(config), launchAttempt);
      } catch (RuntimeException failure) {
        if (!isPortBindFailure(failure)) {
          throw failure;
        }
        lastBindFailure = failure;
        unavailable.add(portBlock);
        M09ScenarioSupport.deleteTree(root);
        createDirectories(root);
      }
    }
    throw new IllegalStateException(
        "M11 Cluster exhausted the bounded UDP bind retry budget", lastBindFailure);
  }

  private static boolean isPortBindFailure(Throwable failure) {
    for (Throwable cursor = failure; cursor != null; cursor = cursor.getCause()) {
      if (cursor instanceof java.net.BindException) {
        return true;
      }
      String message = cursor.getMessage();
      if (message != null) {
        String normalized = message.toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("address already in use")
            || normalized.contains("failed to bind")
            || normalized.contains("bind failed")) {
          return true;
        }
      }
    }
    return false;
  }

  private static PortBlock findUdpPortBlock(int preferred, List<PortBlock> excluded) {
    for (int attempt = 0; attempt < MAX_PORT_PROBE_ATTEMPTS; attempt++) {
      int candidate = preferred + attempt * (PORT_BLOCK_SIZE + 1);
      if (candidate > 65_530) {
        break;
      }
      PortBlock proposed = new PortBlock(candidate, attempt + 1);
      if (excluded.stream().anyMatch(block -> !block.disjoint(proposed))) {
        continue;
      }
      List<DatagramSocket> sockets = new ArrayList<>();
      try {
        for (int offset = 0; offset < PORT_BLOCK_SIZE; offset++) {
          DatagramSocket socket = new DatagramSocket(null);
          socket.setReuseAddress(false);
          socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), candidate + offset));
          sockets.add(socket);
        }
        return proposed;
      } catch (IOException unavailable) {
        // Continue the bounded deterministic scan.
      } finally {
        sockets.forEach(M11GeneratedSuite::closeQuietly);
      }
    }
    throw new IllegalStateException(
        "no free UDP loopback port block after " + MAX_PORT_PROBE_ATTEMPTS + " attempts");
  }

  private static void closeQuietly(DatagramSocket socket) {
    socket.close();
  }

  private static void createDirectories(Path path) {
    try {
      Files.createDirectories(path);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot create M11 working directory", failure);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new M11SemanticFailure(message);
    }
  }

  private static void infrastructureRequire(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }

  enum Lane {
    CURRENT_NEW,
    PREVIOUS_NEW,
    DUPLICATE_REPLAY,
    IDENTITY_CONFLICT
  }

  enum Expected {
    NEW,
    DUPLICATE,
    CONFLICT
  }

  record Action(
      int globalIndex,
      int segment,
      int action,
      Lane lane,
      int laneIndex,
      Expected expected,
      M11CommandRequest request,
      long newOrdinal,
      int sourceActionIndex,
      boolean crossSnapshotDuplicate,
      String detail) {}

  record SegmentSpec(Lane lane, int laneIndex) {
    String id() {
      return lane.name() + "[" + laneIndex + "]";
    }
  }

  record Original(int actionIndex, long newOrdinal, M11CommandRequest request) {}

  record Corpus(List<Action> actions, byte[] canonicalBytes, List<String> segmentSchedule) {
    Corpus {
      actions = List.copyOf(actions);
      canonicalBytes = canonicalBytes.clone();
      segmentSchedule = List.copyOf(segmentSchedule);
    }

    @Override
    public byte[] canonicalBytes() {
      return canonicalBytes.clone();
    }
  }

  record DirectRun(
      List<M11ApplicationResult> results,
      M11RuntimeState finalState,
      Map<Expected, Integer> counts,
      int commandConflicts,
      int slotConflicts,
      int duplicateInvariantChecks,
      int conflictInvariantChecks,
      boolean newOrdinalsContinuous) {}

  record ClusterRun(
      List<M11CommandResponse> responses,
      List<M11ApplicationResult> applicationResults,
      M11RuntimeState finalState,
      ObjectNode snapshotReport,
      M11HarnessReport harnessReport,
      int observationCount,
      PortBlock portBlock,
      List<M11ClusterRuntimeWitness> runtimeWitnesses,
      boolean teardownCompleted,
      int launchAttempts) {
    ClusterRun {
      runtimeWitnesses = List.copyOf(runtimeWitnesses);
    }
  }

  record PortBlock(int base, int probeAttempts) {
    int last() {
      return base + PORT_BLOCK_SIZE - 1;
    }

    boolean disjoint(PortBlock other) {
      return last() < other.base() || other.last() < base;
    }
  }

  record LaunchedHarness(
      M11SingleNodeConfig config,
      PortBlock portBlock,
      M11SingleNodeHarness harness,
      int launchAttempts) {}

  record Result(
      ObjectNode generatedReport,
      ObjectNode clusterReport,
      ObjectNode snapshotReport,
      byte[] canonicalBytes,
      List<M11ApplicationResult> directResults,
      List<M11ApplicationResult> uninterruptedResults,
      List<M11ApplicationResult> restartedResults,
      M11RuntimeState finalState,
      Path clusterRoot) {
    Result {
      canonicalBytes = canonicalBytes.clone();
      directResults = List.copyOf(directResults);
      uninterruptedResults = List.copyOf(uninterruptedResults);
      restartedResults = List.copyOf(restartedResults);
    }

    @Override
    public byte[] canonicalBytes() {
      return canonicalBytes.clone();
    }
  }
}
