package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class M12InfrastructurePreconditionsTest {
  @Test
  void productionPreconditionsAcceptTheDeclaredTopologyAndObservedLeader() {
    assertDoesNotThrow(
        () -> M12InfrastructurePreconditions.requireStaticLaunchTopology(3, List.of(0, 1, 2), 15));
    assertDoesNotThrow(
        () -> M12InfrastructurePreconditions.requireCurrentLeaderFaultTarget(2, 2, "LEADER"));
    assertDoesNotThrow(
        () ->
            M12InfrastructurePreconditions.parseHistory(
                "{\"schemaVersion\":\"matching.m12.command-history.v1\"}"
                    .getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void infrastructureProbeFailuresRemainSystemExceptions() {
    assertThrows(
        IllegalStateException.class,
        () -> M12InfrastructurePreconditions.requireCurrentLeaderFaultTarget(1, 0, "LEADER"));
    assertThrows(
        IllegalStateException.class,
        () -> M12InfrastructurePreconditions.requireStaticLaunchTopology(3, List.of(0, 1), 15));
    assertThrows(
        RuntimeException.class,
        () -> M12InfrastructurePreconditions.parseHistory("{".getBytes(StandardCharsets.UTF_8)));
  }
}
