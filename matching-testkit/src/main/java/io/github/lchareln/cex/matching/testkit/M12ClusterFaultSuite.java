package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.cluster.M11CommandRequest;
import io.github.lchareln.cex.matching.cluster.M11CommandResponse;
import io.github.lchareln.cex.matching.cluster.M11ResponseStatus;
import io.github.lchareln.cex.matching.cluster.M12InvocationAttempt;
import io.github.lchareln.cex.matching.cluster.M12InvocationOutcome;
import io.github.lchareln.cex.matching.cluster.M12InvocationState;
import io.github.lchareln.cex.matching.cluster.M12MatchingClusterClient;
import io.github.lchareln.cex.matching.cluster.M12MemberStatus;
import io.github.lchareln.cex.matching.cluster.M12ThreeMemberConfig;
import io.github.lchareln.cex.matching.cluster.M12TransportAuthority;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Executes the frozen M12 schedule against three independently owned Aeron member JVMs. */
final class M12ClusterFaultSuite {
  private static final String SOURCE_ARTIFACT = "m12-command-history.json";
  private static final String PASS = "PASS";
  private static final Duration TOPOLOGY_DEADLINE = Duration.ofSeconds(45);
  private static final Duration OFFER_DEADLINE = Duration.ofSeconds(15);
  private static final Duration RESPONSE_DEADLINE = Duration.ofSeconds(15);
  private static final Duration MINORITY_RESPONSE_DEADLINE = Duration.ofSeconds(5);
  private static final Duration STOP_DEADLINE = Duration.ofSeconds(5);
  private static final int PREFERRED_PORT_BASE = 52_100;
  private static final int PORT_SEARCH_ATTEMPTS = 256;
  private static final Pattern EXPECTED_AERON_FAIL_STOP_WARNING =
      Pattern.compile(
          "^io\\.aeron\\.cluster\\.client\\.ClusterEvent: WARN - "
              + "(?:leader heartbeat timeout|inactive follower quorum|quorum position went backwards: "
              + "leaderCommitPosition=[0-9]+ quorumPosition=[0-9]+)$");

