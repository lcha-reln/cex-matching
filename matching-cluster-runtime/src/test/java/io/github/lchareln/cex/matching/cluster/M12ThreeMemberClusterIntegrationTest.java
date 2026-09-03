package io.github.lchareln.cex.matching.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class M12ThreeMemberClusterIntegrationTest {
  private static final Duration DEADLINE = Duration.ofSeconds(30);

  @TempDir Path temporaryDirectory;

  @Test
  @Timeout(90)
  void realThreeMemberTopologyElectsOneLeaderAndPublishesEquivalentStatus() throws Exception {
    M12ThreeMemberConfig config =
        M12ThreeMemberConfig.defaults(
            temporaryDirectory.resolve("three-member"), 812, freePortBase());

    try (M12ClusterMember member0 = M12ClusterMember.launch(config, 0, true);
        M12ClusterMember member1 = M12ClusterMember.launch(config, 1, true);
        M12ClusterMember member2 = M12ClusterMember.launch(config, 2, true)) {
      List<M12MemberStatus> statuses = awaitTopology(member0, member1, member2);
      M12MemberStatus leader =
          statuses.stream()
              .filter(status -> "LEADER".equals(status.role()))
              .findFirst()
              .orElseThrow();
      List<M12MemberStatus> followers =
          statuses.stream().filter(status -> "FOLLOWER".equals(status.role())).toList();

      assertTrue(leader.memberId() >= 0 && leader.memberId() <= 2);
      assertEquals(2, followers.size());
      assertTrue(
          followers.stream()
              .allMatch(follower -> follower.leadershipTermId() == leader.leadershipTermId()));
      assertTrue(
          followers.stream()
              .allMatch(
                  follower ->
                      follower.nextApplicationSequence() == leader.nextApplicationSequence()));
      assertTrue(
          followers.stream()
              .allMatch(
                  follower -> follower.semanticStateDigest().equals(leader.semanticStateDigest())));
      assertTrue(
          followers.stream()
              .allMatch(
                  follower ->
                      follower.identityResultDigest().equals(leader.identityResultDigest())));
      assertTrue(member0.componentErrors().isEmpty());
      assertTrue(member1.componentErrors().isEmpty());
      assertTrue(member2.componentErrors().isEmpty());

      M12MemberStatus published = member0.publishStatus();
      assertEquals(published, M12MemberStatusFile.read(config.memberStatusFile(0)));
      for (int memberId = 0; memberId < M12ThreeMemberConfig.MEMBER_COUNT; memberId++) {
        assertTrue(Files.isDirectory(config.memberAeronDirectory(memberId)));
        assertTrue(Files.isDirectory(config.memberArchiveDirectory(memberId)));
        assertTrue(Files.isDirectory(config.memberClusterDirectory(memberId)));
      }
    }
  }

  private static List<M12MemberStatus> awaitTopology(M12ClusterMember... members) {
    long deadline = Math.addExact(System.nanoTime(), DEADLINE.toNanos());
    while (true) {
      for (M12ClusterMember member : members) {
        member.throwIfFailed();
      }
      List<M12MemberStatus> statuses =
          java.util.Arrays.stream(members).map(M12ClusterMember::status).toList();
      if (statuses.stream().filter(status -> "LEADER".equals(status.role())).count() == 1
          && statuses.stream().filter(status -> "FOLLOWER".equals(status.role())).count() == 2
          && statuses.stream().map(M12MemberStatus::leadershipTermId).distinct().count() == 1) {
        return statuses;
      }
      if (System.nanoTime() >= deadline) {
        throw new IllegalStateException("automatic three-member election did not converge");
      }
      LockSupport.parkNanos(Duration.ofMillis(1).toNanos());
    }
  }

  private static int freePortBase() throws IOException {
    int first = 24_000 + Math.floorMod((int) System.nanoTime(), 20_000);
    for (int attempt = 0; attempt < 400; attempt++) {
      int candidate = first + attempt * 40;
      if (candidate > 65_500) {
        candidate = 20_000 + attempt * 40;
      }
      M12ThreeMemberConfig probe =
          M12ThreeMemberConfig.defaults(Path.of("port-probe"), 1, candidate);
      List<DatagramSocket> leases = new ArrayList<>();
      try {
        for (int port : probe.allFixedUdpPorts()) {
          DatagramSocket socket = new DatagramSocket(null);
          socket.setReuseAddress(false);
          socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
          leases.add(socket);
        }
        return candidate;
      } catch (IOException unavailable) {
        // Try another complete three-member block.
      } finally {
        leases.forEach(DatagramSocket::close);
      }
    }
    throw new IOException("no free M12 three-member Aeron port block found");
  }
}
