package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.cluster.DirectM11MatchingRuntime;
import io.github.lchareln.cex.matching.cluster.M11ResponseStatus;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Pure-data M12 history checker. No method starts, stops, or pretends to observe Aeron. */
final class M12HistoryJudge {
  Inspection inspect(M12WorkloadLoader.Workload workload, M12ExecutionTrace trace) {
    require(
        workload.sha256().equals(M12StartCheckRunner.WORKLOAD_SHA256),
        "WORKLOAD_NOT_FROZEN",
        "workload digest changed");
    require(trace.identities().size() == 66, "IDENTITY_CARDINALITY_CHANGED", "need 66 identities");
    require(
        trace.attempts().size() >= 84, "ATTEMPT_CARDINALITY_CHANGED", "need at least 84 attempts");
    require(
        trace.attempts().stream().filter(M12DeterministicCorpus.Attempt::ingressAccepted).count()
            >= 84,
        "INGRESS_ATTEMPT_CARDINALITY_CHANGED",
        "need at least 84 accepted ingress attempts");

    assertCanonicalIdentities(trace);
    Map<Integer, M12DeterministicCorpus.Attempt> attempts = assertInvocationShape(trace);
    Map<String, M12DeterministicCorpus.Binding> bindings = assertBindings(trace);
    RetryFacts retryFacts = assertRetries(trace, attempts, bindings);
    TopologyFacts topologyFacts = assertTopology(trace, bindings);

    List<AssertionObservation> facts = new ArrayList<>();
    add(
        workload,
        facts,
        "THREE_REAL_MEMBERS",
        "three independently owned member observations with distinct positive PIDs",
        "scope="
            + trace.scope()
            + ",memberIds="
            + memberIds(trace.topology().initialMembers())
            + ",pids="
            + processIds(trace.topology().initialMembers()));
    add(
        workload,
        facts,
        "SINGLE_INITIAL_LEADER",
        "the initial topology has exactly one Leader and two Followers",
        "leader=" + trace.topology().initialLeaderId() + ",term=" + trace.topology().initialTerm());
    add(
        workload,
        facts,
        "DISJOINT_MEMBER_OWNERSHIP",
        "member directories and five-port blocks are pairwise disjoint",
        topologyFacts.ownershipObservation());
    add(
        workload,
        facts,
        "CORRELATED_APPLY_ACK",
        "every acknowledgement is ingress-accepted and carries its invocation correlation",
        "acknowledged=" + retryFacts.acknowledgedCount() + ",correlationsExact=true");
    add(
        workload,
        facts,
        "M11_PROTOCOL_UNCHANGED",
        "the harness reports the frozen M11 request and response protocol unchanged",
        "m11ProtocolUnchanged=" + trace.topology().m11ProtocolUnchanged());
    add(
        workload,
        facts,
        "INGRESS_OFFER_NOT_ACK",
        "an accepted ingress offer without a trusted response remains UNKNOWN",
        "acceptedUnknown=" + retryFacts.acceptedUnknownCount());
    add(
        workload,
        facts,
        "PRE_OFFER_NOT_SUBMITTED",
        "a command not accepted by ingress is NOT_SUBMITTED",
        "notSubmitted=" + retryFacts.notSubmittedCount());
    add(
        workload,
        facts,
        "AFTER_OFFER_UNKNOWN",
        "accepted invocations without trusted responses are UNKNOWN and not rejected",
        "acceptedUnknown=" + retryFacts.acceptedUnknownCount() + ",timeoutRejected=false");
    add(
        workload,
        facts,
        "SAME_IDENTITY_RETRY",
        "every retry preserves exact canonical envelope bytes and uses a fresh correlation",
        "retries=" + retryFacts.retryCount() + ",byteExact=true,freshCorrelations=true");
    add(
        workload,
        facts,
        "ORIGINAL_RESULT_REPLAY",
        "duplicate acknowledgements replay the originally bound sequence and result digest",
        "duplicateReplays="
            + retryFacts.duplicateReplayCount()
            + ",noQuorumBranch="
            + retryFacts.noQuorumConvergedStatus()
            + ",exact=true");
    add(
        workload,
        facts,
        "ONE_EFFECT_PER_IDENTITY",
        "all 66 durable identities bind exactly one application sequence and one business effect",
        "identities=66,bindings=66,effectCount=66,nextApplicationSequence=67");
    add(
        workload,
        facts,
        "EXTERNAL_LEADER_KILL",
        "the selected fault target was the observed current Leader and the controller was external",
        "killedMember=" + trace.topology().killedMemberId() + ",external=true");
    add(
        workload,
        facts,
        "NEW_LEADER_ELECTED",
        "a different voting member became the replacement Leader",
        "initial="
            + trace.topology().initialLeaderId()
            + ",replacement="
            + trace.topology().replacementLeaderId());
    add(
        workload,
        facts,
        "LEADERSHIP_TERM_ADVANCES",
        "replacement leadership term is greater than the latest stable pre-kill authority term",
        "preKillAuthorityTerm="
            + trace.topology().preKillAuthorityTerm()
            + ",replacementTerm="
            + trace.topology().replacementTerm());
    add(
        workload,
        facts,
        "CLIENT_CURRENT_LEADER_AUTHORITY",
        "post-failure acknowledgements are accepted under the replacement client authority observation",
        "clientLeader="
            + trace.topology().clientConnectedLeaderId()
            + ",clientTerm="
            + trace.topology().clientConnectedTerm());
    add(
        workload,
        facts,
        "STALE_LEADER_NOT_ACKNOWLEDGED",
        "in this fail-stop schedule, post-failure acknowledgements do not record the old client authority",
        "oldClientAuthorityAcknowledgements="
            + trace.topology().staleLeaderAcknowledgements()
            + ",scope=FAIL_STOP_SCHEDULE");
    add(
        workload,
        facts,
        "APPLICATION_SEQUENCE_CONTINUES",
        "application sequences remain contiguous across the Leader replacement",
        topologyFacts.sequenceObservation());
    add(
        workload,
        facts,
        "FORMER_LEADER_RETURNS_AS_FOLLOWER",
        "the failed former Leader restarted as a Follower",
        "member="
            + trace.topology().formerLeaderId()
            + ",role="
            + trace.topology().formerLeaderRoleAfterRestart());
    add(
        workload,
        facts,
        "FOLLOWER_CATCH_UP",
        "the restarted member caught up through the application observer",
        "caughtUp="
            + trace.topology().formerLeaderCaughtUp()
            + ",observer="
            + trace.topology().stateReadFromApplicationObserver());
    add(
        workload,
        facts,
        "MEMBER_STATE_EQUIVALENCE",
        "all three member observations agree on sequence, identity table, and semantic digest",
        topologyFacts.stateObservation());
    add(
        workload,
        facts,
        "RUNTIME_METADATA_EXCLUDED",
        "the semantic state digest is recomputed solely from durable business bindings",
        "businessDigest=" + topologyFacts.businessDigest() + ",termFieldsExcluded=true");
    add(
        workload,
        facts,
        "NO_QUORUM_NO_ACK",
        "the one-of-three interval produced UNKNOWN and no acknowledgement",
        "liveVotingMembers="
            + trace.topology().liveVotingMembersDuringMinority()
            + ",minorityAcknowledgements="
            + trace.topology().minorityAcknowledgements());
    add(
        workload,
        facts,
        "QUORUM_RESTORE_CONVERGENCE",
        "same-identity retry after quorum restoration converged to one bound effect",
        "noQuorumRetries="
            + retryFacts.noQuorumRetryCount()
            + ",finalStatus="
            + retryFacts.noQuorumConvergedStatus()
            + ",quorumRestored=true");
    add(
        workload,
        facts,
        "CORE_UNCHANGED",
        "the runtime harness reports matching-core unchanged from M11",
        "matchingCoreUnchanged=" + trace.topology().matchingCoreUnchanged());
    require(facts.size() == 24, "ASSERTION_LEDGER_SHAPE_CHANGED", "expected 24 trace facts");
    return new Inspection(
        List.copyOf(facts),
        semanticDigest(trace),
        retryFacts,
        topologyFacts,
        trace.scope() == M12ExecutionTrace.Scope.REAL_AERON_CHILD_PROCESSES);
  }

