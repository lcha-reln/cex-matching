package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

final class M01ProductionContractTest {
  @Test
  void productionEngineMatchesEveryFrozenEventBatchBookAndInvariant() {
    M01Assertions.Observation observation =
        new M01Assertions().judge(M01TestPaths.load(), M01ProductionCandidate::new);

    assertEquals(M01Assertions.PASS, observation.classification(), observation.message());
    assertNotNull(observation.history());
    assertEquals(22, observation.metrics().cases());
    assertEquals(22, observation.metrics().eventBatchChecks());
    assertEquals(22, observation.metrics().conservationChecks());
  }
}
