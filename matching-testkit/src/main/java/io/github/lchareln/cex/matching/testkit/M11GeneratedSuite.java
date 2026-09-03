package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.cluster.DirectM11MatchingRuntime;
import io.github.lchareln.cex.matching.cluster.M11ApplicationResult;
import io.github.lchareln.cex.matching.cluster.M11ApplicationSnapshotWitness;
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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
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
        runCluster(first, direct.results(), workingRoot.resolve("uninterrupted"), false, 23_111);
    ClusterRun restarted =
        runCluster(first, direct.results(), workingRoot.resolve("snapshot-restart"), true, 33_111);
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
    requireClusterCounts(uninterruptedHarness, false);
    requireClusterCounts(restartedHarness, true);

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
    ArrayNode lanes = generated.putArray("lanes");
    for (Lane lane : Lane.values()) {
      ObjectNode value = lanes.addObject();
      value.put("id", lane.name());
      value.put("segments", 8);
      value.put("actions", 1024);
    }

    ObjectNode cluster = JsonSupport.MAPPER.createObjectNode();
    cluster.put("schemaVersion", "matching.m11.cluster-runtime.v1");
    cluster.put("status", M11CheckRunner.PASS);
    cluster.put("implementation", "REAL_AERON_CLUSTER");
    cluster.put("memberCount", 1);
    cluster.put("memberId", 0);
    cluster.put("appointedLeaderId", 0);
    cluster.put("clusterRuns", CLUSTER_RUNS);
    cluster.put("actionsPerRun", ACTIONS_PER_PATH);
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
    cluster.put("correlationRoundTrips", TOTAL_CLUSTER_INGRESS);
    cluster.put(
        "allBusinessOutcomesFromCorrelatedEgress",
        uninterruptedHarness.ingressOffersAccepted()
                == uninterruptedHarness.correlatedEgressResponses()
            && restartedHarness.ingressOffersAccepted()
                == restartedHarness.correlatedEgressResponses());
    cluster.put("singleMemberOnly", true);
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

  private static Corpus generate() {
    M11RequestCodec codec = new M11RequestCodec();
    List<Action> actions = new ArrayList<>(ACTIONS_PER_PATH);
    List<M11CommandRequest> originals = new ArrayList<>(SNAPSHOT_AFTER_ACTION);
    M03SplitMix64V1 random = new M03SplitMix64V1(BASE_SEED);
    for (int segment = 0; segment < 16; segment++) {
      Lane lane = segment < 8 ? Lane.CURRENT_NEW : Lane.PREVIOUS_NEW;
      int requestVersion = lane == Lane.CURRENT_NEW ? 2 : 1;
      for (int action = 0; action < ACTIONS_PER_SEGMENT; action++) {
        int global = segment * ACTIONS_PER_SEGMENT + action;
        M11CommandRequest request =
            create(
                codec,
                requestVersion,
                requestVersion,
                uuid(random),
                "m11-generated",
                1,
                global + 1L,
                uuid(random),
                place(global + 1L, random));
        originals.add(request);
        actions.add(new Action(global, segment, action, lane, Expected.NEW, request, "NEW"));
      }
    }
    for (int segment = 16; segment < 24; segment++) {
      for (int action = 0; action < ACTIONS_PER_SEGMENT; action++) {
        int global = segment * ACTIONS_PER_SEGMENT + action;
        int source = (segment - 16) * ACTIONS_PER_SEGMENT + action;
        M11CommandRequest duplicate = originals.get(source).withCorrelationId(uuid(random));
        actions.add(
            new Action(
                global,
                segment,
                action,
                Lane.DUPLICATE_REPLAY,
                Expected.DUPLICATE,
                duplicate,
                "SOURCE_" + source));
      }
    }
    for (int segment = 24; segment < 32; segment++) {
      for (int action = 0; action < ACTIONS_PER_SEGMENT; action++) {
        int global = segment * ACTIONS_PER_SEGMENT + action;
        int source = (8 + segment - 24) * ACTIONS_PER_SEGMENT + action;
        M11CommandRequest original = originals.get(source);
        boolean commandConflict = (action & 1) == 0;
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
                Lane.IDENTITY_CONFLICT,
                Expected.CONFLICT,
                conflict,
                detail));
      }
    }
    require(actions.size() == ACTIONS_PER_PATH, "M11 generator did not fill the corpus");
    return new Corpus(List.copyOf(actions), canonical(actions));
  }

  private static DirectRun runDirect(Corpus corpus) {
    DirectM11MatchingRuntime runtime = new DirectM11MatchingRuntime();
    List<M11ApplicationResult> results = new ArrayList<>(ACTIONS_PER_PATH);
    Map<Expected, Integer> counts = new EnumMap<>(Expected.class);
    int commandConflicts = 0;
    int slotConflicts = 0;
    for (Action action : corpus.actions()) {
      M11ApplicationResult result = runtime.submit(action.request());
      verifyExpected(action, result.response());
      results.add(result);
      counts.merge(action.expected(), 1, Integer::sum);
      if ("COMMAND_ID_SLOT_CONFLICT".equals(action.detail())) {
        commandConflicts++;
      } else if ("SLOT_IDENTITY_CONFLICT".equals(action.detail())) {
        slotConflicts++;
      }
    }
    require(runtime.nextApplicationSequence() == 2049, "direct application sequence changed");
    require(runtime.stateImage().identityBindings().size() == 2048, "direct identity size changed");
    return new DirectRun(
        List.copyOf(results),
        runtime.stateImage(),
        Map.copyOf(counts),
        commandConflicts,
        slotConflicts);
  }

  private static ClusterRun runCluster(
      Corpus corpus,
      List<M11ApplicationResult> expected,
      Path root,
      boolean restart,
      int preferredPort) {
    createDirectories(root);
    int portBase = findPortBlock(preferredPort);
    M11SingleNodeConfig config = M11SingleNodeConfig.defaults(root, SHARD, portBase);
    List<M11CommandResponse> responses = new ArrayList<>(ACTIONS_PER_PATH);
    M11RuntimeState beforeSnapshot = null;
    String beforeIdentityDigest = null;
    String beforeSnapshotDigest = null;
    String beforeSemanticDigest = null;
    long beforeNextSequence = 0;
    boolean directoriesPresentBefore = false;
    boolean directoriesPresentAfter = false;
    M11SnapshotWitness completedSnapshot = null;
    ObjectNode snapshot = JsonSupport.MAPPER.createObjectNode();
    try (M11SingleNodeHarness harness = M11SingleNodeHarness.launchFresh(config)) {
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
        M11CommandResponse response = harness.submit(action.request(), RESPONSE_TIMEOUT);
        verifyExpected(action, response);
        require(
            response.equals(expected.get(index).response()),
            "Cluster response diverged at action " + index);
        responses.add(response);
      }
      List<M11ServiceObservation> observations = harness.observations();
      require(observations.size() == ACTIONS_PER_PATH, "Cluster observation count changed");
      List<Long> sessionIds =
          observations.stream().map(M11ServiceObservation::clusterSessionId).distinct().toList();
      long preSnapshotSessionId = observations.getFirst().clusterSessionId();
      long postRestartSessionId =
          restart
              ? observations.get(SNAPSHOT_AFTER_ACTION).clusterSessionId()
              : preSnapshotSessionId;
      boolean identityReplayedAcrossSessions = false;
      if (restart) {
        identityReplayedAcrossSessions =
            preSnapshotSessionId != postRestartSessionId
                && corpus
                    .actions()
                    .getFirst()
                    .request()
                    .commandId()
                    .equals(corpus.actions().get(SNAPSHOT_AFTER_ACTION).request().commandId())
                && observations
                    .getFirst()
                    .applicationResult()
                    .fullResult()
                    .equals(
                        observations.get(SNAPSHOT_AFTER_ACTION).applicationResult().fullResult());
        require(sessionIds.size() >= 2, "restart path did not observe a new client session");
        require(
            identityReplayedAcrossSessions,
            "same durable identity was not replayed across client sessions");
      }
      List<M11ApplicationResult> applications =
          observations.stream().map(M11ServiceObservation::applicationResult).toList();
      require(applications.equals(expected), "Cluster full business observations diverged");
      infrastructureRequire(
          harness.componentErrors().isEmpty(), "Aeron component errors were observed");
      M11RuntimeState finalState = harness.stateImage();
      M11HarnessReport harnessReport = harness.report();
      if (restart) {
        require(beforeSnapshot != null, "snapshot cut was not executed");
        require(completedSnapshot != null, "snapshot completion witness is missing");
        M11ApplicationSnapshotWitness application = completedSnapshot.applicationSnapshot();
        snapshot.put("schemaVersion", "matching.m11.snapshot-restart.v1");
        snapshot.put("status", M11CheckRunner.PASS);
        snapshot.put("requestedAfterAction", SNAPSHOT_AFTER_ACTION);
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
        snapshot.put("closedOnlyAfterCompletion", harnessReport.snapshotsCompleted() == 1);
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
        snapshot.put("duplicateOriginalResultsSurvived", 1024);
        snapshot.put("conflictsRemainNonMutating", 1024);
        snapshot.put("distinctClientSessionIds", sessionIds.size());
        snapshot.put("preSnapshotSessionId", preSnapshotSessionId);
        snapshot.put("postRestartSessionId", postRestartSessionId);
        snapshot.put("identityReplayedAcrossSessions", identityReplayedAcrossSessions);
        snapshot.put("adminCorrelationId", completedSnapshot.adminAcceptance().correlationId());
        snapshot.put(
            "completionCountBefore", completedSnapshot.completion().completionCountBefore());
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
            "consensusLeadershipTermId",
            completedSnapshot.completion().consensusLeadershipTermId());
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
      }
      return new ClusterRun(
          List.copyOf(responses),
          applications,
          finalState,
          snapshot,
          harnessReport,
          observations.size());
    }
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

  private static void requireClusterCounts(M11HarnessReport report, boolean restarted) {
    require(report.ingressOffersAccepted() == ACTIONS_PER_PATH, "Cluster ingress count changed");
    require(report.correlatedEgressResponses() == ACTIONS_PER_PATH, "Cluster egress count changed");
    require(report.newBusinessApplications() == 2048, "Cluster NEW count changed");
    require(report.duplicateReplays() == 1024, "Cluster duplicate count changed");
    require(report.rejectedApplications() == 1024, "Cluster rejection count changed");
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
          out.writeByte(action.expected().ordinal());
          out.writeInt(encoded.length);
          out.write(encoded);
        }
      }
      return bytes.toByteArray();
    } catch (IOException impossible) {
      throw new IllegalStateException("cannot encode M11 corpus", impossible);
    }
  }

  private static int findPortBlock(int preferred) {
    for (int candidate = preferred; candidate <= 60_000; candidate += 7) {
      List<ServerSocket> sockets = new ArrayList<>();
      try {
        for (int offset = 0; offset < 6; offset++) {
          ServerSocket socket = new ServerSocket();
          socket.setReuseAddress(false);
          socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), candidate + offset));
          sockets.add(socket);
        }
        return candidate;
      } catch (IOException unavailable) {
        // Continue the bounded deterministic scan.
      } finally {
        sockets.forEach(M11GeneratedSuite::closeQuietly);
      }
    }
    throw new IllegalStateException("no free loopback port block for M11 Cluster");
  }

  private static void closeQuietly(ServerSocket socket) {
    try {
      socket.close();
    } catch (IOException ignored) {
      // Best-effort release of a temporary port reservation.
    }
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
      Expected expected,
      M11CommandRequest request,
      String detail) {}

  record Corpus(List<Action> actions, byte[] canonicalBytes) {
    Corpus {
      actions = List.copyOf(actions);
      canonicalBytes = canonicalBytes.clone();
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
      int slotConflicts) {}

  record ClusterRun(
      List<M11CommandResponse> responses,
      List<M11ApplicationResult> applicationResults,
      M11RuntimeState finalState,
      ObjectNode snapshotReport,
      M11HarnessReport harnessReport,
      int observationCount) {}

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