  static String semanticDigest(M12ExecutionTrace trace) {
    DirectM11MatchingRuntime replay = new DirectM11MatchingRuntime();
    trace.bindings().stream()
        .sorted(Comparator.comparingLong(M12DeterministicCorpus.Binding::applicationSequence))
        .forEach(
            binding -> {
              UUID correlation = new UUID(0x4d31322d5245504cL, binding.applicationSequence());
              var response =
                  replay
                      .submit(M12DeterministicCorpus.requestFor(binding.identity(), correlation))
                      .response();
              require(
                  response.status() == M11ResponseStatus.NEW_APPLIED,
                  "BUSINESS_SEMANTIC_REPLAY_NOT_NEW",
                  "direct replay did not produce NEW_APPLIED");
              require(
                  response.applicationSequence().isPresent()
                      && response.applicationSequence().orElseThrow()
                          == binding.applicationSequence(),
                  "BUSINESS_SEMANTIC_REPLAY_SEQUENCE_MISMATCH",
                  "direct replay sequence differs from the observed binding");
              require(
                  response.resultDigest().isPresent()
                      && response.resultDigest().orElseThrow().equals(binding.resultDigest()),
                  "BUSINESS_SEMANTIC_REPLAY_RESULT_MISMATCH",
                  "direct replay result differs from the observed binding");
            });
    String recomputed = replay.semanticStateDigest();
    require(
        recomputed.equals(trace.expectedBusinessSemanticDigest()),
        "BUSINESS_SEMANTIC_DIGEST_MISMATCH",
        "observed bindings differ from the expected business semantic state");
    return recomputed;
  }

