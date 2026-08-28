package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class M04GeneratedCoverageTest {
  @Test
  void computesAllRequiredObligationsFromActualSemanticPreState() {
    M04GeneratorProfile profile =
        M04GeneratorProfile.load(
            M04TestPaths.root().resolve(M04StartCheckRunner.GENERATOR_PATH),
            M04TestPaths.root().resolve(M04StartCheckRunner.GENERATOR_SCHEMA_PATH));
    M04GeneratedCoverage.Result result =
        new M04GeneratedCoverage().analyze(profile, new M04HistoryGenerator().generate(profile));

    result.assertRequired();
    assertEquals(733, result.counts().get(M04GeneratedCoverage.IOC_ZERO_FILL));
    assertEquals(165, result.counts().get(M04GeneratedCoverage.IOC_PARTIAL_FILL));
    assertEquals(292, result.counts().get(M04GeneratedCoverage.IOC_FULL_FILL));
    assertEquals(713, result.counts().get(M04GeneratedCoverage.FOK_INSUFFICIENT));
    assertEquals(285, result.counts().get(M04GeneratedCoverage.FOK_ACCEPTED));
    assertTrue(result.counts().get(M04GeneratedCoverage.FOK_EXACT) > 0);
    assertEquals(78, result.counts().get(M04GeneratedCoverage.FOK_MULTI_LEVEL));
    assertEquals(214, result.counts().get(M04GeneratedCoverage.FOK_OUTSIDE_LIMIT_EXCLUDED));
    assertTrue(result.counts().get(M04GeneratedCoverage.POST_ONLY_EMPTY_BOOK) > 0);
    assertEquals(331, result.counts().get(M04GeneratedCoverage.POST_ONLY_NON_CROSSING));
    assertEquals(202, result.counts().get(M04GeneratedCoverage.POST_ONLY_TOUCH));
    assertEquals(209, result.counts().get(M04GeneratedCoverage.POST_ONLY_CROSS));
    assertEquals(423, result.counts().get(M04GeneratedCoverage.BASE_VALID_UNKNOWN));
    assertEquals(295, result.counts().get(M04GeneratedCoverage.BASE_VALID_UNUSED_ID_UNKNOWN));
    assertEquals(458, result.counts().get(M04GeneratedCoverage.REJECTED_ID_LATER_REUSED));
    assertEquals(
        new M04GeneratedCoverage.Witness(0, 30, "c1b2b1c8c43402ba", "LEGACY_GTC"),
        result.firstWitnesses().get(M04GeneratedCoverage.FOK_OUTSIDE_LIMIT_EXCLUDED));
    assertEquals(
        new M04GeneratedCoverage.Witness(1, 26, "f2b4ce0f72f08065", "IOC_ZERO_PARTIAL_FULL"),
        result.firstWitnesses().get(M04GeneratedCoverage.BASE_VALID_UNUSED_ID_UNKNOWN));
    assertEquals(23, result.counts().size());
    assertEquals(
        8,
        result.counts().entrySet().stream()
            .filter(entry -> entry.getKey().startsWith("SIDE_POLICY_"))
            .filter(entry -> entry.getValue() > 0)
            .count());
  }
}
