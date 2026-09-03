package io.github.lchareln.cex.matching.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeron.Aeron;
import io.aeron.archive.Archive;
import io.aeron.cluster.ClusteredMediaDriver;
import io.aeron.driver.MediaDriver;
import io.github.lchareln.cex.matching.SingleInstrumentMatchingEngine;
import io.github.lchareln.cex.matching.local.M08Command;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.agrona.DirectBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class M12ChildProcessClusterIntegrationTest {
  private static final Duration DEADLINE = Duration.ofSeconds(45);
  private static final long POLL_NANOS = Duration.ofMillis(1).toNanos();

  @TempDir Path temporaryDirectory;

  @Test
  @Timeout(90)
  void threeChildJvmsHaveDistinctPidsAndConvergeOnOneInitialLeader() throws Exception {
    M12ThreeMemberConfig config =
        M12ThreeMemberConfig.defaults(
            temporaryDirectory.resolve("child-process-cluster"), 912, freePortBase());
    List<Process> processes = new ArrayList<>();
    try {
      for (int memberId = 0; memberId < M12ThreeMemberConfig.MEMBER_COUNT; memberId++) {
        processes.add(start(config, memberId));
      }

      List<M12MemberStatus> statuses = awaitTopology(config, processes, DEADLINE);
      Set<Long> processIds =
          statuses.stream()
              .map(M12MemberStatus::processId)
              .collect(java.util.stream.Collectors.toSet());
      assertEquals(3, processIds.size());
      assertTrue(processIds.stream().noneMatch(id -> id == ProcessHandle.current().pid()));
      assertEquals(1, statuses.stream().filter(status -> "LEADER".equals(status.role())).count());
      assertTrue(
          statuses.stream()
              .filter(status -> "LEADER".equals(status.role()))
              .allMatch(status -> status.memberId() >= 0 && status.memberId() <= 2));
      assertEquals(2, statuses.stream().filter(status -> "FOLLOWER".equals(status.role())).count());
      assertEquals(1, statuses.stream().map(M12MemberStatus::leadershipTermId).distinct().count());
      assertTrue(statuses.stream().allMatch(M12MemberStatus::healthy));

      System.out.println(
          "M12 child topology: "
              + statuses.stream()
                  .map(
                      status ->
                          "member="
                              + status.memberId()
                              + ",pid="
                              + status.processId()
                              + ",role="
                              + status.role()
                              + ",term="
                              + status.leadershipTermId()
                              + ",commit="
                              + status.commitPosition()
                              + ",log="
                              + status.logPosition()
                              + ",errors="
                              + status.componentErrors().size())
                  .toList());
    } finally {
      stop(processes);
    }
  }

  private Process start(M12ThreeMemberConfig config, int memberId) throws IOException {
    Path diagnostics = config.memberStatusFile(memberId).getParent();
    Files.createDirectories(diagnostics);
    List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.add("--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED");
    command.add("-cp");
    command.add(childClasspath());
    command.add(M12ClusterMemberMain.class.getName());
    command.addAll(config.memberProcessArguments(memberId, true));
    return new ProcessBuilder(command)
        .redirectOutput(diagnostics.resolve("stdout.log").toFile())
        .redirectError(diagnostics.resolve("stderr.log").toFile())
        .start();
  }

  private static String childClasspath() {
    Set<String> entries = new LinkedHashSet<>();
    for (Class<?> type :
        List.of(
            M12ClusterMemberMain.class,
            M08Command.class,
            SingleInstrumentMatchingEngine.class,
            ClusteredMediaDriver.class,
            Archive.class,
            MediaDriver.class,
            Aeron.class,
            DirectBuffer.class)) {
      try {
        entries.add(
            Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
      } catch (URISyntaxException failure) {
        throw new IllegalStateException(
            "cannot resolve child classpath for " + type.getName(), failure);
      }
    }
    return String.join(System.getProperty("path.separator"), entries);
  }

  private static List<M12MemberStatus> awaitTopology(
      M12ThreeMemberConfig config, List<Process> processes, Duration timeout) {
    long deadline = Math.addExact(System.nanoTime(), timeout.toNanos());
    while (true) {
      List<M12MemberStatus> statuses = new ArrayList<>();
      for (int memberId = 0; memberId < M12ThreeMemberConfig.MEMBER_COUNT; memberId++) {
        Process process = processes.get(memberId);
        if (!process.isAlive()) {
          throw new IllegalStateException(
              "M12 child " + memberId + " exited with " + process.exitValue());
        }
        Path statusFile = config.memberStatusFile(memberId);
        if (Files.isRegularFile(statusFile)) {
          try {
            M12MemberStatus status = M12MemberStatusFile.read(statusFile);
            if (status.processId() == process.pid()) {
              statuses.add(status);
            }
          } catch (IOException transientRead) {
            // The next bounded poll can observe the last atomic replacement.
          }
        }
      }
      if (statuses.size() == M12ThreeMemberConfig.MEMBER_COUNT
          && statuses.stream().allMatch(M12MemberStatus::healthy)
          && statuses.stream().filter(status -> "LEADER".equals(status.role())).count() == 1
          && statuses.stream().filter(status -> "FOLLOWER".equals(status.role())).count() == 2
          && statuses.stream().map(M12MemberStatus::leadershipTermId).distinct().count() == 1) {
        return List.copyOf(statuses);
      }
      if (System.nanoTime() >= deadline) {
        throw new IllegalStateException("M12 child topology did not converge before the deadline");
      }
      LockSupport.parkNanos(POLL_NANOS);
    }
  }

  private static void stop(List<Process> processes) throws InterruptedException {
    for (Process process : processes) {
      process.destroy();
    }
    for (Process process : processes) {
      if (!process.waitFor(5, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        assertTrue(process.waitFor(5, TimeUnit.SECONDS));
      }
      assertFalse(process.isAlive());
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
    throw new IOException("no free M12 child-process port block found");
  }
}