  private static void assertCanonicalIdentities(M12ExecutionTrace trace) {
    Set<String> digests = new HashSet<>();
    Set<UUID> commandIds = new HashSet<>();
    Set<String> slots = new HashSet<>();
    for (M12DeterministicCorpus.DurableIdentity identity : trace.identities()) {
      require(
          Hashing.sha256Hex(identity.canonicalBytes()).equals(identity.canonicalSha256()),
          "CANONICAL_IDENTITY_DIGEST_MISMATCH",
          "canonical identity bytes changed");
      require(
          Hashing.sha256Hex(identity.payloadBytes()).equals(identity.payloadSha256()),
          "PAYLOAD_DIGEST_MISMATCH",
          "payload bytes changed");
      var rebuilt =
          M12DeterministicCorpus.requestFor(
              identity, new UUID(0x4d31320000000000L, identity.index()));
      require(
          Arrays.equals(rebuilt.envelopeBytes(), identity.canonicalBytes()),
          "CANONICAL_ENVELOPE_ROUND_TRIP_FAILED",
          "M11 request did not preserve M08 envelope bytes");
      require(digests.add(identity.canonicalSha256()), "DUPLICATE_DURABLE_IDENTITY", "identity");
      require(commandIds.add(identity.commandId()), "DUPLICATE_COMMAND_ID", "commandId");
      String slot =
          identity.producerId()
              + '|'
              + identity.producerEpoch()
              + '|'
              + identity.shardId()
              + '|'
              + identity.producerSequence();
      require(slots.add(slot), "DUPLICATE_PRODUCER_SLOT", "producer Slot");
    }
  }