  Result run(
      Path repositoryRoot,
      M12WorkloadLoader.Workload workload,
      M12DeterministicCorpus.Corpus corpus) {
    Path root =
        Objects.requireNonNull(repositoryRoot, "repositoryRoot").toAbsolutePath().normalize();
    Objects.requireNonNull(workload, "workload");
    Objects.requireNonNull(corpus, "corpus");
    require(corpus.identities().size() == 66, "the frozen corpus must have 66 identities");
    require(corpus.attempts().size() == 85, "the frozen corpus must have 85 invocations");
    require(corpus.ingressAttemptCount() == 84, "the frozen corpus must have 84 ingress attempts");

    Map<Integer, M12DeterministicCorpus.Attempt> templates = corpus.attemptsByOrdinal();
    Map<String, M12DeterministicCorpus.Binding> observedBindings = new LinkedHashMap<>();
    List<M12DeterministicCorpus.Attempt> observedAttempts = new ArrayList<>(85);
    List<ClientGenerationObservation> clientGenerations = new ArrayList<>(3);
    Path clusterRoot = root.resolve("build/tmp/m12/three-member-failover");
    int portBase =
        M12ThreeMemberProcessHarness.selectAvailablePortBase(
            PREFERRED_PORT_BASE, PORT_SEARCH_ATTEMPTS);
    M12ThreeMemberConfig config = M12ThreeMemberConfig.defaults(clusterRoot, 1, portBase);

    List<M12MemberStatus> initialStatuses;
    List<M12MemberStatus> appliedUnknownReplicatedStatuses;
    List<M12MemberStatus> formerLeaderCatchupStatuses;
    List<M12MemberStatus> finalStatuses;
    M12MemberStatus initialLeader;
    M12MemberStatus faultTargetLeader;
    M12MemberStatus replacementLeader;
    M12MemberStatus quorumRestoredLeader;
    M12TransportAuthority replacementClientAuthority;
    M12ThreeMemberProcessHarness.StoppedMember leaderStop;
    List<Integer> minorityStoppedFollowers;
    List<M12ThreeMemberProcessHarness.MemberProcessView> processHistory;
    List<M12ThreeMemberProcessHarness.StoppedMember> stopHistory;
    List<M12ThreeMemberProcessHarness.RestartSafetyWitness> restartSafetyWitnesses;
    List<M12ThreeMemberProcessHarness.StableSnapshotWitness> stabilityWitnesses;
    M12InvocationAttempt appliedUnknownInvocation;
    M12InvocationAttempt minorityUnknownInvocation;
    int acceptedOffers;
    int decodedResponses;
    int rejectedResponses;
    int clientComponentErrors;
    int minorityAcknowledgements = 0;
    int starts;
    int forcedStops;

    M12ThreeMemberProcessHarness harness = new M12ThreeMemberProcessHarness(config);
    try (harness) {
      harness.launchFresh(TOPOLOGY_DEADLINE);
      initialLeader = harness.awaitInitialTopology(TOPOLOGY_DEADLINE);
      initialStatuses = harness.lastStableStatuses();
      require(
          initialLeader.leadershipTermId() >= 0, "the initial term may be zero but not negative");

      M12DeterministicCorpus.Attempt notSubmittedTemplate = template(templates, 1);
      M12InvocationAttempt notSubmittedInvocation =
          M12InvocationAttempt.first(
              request(corpus, notSubmittedTemplate), notSubmittedTemplate.ordinal(), 1);
      M12InvocationOutcome notSubmitted = notSubmittedInvocation.abandon();
      require(
          notSubmitted.state() == M12InvocationState.NOT_SUBMITTED,
          "the pre-offer boundary must be NOT_SUBMITTED");
      observedAttempts.add(observedNotSubmitted(notSubmittedTemplate, notSubmitted, initialLeader));

      int generationOneAccepted;
      int generationOneDecoded;
      int generationOneRejected;
      int generationOneErrors;
      M12TransportAuthority generationOneAuthority;
      try (M12MatchingClusterClient client = connect(config, clusterRoot, 1, harness)) {
        generationOneAuthority = client.currentAuthority();
        requireAuthority(generationOneAuthority, initialLeader, "generation 1");

        for (int ordinal = 2; ordinal <= 33; ordinal++) {
          ObservedAcknowledgement observation =
              submit(client, corpus, template(templates, ordinal), observedBindings, false);
          require(
              observation.status() == M12DeterministicCorpus.ResponseStatus.NEW_APPLIED,
              "pre-failover command " + ordinal + " was not NEW");
          observedAttempts.add(observation.attempt());
        }
        for (int ordinal = 34; ordinal <= 41; ordinal++) {
          ObservedAcknowledgement observation =
              submit(client, corpus, template(templates, ordinal), observedBindings, false);
          require(
              observation.status() == M12DeterministicCorpus.ResponseStatus.DUPLICATE_REPLAYED,
              "acknowledged retry " + ordinal + " did not replay");
          observedAttempts.add(observation.attempt());
        }

        M12DeterministicCorpus.Attempt appliedUnknownTemplate = template(templates, 42);
        appliedUnknownInvocation =
            client.offer(
                request(corpus, appliedUnknownTemplate),
                appliedUnknownTemplate.ordinal(),
                OFFER_DEADLINE);
        require(appliedUnknownInvocation.offerAccepted(), "attempt 42 was not accepted by ingress");
        require(
            client.awaitResponseBuffered(appliedUnknownInvocation, RESPONSE_DEADLINE),
            "attempt 42 did not reach the applied-but-undelivered boundary");
        M11CommandResponse buffered = appliedUnknownInvocation.bufferedResponse().orElseThrow();
        M12TransportAuthority bufferedAuthority =
            appliedUnknownInvocation.bufferedAuthority().orElseThrow();
        require(
            buffered.status() == M11ResponseStatus.NEW_APPLIED,
            "attempt 42 must be newly applied before delivery is abandoned");
        M12DeterministicCorpus.Binding appliedUnknownBinding =
            observedBinding(corpus, appliedUnknownTemplate.identity(), buffered, bufferedAuthority);
        require(
            observedBindings.put(
                    appliedUnknownTemplate.identity().canonicalSha256(), appliedUnknownBinding)
                == null,
            "attempt 42 identity was already bound");
        M12InvocationOutcome appliedUnknown = client.abandon(appliedUnknownInvocation);
        require(
            appliedUnknown.state() == M12InvocationState.UNKNOWN,
            "accepted abandoned attempt 42 must remain UNKNOWN");
        observedAttempts.add(
            observedUnknownApplied(
                appliedUnknownTemplate, appliedUnknown, appliedUnknownBinding, bufferedAuthority));

        appliedUnknownReplicatedStatuses =
            harness.awaitConvergence(Set.of(0, 1, 2), TOPOLOGY_DEADLINE);
        assertAppliedUnknownReplicated(appliedUnknownReplicatedStatuses);

        faultTargetLeader = harness.awaitStableLeader(Set.of(0, 1, 2), TOPOLOGY_DEADLINE);
        M12InfrastructurePreconditions.requireCurrentLeaderFaultTarget(
            initialLeader.memberId(), faultTargetLeader.memberId(), faultTargetLeader.role());
        leaderStop =
            harness.forceStop(faultTargetLeader.memberId(), faultTargetLeader, STOP_DEADLINE);
        faultTargetLeader = leaderStop.lastStatus();
        client.onClusterProcessExit();
        generationOneAccepted = Math.toIntExact(client.ingressOffersAccepted());
        generationOneDecoded = Math.toIntExact(client.egressResponsesDecoded());
        generationOneRejected = Math.toIntExact(client.rejectedEgressResponses());
        generationOneErrors = client.componentErrors().size();
      }
      clientGenerations.add(
          new ClientGenerationObservation(
              1,
              generationOneAuthority,
              generationOneAccepted,
              generationOneDecoded,
              generationOneRejected,
              generationOneErrors));

      Set<Integer> postLeaderFailureMembers =
          without(Set.of(0, 1, 2), faultTargetLeader.memberId());
      replacementLeader =
          harness.awaitReplacementLeader(
              postLeaderFailureMembers,
              faultTargetLeader.memberId(),
              faultTargetLeader.leadershipTermId(),
              TOPOLOGY_DEADLINE);
      require(
          replacementLeader.memberId() != faultTargetLeader.memberId(),
          "replacement Leader must be a different member");
      require(
          replacementLeader.leadershipTermId() > faultTargetLeader.leadershipTermId(),
          "replacement term did not advance beyond the pre-kill authority");

      int generationTwoAccepted;
      int generationTwoDecoded;
      int generationTwoRejected;
      int generationTwoErrors;
      M12TransportAuthority generationTwoAuthority;
      try (M12MatchingClusterClient client = connect(config, clusterRoot, 2, harness)) {
        generationTwoAuthority = client.currentAuthority();
        replacementClientAuthority = generationTwoAuthority;
        requireAuthority(generationTwoAuthority, replacementLeader, "generation 2");

        M12DeterministicCorpus.Attempt retryTemplate = template(templates, 43);
        M12InvocationAttempt appliedUnknownRetry =
            appliedUnknownInvocation.retry(
                retryTemplate.correlationId(), retryTemplate.ordinal(), 2);
        require(
            appliedUnknownInvocation.sameDurableIdentity(appliedUnknownRetry),
            "attempt 43 changed the durable identity");
        ObservedAcknowledgement retryObservation =
            submit(client, corpus, retryTemplate, appliedUnknownRetry, observedBindings, false);
        require(
            retryObservation.status() == M12DeterministicCorpus.ResponseStatus.DUPLICATE_REPLAYED,
            "same-identity retry after failover must replay the attempt 42 result");
        observedAttempts.add(retryObservation.attempt());

        for (int ordinal = 44; ordinal <= 75; ordinal++) {
          ObservedAcknowledgement observation =
              submit(client, corpus, template(templates, ordinal), observedBindings, false);
          require(
              observation.status() == M12DeterministicCorpus.ResponseStatus.NEW_APPLIED,
              "post-failover command " + ordinal + " was not NEW");
          observedAttempts.add(observation.attempt());
        }
        for (int ordinal = 76; ordinal <= 83; ordinal++) {
          ObservedAcknowledgement observation =
              submit(client, corpus, template(templates, ordinal), observedBindings, false);
          require(
              observation.status() == M12DeterministicCorpus.ResponseStatus.DUPLICATE_REPLAYED,
              "post-failover retry " + ordinal + " did not replay");
          observedAttempts.add(observation.attempt());
        }

        harness.startMember(initialLeader.memberId(), false);
        formerLeaderCatchupStatuses = harness.awaitConvergence(Set.of(0, 1, 2), TOPOLOGY_DEADLINE);
        M12MemberStatus formerLeader =
            member(formerLeaderCatchupStatuses, initialLeader.memberId());
        require("FOLLOWER".equals(formerLeader.role()), "former Leader did not return as Follower");

        M12MemberStatus leaderBeforeMinority =
            harness.awaitStableLeader(Set.of(0, 1, 2), TOPOLOGY_DEADLINE);
        require(
            leaderBeforeMinority.memberId() == replacementLeader.memberId(),
            "the first replacement Leader changed before the minority test");
        List<M12MemberStatus> preMinorityStatuses = harness.lastStableStatuses();
        minorityStoppedFollowers =
            preMinorityStatuses.stream()
                .filter(status -> status.memberId() != leaderBeforeMinority.memberId())
                .map(M12MemberStatus::memberId)
                .sorted()
                .toList();
        require(minorityStoppedFollowers.size() == 2, "two current Followers were not observed");
        for (int followerId : minorityStoppedFollowers) {
          M12MemberStatus beforeStop = member(preMinorityStatuses, followerId);
          require("FOLLOWER".equals(beforeStop.role()), "fault target was not a current Follower");
          harness.forceStop(followerId, beforeStop, STOP_DEADLINE);
        }
        require(harness.activeMemberIds().size() == 1, "the minority window is not one-of-three");
        require(
            harness.activeMemberIds().contains(leaderBeforeMinority.memberId()),
            "the observed Leader is not the surviving minority member");

        M12DeterministicCorpus.Attempt minorityTemplate = template(templates, 84);
        minorityUnknownInvocation =
            client.offer(
                request(corpus, minorityTemplate), minorityTemplate.ordinal(), OFFER_DEADLINE);
        if (!minorityUnknownInvocation.offerAccepted()) {
          throw new IllegalStateException(
              "attempt 84 could not cross the accepted ingress boundary; the no-quorum semantic observation is unavailable");
        }
        boolean minorityResponse =
            client.awaitResponseBuffered(minorityUnknownInvocation, MINORITY_RESPONSE_DEADLINE);
        require(!minorityResponse, "one-of-three produced a trusted response");
        M12InvocationOutcome minorityUnknown = minorityUnknownInvocation.outcome().orElseThrow();
        require(
            minorityUnknown.state() == M12InvocationState.UNKNOWN,
            "one-of-three accepted attempt must be UNKNOWN");
        observedAttempts.add(observedUnknownNotApplied(minorityTemplate, minorityUnknown));

        generationTwoAccepted = Math.toIntExact(client.ingressOffersAccepted());
        generationTwoDecoded = Math.toIntExact(client.egressResponsesDecoded());
        generationTwoRejected = Math.toIntExact(client.rejectedEgressResponses());
        generationTwoErrors = client.componentErrors().size();
      }
      clientGenerations.add(
          new ClientGenerationObservation(
              2,
              generationTwoAuthority,
              generationTwoAccepted,
              generationTwoDecoded,
              generationTwoRejected,
              generationTwoErrors));

      int firstRestoredFollower = minorityStoppedFollowers.getFirst();
      int lastRestoredFollower = minorityStoppedFollowers.getLast();
      harness.startMember(firstRestoredFollower, false);
      Set<Integer> restoredQuorum = Set.of(replacementLeader.memberId(), firstRestoredFollower);
      quorumRestoredLeader = harness.awaitStableLeader(restoredQuorum, TOPOLOGY_DEADLINE);
      require(
          quorumRestoredLeader.memberId() == replacementLeader.memberId(),
          "quorum restoration elected an unexpected Leader");

      int generationThreeAccepted;
      int generationThreeDecoded;
      int generationThreeRejected;
      int generationThreeErrors;
      M12TransportAuthority generationThreeAuthority;
      try (M12MatchingClusterClient client = connect(config, clusterRoot, 3, harness)) {
        generationThreeAuthority = client.currentAuthority();
        // Aeron may complete one more election term while the restored member and a fresh client
        // join. Re-sample the member topology after connect instead of comparing against a status
        // snapshot taken before the client handshake.
        quorumRestoredLeader = harness.awaitStableLeader(restoredQuorum, TOPOLOGY_DEADLINE);
        requireAuthority(generationThreeAuthority, quorumRestoredLeader, "generation 3");
        M12DeterministicCorpus.Attempt restoredTemplate = template(templates, 85);
        M12InvocationAttempt restoredRetry =
            minorityUnknownInvocation.retry(
                restoredTemplate.correlationId(), restoredTemplate.ordinal(), 3);
        require(
            minorityUnknownInvocation.sameDurableIdentity(restoredRetry),
            "attempt 85 changed the no-quorum durable identity");
        ObservedAcknowledgement restored =
            submit(client, corpus, restoredTemplate, restoredRetry, observedBindings, true);
        require(
            restored.status() == M12DeterministicCorpus.ResponseStatus.NEW_APPLIED
                || restored.status() == M12DeterministicCorpus.ResponseStatus.DUPLICATE_REPLAYED,
            "quorum-restored retry did not converge");
        if (restored.status() == M12DeterministicCorpus.ResponseStatus.DUPLICATE_REPLAYED) {
          M12DeterministicCorpus.Binding learnedOriginal =
              new M12DeterministicCorpus.Binding(
                  restored.binding().identity(),
                  restored.binding().applicationSequence(),
                  restored.binding().resultDigest(),
                  1,
                  restored.binding().observedResponseAuthorityTerm());
          observedBindings.put(learnedOriginal.identity().canonicalSha256(), learnedOriginal);
          int unknownIndex = observedAttempts.size() - 1;
          M12DeterministicCorpus.Attempt unknownAttempt = observedAttempts.get(unknownIndex);
          require(unknownAttempt.ordinal() == 84, "attempt 84 was not the pending observation");
          observedAttempts.set(
              unknownIndex,
              observedUnknownApplied(
                  template(templates, 84),
                  minorityUnknownInvocation.outcome().orElseThrow(),
                  learnedOriginal,
                  minorityUnknownInvocation.acceptedAuthority().orElseThrow()));
        }
        observedAttempts.add(restored.attempt());

        generationThreeAccepted = Math.toIntExact(client.ingressOffersAccepted());
        generationThreeDecoded = Math.toIntExact(client.egressResponsesDecoded());
        generationThreeRejected = Math.toIntExact(client.rejectedEgressResponses());
        generationThreeErrors = client.componentErrors().size();
      }
      clientGenerations.add(
          new ClientGenerationObservation(
              3,
              generationThreeAuthority,
              generationThreeAccepted,
              generationThreeDecoded,
              generationThreeRejected,
              generationThreeErrors));

      harness.startMember(lastRestoredFollower, false);
      finalStatuses = harness.awaitConvergence(Set.of(0, 1, 2), TOPOLOGY_DEADLINE);
      M12MemberStatus finalLeader =
          finalStatuses.stream()
              .filter(status -> "LEADER".equals(status.role()))
              .findFirst()
              .orElseThrow();
      require(
          finalLeader.memberId() == replacementLeader.memberId(),
          "the replacement Leader is not authoritative at final convergence");
      assertFinalState(finalStatuses, corpus);

      starts = harness.starts();
      forcedStops = harness.forcedStops();
      processHistory = harness.memberProcesses();
      stopHistory = harness.stoppedMembers();
      acceptedOffers =
          clientGenerations.stream().mapToInt(ClientGenerationObservation::acceptedOffers).sum();
      decodedResponses =
          clientGenerations.stream().mapToInt(ClientGenerationObservation::decodedResponses).sum();
      rejectedResponses =
          clientGenerations.stream().mapToInt(ClientGenerationObservation::rejectedResponses).sum();
      clientComponentErrors =
          clientGenerations.stream().mapToInt(ClientGenerationObservation::componentErrors).sum();

      require(starts == 6, "the scenario must start exactly six member processes");
      require(forcedStops == 3, "the scenario must force-stop exactly three member processes");
      require(processHistory.size() == 6, "all six process starts must remain observable");
      require(
          processHistory.stream()
                  .map(M12ThreeMemberProcessHarness.MemberProcessView::processId)
                  .distinct()
                  .count()
              == 6,
          "every member start must have a distinct PID");
      require(acceptedOffers == 84, "the real clients must accept exactly 84 ingress offers");
      require(
          decodedResponses == 83,
          "the real clients must decode 82 acknowledgements plus one abandoned response");
      require(rejectedResponses == 0, "the clients rejected an egress response");
      require(clientComponentErrors == 0, "a client component reported an error");
      require(observedAttempts.size() == 85, "the observed invocation history is not complete");
      require(observedBindings.size() == 66, "the observed binding history is not complete");
      require(
          stopHistory.stream()
              .allMatch(M12ThreeMemberProcessHarness.StoppedMember::externalController),
          "a fault was not injected by the external parent process");
      stopHistory.forEach(stop -> requireExpectedDiagnostics(stop.lastStatus()));
    }
    if (!harness.teardownComplete()) {
      throw new IllegalStateException("M12 child-process teardown did not complete");
    }
    processHistory = harness.memberProcesses();
    if (processHistory.stream().anyMatch(M12ThreeMemberProcessHarness.MemberProcessView::alive)) {
      throw new IllegalStateException("M12 child process remained alive after teardown");
    }
    stabilityWitnesses = harness.stabilityWitnesses();
    restartSafetyWitnesses = harness.restartSafetyWitnesses();
    if (stabilityWitnesses.isEmpty()) {
      throw new IllegalStateException("M12 produced no fresh unchanged status witness pair");
    }
    if (restartSafetyWitnesses.size() != 3) {
      throw new IllegalStateException(
          "M12 did not produce exactly three Archive mark-file restart witnesses");
    }

    ObjectNode architecture = new M12ArchitectureGate().run(root);
    M12ExecutionTrace.Topology topology =
        new M12ExecutionTrace.Topology(
            initialStatuses.stream().map(status -> memberObservation(config, status)).toList(),
            finalStatuses.stream().map(status -> memberObservation(config, status)).toList(),
            initialLeader.memberId(),
            initialLeader.leadershipTermId(),
            leaderStop.memberId(),
            faultTargetLeader.leadershipTermId(),
            leaderStop.externalController(),
            leaderStop.memberId() == initialLeader.memberId()
                && "LEADER".equals(leaderStop.lastStatus().role()),
            replacementLeader.memberId(),
            replacementLeader.leadershipTermId(),
            replacementClientAuthority.leaderMemberId(),
            replacementClientAuthority.leadershipTermId(),
            staleLeaderAcknowledgements(observedAttempts, faultTargetLeader),
            true,
            initialLeader.memberId(),
            role(member(formerLeaderCatchupStatuses, initialLeader.memberId())),
            equivalentState(formerLeaderCatchupStatuses),
            true,
            1,
            minorityAcknowledgements,
            true,
            architecture.path("matchingCoreByteIdentical").booleanValue(),
            architecture.path("m11WireSourcesByteIdentical").booleanValue(),
            true,
            false,
            true);
    M12ExecutionTrace trace =
        M12ExecutionTrace.realAeronChildProcesses(
            corpus,
            SOURCE_ARTIFACT,
            observedAttempts,
            new ArrayList<>(observedBindings.values()),
            topology);
    new M12HistoryJudge().inspect(workload, trace);

    ObjectNode history =
        historyReport(corpus, trace, appliedUnknownReplicatedStatuses, starts, forcedStops);
    ObjectNode topologyReport =
        topologyReport(
            config,
            topology,
            appliedUnknownReplicatedStatuses,
            processHistory,
            stopHistory,
            restartSafetyWitnesses,
            stabilityWitnesses,
            harness.teardownComplete(),
            starts,
            forcedStops);
    ObjectNode leadership =
        leadershipReport(
            initialLeader,
            faultTargetLeader,
            replacementLeader,
            clientGenerations,
            topology.staleLeaderAcknowledgements());
    ObjectNode quorum = quorumReport(trace, quorumRestoredLeader);
    ObjectNode catchup =
        catchupReport(
            initialLeader, formerLeaderCatchupStatuses, finalStatuses, restartSafetyWitnesses);
    ObjectNode stateEquivalence = stateEquivalenceReport(corpus, finalStatuses);
    return new Result(
        trace,
        history,
        topologyReport,
        leadership,
        quorum,
        catchup,
        stateEquivalence,
        canonicalCommandBytes(corpus),
        clusterRoot);
  }

