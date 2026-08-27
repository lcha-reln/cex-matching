package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

final class M01ScenarioBoundaryTest {
  @Test
  void loadsTheFrozenEightScenarioTwentyTwoCaseCorpus() {
    M01ScenarioPack pack = M01TestPaths.load();

    assertEquals(8, pack.scenarios().size());
    assertEquals(22, pack.caseCount());
    assertEquals(
        M01CheckRunner.FROZEN_FIXTURE_SHA256, Hashing.sha256Hex(readBytes(M01TestPaths.fixture())));
  }

  @Test
  void rejectsDuplicateFieldsDecimalTokensAndUnknownFields() {
    byte[] fixture = readBytes(M01TestPaths.fixture());
    String schema = readString(M01TestPaths.fixtureSchema());
    String source = new String(fixture, StandardCharsets.UTF_8);
    M01ScenarioLoader loader = new M01ScenarioLoader();

    assertThrows(
        FixtureSchemaException.class,
        () ->
            loader.load(
                replaceOnce(source, "\"priceTicks\": 0,", "\"priceTicks\": 0, \"priceTicks\": 0,")
                    .getBytes(StandardCharsets.UTF_8),
                schema));
    assertThrows(
        FixtureSchemaException.class,
        () ->
            loader.load(
                replaceOnce(source, "\"quantityLots\": 2", "\"quantityLots\": 2.0")
                    .getBytes(StandardCharsets.UTF_8),
                schema));
    assertThrows(
        FixtureSchemaException.class,
        () ->
            loader.load(
                replaceOnce(
                        source, "\"quantityLots\": 2 }", "\"quantityLots\": 2, \"unexpected\": 1 }")
                    .getBytes(StandardCharsets.UTF_8),
                schema));
  }

  private static String replaceOnce(String source, String target, String replacement) {
    int index = source.indexOf(target);
    if (index < 0) {
      throw new IllegalStateException("test fixture target missing: " + target);
    }
    return source.substring(0, index) + replacement + source.substring(index + target.length());
  }

  private static byte[] readBytes(java.nio.file.Path path) {
    try {
      return Files.readAllBytes(path);
    } catch (IOException exception) {
      throw new IllegalStateException("cannot read " + path, exception);
    }
  }

  private static String readString(java.nio.file.Path path) {
    return new String(readBytes(path), StandardCharsets.UTF_8);
  }
}