  private static Map<Integer, M12DeterministicCorpus.Attempt> assertInvocationShape(
      M12ExecutionTrace trace) {
    Map<Integer, M12DeterministicCorpus.Attempt> byOrdinal = new LinkedHashMap<>();
    Set<UUID> correlations = new HashSet<>();
    Set<String> allowedPhases = Set.copyOf(M12StartCheckRunner.PHASE_ORDER);
    int expectedOrdinal = 1;
    for (M12DeterministicCorpus.Attempt attempt : trace.attempts()) {
      require(attempt.ordinal() == expectedOrdinal++, "ATTEMPT_ORDER_CHANGED", "attempt order");
      require(
          allowedPhases.contains(attempt.phase())
              || "PRE_OFFER_IS_NOT_SUBMITTED".equals(attempt.phase()),
          "UNKNOWN_ATTEMPT_PHASE",
          attempt.phase());
      require(
          byOrdinal.put(attempt.ordinal(), attempt) == null,
          "DUPLICATE_ATTEMPT_ORDINAL",
          "ordinal");
      require(correlations.add(attempt.correlationId()), "CORRELATION_REUSED", "correlation");
      require(
          !attempt.timeoutClassifiedAsBusinessRejection(),
          "TIMEOUT_AS_BUSINESS_REJECTION",
          "UNKNOWN was converted to a business rejection");
      require(
          !attempt.noQuorumWindow()
              || attempt.outcome() != M12DeterministicCorpus.ClientOutcome.ACKNOWLEDGED,
          "MINORITY_ACKNOWLEDGED",
          "one-of-three invocation was acknowledged");

      switch (attempt.outcome()) {
        case NOT_SUBMITTED -> {
          require(!attempt.ingressAccepted(), "NOT_SUBMITTED_AFTER_ACCEPTANCE", "NOT_SUBMITTED");
          require(
              !attempt.trustedResponseObserved(), "NOT_SUBMITTED_HAS_RESPONSE", "NOT_SUBMITTED");
          require(attempt.responseStatus() == null, "NOT_SUBMITTED_HAS_STATUS", "NOT_SUBMITTED");
        }
        case UNKNOWN -> {
          require(attempt.ingressAccepted(), "UNKNOWN_BEFORE_ACCEPTANCE", "UNKNOWN");
          require(!attempt.trustedResponseObserved(), "UNKNOWN_HAS_TRUSTED_RESPONSE", "UNKNOWN");
          require(attempt.responseStatus() == null, "UNKNOWN_HAS_BUSINESS_STATUS", "UNKNOWN");
          require(
              attempt.responseCorrelationId() == null,
              "UNKNOWN_HAS_RESPONSE_CORRELATION",
              "UNKNOWN");
        }
        case ACKNOWLEDGED -> {
          require(attempt.ingressAccepted(), "ACK_BEFORE_ACCEPTANCE", "ACKNOWLEDGED");
          require(
              attempt.trustedResponseObserved(),
              "ACK_WITHOUT_TRUSTED_RESPONSE",
              "offer acceptance was upgraded to acknowledgement");
          require(attempt.responseStatus() != null, "ACK_WITHOUT_STATUS", "ACKNOWLEDGED");
          require(
              attempt.correlationId().equals(attempt.responseCorrelationId()),
              "ACK_CORRELATION_MISMATCH",
              "response correlation changed");
          require(
              attempt.applicationSequence() != null && attempt.resultDigest() != null,
              "ACK_WITHOUT_BOUND_RESULT",
              "ACKNOWLEDGED");
        }
      }
    }
    return Map.copyOf(byOrdinal);
  }

  private static Map<String, M12DeterministicCorpus.Binding> assertBindings(
      M12ExecutionTrace trace) {
    Map<String, M12DeterministicCorpus.Binding> oracle = new HashMap<>();
    for (M12DeterministicCorpus.Binding expected : trace.directOracleBindings()) {
      require(
          oracle.put(expected.identity().canonicalSha256(), expected) == null,
          "DIRECT_ORACLE_BINDING_DUPLICATED",
          "direct M11 oracle identity");
    }
    require(oracle.size() == 66, "DIRECT_ORACLE_CARDINALITY_CHANGED", "need 66 oracle results");
    Map<String, M12DeterministicCorpus.Binding> byIdentity = new HashMap<>();
    Set<Long> sequences = new HashSet<>();
    for (M12DeterministicCorpus.Binding binding : trace.bindings()) {
      String identity = binding.identity().canonicalSha256();
      require(
          byIdentity.put(identity, binding) == null,
          "DUPLICATE_CREATED_SECOND_EFFECT",
          "one durable identity has multiple result bindings");
      require(
          binding.businessEffectCount() == 1,
          "DUPLICATE_CREATED_SECOND_EFFECT",
          "business effect count is not one");
      require(
          sequences.add(binding.applicationSequence()),
          "APPLICATION_SEQUENCE_REUSED",
          "application sequence is not unique");
      M12DeterministicCorpus.Binding expected = oracle.get(identity);
      require(expected != null, "OBSERVED_IDENTITY_NOT_IN_ORACLE", "identity result");
      require(
          expected.applicationSequence() == binding.applicationSequence()
              && expected.resultDigest().equals(binding.resultDigest()),
          "DIRECT_ORACLE_RESULT_MISMATCH",
          "observed result differs from direct M11 runtime");
    }
    require(byIdentity.size() == 66, "IDENTITY_BINDING_CARDINALITY_CHANGED", "need 66 bindings");
    List<Long> ordered = sequences.stream().sorted().toList();
    require(
        ordered.equals(java.util.stream.LongStream.rangeClosed(1, 66).boxed().toList()),
        "APPLICATION_SEQUENCE_NOT_CONTIGUOUS",
        "expected 1..66");
    return Map.copyOf(byIdentity);
  }