  private static M12MatchingClusterClient connect(
      M12ThreeMemberConfig config,
      Path clusterRoot,
      long generation,
      M12ThreeMemberProcessHarness harness) {
    return M12MatchingClusterClient.connect(
        clusterRoot.resolve("client/generation-" + generation + "/aeron"),
        config.ingressEndpoints(),
        config.clientMessageTimeout(),
        generation,
        harness::firstUnexpectedProcessFailure);
  }

  private static ObservedAcknowledgement submit(
      M12MatchingClusterClient client,
      M12DeterministicCorpus.Corpus corpus,
      M12DeterministicCorpus.Attempt template,
      Map<String, M12DeterministicCorpus.Binding> bindings,
      boolean permitLearningDuplicate) {
    M12InvocationOutcome outcome =
        client.submit(
            request(corpus, template), template.ordinal(), OFFER_DEADLINE, RESPONSE_DEADLINE);
    return observedAcknowledgement(
        client, corpus, template, outcome, bindings, permitLearningDuplicate);
  }

  private static ObservedAcknowledgement submit(
      M12MatchingClusterClient client,
      M12DeterministicCorpus.Corpus corpus,
      M12DeterministicCorpus.Attempt template,
      M12InvocationAttempt invocation,
      Map<String, M12DeterministicCorpus.Binding> bindings,
      boolean permitLearningDuplicate) {
    M12InvocationAttempt offered = client.offer(invocation, OFFER_DEADLINE);
    require(offered.offerAccepted(), "retry " + template.ordinal() + " was not accepted");
    require(
        client.awaitResponseBuffered(offered, RESPONSE_DEADLINE),
        "retry " + template.ordinal() + " did not produce a trusted response");
    return observedAcknowledgement(
        client, corpus, template, client.acknowledge(offered), bindings, permitLearningDuplicate);
  }

