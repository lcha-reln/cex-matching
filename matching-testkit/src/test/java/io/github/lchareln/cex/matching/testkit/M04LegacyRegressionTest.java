package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class M04LegacyRegressionTest {
  @Test
  void preservesM03SemanticSuiteWithoutInvokingHistoricalArchitectureIdentity() {
    M04LegacyRegression.Result result = new M04LegacyRegression().run(M04TestPaths.root());

    assertEquals(16_384, result.commands());
    assertEquals(1_682_592, result.bytes());
    assertEquals(16_641, result.lines());
    assertEquals(M04LegacyRegression.EXPECTED_DIGEST, result.digest());
    assertEquals(6, result.mutants().size());
    assertTrue(result.mutants().stream().allMatch(M04LegacyRegression.MutantFact::oneMinimal));
    assertEquals(M03PropertyJudge.SYSTEM_ERROR, result.systemErrorControl());
  }
}