  private static RetryFacts assertRetries(
      M12ExecutionTrace trace,
      Map<Integer, M12DeterministicCorpus.Attempt> attempts,
      Map<String, M12DeterministicCorpus.Binding> bindings) {
    int acknowledged = 0;
    int unknown = 0;
    int notSubmitted = 0;
    int retries = 0;
    int duplicateReplays = 0;
    int noQuorumRetries = 0;
    String noQuorumConvergedStatus = "MISSING";
    for (M12DeterministicCorpus.Attempt attempt : trace.attempts()) {
      switch (attempt.outcome()) {
        case ACKNOWLEDGED -> acknowledged++;
        case UNKNOWN -> unknown++;
        case NOT_SUBMITTED -> notSubmitted++;
      }
      if (attempt.retryOfAttemptOrdinal() == null) {
        assertAttemptBinding(attempt, bindings);
        continue;
      }
      retries++;
      M12DeterministicCorpus.Attempt original = attempts.get(attempt.retryOfAttemptOrdinal());
      require(original != null, "RETRY_TARGET_MISSING", "retry target");
      require(original.ordinal() < attempt.ordinal(), "RETRY_TARGET_NOT_PRIOR", "retry target");
      require(
          Arrays.equals(original.identity().canonicalBytes(), attempt.identity().canonicalBytes()),
          "UNKNOWN_RETRY_CHANGED_DURABLE_IDENTITY",
          "retry changed canonical M08 envelope bytes");
      require(
          !original.correlationId().equals(attempt.correlationId()),
          "RETRY_REUSED_CORRELATION",
          "retry correlation must be fresh");
      assertAttemptBinding(attempt, bindings);
      if (original.outcome() == M12DeterministicCorpus.ClientOutcome.ACKNOWLEDGED) {
        require(
            attempt.responseStatus() == M12DeterministicCorpus.ResponseStatus.DUPLICATE_REPLAYED,
            "DUPLICATE_CREATED_SECOND_EFFECT",
            "retry of acknowledged identity was treated as new");
      }
      if (attempt.responseStatus() == M12DeterministicCorpus.ResponseStatus.DUPLICATE_REPLAYED) {
        duplicateReplays++;
        require(
            !attempt.businessEffectApplied(),
            "DUPLICATE_CREATED_SECOND_EFFECT",
            "duplicate effect");
        require(
            original.applicationSequence() != null
                && original.applicationSequence().equals(attempt.applicationSequence())
                && original.resultDigest().equals(attempt.resultDigest()),
            "ORIGINAL_RESULT_NOT_REPLAYED",
            "duplicate did not replay original result");
      }
      if (original.outcome() == M12DeterministicCorpus.ClientOutcome.UNKNOWN
          && original.applicationSequence() != null) {
        require(
            attempt.responseStatus() == M12DeterministicCorpus.ResponseStatus.DUPLICATE_REPLAYED,
            "DUPLICATE_CREATED_SECOND_EFFECT",
            "an already-bound UNKNOWN was applied again");
      }
      if (original.outcome() == M12DeterministicCorpus.ClientOutcome.UNKNOWN
          && original.applicationSequence() == null) {
        require(
            attempt.responseStatus() == M12DeterministicCorpus.ResponseStatus.NEW_APPLIED,
            "UNBOUND_UNKNOWN_REPLAYED_NONEXISTENT_RESULT",
            "unbound UNKNOWN returned duplicate");
      }
      if (original.noQuorumWindow()) {
        noQuorumRetries++;
        require(
            attempt.responseStatus() == M12DeterministicCorpus.ResponseStatus.NEW_APPLIED
                || attempt.responseStatus()
                    == M12DeterministicCorpus.ResponseStatus.DUPLICATE_REPLAYED,
            "NO_QUORUM_RETRY_DID_NOT_CONVERGE",
            "same-identity retry did not converge");
        noQuorumConvergedStatus = attempt.responseStatus().name();
      }
    }
    require(retries == 18, "RETRY_CARDINALITY_CHANGED", "expected 18 retries");
    require(
        duplicateReplays >= 17 && duplicateReplays <= 18,
        "DUPLICATE_REPLAY_CARDINALITY_CHANGED",
        "expected 17 fixed duplicates and one optional no-quorum duplicate");
    require(unknown == 2, "UNKNOWN_CARDINALITY_CHANGED", "expected two UNKNOWN invocations");
    require(notSubmitted >= 1, "NOT_SUBMITTED_MISSING", "expected pre-offer invocation");
    require(noQuorumRetries == 1, "NO_QUORUM_RETRY_MISSING", "expected no-quorum retry");
    Map<String, Integer> effects = new HashMap<>();
    trace.attempts().stream()
        .filter(M12DeterministicCorpus.Attempt::businessEffectApplied)
        .forEach(attempt -> effects.merge(attempt.identity().canonicalSha256(), 1, Integer::sum));
    require(effects.size() == 66, "BUSINESS_EFFECT_CARDINALITY_CHANGED", "need 66 effects");
    require(
        effects.values().stream().allMatch(count -> count == 1),
        "DUPLICATE_CREATED_SECOND_EFFECT",
        "an identity has multiple business effects");
    return new RetryFacts(
        acknowledged,
        unknown,
        notSubmitted,
        retries,
        duplicateReplays,
        noQuorumRetries,
        noQuorumConvergedStatus);
  }

