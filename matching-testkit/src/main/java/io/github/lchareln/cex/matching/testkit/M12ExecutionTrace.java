package io.github.lchareln.cex.matching.testkit;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Immutable observation DTO consumed by the M12 judge; it has no Aeron dependency. */
final class M12ExecutionTrace {
  private final Scope scope;
  private final String sourceArtifact;
  private final List<M12DeterministicCorpus.DurableIdentity> identities;
  private final List<M12DeterministicCorpus.Attempt> attempts;
  private final List<M12DeterministicCorpus.Binding> bindings;
  private final List<M12DeterministicCorpus.Binding> directOracleBindings;
  private final String expectedBusinessSemanticDigest;
  private final String expectedIdentityResultDigest;
  private final Topology topology;

  M12ExecutionTrace(
      Scope scope,
      String sourceArtifact,
      List<M12DeterministicCorpus.DurableIdentity> identities,
      List<M12DeterministicCorpus.Attempt> attempts,
      List<M12DeterministicCorpus.Binding> bindings,
      List<M12DeterministicCorpus.Binding> directOracleBindings,
      String expectedBusinessSemanticDigest,
      String expectedIdentityResultDigest,
      Topology topology) {
    this.scope = Objects.requireNonNull(scope);
    this.sourceArtifact = requireText(sourceArtifact, "sourceArtifact");
    this.identities = List.copyOf(identities);
    this.attempts = List.copyOf(attempts);
    this.bindings = List.copyOf(bindings);
    this.directOracleBindings = List.copyOf(directOracleBindings);
    this.expectedBusinessSemanticDigest =
        requireText(expectedBusinessSemanticDigest, "expectedBusinessSemanticDigest");
    this.expectedIdentityResultDigest =
        requireText(expectedIdentityResultDigest, "expectedIdentityResultDigest");
    this.topology = Objects.requireNonNull(topology);
  }

  static M12ExecutionTrace deterministicModelControl(M12DeterministicCorpus.Corpus corpus) {
    String semanticDigest = corpus.expectedFinalSemanticDigest();
    String identityDigest = corpus.expectedIdentityResultDigest();
    List<MemberObservation> initial =
        List.of(
            member(0, 12000, Role.LEADER, 52100, 1, 67, 66, semanticDigest, identityDigest),
            member(1, 12001, Role.FOLLOWER, 52110, 1, 67, 66, semanticDigest, identityDigest),
            member(2, 12002, Role.FOLLOWER, 52120, 1, 67, 66, semanticDigest, identityDigest));
    List<MemberObservation> converged =
        List.of(
            member(0, 12100, Role.FOLLOWER, 52100, 2, 67, 66, semanticDigest, identityDigest),
            member(1, 12001, Role.LEADER, 52110, 2, 67, 66, semanticDigest, identityDigest),
            member(2, 12002, Role.FOLLOWER, 52120, 2, 67, 66, semanticDigest, identityDigest));
    Topology topology =
        new Topology(
            initial,
            converged,
            0,
            1,
            0,
            1,
            true,
            true,
            1,
            2,
            1,
            2,
            0,
            true,
            0,
            Role.FOLLOWER,
            true,
            true,
            1,
            0,
            true,
            true,
            true,
            true,
            false,
            true);
    return new M12ExecutionTrace(
        Scope.DETERMINISTIC_MODEL_CONTROL,
        "m12-deterministic-model-control.json",
        corpus.identities(),
        corpus.attempts(),
        corpus.bindings(),
        corpus.bindings(),
        corpus.expectedFinalSemanticDigest(),
        corpus.expectedIdentityResultDigest(),
        topology);
  }

  static M12ExecutionTrace realAeronChildProcesses(
      M12DeterministicCorpus.Corpus corpus,
      String sourceArtifact,
      List<M12DeterministicCorpus.Attempt> observedAttempts,
      List<M12DeterministicCorpus.Binding> observedBindings,
      Topology observedTopology) {
    return new M12ExecutionTrace(
        Scope.REAL_AERON_CHILD_PROCESSES,
        sourceArtifact,
        corpus.identities(),
        observedAttempts,
        observedBindings,
        corpus.bindings(),
        corpus.expectedFinalSemanticDigest(),
        corpus.expectedIdentityResultDigest(),
        observedTopology);
  }

  private static MemberObservation member(
      int id,
      long pid,
      Role role,
      int portBase,
      long term,
      long nextSequence,
      int identityCount,
      String semanticDigest,
      String identityDigest) {
    String root = "build/tmp/m12/three-member-failover/member-" + id;
    return new MemberObservation(
        id,
        pid,
        role,
        Path.of(root, "aeron").toString(),
        Path.of(root, "archive").toString(),
        Path.of(root, "cluster").toString(),
        portBase,
        List.of(portBase + 1, portBase + 2, portBase + 3, portBase + 4, portBase + 5),
        term,
        nextSequence,
        identityCount,
        semanticDigest,
        identityDigest,
        0);
  }