  private static ObservedAcknowledgement observedAcknowledgement(
      M12MatchingClusterClient client,
      M12DeterministicCorpus.Corpus corpus,
      M12DeterministicCorpus.Attempt template,
      M12InvocationOutcome outcome,
      Map<String, M12DeterministicCorpus.Binding> bindings,
      boolean permitLearningDuplicate) {
    require(
        outcome.state() == M12InvocationState.ACKNOWLEDGED,
        "attempt " + template.ordinal() + " was not acknowledged");
    require(outcome.acceptedPosition().isPresent(), "acknowledgement was not ingress-accepted");
    M11CommandResponse response = outcome.response().orElseThrow();
    M12TransportAuthority authority = outcome.completionAuthority().orElseThrow();
    require(
        outcome.correlationId().equals(template.correlationId()),
        "attempt correlation differs from the frozen schedule");
    require(
        response.correlationId().equals(outcome.correlationId()),
        "response correlation differs from its invocation");
    require(
        response.commandId().orElseThrow().equals(template.identity().commandId()),
        "response command identity differs from its invocation");
    require(
        authority.equals(client.currentAuthority()),
        "acknowledgement did not come from the current client authority");
    require(
        response.status() == M11ResponseStatus.NEW_APPLIED
            || response.status() == M11ResponseStatus.DUPLICATE_REPLAYED,
        "attempt returned a non-success business status");

    M12DeterministicCorpus.Binding binding =
        observedBinding(corpus, template.identity(), response, authority);
    String identityDigest = template.identity().canonicalSha256();
    M12DeterministicCorpus.Binding prior = bindings.get(identityDigest);
    boolean businessEffectApplied;
    M12DeterministicCorpus.ResponseStatus status;
    if (response.status() == M11ResponseStatus.NEW_APPLIED) {
      require(prior == null, "NEW response repeated an already-bound identity");
      bindings.put(identityDigest, binding);
      businessEffectApplied = true;
      status = M12DeterministicCorpus.ResponseStatus.NEW_APPLIED;
    } else {
      require(
          prior != null || permitLearningDuplicate, "DUP response has no earlier observed binding");
      if (prior == null) {
        bindings.put(identityDigest, binding);
      } else {
        requireSameBinding(prior, binding, "duplicate response changed its original result");
        binding = prior;
      }
      businessEffectApplied = false;
      status = M12DeterministicCorpus.ResponseStatus.DUPLICATE_REPLAYED;
    }
    M12DeterministicCorpus.Attempt observed =
        new M12DeterministicCorpus.Attempt(
            template.ordinal(),
            template.phase(),
            template.identity(),
            outcome.correlationId(),
            true,
            M12DeterministicCorpus.ClientOutcome.ACKNOWLEDGED,
            true,
            response.correlationId(),
            status,
            binding.applicationSequence(),
            binding.resultDigest(),
            businessEffectApplied,
            template.retryOfAttemptOrdinal(),
            authority.leadershipTermId(),
            authority.leaderMemberId(),
            true,
            false,
            false);
    return new ObservedAcknowledgement(observed, binding, status);
  }

  private static M12DeterministicCorpus.Attempt observedNotSubmitted(
      M12DeterministicCorpus.Attempt template,
      M12InvocationOutcome outcome,
      M12MemberStatus authority) {
    return new M12DeterministicCorpus.Attempt(
        template.ordinal(),
        template.phase(),
        template.identity(),
        outcome.correlationId(),
        false,
        M12DeterministicCorpus.ClientOutcome.NOT_SUBMITTED,
        false,
        null,
        null,
        null,
        null,
        false,
        null,
        authority.leadershipTermId(),
        authority.memberId(),
        false,
        false,
        false);
  }

  private static M12DeterministicCorpus.Attempt observedUnknownApplied(
      M12DeterministicCorpus.Attempt template,
      M12InvocationOutcome outcome,
      M12DeterministicCorpus.Binding binding,
      M12TransportAuthority authority) {
    require(outcome.state() == M12InvocationState.UNKNOWN, "accepted abandonment was not UNKNOWN");
    return new M12DeterministicCorpus.Attempt(
        template.ordinal(),
        template.phase(),
        template.identity(),
        outcome.correlationId(),
        true,
        M12DeterministicCorpus.ClientOutcome.UNKNOWN,
        false,
        null,
        null,
        binding.applicationSequence(),
        binding.resultDigest(),
        true,
        template.retryOfAttemptOrdinal(),
        authority.leadershipTermId(),
        authority.leaderMemberId(),
        false,
        template.noQuorumWindow(),
        false);
  }

  private static M12DeterministicCorpus.Attempt observedUnknownNotApplied(
      M12DeterministicCorpus.Attempt template, M12InvocationOutcome outcome) {
    require(outcome.state() == M12InvocationState.UNKNOWN, "minority attempt was not UNKNOWN");
    M12TransportAuthority authority = outcome.acceptedAuthority().orElseThrow();
    return new M12DeterministicCorpus.Attempt(
        template.ordinal(),
        template.phase(),
        template.identity(),
        outcome.correlationId(),
        true,
        M12DeterministicCorpus.ClientOutcome.UNKNOWN,
        false,
        null,
        null,
        null,
        null,
        false,
        template.retryOfAttemptOrdinal(),
        authority.leadershipTermId(),
        authority.leaderMemberId(),
        false,
        true,
        false);
  }

  private static M12DeterministicCorpus.Binding observedBinding(
      M12DeterministicCorpus.Corpus corpus,
      M12DeterministicCorpus.DurableIdentity identity,
      M11CommandResponse response,
      M12TransportAuthority authority) {
    long sequence = response.applicationSequence().orElseThrow();
    String digest = response.resultDigest().orElseThrow();
    M12DeterministicCorpus.Binding expected = oracleBinding(corpus, identity);
    require(sequence == expected.applicationSequence(), "application sequence differs from oracle");
    require(digest.equals(expected.resultDigest()), "result digest differs from oracle");
    return new M12DeterministicCorpus.Binding(
        identity, sequence, digest, 1, authority.leadershipTermId());
  }

  private static M12DeterministicCorpus.Binding oracleBinding(
      M12DeterministicCorpus.Corpus corpus, M12DeterministicCorpus.DurableIdentity identity) {
    return corpus.bindings().stream()
        .filter(binding -> binding.identity().equals(identity))
        .findFirst()
        .orElseThrow();
  }

  private static void requireSameBinding(
      M12DeterministicCorpus.Binding expected,
      M12DeterministicCorpus.Binding actual,
      String message) {
    require(
        expected.applicationSequence() == actual.applicationSequence()
            && expected.resultDigest().equals(actual.resultDigest()),
        message);
  }