  private static void assertAttemptBinding(
      M12DeterministicCorpus.Attempt attempt,
      Map<String, M12DeterministicCorpus.Binding> bindings) {
    M12DeterministicCorpus.Binding binding = bindings.get(attempt.identity().canonicalSha256());
    if (attempt.applicationSequence() == null) {
      return;
    }
    require(binding != null, "RESULT_WITHOUT_IDENTITY_BINDING", "attempt binding");
    require(
        binding.applicationSequence() == attempt.applicationSequence()
            && binding.resultDigest().equals(attempt.resultDigest()),
        "ORIGINAL_RESULT_NOT_REPLAYED",
        "attempt result differs from identity binding");
  }

  private static TopologyFacts assertTopology(
      M12ExecutionTrace trace, Map<String, M12DeterministicCorpus.Binding> bindings) {
    M12ExecutionTrace.Topology topology = trace.topology();
    List<M12ExecutionTrace.MemberObservation> initial = topology.initialMembers();
    List<M12ExecutionTrace.MemberObservation> converged = topology.convergedMembers();
    require(initial.size() == 3, "THREE_MEMBERS_NOT_OBSERVED", "initial members");
    require(converged.size() == 3, "THREE_MEMBERS_NOT_CONVERGED", "converged members");
    require(memberIds(initial).equals(Set.of(0, 1, 2)), "STATIC_MEMBERS_CHANGED", "member IDs");
    require(memberIds(converged).equals(Set.of(0, 1, 2)), "STATIC_MEMBERS_CHANGED", "member IDs");
    require(processIds(initial).size() == 3, "MEMBER_PROCESS_NOT_ISOLATED", "initial PIDs");
    require(
        initial.stream().filter(member -> member.role() == M12ExecutionTrace.Role.LEADER).count()
            == 1,
        "INITIAL_LEADER_COUNT_CHANGED",
        "initial roles");
    require(
        member(initial, topology.initialLeaderId()).role() == M12ExecutionTrace.Role.LEADER,
        "INITIAL_LEADER_ID_MISMATCH",
        "initial leader");
    require(disjointOwnership(initial), "MEMBER_OWNERSHIP_OVERLAP", "directories or ports overlap");

    require(
        topology.faultControllerExternal() && topology.faultTargetWasObservedCurrentLeader(),
        "FAULT_TARGET_NOT_CURRENT_EXTERNAL_LEADER",
        "leader fault controller");
    require(
        topology.killedMemberId() == topology.initialLeaderId(),
        "FAULT_TARGET_NOT_CURRENT_EXTERNAL_LEADER",
        "killed member");
    require(
        topology.replacementLeaderId() != topology.initialLeaderId()
            && member(converged, topology.replacementLeaderId()).role()
                == M12ExecutionTrace.Role.LEADER,
        "REPLACEMENT_LEADER_NOT_ELECTED",
        "replacement leader");
    require(
        topology.preKillAuthorityTerm() >= topology.initialTerm()
            && topology.replacementTerm() > topology.preKillAuthorityTerm(),
        "LEADERSHIP_TERM_DID_NOT_ADVANCE",
        "leadership term");
    require(
        topology.clientConnectedLeaderId() == topology.replacementLeaderId()
            && topology.clientConnectedTerm() == topology.replacementTerm(),
        "CLIENT_NOT_CONNECTED_TO_CURRENT_AUTHORITY",
        "client authority");
    require(
        topology.staleLeaderAcknowledgements() == 0,
        "STALE_LEADER_ACKNOWLEDGED",
        "stale Leader response");
    require(
        trace.attempts().stream()
            .filter(attempt -> attempt.authorityTerm() >= topology.replacementTerm())
            .filter(
                attempt -> attempt.outcome() == M12DeterministicCorpus.ClientOutcome.ACKNOWLEDGED)
            .allMatch(M12DeterministicCorpus.Attempt::responseAcceptedUnderCurrentClientAuthority),
        "STALE_LEADER_ACKNOWLEDGED",
        "post-failure response authority");

    require(
        topology.formerLeaderRestarted()
            && topology.formerLeaderId() == topology.initialLeaderId()
            && topology.formerLeaderRoleAfterRestart() == M12ExecutionTrace.Role.FOLLOWER,
        "FORMER_LEADER_NOT_FOLLOWER",
        "former Leader role");
    require(
        topology.formerLeaderCaughtUp() && topology.stateReadFromApplicationObserver(),
        "FORMER_LEADER_DID_NOT_CATCH_UP",
        "former Leader catch-up");
    require(topology.majorityLost(), "NO_QUORUM_WINDOW_NOT_OBSERVED", "majority loss");
    require(
        topology.liveVotingMembersDuringMinority() == 1,
        "NO_QUORUM_WINDOW_NOT_OBSERVED",
        "minority size");
    require(
        topology.minorityAcknowledgements() == 0,
        "MINORITY_ACKNOWLEDGED",
        "minority acknowledged a command");
    require(topology.quorumRestored(), "QUORUM_NOT_RESTORED", "quorum restoration");
    require(topology.matchingCoreUnchanged(), "MATCHING_CORE_CHANGED", "matching-core tree");
    require(topology.m11ProtocolUnchanged(), "M11_PROTOCOL_CHANGED", "M11 protocol");
    require(
        topology.boundedPollingUsed() && !topology.fixedSleepsUsed(),
        "UNBOUNDED_OR_FIXED_SLEEP_POLLING",
        "deadline policy");
    require(
        converged.stream().allMatch(member -> member.componentErrorCount() == 0),
        "CLUSTER_COMPONENT_ERROR",
        "component errors");

    String expectedSemantic = trace.expectedBusinessSemanticDigest();
    Set<String> observedIdentityDigests =
        new LinkedHashSet<>(
            converged.stream()
                .map(M12ExecutionTrace.MemberObservation::identityTableDigest)
                .toList());
    require(
        observedIdentityDigests.size() == 1,
        "FOLLOWER_IDENTITY_TABLE_DIVERGED",
        "member identity-result digests differ");
    require(
        observedIdentityDigests.equals(Set.of(trace.expectedIdentityResultDigest())),
        "DIRECT_ORACLE_IDENTITY_TABLE_MISMATCH",
        "member identity-result digest differs from the direct M11 oracle");
    for (M12ExecutionTrace.MemberObservation observed : converged) {
      require(observed.nextApplicationSequence() == 67, "FOLLOWER_SEQUENCE_DIVERGED", "sequence");
      require(observed.identityCount() == 66, "FOLLOWER_IDENTITY_TABLE_DIVERGED", "identity count");
      require(
          expectedSemantic.equals(observed.semanticDigest()),
          "RUNTIME_METADATA_CHANGED_SEMANTIC_DIGEST",
          "business digest differs from term-independent recomputation");
    }
    require(
        converged.stream()
                .map(M12ExecutionTrace.MemberObservation::semanticDigest)
                .distinct()
                .count()
            == 1,
        "MEMBER_SEMANTIC_STATE_DIVERGED",
        "member semantic digests");

    List<Long> before =
        trace.attempts().stream()
            .filter(attempt -> "PRE_FAILOVER_ACKNOWLEDGED_NEW_32".equals(attempt.phase()))
            .map(M12DeterministicCorpus.Attempt::applicationSequence)
            .sorted()
            .toList();
    List<Long> after =
        trace.attempts().stream()
            .filter(attempt -> "POST_FAILOVER_ACKNOWLEDGED_NEW_32".equals(attempt.phase()))
            .map(M12DeterministicCorpus.Attempt::applicationSequence)
            .sorted()
            .toList();
    require(
        before.size() == 32 && before.getLast() == 32,
        "PRE_FAILOVER_SEQUENCE_CHANGED",
        "pre sequence");
    require(
        after.size() == 32 && after.getFirst() == 34 && after.getLast() == 65,
        "APPLICATION_SEQUENCE_DID_NOT_CONTINUE",
        "post sequence");
    String ownership =
        initial.stream()
            .sorted(Comparator.comparingInt(M12ExecutionTrace.MemberObservation::memberId))
            .map(
                member ->
                    member.memberId()
                        + "@base="
                        + member.portBlockBase()
                        + ":ports="
                        + member.udpPorts())
            .reduce((left, right) -> left + "," + right)
            .orElseThrow();
    String state =
        "members=3,nextApplicationSequence=67,identityCount=66,identityResultDigest="
            + observedIdentityDigests.iterator().next()
            + ",semantic="
            + expectedSemantic;
    return new TopologyFacts(
        ownership,
        "preMax=32,unknownApplied=33,postRange=34..65,final=66",
        state,
        expectedSemantic);
  }