  static String identityTableDigest(List<M12DeterministicCorpus.DurableIdentity> identities) {
    StringBuilder canonical = new StringBuilder("M12-IDENTITY-TABLE-V1\n");
    identities.stream()
        .sorted(Comparator.comparingInt(M12DeterministicCorpus.DurableIdentity::index))
        .forEach(identity -> canonical.append(identity.canonicalSha256()).append('\n'));
    return Hashing.sha256Hex(canonical.toString().getBytes(StandardCharsets.UTF_8));
  }

  Scope scope() {
    return scope;
  }

  String sourceArtifact() {
    return sourceArtifact;
  }

  List<M12DeterministicCorpus.DurableIdentity> identities() {
    return identities;
  }

  List<M12DeterministicCorpus.Attempt> attempts() {
    return attempts;
  }

  List<M12DeterministicCorpus.Binding> bindings() {
    return bindings;
  }

  List<M12DeterministicCorpus.Binding> directOracleBindings() {
    return directOracleBindings;
  }

  String expectedBusinessSemanticDigest() {
    return expectedBusinessSemanticDigest;
  }

  String expectedIdentityResultDigest() {
    return expectedIdentityResultDigest;
  }

  Topology topology() {
    return topology;
  }

  M12ExecutionTrace withAttempts(List<M12DeterministicCorpus.Attempt> replacement) {
    return new M12ExecutionTrace(
        scope,
        sourceArtifact,
        identities,
        replacement,
        bindings,
        directOracleBindings,
        expectedBusinessSemanticDigest,
        expectedIdentityResultDigest,
        topology);
  }

  M12ExecutionTrace withBindings(List<M12DeterministicCorpus.Binding> replacement) {
    return new M12ExecutionTrace(
        scope,
        sourceArtifact,
        identities,
        attempts,
        replacement,
        directOracleBindings,
        expectedBusinessSemanticDigest,
        expectedIdentityResultDigest,
        topology);
  }

  M12ExecutionTrace withTopology(Topology replacement) {
    return new M12ExecutionTrace(
        scope,
        sourceArtifact,
        identities,
        attempts,
        bindings,
        directOracleBindings,
        expectedBusinessSemanticDigest,
        expectedIdentityResultDigest,
        replacement);
  }

  M12ExecutionTrace withRuntimeTerms(long initialTerm, long replacementTerm) {
    List<MemberObservation> initial =
        topology.initialMembers().stream().map(member -> member.withTerm(initialTerm)).toList();
    List<MemberObservation> converged =
        topology.convergedMembers().stream()
            .map(member -> member.withTerm(replacementTerm))
            .toList();
    Topology changed =
        topology.withMembersAndTerms(
            initial, converged, initialTerm, replacementTerm, replacementTerm);
    return withTopology(changed);
  }

  enum Scope {
    DETERMINISTIC_MODEL_CONTROL,
    REAL_AERON_CHILD_PROCESSES
  }

  enum Role {
    LEADER,
    FOLLOWER
  }

  record MemberObservation(
      int memberId,
      long processId,
      Role role,
      String aeronDirectory,
      String archiveDirectory,
      String clusterDirectory,
      int portBlockBase,
      List<Integer> udpPorts,
      long leadershipTerm,
      long nextApplicationSequence,
      int identityCount,
      String semanticDigest,
      String identityTableDigest,
      int componentErrorCount) {
    MemberObservation {
      Objects.requireNonNull(role);
      requireText(aeronDirectory, "aeronDirectory");
      requireText(archiveDirectory, "archiveDirectory");
      requireText(clusterDirectory, "clusterDirectory");
      udpPorts = List.copyOf(Objects.requireNonNull(udpPorts, "udpPorts"));
      requireText(semanticDigest, "semanticDigest");
      requireText(identityTableDigest, "identityTableDigest");
      if (memberId < 0
          || processId <= 0
          || portBlockBase <= 0
          || udpPorts.size() != 5
          || udpPorts.stream().distinct().count() != 5
          || udpPorts.stream().anyMatch(port -> port == null || port <= 0 || port > 65_535)
          || leadershipTerm < 0
          || nextApplicationSequence <= 0
          || identityCount < 0
          || componentErrorCount < 0) {
        throw new IllegalArgumentException("invalid member observation");
      }
    }

    MemberObservation withTerm(long term) {
      return new MemberObservation(
          memberId,
          processId,
          role,
          aeronDirectory,
          archiveDirectory,
          clusterDirectory,
          portBlockBase,
          udpPorts,
          term,
          nextApplicationSequence,
          identityCount,
          semanticDigest,
          identityTableDigest,
          componentErrorCount);
    }

    MemberObservation withState(
        long nextSequence, int bindings, String semantic, String identityDigest) {
      return new MemberObservation(
          memberId,
          processId,
          role,
          aeronDirectory,
          archiveDirectory,
          clusterDirectory,
          portBlockBase,
          udpPorts,
          leadershipTerm,
          nextSequence,
          bindings,
          semantic,
          identityDigest,
          componentErrorCount);
    }
  }

