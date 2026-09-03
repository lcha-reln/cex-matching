package io.github.lchareln.cex.matching.cluster;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/** Owned directories, loopback ports, and application identity for one Cluster member. */
public record M11SingleNodeConfig(
    Path rootDirectory,
    int clusterId,
    long shardId,
    int portBase,
    int appVersion,
    Duration clientMessageTimeout) {
  public M11SingleNodeConfig {
    rootDirectory =
        Objects.requireNonNull(rootDirectory, "rootDirectory").toAbsolutePath().normalize();
    if (clusterId < 0) {
      throw new IllegalArgumentException("clusterId must not be negative");
    }
    if (shardId <= 0) {
      throw new IllegalArgumentException("shardId must be positive");
    }
    if (portBase < 1024 || portBase > 65_530) {
      throw new IllegalArgumentException("portBase must leave a valid six-port block");
    }
    if (appVersion <= 0) {
      throw new IllegalArgumentException("appVersion must be positive");
    }
    clientMessageTimeout = Objects.requireNonNull(clientMessageTimeout, "clientMessageTimeout");
    if (clientMessageTimeout.isNegative() || clientMessageTimeout.isZero()) {
      throw new IllegalArgumentException("clientMessageTimeout must be positive");
    }
  }

  public static M11SingleNodeConfig defaults(Path rootDirectory, long shardId, int portBase) {
    return new M11SingleNodeConfig(
        rootDirectory, 11, shardId, portBase, 0x020000, Duration.ofSeconds(15));
  }

  public Path nodeAeronDirectory() {
    return rootDirectory.resolve("node-0/aeron");
  }

  public Path archiveDirectory() {
    return rootDirectory.resolve("node-0/archive");
  }

  public Path clusterDirectory() {
    return rootDirectory.resolve("node-0/cluster");
  }

  public Path clientAeronDirectory() {
    return rootDirectory.resolve("client/aeron");
  }

  public String archiveControlChannel() {
    return "aeron:udp?endpoint=127.0.0.1:" + (portBase + 1);
  }

  public String ingressEndpoints() {
    return "0=127.0.0.1:" + (portBase + 2);
  }

  public String clusterMembers() {
    return "0,127.0.0.1:"
        + (portBase + 2)
        + ",127.0.0.1:"
        + (portBase + 3)
        + ",127.0.0.1:"
        + (portBase + 4)
        + ",127.0.0.1:"
        + (portBase + 5)
        + ",127.0.0.1:"
        + (portBase + 1)
        + "|";
  }
}