  private static boolean disjointOwnership(List<M12ExecutionTrace.MemberObservation> members) {
    Set<String> paths = new HashSet<>();
    Set<Integer> ports = new HashSet<>();
    for (M12ExecutionTrace.MemberObservation member : members) {
      if (!paths.add(member.aeronDirectory())
          || !paths.add(member.archiveDirectory())
          || !paths.add(member.clusterDirectory())) {
        return false;
      }
      int base = member.portBlockBase();
      if (!member.udpPorts().equals(List.of(base + 1, base + 2, base + 3, base + 4, base + 5))) {
        return false;
      }
      for (int port : member.udpPorts()) {
        if (!ports.add(port)) {
          return false;
        }
      }
    }
    return true;
  }

  private static M12ExecutionTrace.MemberObservation member(
      List<M12ExecutionTrace.MemberObservation> members, int id) {
    return members.stream().filter(member -> member.memberId() == id).findFirst().orElseThrow();
  }

  private static Set<Integer> memberIds(List<M12ExecutionTrace.MemberObservation> members) {
    return java.util.Collections.unmodifiableSet(
        new LinkedHashSet<>(
            members.stream().map(M12ExecutionTrace.MemberObservation::memberId).toList()));
  }

  private static Set<Long> processIds(List<M12ExecutionTrace.MemberObservation> members) {
    return java.util.Collections.unmodifiableSet(
        new LinkedHashSet<>(
            members.stream().map(M12ExecutionTrace.MemberObservation::processId).toList()));
  }

