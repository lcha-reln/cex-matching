package io.github.lchareln.cex.matching.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lchareln.cex.matching.local.M08Command;
import java.io.IOException;
import java.math.BigInteger;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class M12MatchingClusterClientIntegrationTest {
  private static final Duration DEADLINE = Duration.ofSeconds(20);
  private static final long SHARD = 97;

  @TempDir Path temporaryDirectory;

  @Test
  @Timeout(60)
  void realAeronClientKeepsBufferedAbandonmentUnknownAndRetryReplaysOneEffect() throws Exception {
    M11SingleNodeConfig config =
        M11SingleNodeConfig.defaults(
            temporaryDirectory.resolve("m12-client"), SHARD, freePortBase());
    M11CommandRequest request =
        new M11RequestCodec()
            .create(
                2,
                2,
                new UUID(12, 1),
                "m12-client",
                1,
                SHARD,
                1,
                new UUID(97, 1),
                new M08Command.Cancel("BTC-USDT", BigInteger.ONE));

    try (M11SingleNodeCluster node = M11SingleNodeCluster.launch(config, true)) {
      node.runtimeWitness(DEADLINE);
      try (M12MatchingClusterClient client =
          M12MatchingClusterClient.connect(
              config.clientAeronDirectory(),
              config.ingressEndpoints(),
              config.clientMessageTimeout(),
              1,
              () -> node.componentErrors().stream().findFirst().orElse(null))) {
        M12TransportAuthority initialAuthority = client.currentAuthority();
        assertEquals(1, initialAuthority.clientGeneration());
        assertEquals(0, initialAuthority.leaderMemberId());

        M12InvocationAttempt first = client.offer(request, 1, DEADLINE);
        assertTrue(first.offerAccepted());
        assertTrue(client.awaitResponseBuffered(first, DEADLINE));
        M12InvocationOutcome unknown = client.abandon(first);
        assertEquals(M12InvocationState.UNKNOWN, unknown.state());
        assertEquals(M12UnknownReason.ABANDONED, unknown.unresolvedReason().orElseThrow());

        M12InvocationAttempt retry = first.retry(new UUID(12, 2), 2, 1);
        client.offer(retry, DEADLINE);
        assertTrue(client.awaitResponseBuffered(retry, DEADLINE));
        M12InvocationOutcome acknowledged = client.acknowledge(retry);

        assertEquals(M12InvocationState.ACKNOWLEDGED, acknowledged.state());
        assertEquals(
            M11ResponseStatus.DUPLICATE_REPLAYED, acknowledged.response().orElseThrow().status());
        assertEquals(1, acknowledged.response().orElseThrow().applicationSequence().orElseThrow());
        assertTrue(first.sameDurableIdentity(retry));
        assertEquals(2, node.service().nextApplicationSequence());
        assertEquals(2, client.ingressOffersAccepted());
        assertEquals(2, client.egressResponsesDecoded());
        assertEquals(0, client.rejectedEgressResponses());
      }
      assertTrue(node.componentErrors().isEmpty());
    }
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
        // Try another disjoint port block.
      } finally {
        leases.forEach(DatagramSocket::close);
      }
    }
    throw new IOException("no free Aeron Cluster port block found");
  }
}