  private static M11CommandRequest request(
      M12DeterministicCorpus.Corpus corpus, M12DeterministicCorpus.Attempt template) {
    return corpus.requestFor(template.identity(), template.correlationId());
  }

  private static M12DeterministicCorpus.Attempt template(
      Map<Integer, M12DeterministicCorpus.Attempt> templates, int ordinal) {
    M12DeterministicCorpus.Attempt template = templates.get(ordinal);
    if (template == null) {
      throw new IllegalStateException("frozen attempt is missing: " + ordinal);
    }
    return template;
  }

  private static void requireAuthority(
      M12TransportAuthority authority, M12MemberStatus leader, String label) {
    require(
        authority.leaderMemberId() == leader.memberId(),
        label + " connected to a different Leader");
    require(
        authority.leadershipTermId() == leader.leadershipTermId(),
        label + " connected with a different leadership term");
  }

  private static void assertFinalState(
      List<M12MemberStatus> statuses, M12DeterministicCorpus.Corpus corpus) {
    require(statuses.size() == 3, "three final member states were not observed");
    require(
        statuses.stream().filter(status -> "LEADER".equals(status.role())).count() == 1,
        "final topology does not have exactly one Leader");
    require(
        statuses.stream().filter(status -> "FOLLOWER".equals(status.role())).count() == 2,
        "final topology does not have exactly two Followers");
    require(
        statuses.stream().allMatch(status -> "CLOSED".equals(status.electionState())),
        "final topology still has an active election");
    require(
        statuses.stream().map(M12MemberStatus::leadershipTermId).distinct().count() == 1,
        "final topology does not agree on one leadership term");
    require(equivalentState(statuses), "final application states or log positions diverged");
    for (M12MemberStatus status : statuses) {
      require(status.nextApplicationSequence() == 67, "final next sequence is not 67");
      require(
          status.semanticStateDigest().equals(corpus.expectedFinalSemanticDigest()),
          "final semantic digest differs from the direct oracle");
      require(status.identityResultCount() == 66, "final identity result count is not 66");
      require(
          status.identityResultDigest().equals(corpus.expectedIdentityResultDigest()),
          "final identity result digest differs from the direct oracle");
      require(status.componentErrors().isEmpty(), "a member reported a component error");
      requireExpectedDiagnostics(status);
    }
  }

  private static void requireExpectedDiagnostics(M12MemberStatus status) {
    if (status.droppedDiagnosticWarnings() != 0) {
      throw new IllegalStateException(
          "M12 member "
              + status.memberId()
              + " dropped "
              + status.droppedDiagnosticWarnings()
              + " diagnostic warnings");
    }
    List<String> unexpected =
        status.diagnosticWarnings().stream()
            .filter(warning -> !EXPECTED_AERON_FAIL_STOP_WARNING.matcher(warning).matches())
            .toList();
    if (!unexpected.isEmpty()) {
      throw new IllegalStateException(
          "M12 member "
              + status.memberId()
              + " reported unexpected diagnostic warnings: "
              + unexpected);
    }
    Set<String> families = new LinkedHashSet<>();
    for (String warning : status.diagnosticWarnings()) {
      if (!families.add(diagnosticWarningFamily(warning))) {
        throw new IllegalStateException(
            "M12 member "
                + status.memberId()
                + " repeated an allowlisted diagnostic warning family: "
                + warning);
      }
    }
  }

  private static String diagnosticWarningFamily(String warning) {
    if (warning.endsWith("leader heartbeat timeout")) {
      return "LEADER_HEARTBEAT_TIMEOUT";
    }
    if (warning.endsWith("inactive follower quorum")) {
      return "INACTIVE_FOLLOWER_QUORUM";
    }
    if (warning.contains("quorum position went backwards:")) {
      return "QUORUM_POSITION_WENT_BACKWARDS";
    }
    throw new IllegalArgumentException("warning is not in the M12 allowlist");
  }

  private static void assertAppliedUnknownReplicated(List<M12MemberStatus> statuses) {
    require(statuses.size() == 3, "attempt 42 was not observed on all three members before kill");
    require(
        statuses.stream()
            .map(M12MemberStatus::memberId)
            .collect(java.util.stream.Collectors.toSet())
            .equals(Set.of(0, 1, 2)),
        "attempt 42 replication observations did not cover the static member set");
    require(
        equivalentState(statuses),
        "attempt 42 application state or log position was not replicated before kill");
    for (M12MemberStatus status : statuses) {
      require(
          status.nextApplicationSequence() == 34,
          "attempt 42 was not application sequence 33 on member " + status.memberId());
      require(
          status.identityResultCount() == 33,
          "attempt 42 identity was not bound on member " + status.memberId());
      require(
          status.componentErrors().isEmpty(),
          "member reported a component error before the attempt 42 Leader kill");
      if (!status.diagnosticWarnings().isEmpty() || status.droppedDiagnosticWarnings() != 0) {
        throw new IllegalStateException(
            "M12 pre-fault member reported diagnostics before the Leader kill: "
                + status.memberId());
      }
    }
  }

  private static boolean equivalentState(List<M12MemberStatus> statuses) {
    if (statuses.isEmpty()) {
      return false;
    }
    M12MemberStatus first = statuses.getFirst();
    return statuses.stream()
        .allMatch(
            status ->
                status.nextApplicationSequence() == first.nextApplicationSequence()
                    && status.semanticStateDigest().equals(first.semanticStateDigest())
                    && status.identityResultDigest().equals(first.identityResultDigest())
                    && status.identityResultCount() == first.identityResultCount()
                    && status.commitPosition() == first.commitPosition()
                    && status.logPosition() == first.logPosition());
  }

  private static M12MemberStatus member(List<M12MemberStatus> statuses, int memberId) {
    return statuses.stream()
        .filter(status -> status.memberId() == memberId)
        .findFirst()
        .orElseThrow();
  }

  private static Set<Integer> without(Set<Integer> members, int removed) {
    LinkedHashSet<Integer> result = new LinkedHashSet<>(members);
    result.remove(removed);
    return Set.copyOf(result);
  }

  private static M12ExecutionTrace.Role role(M12MemberStatus status) {
    return switch (status.role()) {
      case "LEADER" -> M12ExecutionTrace.Role.LEADER;
      case "FOLLOWER" -> M12ExecutionTrace.Role.FOLLOWER;
      default -> throw new IllegalStateException("unsupported stable role: " + status.role());
    };
  }

  private static M12ExecutionTrace.MemberObservation memberObservation(
      M12ThreeMemberConfig config, M12MemberStatus status) {
    require(
        status.udpPortBlockBase() == config.memberPortBase(status.memberId()),
        "member status port-block base differs from the configured topology");
    return new M12ExecutionTrace.MemberObservation(
        status.memberId(),
        status.processId(),
        role(status),
        status.aeronDirectory(),
        status.archiveDirectory(),
        status.clusterDirectory(),
        status.udpPortBlockBase(),
        config.fixedUdpPorts(status.memberId()),
        status.leadershipTermId(),
        status.nextApplicationSequence(),
        status.identityResultCount(),
        status.semanticStateDigest(),
        status.identityResultDigest(),
        status.componentErrors().size());
  }

  private static int staleLeaderAcknowledgements(
      List<M12DeterministicCorpus.Attempt> attempts, M12MemberStatus preKillLeader) {
    return Math.toIntExact(
        attempts.stream()
            .filter(attempt -> attempt.ordinal() >= 43)
            .filter(
                attempt -> attempt.outcome() == M12DeterministicCorpus.ClientOutcome.ACKNOWLEDGED)
            .filter(
                attempt ->
                    attempt.authorityLeaderId() == preKillLeader.memberId()
                        || attempt.authorityTerm() <= preKillLeader.leadershipTermId())
            .count());
  }

