package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class M02RegressionTest {
  @Test
  void preservesTheCompleteM01CheckAndInheritedM00Facts() {
    M02M01Regression.Result result =
        new M02M01Regression().verify(M02TestPaths.root(), M02ProductionCandidate::new);

    assertTrue(result.passed(), result.message());
    assertEquals(8, result.m01Scenarios());
    assertEquals(22, result.m01Commands());
    assertEquals(M01CheckRunner.EXPECTED_DIGEST, result.m01Digest());
    assertTrue(result.m00().passed(), result.m00().message());
    assertEquals(100, result.m00().completedReplays());
    assertEquals(1, result.m00().distinctDigests());
  }
}
