package io.github.lchareln.cex.matching.cluster;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Static localhost topology for the three voting members exercised by M12. */
public record M12ThreeMemberConfig(
    Path rootDirectory,
    int clusterId,
    long shardId,
    int portBase,
    int appVersion,
    Duration clientMessageTimeout,
    Duration leaderHeartbeatInterval,
    Duration leaderHeartbeatTimeout,
    Duration electionTimeout,
    Duration startupCanvassTimeout,
    Duration statusPublishInterval) {
  public static final int MEMBER_COUNT = 3;
  public static final int QUORUM_SIZE = 2;

  /** Aeron NULL_VALUE: HA members must use automatic election after bootstrap. */
  public static final int APPOINTED_LEADER_ID = -1;

  /** Historical value recorded by the immutable M12 start workload; not a runtime appointment. */
  public static final int FROZEN_APPOINTED_INITIAL_LEADER_ID = 0;

  public static final int PORTS_PER_MEMBER = 10;
  public static final int ARCHIVE_PORT_OFFSET = 1;
  public static final int INGRESS_PORT_OFFSET = 2;
  public static final int CONSENSUS_PORT_OFFSET = 3;
  public static final int LOG_PORT_OFFSET = 4;
  public static final int CATCHUP_PORT_OFFSET = 5;

  private static final String LOOPBACK = "127.0.0.1";
  private static final int MAX_USED_PORT_OFFSET =
      (MEMBER_COUNT - 1) * PORTS_PER_MEMBER + CATCHUP_PORT_OFFSET;

  public M12ThreeMemberConfig {
    rootDirectory =
        Objects.requireNonNull(rootDirectory, "rootDirectory").toAbsolutePath().normalize();
    if (clusterId < 0) {
      throw new IllegalArgumentException("clusterId must not be negative");
    }
    if (shardId <= 0) {
      throw new IllegalArgumentException("shardId must be positive");
    }
    if (portBase < 1_024 || portBase > 65_535 - MAX_USED_PORT_OFFSET) {
      throw new IllegalArgumentException("portBase must leave three valid ten-port blocks");
    }
    if (appVersion <= 0) {
      throw new IllegalArgumentException("appVersion must be positive");
    }
    clientMessageTimeout = positive(clientMessageTimeout, "clientMessageTimeout");
    leaderHeartbeatInterval = positive(leaderHeartbeatInterval, "leaderHeartbeatInterval");
    leaderHeartbeatTimeout = positive(leaderHeartbeatTimeout, "leaderHeartbeatTimeout");
    electionTimeout = positive(electionTimeout, "electionTimeout");
    startupCanvassTimeout = positive(startupCanvassTimeout, "startupCanvassTimeout");
    statusPublishInterval = positive(statusPublishInterval, "statusPublishInterval");
    if (leaderHeartbeatInterval.compareTo(leaderHeartbeatTimeout) >= 0) {
      throw new IllegalArgumentException(
          "leaderHeartbeatInterval must be shorter than leaderHeartbeatTimeout");
    }
    if (startupCanvassTimeout.toNanos() % leaderHeartbeatTimeout.toNanos() != 0) {
      throw new IllegalArgumentException(
          "startupCanvassTimeout must be a multiple of leaderHeartbeatTimeout");
    }
  }

  public static M12ThreeMemberConfig defaults(Path rootDirectory, long shardId, int portBase) {
    return new M12ThreeMemberConfig(
        rootDirectory,
        12,
        shardId,
        portBase,
        0x020000,
        Duration.ofSeconds(20),
        Duration.ofMillis(500),
        Duration.ofSeconds(3),
        Duration.ofSeconds(2),
        Duration.ofSeconds(6),
        Duration.ofMillis(100));
  }

  public int memberPortBase(int memberId) {
    requireMemberId(memberId);
    return portBase + memberId * PORTS_PER_MEMBER;
  }

  public int port(int memberId, int offset) {
    requireMemberId(memberId);
    if (offset < ARCHIVE_PORT_OFFSET || offset > CATCHUP_PORT_OFFSET) {
      throw new IllegalArgumentException("port offset must be in the fixed member block");
    }
    return memberPortBase(memberId) + offset;
  }

  public List<Integer> fixedUdpPorts(int memberId) {
    requireMemberId(memberId);
    List<Integer> ports = new ArrayList<>(5);
    for (int offset = ARCHIVE_PORT_OFFSET; offset <= CATCHUP_PORT_OFFSET; offset++) {
      ports.add(port(memberId, offset));
    }
    return List.copyOf(ports);
  }

  public Set<Integer> allFixedUdpPorts() {
    Set<Integer> ports = new HashSet<>();
    for (int memberId = 0; memberId < MEMBER_COUNT; memberId++) {
      ports.addAll(fixedUdpPorts(memberId));
    }
    return Set.copyOf(ports);
  }

  public Path memberRootDirectory(int memberId) {
    requireMemberId(memberId);
    return rootDirectory.resolve("node-" + memberId);
  }

  public Path memberAeronDirectory(int memberId) {
    return memberRootDirectory(memberId).resolve("aeron");
  }

  public Path memberArchiveDirectory(int memberId) {
    return memberRootDirectory(memberId).resolve("archive");
  }

  public Path memberClusterDirectory(int memberId) {
    return memberRootDirectory(memberId).resolve("cluster");
  }

  public Path memberStatusFile(int memberId) {
    return memberRootDirectory(memberId).resolve("diagnostics/member-status.json");
  }

  public String archiveControlChannel(int memberId) {
    return udpChannel(port(memberId, ARCHIVE_PORT_OFFSET));
  }

  public String replicationChannel() {
    // Archive replication needs a receive endpoint chosen by the local OS. All member-facing
    // listening endpoints remain the fixed, non-overlapping five-port blocks above.
    return "aeron:udp?endpoint=" + LOOPBACK + ":0";
  }

  public String ingressChannel() {
    return "aeron:udp?term-length=64k";
  }

  public String consensusChannel() {
    return "aeron:udp?term-length=64k";
  }

  public String logChannel() {
    return "aeron:udp?term-length=64k";
  }

  public String followerCatchupChannel() {
    return "aeron:udp?term-length=64k";
  }

  public String leaderArchiveControlChannel() {
    return "aeron:udp?term-length=64k";
  }

  public String ingressEndpoints() {
    StringBuilder endpoints = new StringBuilder();
    for (int memberId = 0; memberId < MEMBER_COUNT; memberId++) {
      if (!endpoints.isEmpty()) {
        endpoints.append(',');
      }
      endpoints
          .append(memberId)
          .append('=')
          .append(LOOPBACK)
          .append(':')
          .append(port(memberId, INGRESS_PORT_OFFSET));
    }
    return endpoints.toString();
  }

  public String clusterMembers() {
    StringBuilder members = new StringBuilder();
    for (int memberId = 0; memberId < MEMBER_COUNT; memberId++) {
      members
          .append(memberId)
          .append(',')
          .append(endpoint(memberId, INGRESS_PORT_OFFSET))
          .append(',')
          .append(endpoint(memberId, CONSENSUS_PORT_OFFSET))
          .append(',')
          .append(endpoint(memberId, LOG_PORT_OFFSET))
          .append(',')
          .append(endpoint(memberId, CATCHUP_PORT_OFFSET))
          .append(',')
          .append(endpoint(memberId, ARCHIVE_PORT_OFFSET))
          .append('|');
    }
    return members.toString();
  }

  /** Exact arguments understood by {@link M12ClusterMemberMain}. */
  public List<String> memberProcessArguments(int memberId, boolean freshStart) {
    requireMemberId(memberId);
    return List.of(
        "--root",
        rootDirectory.toString(),
        "--member-id",
        Integer.toString(memberId),
        "--cluster-id",
        Integer.toString(clusterId),
        "--shard-id",
        Long.toString(shardId),
        "--port-base",
        Integer.toString(portBase),
        "--app-version",
        Integer.toString(appVersion),
        "--client-message-timeout-ms",
        Long.toString(clientMessageTimeout.toMillis()),
        "--heartbeat-interval-ms",
        Long.toString(leaderHeartbeatInterval.toMillis()),
        "--heartbeat-timeout-ms",
        Long.toString(leaderHeartbeatTimeout.toMillis()),
        "--election-timeout-ms",
        Long.toString(electionTimeout.toMillis()),
        "--startup-canvass-timeout-ms",
        Long.toString(startupCanvassTimeout.toMillis()),
        "--status-interval-ms",
        Long.toString(statusPublishInterval.toMillis()),
        "--fresh-start",
        Boolean.toString(freshStart));
  }

  public void requireMemberId(int memberId) {
    if (memberId < 0 || memberId >= MEMBER_COUNT) {
      throw new IllegalArgumentException("memberId must be 0, 1, or 2");
    }
  }

  private String endpoint(int memberId, int offset) {
    return LOOPBACK + ':' + port(memberId, offset);
  }

  private static String udpChannel(int port) {
    return "aeron:udp?endpoint=" + LOOPBACK + ':' + port;
  }

  private static Duration positive(Duration value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(field + " must be positive");
    }
    return value;
  }
}