  private static ObjectNode historyReport(
      M12DeterministicCorpus.Corpus corpus,
      M12ExecutionTrace trace,
      List<M12MemberStatus> appliedUnknownReplicatedStatuses,
      int starts,
      int forcedStops) {
    ObjectNode report = report("matching.m12.command-history.v1");
    report.put("sourceArtifact", trace.sourceArtifact());
    report.put("executionScope", trace.scope().name());
    report.put("corpusSha256", corpus.corpusSha256());
    report.put("identityCount", trace.identities().size());
    report.put("invocationCount", trace.attempts().size());
    report.put(
        "ingressAccepted",
        trace.attempts().stream().filter(M12DeterministicCorpus.Attempt::ingressAccepted).count());
    report.put(
        "acknowledged",
        trace.attempts().stream()
            .filter(
                attempt -> attempt.outcome() == M12DeterministicCorpus.ClientOutcome.ACKNOWLEDGED)
            .count());
    report.put(
        "unknown",
        trace.attempts().stream()
            .filter(attempt -> attempt.outcome() == M12DeterministicCorpus.ClientOutcome.UNKNOWN)
            .count());
    report.put(
        "notSubmitted",
        trace.attempts().stream()
            .filter(
                attempt -> attempt.outcome() == M12DeterministicCorpus.ClientOutcome.NOT_SUBMITTED)
            .count());
    report.put("memberProcessStarts", starts);
    report.put("externalForceStops", forcedStops);
    report.put("finalNextApplicationSequence", 67);
    addAppliedUnknownReplicationEvidence(report, appliedUnknownReplicatedStatuses);
    ArrayNode attempts = report.putArray("attempts");
    trace.attempts().forEach(attempt -> addAttempt(attempts.addObject(), attempt));
    ArrayNode bindings = report.putArray("bindings");
    trace.bindings().stream()
        .sorted(Comparator.comparingLong(M12DeterministicCorpus.Binding::applicationSequence))
        .forEach(binding -> addBinding(bindings.addObject(), binding));
    return report;
  }

  private static ObjectNode topologyReport(
      M12ThreeMemberConfig config,
      M12ExecutionTrace.Topology topology,
      List<M12MemberStatus> appliedUnknownReplicatedStatuses,
      List<M12ThreeMemberProcessHarness.MemberProcessView> processes,
      List<M12ThreeMemberProcessHarness.StoppedMember> stops,
      List<M12ThreeMemberProcessHarness.RestartSafetyWitness> restartSafetyWitnesses,
      List<M12ThreeMemberProcessHarness.StableSnapshotWitness> stabilityWitnesses,
      boolean teardownComplete,
      int starts,
      int forcedStops) {
    ObjectNode report = report("matching.m12.topology.v1");
    report.put("clusterId", config.clusterId());
    report.put("memberCount", M12ThreeMemberConfig.MEMBER_COUNT);
    report.put("quorumSize", M12ThreeMemberConfig.QUORUM_SIZE);
    report.put("aeronAppointedLeaderId", M12ThreeMemberConfig.APPOINTED_LEADER_ID);
    report.put("automaticLeaderElection", true);
    report.put("initialLeaderIsRuntimeObservation", true);
    ObjectNode correction = report.putObject("contractCorrection");
    correction.put("schemaVersion", "matching.m12.contract-correction.v1");
    correction.put("status", PASS);
    correction.put("frozenWorkloadSha256", M12StartCheckRunner.WORKLOAD_SHA256);
    correction.put("frozenField", "realClusterProfile.appointedInitialLeaderId");
    correction.put(
        "frozenAppointedInitialLeaderId", M12ThreeMemberConfig.FROZEN_APPOINTED_INITIAL_LEADER_ID);
    correction.put("frozenAppointmentCompatibleWithThreeMemberHa", false);
    correction.put("effectiveAeronAppointedLeaderId", M12ThreeMemberConfig.APPOINTED_LEADER_ID);
    correction.put("automaticElection", true);
    correction.put("initialLeaderSelection", "OBSERVED_AUTOMATIC_ELECTION");
    correction.put("initialLeaderId", topology.initialLeaderId());
    correction.put(
        "reason",
        "Aeron appointedLeaderId disables automatic election and prevents the required Leader failover.");
    report.put("localhostOnly", true);
    report.put("memberProcessStarts", starts);
    report.put("externalForceStops", forcedStops);
    report.put("teardownComplete", teardownComplete);
    report.put(
        "childProcessesAliveAfterTeardown",
        processes.stream().filter(M12ThreeMemberProcessHarness.MemberProcessView::alive).count());
    report.put("ownerProcessId", ProcessHandle.current().pid());
    report.put("childClasspathStrategy", M12ThreeMemberProcessHarness.childClasspathStrategy());
    report.put(
        "archiveMarkFileLivenessTimeoutMillis",
        M12ThreeMemberProcessHarness.archiveMarkFileLivenessTimeoutMillis());
    report.put("restartSafetyPredicate", M12ThreeMemberProcessHarness.restartSafetyPredicate());
    report.put(
        "restartSafetyReason",
        "A forced-stopped member is restarted only after a live ArchiveMarkFile activity-timestamp read observes age strictly greater than the dependency-pinned liveness timeout.");
    report.put("restartSafetyWitnessCount", restartSafetyWitnesses.size());
    ArrayNode restartSafety = report.putArray("restartSafetyWitnesses");
    restartSafetyWitnesses.forEach(
        witness -> addRestartSafetyWitness(restartSafety.addObject(), witness));
    report.put("statusFreshnessClock", "MONOTONIC_STATUS_SEQUENCE_ADVANCE");
    report.put("wallClockUsedForStatusAcceptance", false);
    report.put("stableSnapshotWitnessCount", stabilityWitnesses.size());
    ArrayNode stableSnapshots = report.putArray("stableSnapshotWitnesses");
    stabilityWitnesses.forEach(
        witness -> {
          ObjectNode node = stableSnapshots.addObject();
          node.put("ordinal", witness.ordinal());
          node.put("condition", witness.condition());
          node.put("statusPublishIntervalMillis", witness.statusPublishIntervalMillis());
          node.put("freshnessBoundMillis", witness.freshnessBoundMillis());
          node.put("elapsedNanos", witness.elapsedNanos());
          node.put("allMemberStatusSequencesAdvanced", true);
          node.put("monotonicElapsedWithinFreshnessBound", true);
          ArrayNode first = node.putArray("firstSnapshot");
          witness.firstSnapshot().forEach(status -> addStabilityStatus(first.addObject(), status));
          ArrayNode second = node.putArray("secondSnapshot");
          witness
              .secondSnapshot()
              .forEach(status -> addStabilityStatus(second.addObject(), status));
        });
    addAppliedUnknownReplicationEvidence(report, appliedUnknownReplicatedStatuses);
    ArrayNode initial = report.putArray("initialMembers");
    topology.initialMembers().forEach(member -> addMember(initial.addObject(), member));
    ArrayNode converged = report.putArray("convergedMembers");
    topology.convergedMembers().forEach(member -> addMember(converged.addObject(), member));
    ArrayNode processStarts = report.putArray("processStarts");
    processes.forEach(
        process -> {
          ObjectNode node = processStarts.addObject();
          node.put("memberId", process.memberId());
          node.put("processId", process.processId());
          node.put("freshStart", process.freshStart());
          node.put("aliveAfterTeardown", process.alive());
          node.put("portBlockBase", config.memberPortBase(process.memberId()));
          ArrayNode ports = node.putArray("udpPorts");
          config.fixedUdpPorts(process.memberId()).forEach(ports::add);
          node.put("rootDirectory", config.memberRootDirectory(process.memberId()).toString());
          node.put("aeronDirectory", config.memberAeronDirectory(process.memberId()).toString());
          node.put(
              "archiveDirectory", config.memberArchiveDirectory(process.memberId()).toString());
          node.put(
              "clusterDirectory", config.memberClusterDirectory(process.memberId()).toString());
        });
    ArrayNode forced = report.putArray("forcedStops");
    stops.forEach(
        stop -> {
          ObjectNode node = forced.addObject();
          node.put("memberId", stop.memberId());
          node.put("processId", stop.processId());
          node.put("destroyForciblyRequested", stop.forcedStopRequested());
          node.put("exitCode", stop.exitCode());
          node.put("externalController", stop.externalController());
          node.put("roleBeforeStop", stop.lastStatus().role());
          node.put("termBeforeStop", stop.lastStatus().leadershipTermId());
          node.put("commitPositionBeforeStop", stop.lastStatus().commitPosition());
          node.put("logPositionBeforeStop", stop.lastStatus().logPosition());
          node.put("componentErrorCount", stop.lastStatus().componentErrors().size());
          node.put("diagnosticWarningCount", stop.lastStatus().diagnosticWarnings().size());
          node.put("droppedDiagnosticWarnings", stop.lastStatus().droppedDiagnosticWarnings());
          ArrayNode warnings = node.putArray("diagnosticWarnings");
          stop.lastStatus().diagnosticWarnings().forEach(warnings::add);
        });
    return report;
  }

