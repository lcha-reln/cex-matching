package io.github.lchareln.cex.matching.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class M12ThreeMemberConfigTest {
  @TempDir Path temporaryDirectory;

  @Test
  void freezesThreeVotingMembersIntoOwnedDirectoriesAndDisjointPorts() {
    M12ThreeMemberConfig config =
        M12ThreeMemberConfig.defaults(temporaryDirectory.resolve("cluster"), 71, 22_000);

    assertEquals(3, M12ThreeMemberConfig.MEMBER_COUNT);
    assertEquals(2, M12ThreeMemberConfig.QUORUM_SIZE);
    assertEquals(-1, M12ThreeMemberConfig.APPOINTED_LEADER_ID);
    assertEquals(0, M12ThreeMemberConfig.FROZEN_APPOINTED_INITIAL_LEADER_ID);
    assertEquals(15, config.allFixedUdpPorts().size());
    assertEquals(
        config.allFixedUdpPorts(),
        new HashSet<>(
            List.of(
                22_001, 22_002, 22_003, 22_004, 22_005, 22_011, 22_012, 22_013, 22_014, 22_015,
                22_021, 22_022, 22_023, 22_024, 22_025)));
    assertEquals(
        "0=127.0.0.1:22002,1=127.0.0.1:22012,2=127.0.0.1:22022", config.ingressEndpoints());
    assertEquals(
        "0,127.0.0.1:22002,127.0.0.1:22003,127.0.0.1:22004,127.0.0.1:22005,127.0.0.1:22001|"
            + "1,127.0.0.1:22012,127.0.0.1:22013,127.0.0.1:22014,127.0.0.1:22015,127.0.0.1:22011|"
            + "2,127.0.0.1:22022,127.0.0.1:22023,127.0.0.1:22024,127.0.0.1:22025,127.0.0.1:22021|",
        config.clusterMembers());

    for (int memberId = 0; memberId < M12ThreeMemberConfig.MEMBER_COUNT; memberId++) {
      Path memberRoot = config.memberRootDirectory(memberId);
      assertTrue(config.memberAeronDirectory(memberId).startsWith(memberRoot));
      assertTrue(config.memberArchiveDirectory(memberId).startsWith(memberRoot));
      assertTrue(config.memberClusterDirectory(memberId).startsWith(memberRoot));
      assertTrue(config.memberStatusFile(memberId).startsWith(memberRoot));
      for (int other = memberId + 1; other < M12ThreeMemberConfig.MEMBER_COUNT; other++) {
        assertNotEquals(memberRoot, config.memberRootDirectory(other));
      }
    }
  }

  @Test
  void exactProcessArgumentsRoundTripAndRejectUnknownOrInvalidValues() {
    M12ThreeMemberConfig config =
        M12ThreeMemberConfig.defaults(temporaryDirectory.resolve("cluster"), 72, 23_000);
    M12ClusterMemberMain.LaunchOptions parsed =
        M12ClusterMemberMain.parseArguments(
            config.memberProcessArguments(2, false).toArray(String[]::new));

    assertEquals(config, parsed.config());
    assertEquals(2, parsed.memberId());
    assertEquals(false, parsed.freshStart());
    assertThrows(
        IllegalArgumentException.class,
        () -> M12ClusterMemberMain.parseArguments(new String[] {"--unknown", "value"}));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            M12ClusterMemberMain.parseArguments(
                replace(config.memberProcessArguments(0, true), "--fresh-start", "yes")));
    assertThrows(
        IllegalArgumentException.class,
        () -> M12ThreeMemberConfig.defaults(temporaryDirectory, 72, 65_520));
  }

  private static String[] replace(List<String> arguments, String name, String replacement) {
    String[] copy = arguments.toArray(String[]::new);
    for (int index = 0; index < copy.length; index += 2) {
      if (name.equals(copy[index])) {
        copy[index + 1] = replacement;
      }
    }
    return copy;
  }
}