  private static void add(
      M12WorkloadLoader.Workload workload,
      List<AssertionObservation> facts,
      String obligation,
      String assertion,
      String observed) {
    String scenario =
        workload.scenarios().stream()
            .filter(value -> value.proofObligations().contains(obligation))
            .map(M12WorkloadLoader.Scenario::id)
            .findFirst()
            .orElseThrow();
    facts.add(
        new AssertionObservation(
            obligation,
            scenario,
            "M12." + scenario + '.' + obligation + ".V1",
            "M12HistoryJudge#assertObligation(" + obligation + ")",
            assertion,
            observed));
  }

  private static void require(boolean condition, String fingerprint, String message) {
    if (!condition) {
      throw new M12SemanticFailure(fingerprint, "M12 semantic failure: " + message);
    }
  }

  record AssertionObservation(
      String obligation,
      String scenarioId,
      String assertionId,
      String producer,
      String assertion,
      String observedValue) {}

  record RetryFacts(
      int acknowledgedCount,
      int acceptedUnknownCount,
      int notSubmittedCount,
      int retryCount,
      int duplicateReplayCount,
      int noQuorumRetryCount,
      String noQuorumConvergedStatus) {}

  record TopologyFacts(
      String ownershipObservation,
      String sequenceObservation,
      String stateObservation,
      String businessDigest) {}

  record Inspection(
      List<AssertionObservation> observations,
      String semanticDigest,
      RetryFacts retries,
      TopologyFacts topology,
      boolean qualifiesAsRealClusterEvidence) {
    Inspection {
      observations = List.copyOf(observations);
    }
  }
}
