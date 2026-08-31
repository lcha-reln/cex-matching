package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

final class M05CounterexampleSuiteTest {
  @Test
  void allEightFaultsPersistParseCanonicalizeAndStrictlyReplayFromFreshState() {
    M05CounterexampleSuite.Result result = new M05CounterexampleSuite().run(M05TestPaths.root());

    assertEquals(8, result.counterexamples().size());
    assertEquals(8, result.canonical().scenarios());
    assertEquals(8 * 64, result.canonical().originalCommands());
    assertTrue(result.replay().allPassed());
    assertEquals(M05PropertyJudge.SYSTEM_ERROR, result.systemErrorControl());
    assertTrue(result.counterexamples().stream().allMatch(item -> item.shrunk().oneMinimal()));

    ObjectNode tampered = (ObjectNode) result.persisted().deepCopy();
    ((ObjectNode) tampered.path("scenarios").get(0)).put("seed", "0000000000000000");
    assertNotEquals(
        result.canonical().digest(),
        new M05CounterexampleCanonicalizer().canonicalize(tampered).digest());
    M05ScenarioCorpus.Corpus corpus = M05ScenarioCorpus.load(M05TestPaths.root());
    M05GeneratorProfile profile = M05GeneratorProfile.load(M05TestPaths.root());
    assertFalse(
        new M05CounterexampleReplay()
            .replay(tampered, profile, corpus, M05RequiredMutants.all())
            .allPassed());
  }

  @Test
  void strictSchemaRejectsUnknownPersistedFields() {
    M05CounterexampleSuite.Result result = new M05CounterexampleSuite().run(M05TestPaths.root());
    ObjectNode tampered = (ObjectNode) result.persisted().deepCopy();
    ((ObjectNode) tampered.path("scenarios").get(0)).put("unexpected", true);

    assertThrows(
        FixtureSchemaException.class,
        () ->
            JsonSupport.validate(
                tampered,
                java.nio.file.Files.readString(
                    M05TestPaths.root().resolve(M05CounterexampleSuite.SCHEMA_PATH)),
                false));
  }
}