  record Topology(
      List<MemberObservation> initialMembers,
      List<MemberObservation> convergedMembers,
      int initialLeaderId,
      long initialTerm,
      int killedMemberId,
      long preKillAuthorityTerm,
      boolean faultControllerExternal,
      boolean faultTargetWasObservedCurrentLeader,
      int replacementLeaderId,
      long replacementTerm,
      int clientConnectedLeaderId,
      long clientConnectedTerm,
      int staleLeaderAcknowledgements,
      boolean formerLeaderRestarted,
      int formerLeaderId,
      Role formerLeaderRoleAfterRestart,
      boolean formerLeaderCaughtUp,
      boolean majorityLost,
      int liveVotingMembersDuringMinority,
      int minorityAcknowledgements,
      boolean quorumRestored,
      boolean matchingCoreUnchanged,
      boolean m11ProtocolUnchanged,
      boolean boundedPollingUsed,
      boolean fixedSleepsUsed,
      boolean stateReadFromApplicationObserver) {
    Topology {
      initialMembers = List.copyOf(initialMembers);
      convergedMembers = List.copyOf(convergedMembers);
      Objects.requireNonNull(formerLeaderRoleAfterRestart);
    }

    Topology withMembersAndTerms(
        List<MemberObservation> initial,
        List<MemberObservation> converged,
        long newInitialTerm,
        long newReplacementTerm,
        long newClientTerm) {
      return new Topology(
          initial,
          converged,
          initialLeaderId,
          newInitialTerm,
          killedMemberId,
          newInitialTerm,
          faultControllerExternal,
          faultTargetWasObservedCurrentLeader,
          replacementLeaderId,
          newReplacementTerm,
          clientConnectedLeaderId,
          newClientTerm,
          staleLeaderAcknowledgements,
          formerLeaderRestarted,
          formerLeaderId,
          formerLeaderRoleAfterRestart,
          formerLeaderCaughtUp,
          majorityLost,
          liveVotingMembersDuringMinority,
          minorityAcknowledgements,
          quorumRestored,
          matchingCoreUnchanged,
          m11ProtocolUnchanged,
          boundedPollingUsed,
          fixedSleepsUsed,
          stateReadFromApplicationObserver);
    }

    Topology withConvergedMembers(List<MemberObservation> replacement) {
      return new Topology(
          initialMembers,
          replacement,
          initialLeaderId,
          initialTerm,
          killedMemberId,
          preKillAuthorityTerm,
          faultControllerExternal,
          faultTargetWasObservedCurrentLeader,
          replacementLeaderId,
          replacementTerm,
          clientConnectedLeaderId,
          clientConnectedTerm,
          staleLeaderAcknowledgements,
          formerLeaderRestarted,
          formerLeaderId,
          formerLeaderRoleAfterRestart,
          formerLeaderCaughtUp,
          majorityLost,
          liveVotingMembersDuringMinority,
          minorityAcknowledgements,
          quorumRestored,
          matchingCoreUnchanged,
          m11ProtocolUnchanged,
          boundedPollingUsed,
          fixedSleepsUsed,
          stateReadFromApplicationObserver);
    }

    Topology withMinorityAcknowledgements(int acknowledgements) {
      return new Topology(
          initialMembers,
          convergedMembers,
          initialLeaderId,
          initialTerm,
          killedMemberId,
          preKillAuthorityTerm,
          faultControllerExternal,
          faultTargetWasObservedCurrentLeader,
          replacementLeaderId,
          replacementTerm,
          clientConnectedLeaderId,
          clientConnectedTerm,
          staleLeaderAcknowledgements,
          formerLeaderRestarted,
          formerLeaderId,
          formerLeaderRoleAfterRestart,
          formerLeaderCaughtUp,
          majorityLost,
          liveVotingMembersDuringMinority,
          acknowledgements,
          quorumRestored,
          matchingCoreUnchanged,
          m11ProtocolUnchanged,
          boundedPollingUsed,
          fixedSleepsUsed,
          stateReadFromApplicationObserver);
    }

    Topology withStaleLeaderAcknowledgements(int acknowledgements) {
      return new Topology(
          initialMembers,
          convergedMembers,
          initialLeaderId,
          initialTerm,
          killedMemberId,
          preKillAuthorityTerm,
          faultControllerExternal,
          faultTargetWasObservedCurrentLeader,
          replacementLeaderId,
          replacementTerm,
          clientConnectedLeaderId,
          clientConnectedTerm,
          acknowledgements,
          formerLeaderRestarted,
          formerLeaderId,
          formerLeaderRoleAfterRestart,
          formerLeaderCaughtUp,
          majorityLost,
          liveVotingMembersDuringMinority,
          minorityAcknowledgements,
          quorumRestored,
          matchingCoreUnchanged,
          m11ProtocolUnchanged,
          boundedPollingUsed,
          fixedSleepsUsed,
          stateReadFromApplicationObserver);
    }
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is blank");
    }
    return value;
  }
}
