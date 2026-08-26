package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;

final class M00FixtureBoundaryTest {
  private final M00FixtureLoader loader = new M00FixtureLoader();

  @Test
  void loadsTheFrozenArbitraryPrecisionFixture() {
    M00Fixture fixture = loader.load(M00TestPaths.fixture(), M00TestPaths.fixtureSchema());
    assertEquals(17, fixture.records().size());
    assertEquals("9223372036854775808", fixture.records().get(5).input().orderId().toString());
  }

  @Test
  void rejectsDuplicateFloatingExponentWrongTypeAndTrailingJson() throws IOException {
    String source = Files.readString(M00TestPaths.fixture(), StandardCharsets.UTF_8);
    String schema = Files.readString(M00TestPaths.fixtureSchema(), StandardCharsets.UTF_8);
    for (String invalid :
        new String[] {
          replaceOnce(source, "\"side\": \"BUY\",", "\"side\": \"BUY\", \"side\": \"BUY\","),
          replaceOnce(source, "\"priceTicks\": 1,", "\"priceTicks\": 1.0,"),
          replaceOnce(source, "\"quantityLots\": 1,", "\"quantityLots\": 1e0,"),
          replaceOnce(source, "\"orderId\": 1,", "\"orderId\": \"1\","),
          source + "{}"
        }) {
      assertThrows(
          FixtureSchemaException.class,
          () -> loader.load(invalid.getBytes(StandardCharsets.UTF_8), schema));
    }
  }

  @Test
  void normalizesNegativeZeroToTheShortestDecimalInteger() throws IOException {
    String source = Files.readString(M00TestPaths.fixture(), StandardCharsets.UTF_8);
    String schema = Files.readString(M00TestPaths.fixtureSchema(), StandardCharsets.UTF_8);
    String negativeZero = replaceOnce(source, "\"orderId\": 1,", "\"orderId\": -0,");
    M00Fixture fixture = loader.load(negativeZero.getBytes(StandardCharsets.UTF_8), schema);

    String canonical =
        new String(
            new M00Canonicalizer()
                .canonicalize(List.of(fixture.records().getFirst().input()))
                .bytes(),
            StandardCharsets.UTF_8);
    assertTrue(canonical.contains("|orderId=0|"));
  }

  private static String replaceOnce(String source, String target, String replacement) {
    int index = source.indexOf(target);
    return source.substring(0, index) + replacement + source.substring(index + target.length());
  }
}