  private static ObjectNode leadershipReport(
      M12MemberStatus initialLeader,
      M12MemberStatus faultTargetLeader,
      M12MemberStatus replacementLeader,
      List<ClientGenerationObservation> clients,
      int staleLeaderAcknowledgements) {
    ObjectNode report = report("matching.m12.leadership.v1");
    report.put("initialLeaderId", initialLeader.memberId());
    report.put("initialLeadershipTermId", initialLeader.leadershipTermId());
    report.put("faultTargetLeaderId", faultTargetLeader.memberId());
    report.put("faultTargetLeadershipTermId", faultTargetLeader.leadershipTermId());
    report.put("killedObservedCurrentLeader", true);
    report.put("replacementLeaderId", replacementLeader.memberId());
    report.put("replacementLeadershipTermId", replacementLeader.leadershipTermId());
    report.put(
        "leadershipTermAdvanced",
        replacementLeader.leadershipTermId() > faultTargetLeader.leadershipTermId());
    report.put("staleLeaderAcknowledgements", staleLeaderAcknowledgements);
    ArrayNode generations = report.putArray("clientGenerations");
    clients.forEach(
        client -> {
          ObjectNode node = generations.addObject();
          node.put("clientGeneration", client.generation());
          node.put("clusterSessionId", client.authority().clusterSessionId());
          node.put("leadershipTermId", client.authority().leadershipTermId());
          node.put("leaderMemberId", client.authority().leaderMemberId());
          node.put("acceptedOffers", client.acceptedOffers());
          node.put("decodedResponses", client.decodedResponses());
          node.put("rejectedResponses", client.rejectedResponses());
          node.put("componentErrors", client.componentErrors());
        });
    return report;
  }

  private static ObjectNode quorumReport(
      M12ExecutionTrace trace, M12MemberStatus quorumRestoredLeader) {
    M12DeterministicCorpus.Attempt minority = trace.attempts().get(83);
    M12DeterministicCorpus.Attempt retry = trace.attempts().get(84);
    ObjectNode report = report("matching.m12.quorum.v1");
    report.put("votingMembers", 3);
    report.put("quorumSize", 2);
    report.put("liveVotingMembersDuringMinority", 1);
    report.put("minorityAttemptOrdinal", minority.ordinal());
    report.put("minorityIngressAccepted", minority.ingressAccepted());
    report.put("minorityOutcome", minority.outcome().name());
    report.put("minorityAcknowledgements", 0);
    report.put("quorumRestored", true);
    report.put("restoredLeaderId", quorumRestoredLeader.memberId());
    report.put("restoredLeadershipTermId", quorumRestoredLeader.leadershipTermId());
    report.put("retryAttemptOrdinal", retry.ordinal());
    report.put("retryOutcome", retry.outcome().name());
    report.put("retryResponseStatus", retry.responseStatus().name());
    return report;
  }

  private static ObjectNode catchupReport(
      M12MemberStatus initialLeader,
      List<M12MemberStatus> catchup,
      List<M12MemberStatus> converged,
      List<M12ThreeMemberProcessHarness.RestartSafetyWitness> restartSafetyWitnesses) {
    M12MemberStatus former = member(catchup, initialLeader.memberId());
    M12ThreeMemberProcessHarness.RestartSafetyWitness firstReturnWitness =
        restartSafetyWitnesses.stream()
            .filter(witness -> witness.memberId() == initialLeader.memberId())
            .findFirst()
            .orElseThrow();
    ObjectNode report = report("matching.m12.catchup.v1");
    report.put("formerLeaderId", initialLeader.memberId());
    report.put("freshStartOnReturn", former.freshStart());
    report.put("roleAfterReturn", former.role());
    report.put("caughtUpBeforeMinorityFault", equivalentState(catchup));
    report.put("catchupCommitPosition", former.commitPosition());
    report.put("catchupLogPosition", former.logPosition());
    report.put("catchupNextApplicationSequence", former.nextApplicationSequence());
    report.put("allThreeConvergedAfterFinalRestore", equivalentState(converged));
    report.put(
        "archiveMarkFileLivenessTimeoutMillis",
        M12ThreeMemberProcessHarness.archiveMarkFileLivenessTimeoutMillis());
    report.put("restartSafetyPredicate", M12ThreeMemberProcessHarness.restartSafetyPredicate());
    report.put("firstReturnRestartSafetyWitnessOrdinal", firstReturnWitness.ordinal());
    addRestartSafetyWitness(
        report.putObject("firstReturnRestartSafetyWitness"), firstReturnWitness);
    return report;
  }

  private static void addRestartSafetyWitness(
      ObjectNode node, M12ThreeMemberProcessHarness.RestartSafetyWitness witness) {
    node.put("ordinal", witness.ordinal());
    node.put("memberId", witness.memberId());
    node.put("stoppedProcessId", witness.stoppedProcessId());
    node.put("archiveMarkFile", witness.archiveMarkFile().toString());
    node.put("lastActivityTimestampMillis", witness.lastActivityTimestampMillis());
    node.put("observedAtMillis", witness.observedAtMillis());
    node.put("ageMillis", witness.ageMillis());
    node.put("livenessTimeoutMillis", witness.livenessTimeoutMillis());
    node.put("probeCount", witness.probeCount());
    node.put("waitElapsedNanos", witness.waitElapsedNanos());
    node.put("aeronVersion", witness.aeronVersion());
    node.put("predicate", witness.predicate());
    node.put("activityTimestampPositive", true);
    node.put("ageStrictlyExceedsLivenessTimeout", true);
  }

  private static ObjectNode stateEquivalenceReport(
      M12DeterministicCorpus.Corpus corpus, List<M12MemberStatus> statuses) {
    ObjectNode report = report("matching.m12.state-equivalence.v1");
    M12MemberStatus first = statuses.getFirst();
    report.put("applicationObserver", true);
    report.put("memberCount", statuses.size());
    report.put("nextApplicationSequence", first.nextApplicationSequence());
    report.put("identityCount", first.identityResultCount());
    report.put("semanticStateDigest", first.semanticStateDigest());
    report.put("expectedSemanticStateDigest", corpus.expectedFinalSemanticDigest());
    report.put("identityResultDigest", first.identityResultDigest());
    report.put("expectedIdentityResultDigest", corpus.expectedIdentityResultDigest());
    report.put(
        "allMembersIdentityResultDigestMatchDirectOracle",
        statuses.stream()
            .allMatch(
                status ->
                    status.identityResultDigest().equals(corpus.expectedIdentityResultDigest())));
    report.put(
        "allMembersIdentityCountExact",
        statuses.stream().allMatch(status -> status.identityResultCount() == 66));
    report.put("commitPosition", first.commitPosition());
    report.put("logPosition", first.logPosition());
    report.put("stateEquivalent", equivalentState(statuses));
    report.put(
        "componentErrorCount",
        statuses.stream().mapToInt(status -> status.componentErrors().size()).sum());
    report.put(
        "diagnosticWarningCount",
        statuses.stream().mapToInt(status -> status.diagnosticWarnings().size()).sum());
    report.put(
        "droppedDiagnosticWarnings",
        statuses.stream().mapToLong(M12MemberStatus::droppedDiagnosticWarnings).sum());
    ArrayNode members = report.putArray("members");
    statuses.forEach(status -> addStatus(members.addObject(), status));
    return report;
  }

  private static ObjectNode report(String schemaVersion) {
    ObjectNode report = JsonSupport.MAPPER.createObjectNode();
    report.put("schemaVersion", schemaVersion);
    report.put("status", PASS);
    report.put("implementation", "REAL_AERON_CHILD_PROCESSES");
    return report;
  }

  private static byte[] canonicalCommandBytes(M12DeterministicCorpus.Corpus corpus) {
    List<byte[]> envelopes =
        corpus.identities().stream()
            .sorted(Comparator.comparingInt(M12DeterministicCorpus.DurableIdentity::index))
            .map(M12DeterministicCorpus.DurableIdentity::canonicalBytes)
            .toList();
    int size = 0;
    for (byte[] envelope : envelopes) {
      size = Math.addExact(size, Math.addExact(Integer.BYTES, envelope.length));
    }
    ByteBuffer canonical = ByteBuffer.allocate(size);
    envelopes.forEach(envelope -> canonical.putInt(envelope.length).put(envelope));
    return canonical.array();
  }

