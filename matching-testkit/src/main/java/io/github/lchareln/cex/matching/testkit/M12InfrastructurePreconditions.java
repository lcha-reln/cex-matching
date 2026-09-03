package io.github.lchareln.cex.matching.testkit;

import java.util.List;
import java.util.Objects;
import tools.jackson.databind.JsonNode;

/** Executable fail-closed preconditions shared by the real harness and SYSTEM_ERROR probes. */
final class M12InfrastructurePreconditions {
  private M12InfrastructurePreconditions() {}

  static void requireCurrentLeaderFaultTarget(
      int selectedMemberId, int observedLeaderId, String observedRole) {
    if (selectedMemberId != observedLeaderId || !"LEADER".equals(observedRole)) {
      throw new IllegalStateException(
          "selected fault member is not the currently observed Leader: selected="
              + selectedMemberId
              + ", observed="
              + observedLeaderId
              + ", role="
              + observedRole);
    }
  }

  static void requireStaticLaunchTopology(
      int configuredMemberCount, List<Integer> memberIds, int fixedUdpPortCount) {
    Objects.requireNonNull(memberIds, "memberIds");
    if (configuredMemberCount != 3
        || !List.of(0, 1, 2).equals(List.copyOf(memberIds))
        || fixedUdpPortCount != 15) {
      throw new IllegalStateException(
          "three-member launch preflight failed: configuredMemberCount="
              + configuredMemberCount
              + ", memberIds="
              + memberIds
              + ", fixedUdpPortCount="
              + fixedUdpPortCount);
    }
  }

  static JsonNode parseHistory(byte[] bytes) {
    return JsonSupport.parse(Objects.requireNonNull(bytes, "bytes"));
  }
}
