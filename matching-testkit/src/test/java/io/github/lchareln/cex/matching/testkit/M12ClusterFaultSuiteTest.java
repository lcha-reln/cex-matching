package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class M12ClusterFaultSuiteTest {
  @Test
  void archiveMarkFileLivenessUsesTheStrictAeronBoundary() {
    assertFalse(M12ThreeMemberProcessHarness.isArchiveMarkFileInactive(20_000, 10_000, 10_000));
    assertTrue(M12ThreeMemberProcessHarness.isArchiveMarkFileInactive(20_001, 10_000, 10_000));
    assertFalse(M12ThreeMemberProcessHarness.isArchiveMarkFileInactive(9_999, 10_000, 10_000));
    assertFalse(M12ThreeMemberProcessHarness.isArchiveMarkFileInactive(20_001, 0, 10_000));
  }

  @Test
  @Timeout(300)
  void executesFrozenHistoryAcrossRealChildProcessesAndCleansThemUp() {
    long startedAtNanos = System.nanoTime();
    Path root = Path.of(System.getProperty("matching.repositoryRoot")).toAbsolutePath().normalize();
    M12WorkloadLoader.Workload workload = M12WorkloadLoader.load(root);
    M12DeterministicCorpus.Corpus corpus = M12DeterministicCorpus.generate(workload);

    M12ClusterFaultSuite.Result result = new M12ClusterFaultSuite().run(root, workload, corpus);
    M12ExecutionTrace trace = result.trace();
    M12HistoryJudge.Inspection inspection = new M12HistoryJudge().inspect(workload, trace);

    assertTrue(inspection.qualifiesAsRealClusterEvidence());
    assertEquals(M12ExecutionTrace.Scope.REAL_AERON_CHILD_PROCESSES, trace.scope());
    assertEquals("m12-command-history.json", trace.sourceArtifact());
    assertEquals(85, trace.attempts().size());
    assertEquals(
        84,
        trace.attempts().stream().filter(M12DeterministicCorpus.Attempt::ingressAccepted).count());
    assertEquals(66, trace.bindings().size());
    assertEquals(67, trace.topology().convergedMembers().getFirst().nextApplicationSequence());
    assertEquals(6, result.topologyReport().path("memberProcessStarts").intValue());
    assertEquals(3, result.topologyReport().path("externalForceStops").intValue());
    assertEquals(
        10_000, result.topologyReport().path("archiveMarkFileLivenessTimeoutMillis").intValue());
    assertEquals(3, result.topologyReport().path("restartSafetyWitnessCount").intValue());
    result
        .topologyReport()
        .path("restartSafetyWitnesses")
        .forEach(
            witness -> {
              assertEquals("1.52.2", witness.path("aeronVersion").stringValue());
              assertEquals(10_000, witness.path("livenessTimeoutMillis").intValue());
              assertTrue(
                  witness.path("ageMillis").longValue()
                      > witness.path("livenessTimeoutMillis").longValue());
              assertTrue(witness.path("activityTimestampPositive").booleanValue());
              assertTrue(witness.path("ageStrictlyExceedsLivenessTimeout").booleanValue());
            });
    assertEquals(0, result.quorumReport().path("minorityAcknowledgements").intValue());
    assertEquals("UNKNOWN", result.quorumReport().path("minorityOutcome").stringValue());
    assertEquals(0, result.stateEquivalenceReport().path("componentErrorCount").intValue());
    assertTrue(result.stateEquivalenceReport().path("diagnosticWarningCount").intValue() >= 0);
    assertTrue(result.stateEquivalenceReport().path("droppedDiagnosticWarnings").longValue() >= 0);
    result
        .stateEquivalenceReport()
        .path("members")
        .forEach(member -> assertTrue(member.path("diagnosticWarningCount").intValue() <= 128));
    assertTrue(result.topologyReport().path("teardownComplete").booleanValue());
    assertEquals(0, result.topologyReport().path("childProcessesAliveAfterTeardown").intValue());
    assertFalse(result.topologyReport().path("wallClockUsedForStatusAcceptance").booleanValue());
    assertTrue(result.topologyReport().path("stableSnapshotWitnessCount").intValue() >= 1);
    result
        .topologyReport()
        .path("stableSnapshotWitnesses")
        .forEach(
            witness -> {
              assertTrue(witness.path("allMemberStatusSequencesAdvanced").booleanValue());
              assertTrue(witness.path("monotonicElapsedWithinFreshnessBound").booleanValue());
              assertTrue(witness.path("firstSnapshot").size() >= 1);
              assertEquals(
                  witness.path("firstSnapshot").size(), witness.path("secondSnapshot").size());
              for (int index = 0; index < witness.path("firstSnapshot").size(); index++) {
                var first = witness.path("firstSnapshot").get(index);
                var second = witness.path("secondSnapshot").get(index);
                assertEquals(first.path("memberId").intValue(), second.path("memberId").intValue());
                assertEquals(
                    first.path("processId").longValue(), second.path("processId").longValue());
                assertTrue(
                    second.path("statusSequence").longValue()
                        > first.path("statusSequence").longValue());
              }
            });
    for (var report : List.of(result.historyReport(), result.topologyReport())) {
      assertTrue(report.path("appliedUnknownObservedOnAllMembersBeforeLeaderKill").booleanValue());
      var observations = report.path("appliedUnknownMembersBeforeLeaderKill");
      assertEquals(3, observations.size());
      Set<Integer> observedMemberIds = new HashSet<>();
      String semanticDigest = observations.get(0).path("semanticStateDigest").stringValue();
      String identityDigest = observations.get(0).path("identityResultDigest").stringValue();
      long commitPosition = observations.get(0).path("commitPosition").longValue();
      long logPosition = observations.get(0).path("logPosition").longValue();
      observations.forEach(
          observation -> {
            observedMemberIds.add(observation.path("memberId").intValue());
            assertEquals(34, observation.path("nextApplicationSequence").longValue());
            assertEquals(33, observation.path("identityCount").intValue());
            assertEquals(0, observation.path("componentErrorCount").intValue());
            assertEquals(semanticDigest, observation.path("semanticStateDigest").stringValue());
            assertEquals(identityDigest, observation.path("identityResultDigest").stringValue());
            assertEquals(commitPosition, observation.path("commitPosition").longValue());
            assertEquals(logPosition, observation.path("logPosition").longValue());
          });
      assertEquals(Set.of(0, 1, 2), observedMemberIds);
    }

    Set<Long> childPids = new HashSet<>();
    result
        .topologyReport()
        .path("processStarts")
        .forEach(
            node -> {
              childPids.add(node.path("processId").longValue());
              assertEquals(5, node.path("udpPorts").size());
              assertFalse(node.path("aliveAfterTeardown").booleanValue());
            });
    assertEquals(6, childPids.size());
    childPids.forEach(
        pid -> assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)));

    byte[] canonical = result.canonicalCommandBytes();
    assertTrue(canonical.length > 0);
    ByteBuffer framed = ByteBuffer.wrap(canonical);
    int frames = 0;
    while (framed.hasRemaining()) {
      int length = framed.getInt();
      assertTrue(length > 0 && length <= framed.remaining());
      framed.position(framed.position() + length);
      frames++;
    }
    assertEquals(66, frames);
    assertEquals(canonical.length, result.canonicalCommandBytes().length);
    canonical[0] ^= 0x01;
    assertNotEquals(canonical[0], result.canonicalCommandBytes()[0]);

    var history = result.historyReport();
    history.put("status", "MUTATED");
    assertEquals("PASS", result.historyReport().path("status").stringValue());
    for (var report :
        Set.of(
            result.historyReport(),
            result.topologyReport(),
            result.leadershipReport(),
            result.quorumReport(),
            result.catchupReport(),
            result.stateEquivalenceReport())) {
      assertTrue(report.path("schemaVersion").isString());
      assertEquals("PASS", report.path("status").stringValue());
    }
    System.out.println(
        "M12 real fault timeline: elapsedMillis="
            + java.time.Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis()
            + ",initialLeader="
            + trace.topology().initialLeaderId()
            + ",initialTerm="
            + trace.topology().initialTerm()
            + ",replacementLeader="
            + trace.topology().replacementLeaderId()
            + ",replacementTerm="
            + trace.topology().replacementTerm()
            + ",noQuorumRetry="
            + trace.attempts().get(84).responseStatus()
            + ",pids="
            + childPids
            + ",finalMembers="
            + trace.topology().convergedMembers().stream()
                .map(
                    member ->
                        member.memberId()
                            + "@"
                            + member.processId()
                            + ":"
                            + member.role()
                            + ":term="
                            + member.leadershipTerm()
                            + ":next="
                            + member.nextApplicationSequence())
                .toList()
            + ",restartSafetyWitnessCount="
            + result.topologyReport().path("restartSafetyWitnessCount").intValue());
  }
}
