package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lchareln.cex.matching.reference.ReferenceCommand;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

final class M03GeneratorProfileTest {
  @Test
  void loadsTheFrozenProfileAndExactLanePrefixes() {
    M03GeneratorProfile profile = load();

    assertEquals(M03GeneratorProfile.SCHEMA_VERSION, profile.schemaVersion());
    assertEquals(M03GeneratorProfile.ALGORITHM, profile.algorithm());
    assertEquals(6824L, profile.baseSeed());
    assertEquals(256, profile.histories());
    assertEquals(64, profile.commandsPerHistory());
    assertEquals(
        List.of("BEST_PRICE", "SAME_PRICE_FIFO", "MAKER_PRICE", "CANCELED_IDENTITY"),
        profile.lanes().stream().map(M03GeneratorProfile.Lane::id).toList());
    assertEquals(
        List.of(0, 1, 2, 3),
        profile.lanes().stream().map(M03GeneratorProfile.Lane::historyModulo).toList());
    assertEquals(
        List.of(3, 3, 2, 3), profile.lanes().stream().map(lane -> lane.prefix().size()).toList());
    assertInstanceOf(ReferenceCommand.Place.class, profile.lanes().getFirst().prefix().getFirst());
    assertInstanceOf(ReferenceCommand.Cancel.class, profile.lanes().getLast().prefix().get(1));

    M03GeneratorProfile.RandomDomain domain = profile.randomDomain();
    assertEquals(65, domain.placeWeight());
    assertEquals(35, domain.cancelWeight());
    assertEquals(32, domain.invalidOneIn());
    assertEquals("BTC-USDT", domain.validInstrumentId());
    assertEquals("ETH-USDT", domain.invalidInstrumentId());
    assertEquals(List.of("BUY", "SELL"), domain.validSides());
    assertEquals("HOLD", domain.invalidSide());
  }

  @Test
  void rejectsStrictJsonSchemaLexicalAndSemanticBoundaryViolations() throws IOException {
    String source = Files.readString(fixture(), StandardCharsets.UTF_8);
    String schema = Files.readString(schema(), StandardCharsets.UTF_8);
    List<String> invalid =
        List.of(
            replaceOnce(source, "\"histories\": 256,", "\"histories\": 256, \"clock\": 1,"),
            replaceOnce(source, "\"histories\": 256,", "\"histories\": 256, \"histories\": 256,"),
            replaceOnce(source, "\"commandsPerHistory\": 64,", "\"commandsPerHistory\": 64.0,"),
            replaceOnce(source, "\"historyModulo\": 1,", "\"historyModulo\": 0,"),
            replaceOnce(source, "\"minimumPriceTicks\": 98,", "\"minimumPriceTicks\": 103,"),
            replaceOnce(
                source, "\"baseSeed\": \"6824\"", "\"baseSeed\": \"18446744073709551616\""));

    for (String probe : invalid) {
      assertThrows(
          FixtureSchemaException.class,
          () -> M03GeneratorProfile.load(probe.getBytes(StandardCharsets.UTF_8), schema));
    }
  }

  private static M03GeneratorProfile load() {
    return M03GeneratorProfile.load(fixture(), schema());
  }

  private static Path fixture() {
    return root()
        .resolve("matching-testkit/src/test/resources/m03/fixtures/property-suite-v1.json");
  }

  private static Path schema() {
    return root().resolve("schemas/matching.m03.generator.v1.schema.json");
  }

  private static Path root() {
    return Path.of(System.getProperty("matching.repositoryRoot"));
  }

  private static String replaceOnce(String source, String target, String replacement) {
    int index = source.indexOf(target);
    if (index < 0) {
      throw new IllegalStateException("test fixture target is missing: " + target);
    }
    return source.substring(0, index) + replacement + source.substring(index + target.length());
  }
}
