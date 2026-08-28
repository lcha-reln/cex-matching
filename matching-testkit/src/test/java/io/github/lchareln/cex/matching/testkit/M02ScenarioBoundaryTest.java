package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

final class M02ScenarioBoundaryTest {
  @Test
  void loadsTheExactFrozenMixedCommandShape() {
    M02ScenarioPack pack = M02TestPaths.load();

    assertEquals(10, pack.scenarios().size());
    assertEquals(34, pack.commandCount());
    assertEquals(22, pack.placeCommandCount());
    assertEquals(12, pack.cancelCommandCount());
  }

  @Test
  void rejectsAllEightFrozenSchemaAndLexicalProbes() throws IOException {
    byte[] fixture = Files.readAllBytes(M02TestPaths.fixture());
    String schema = Files.readString(M02TestPaths.fixtureSchema(), StandardCharsets.UTF_8);

    assertEquals(
        8, M02CheckRunner.verifyScenarioBoundary(new M02ScenarioLoader(), fixture, schema));
  }

  @Test
  void rejectsDuplicateIdentityEvenWhenTheJsonSchemaWouldAcceptIt() throws IOException {
    String source = Files.readString(M02TestPaths.fixture(), StandardCharsets.UTF_8);
    String duplicate =
        source.replace(
            "\"scenarioId\": \"cancel-only-resting-order-removes-level\"",
            "\"scenarioId\": \"invalid-cancel-does-not-mutate-or-consume-sequence\"");
    String schema = Files.readString(M02TestPaths.fixtureSchema(), StandardCharsets.UTF_8);

    assertThrows(
        FixtureSchemaException.class,
        () -> new M02ScenarioLoader().load(duplicate.getBytes(StandardCharsets.UTF_8), schema));
  }
}
