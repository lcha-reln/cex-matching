package io.github.lchareln.cex.matching.cluster;

import java.util.Objects;

/** Aeron-independent evidence sampled from the running Consensus Module and service. */
public record M11ClusterRuntimeWitness(
    int clusterId,
    int memberCount,
    int memberId,
    int appointedLeaderId,
    String clusterMembers,
    String serviceRole,
    String aeronImplementationVersion,
    String agronaImplementationVersion,
    String rootDirectory,
    int udpPortBlockBase) {
  public M11ClusterRuntimeWitness {
    if (clusterId < 0 || memberCount <= 0 || memberId < 0 || appointedLeaderId < 0) {
      throw new IllegalArgumentException("cluster identity witness is invalid");
    }
    clusterMembers = requireText(clusterMembers, "clusterMembers");
    serviceRole = requireText(serviceRole, "serviceRole");
    aeronImplementationVersion =
        requireText(aeronImplementationVersion, "aeronImplementationVersion");
    agronaImplementationVersion =
        requireText(agronaImplementationVersion, "agronaImplementationVersion");
    rootDirectory = requireText(rootDirectory, "rootDirectory");
    if (udpPortBlockBase < 1024 || udpPortBlockBase > 65_530) {
      throw new IllegalArgumentException("UDP port block witness is invalid");
    }
  }

  private static String requireText(String value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }
}
