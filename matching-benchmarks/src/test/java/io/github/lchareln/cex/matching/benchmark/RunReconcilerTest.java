package io.github.lchareln.cex.matching.benchmark;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class RunReconcilerTest {
  @Test
  void reconcilesOffersCompletionsAndDurableAcknowledgementsWithoutTreatingOverloadAsCompletion() {
    RunAccounting accounting =
        new RunAccounting(
            12, 9, 2, 1, Map.of("NEW_DURABLY_APPLIED", 6L, "CHECKPOINT_REQUIRED", 2L), 1, 0);

    assertEquals(8, accounting.submissionResultCompletions());
    assertEquals(9, accounting.terminalCompletions());
    assertEquals(6, accounting.durableAcknowledgements());
    assertDoesNotThrow(() -> RunReconciler.requireValid(accounting, true, 9, 12, 1));
  }

  @Test
  void rejectsSchemaLikeCounterAndRawSeriesMismatches() {
    RunAccounting invalid = new RunAccounting(4, 3, 2, 0, Map.of("InventedSuccess", 1L), 0, 2);
    var violations = RunReconciler.violations(invalid, true, 0, 0, 0);
    assertTrue(violations.stream().anyMatch(value -> value.startsWith("offers !=")));
    assertTrue(violations.stream().anyMatch(value -> value.startsWith("unknown")));
    assertTrue(violations.stream().anyMatch(value -> value.contains("resource")));
    assertThrows(
        IllegalArgumentException.class, () -> RunReconciler.requireValid(invalid, true, 0, 0, 0));
  }
}
