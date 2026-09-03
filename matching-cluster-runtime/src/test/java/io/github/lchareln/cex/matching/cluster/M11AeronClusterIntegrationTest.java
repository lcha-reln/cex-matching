package io.github.lchareln.cex.matching.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lchareln.cex.matching.local.M08Command;
import java.io.IOException;
import java.math.BigInteger;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class M11AeronClusterIntegrationTest {
  private static final Duration DEADLINE = Duration.ofSeconds(20);
  private static final long SHARD = 71;

  @TempDir Path temporaryDirectory;

  @Test
  @Timeout(90)
  void realSingleMemberSnapshotCompletesAndExactSnapshotIsLoadedOnRestart() throws Exception {
    M11SingleNodeConfig config =
        M11SingleNodeConfig.defaults(
            temporaryDirectory.resolve("cluster-run"), SHARD, freePortBase());
    M11RequestCodec requests = new M11RequestCodec();
    DirectM11MatchingRuntime direct = new DirectM11MatchingRuntime();

    M11CommandRequest place =
        requests.create(
            2, 2, new UUID(1, 1), "integration", 1, SHARD, 1, new UUID(71, 1), place(1));
    M11CommandRequest retry = place.withCorrelationId(new UUID(1, 2));
    M11CommandRequest afterRestartRetry = place.withCorrelationId(new UUID(1, 3));
    M11CommandRequest cancel =
        requests.create(
            1,
            1,
            new UUID(2, 1),
            "integration",
            1,
            SHARD,
            2,
            new UUID(71, 2),
            new M08Command.Cancel("BTC-USDT", BigInteger.ONE));

    M11SingleNodeHarness harness = M11SingleNodeHarness.launchFresh(config);
    try (harness) {
      M11ClusterRuntimeWitness runtime = harness.runtimeWitness(DEADLINE);
      assertEquals(config.clusterId(), runtime.clusterId());
      assertEquals(1, runtime.memberCount());
      assertEquals(0, runtime.memberId());
      assertEquals(0, runtime.appointedLeaderId());
      assertEquals(config.clusterMembers(), runtime.clusterMembers());
      assertEquals("LEADER", runtime.serviceRole());
      assertEquals("1.52.2", runtime.aeronImplementationVersion());
      assertEquals("2.5.0", runtime.agronaImplementationVersion());
      assertEquals(config.rootDirectory().toString(), runtime.rootDirectory());
      assertEquals(config.portBase(), runtime.udpPortBlockBase());
      assertEquivalent(direct.submit(place), harness.submit(place, DEADLINE));
      assertEquivalent(direct.submit(retry), harness.submit(retry, DEADLINE));
      String digestAtSnapshot = direct.semanticStateDigest();

      M11SnapshotWitness snapshot = harness.takeSnapshot(DEADLINE);
      assertEquals(0, snapshot.completion().completionCountBefore());
      assertEquals(1, snapshot.completion().completionCountAfter());
      assertEquals(1, snapshot.applicationSnapshot().snapshotSequence());
      assertEquals(digestAtSnapshot, snapshot.applicationSnapshot().semanticStateDigest());
      assertTrue(snapshot.completion().serviceRecordingId() >= 0);
      assertTrue(snapshot.completion().consensusRecordingId() >= 0);
      assertTrue(snapshot.completion().recordingIdsChanged());
      assertTrue(snapshot.completion().sameTermAndLogPosition());

      harness.restartFromSnapshot();
      harness.restartFromSnapshot();
      M11HarnessReport restarted = harness.report();
      assertTrue(restarted.restartDirectoriesPreserved());
      assertTrue(restarted.completedSnapshotLoaded());
      assertEquals(snapshot.applicationSnapshot(), restarted.lastLoadedSnapshot().orElseThrow());
      assertEquals(digestAtSnapshot, harness.semanticStateDigest());

      assertEquivalent(
          direct.submit(afterRestartRetry), harness.submit(afterRestartRetry, DEADLINE));
      assertEquivalent(direct.submit(cancel), harness.submit(cancel, DEADLINE));
      assertEquals(direct.semanticStateDigest(), harness.semanticStateDigest());
      assertEquals(direct.stateImage(), harness.stateImage());

      M11HarnessReport report = harness.report();
      assertEquals(4, report.ingressOffersAccepted());
      assertEquals(4, report.correlatedEgressResponses());
      assertEquals(2, report.newBusinessApplications());
      assertEquals(2, report.duplicateReplays());
      assertEquals(0, report.rejectedApplications());
      assertEquals(1, report.snapshotAdminAccepted());
      assertEquals(1, report.snapshotsCompleted());
      assertEquals(2, report.restarts());
      assertEquals(0, report.componentErrorCount());
      assertTrue(harness.componentErrors().isEmpty());
      assertTrue(Files.isRegularFile(config.clusterDirectory().resolve("recording.log")));
      try (var archiveFiles = Files.list(config.archiveDirectory())) {
        assertTrue(archiveFiles.anyMatch(path -> path.getFileName().toString().endsWith(".rec")));
      }
      assertFalse(harness.observations().isEmpty());
    }
    assertEquals(0, harness.report().componentErrorCount());
    assertTrue(harness.componentErrors().isEmpty());
  }

  @Test
  @Timeout(90)
  void observerFailuresStayNonInfluencingAndAreRetainedAcrossRestart() throws Exception {
    M11SingleNodeConfig config =
        M11SingleNodeConfig.defaults(
            temporaryDirectory.resolve("observer-run"), SHARD + 1, freePortBase());
    M11RequestCodec requests = new M11RequestCodec();
    M11CommandRequest place =
        requests.create(
            2, 2, new UUID(3, 1), "observer", 1, SHARD + 1, 1, new UUID(72, 1), place(72));
    AtomicInteger observerCalls = new AtomicInteger();

    try (M11SingleNodeHarness harness =
        M11SingleNodeHarness.launchFresh(
            config,
            observation -> {
              observerCalls.incrementAndGet();
              throw new IllegalStateException("intentional observer failure");
            })) {
      assertEquals(M11ResponseStatus.NEW_APPLIED, harness.submit(place, DEADLINE).status());
      assertEquals(1, observerCalls.get());
      assertEquals(1, harness.componentErrors().size());

      harness.takeSnapshot(DEADLINE);
      harness.restartFromSnapshot();
      assertEquals(1, harness.componentErrors().size());

      M11CommandRequest duplicate = place.withCorrelationId(new UUID(3, 2));
      assertEquals(
          M11ResponseStatus.DUPLICATE_REPLAYED, harness.submit(duplicate, DEADLINE).status());
      assertEquals(2, observerCalls.get());
      assertEquals(2, harness.report().componentErrorCount());
    }
  }

  private static void assertEquivalent(M11ApplicationResult direct, M11CommandResponse clustered) {
    assertEquals(direct.response().status(), clustered.status());
    assertEquals(direct.response().applicationSequence(), clustered.applicationSequence());
    assertEquals(direct.response().resultDigest(), clustered.resultDigest());
    assertEquals(direct.response().semanticStateDigest(), clustered.semanticStateDigest());
  }

  private static M08Command.Place place(long orderId) {
    return new M08Command.Place(
        "BTC-USDT",
        BigInteger.valueOf(orderId),
        "BUY",
        BigInteger.valueOf(100),
        BigInteger.valueOf(3),
        "GTC",
        0,
        "NONE",
        Optional.empty());
  }

  private static int freePortBase() throws IOException {
    int first = 24_000 + Math.floorMod((int) System.nanoTime(), 20_000);
    for (int attempt = 0; attempt < 500; attempt++) {
      int candidate = 1_024 + Math.floorMod(first + attempt * 100, 64_000);
      if (candidate > 65_530) {
        continue;
      }
      List<DatagramSocket> leases = new ArrayList<>();
      try {
        for (int offset = 1; offset <= 5; offset++) {
          DatagramSocket socket = new DatagramSocket(null);
          socket.setReuseAddress(false);
          socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), candidate + offset));
          leases.add(socket);
        }
        return candidate;
      } catch (IOException unavailable) {
        // Try another 100-port member block.
      } finally {
        leases.forEach(DatagramSocket::close);
      }
    }
    throw new IOException("no free Aeron Cluster port block found");
  }
}
