package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

final class M04CounterexampleSuiteTest {
  @Test
  void allEightFaultsPersistParseAndStrictlyReplayFromFreshState() {
    M04CounterexampleSuite.Result result = new M04CounterexampleSuite().run(M04TestPaths.root());

    assertEquals(8, result.counterexamples().size());
    assertEquals(8, result.canonical().scenarios());
    assertTrue(result.replay().allPassed());
    assertEquals(M04PropertyJudge.SYSTEM_ERROR, result.systemErrorControl());
    assertTrue(result.counterexamples().stream().allMatch(item -> item.shrunk().oneMinimal()));

    ObjectNode tampered = (ObjectNode) result.persisted().deepCopy();
    ((ObjectNode) tampered.path("scenarios").get(0)).put("seed", "0000000000000000");
    assertNotEquals(
        result.canonical().digest(),
        new M04CounterexampleCanonicalizer().canonicalize(tampered).digest());
    M04GeneratorProfile profile =
        M04GeneratorProfile.load(
            M04TestPaths.root().resolve(M04StartCheckRunner.GENERATOR_PATH),
            M04TestPaths.root().resolve(M04StartCheckRunner.GENERATOR_SCHEMA_PATH));
    assertFalse(
        new M04CounterexampleReplay()
            .replay(tampered, profile, M04RequiredMutants.all())
            .allPassed());
  }
}
