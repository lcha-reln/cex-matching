package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

final class M02ProductionContractTest {
  @Test
  void productionMatchesEveryCommandAndTheIndependentLifecycleLedger() {
    M02Assertions.Observation observation =
        new M02Assertions().judge(M02TestPaths.load(), M02ProductionCandidate::new);

    assertEquals(M02Assertions.PASS, observation.classification(), observation.message());
    assertNotNull(observation.history());
    assertEquals(34, observation.metrics().commands());
    assertEquals(34, observation.metrics().eventBatchChecks());
    assertEquals(34, observation.metrics().bookTransitionChecks());
    assertEquals(34, observation.metrics().lifecycleChecks());
    assertEquals(34, observation.metrics().registryBookChecks());
  }
}