  private static void addAttempt(ObjectNode node, M12DeterministicCorpus.Attempt attempt) {
    node.put("ordinal", attempt.ordinal());
    node.put("phase", attempt.phase());
    node.put("identityIndex", attempt.identity().index());
    node.put("canonicalEnvelopeSha256", attempt.identity().canonicalSha256());
    node.put("commandId", attempt.identity().commandId().toString());
    node.put("correlationId", attempt.correlationId().toString());
    node.put("ingressAccepted", attempt.ingressAccepted());
    node.put("outcome", attempt.outcome().name());
    node.put("trustedResponseObserved", attempt.trustedResponseObserved());
    nullable(node, "responseCorrelationId", attempt.responseCorrelationId());
    nullable(node, "responseStatus", attempt.responseStatus());
    nullable(node, "applicationSequence", attempt.applicationSequence());
    nullable(node, "resultDigest", attempt.resultDigest());
    node.put("businessEffectApplied", attempt.businessEffectApplied());
    nullable(node, "retryOfAttemptOrdinal", attempt.retryOfAttemptOrdinal());
    node.put("authorityTerm", attempt.authorityTerm());
    node.put("authorityLeaderId", attempt.authorityLeaderId());
    node.put(
        "responseAcceptedUnderCurrentClientAuthority",
        attempt.responseAcceptedUnderCurrentClientAuthority());
    node.put("noQuorumWindow", attempt.noQuorumWindow());
    node.put(
        "timeoutClassifiedAsBusinessRejection", attempt.timeoutClassifiedAsBusinessRejection());
  }

  private static void addBinding(ObjectNode node, M12DeterministicCorpus.Binding binding) {
    node.put("identityIndex", binding.identity().index());
    node.put("canonicalEnvelopeSha256", binding.identity().canonicalSha256());
    node.put("applicationSequence", binding.applicationSequence());
    node.put("resultDigest", binding.resultDigest());
    node.put("businessEffectCount", binding.businessEffectCount());
    node.put("observedResponseAuthorityTerm", binding.observedResponseAuthorityTerm());
  }

  private static void addMember(ObjectNode node, M12ExecutionTrace.MemberObservation member) {
    node.put("memberId", member.memberId());
    node.put("processId", member.processId());
    node.put("role", member.role().name());
    node.put("leadershipTermId", member.leadershipTerm());
    node.put("nextApplicationSequence", member.nextApplicationSequence());
    node.put("identityCount", member.identityCount());
    node.put("semanticStateDigest", member.semanticDigest());
    node.put("identityResultDigest", member.identityTableDigest());
    node.put("aeronDirectory", member.aeronDirectory());
    node.put("archiveDirectory", member.archiveDirectory());
    node.put("clusterDirectory", member.clusterDirectory());
    node.put("portBlockBase", member.portBlockBase());
    ArrayNode ports = node.putArray("udpPorts");
    member.udpPorts().forEach(ports::add);
    node.put("componentErrorCount", member.componentErrorCount());
  }

  private static void addStabilityStatus(ObjectNode node, M12MemberStatus status) {
    node.put("memberId", status.memberId());
    node.put("processId", status.processId());
    node.put("statusSequence", status.statusSequence());
    node.put("observedAtEpochMillis", status.observedAtEpochMillis());
    node.put("role", status.role());
    node.put("electionState", status.electionState());
    node.put("leadershipTermId", status.leadershipTermId());
    node.put("commitPosition", status.commitPosition());
    node.put("logPosition", status.logPosition());
    node.put("nextApplicationSequence", status.nextApplicationSequence());
    node.put("identityCount", status.identityResultCount());
    node.put("semanticStateDigest", status.semanticStateDigest());
    node.put("identityResultDigest", status.identityResultDigest());
    node.put("componentErrorCount", status.componentErrors().size());
    node.put("diagnosticWarningCount", status.diagnosticWarnings().size());
    node.put("droppedDiagnosticWarnings", status.droppedDiagnosticWarnings());
    ArrayNode warnings = node.putArray("diagnosticWarnings");
    status.diagnosticWarnings().forEach(warnings::add);
  }

  private static void addStatus(ObjectNode node, M12MemberStatus status) {
    node.put("memberId", status.memberId());
    node.put("processId", status.processId());
    node.put("role", status.role());
    node.put("electionState", status.electionState());
    node.put("leadershipTermId", status.leadershipTermId());
    node.put("commitPosition", status.commitPosition());
    node.put("logPosition", status.logPosition());
    node.put("nextApplicationSequence", status.nextApplicationSequence());
    node.put("identityCount", status.identityResultCount());
    node.put("semanticStateDigest", status.semanticStateDigest());
    node.put("identityResultDigest", status.identityResultDigest());
    node.put("componentErrorCount", status.componentErrors().size());
    node.put("diagnosticWarningCount", status.diagnosticWarnings().size());
    node.put("droppedDiagnosticWarnings", status.droppedDiagnosticWarnings());
    ArrayNode warnings = node.putArray("diagnosticWarnings");
    status.diagnosticWarnings().forEach(warnings::add);
  }

  private static void addAppliedUnknownReplicationEvidence(
      ObjectNode report, List<M12MemberStatus> statuses) {
    report.put("appliedUnknownAttemptOrdinal", 42);
    report.put("appliedUnknownObservedOnAllMembersBeforeLeaderKill", true);
    report.put("appliedUnknownExpectedNextApplicationSequence", 34);
    report.put("appliedUnknownExpectedIdentityCount", 33);
    ArrayNode observations = report.putArray("appliedUnknownMembersBeforeLeaderKill");
    statuses.forEach(status -> addStatus(observations.addObject(), status));
  }

  private static void nullable(ObjectNode node, String name, Object value) {
    if (value == null) {
      node.putNull(name);
    } else if (value instanceof Number number) {
      node.put(name, number.longValue());
    } else if (value instanceof Enum<?> enumeration) {
      node.put(name, enumeration.name());
    } else {
      node.put(name, value.toString());
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new M12SemanticFailure(
          "M12_REAL_CLUSTER_CONTRACT", "M12 real cluster fault suite failed: " + message);
    }
  }

  private record ObservedAcknowledgement(
      M12DeterministicCorpus.Attempt attempt,
      M12DeterministicCorpus.Binding binding,
      M12DeterministicCorpus.ResponseStatus status) {}

  private record ClientGenerationObservation(
      long generation,
      M12TransportAuthority authority,
      int acceptedOffers,
      int decodedResponses,
      int rejectedResponses,
      int componentErrors) {}

  record Result(
      M12ExecutionTrace trace,
      ObjectNode historyReport,
      ObjectNode topologyReport,
      ObjectNode leadershipReport,
      ObjectNode quorumReport,
      ObjectNode catchupReport,
      ObjectNode stateEquivalenceReport,
      byte[] canonicalCommandBytes,
      Path clusterRoot) {
    Result {
      Objects.requireNonNull(trace, "trace");
      historyReport = Objects.requireNonNull(historyReport, "historyReport").deepCopy();
      topologyReport = Objects.requireNonNull(topologyReport, "topologyReport").deepCopy();
      leadershipReport = Objects.requireNonNull(leadershipReport, "leadershipReport").deepCopy();
      quorumReport = Objects.requireNonNull(quorumReport, "quorumReport").deepCopy();
      catchupReport = Objects.requireNonNull(catchupReport, "catchupReport").deepCopy();
      stateEquivalenceReport =
          Objects.requireNonNull(stateEquivalenceReport, "stateEquivalenceReport").deepCopy();
      canonicalCommandBytes =
          Objects.requireNonNull(canonicalCommandBytes, "canonicalCommandBytes").clone();
      clusterRoot = Objects.requireNonNull(clusterRoot, "clusterRoot").toAbsolutePath().normalize();
    }

    @Override
    public ObjectNode historyReport() {
      return historyReport.deepCopy();
    }

    @Override
    public ObjectNode topologyReport() {
      return topologyReport.deepCopy();
    }

    @Override
    public ObjectNode leadershipReport() {
      return leadershipReport.deepCopy();
    }

    @Override
    public ObjectNode quorumReport() {
      return quorumReport.deepCopy();
    }

    @Override
    public ObjectNode catchupReport() {
      return catchupReport.deepCopy();
    }

    @Override
    public ObjectNode stateEquivalenceReport() {
      return stateEquivalenceReport.deepCopy();
    }

    @Override
    public byte[] canonicalCommandBytes() {
      return canonicalCommandBytes.clone();
    }
  }
}
