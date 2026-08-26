package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lchareln.cex.matching.PlaceLimitOrderInput;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;

final class M00CanonicalHistoryGoldenTest {
  @Test
  void matchesTheCheckedInByteGoldenAndDigest() throws IOException {
    M00Fixture fixture =
        new M00FixtureLoader().load(M00TestPaths.fixture(), M00TestPaths.fixtureSchema());
    CanonicalHistory actual =
        new M00Canonicalizer()
            .canonicalize(fixture.records().stream().map(M00Fixture.Record::input).toList());
    byte[] expected =
        Files.readAllBytes(
            M00TestPaths.root()
                .resolve(
                    "matching-testkit/src/test/resources/m00/golden/history-v1.canonical.txt"));

    assertArrayEquals(expected, actual.bytes());
    assertEquals(37, actual.lineCount());
    assertEquals(3199, actual.bytes().length);
    assertEquals(
        "sha256:2d287d677d5f200f2b5bd1dd18dabbd40e865779489ce6da36d0411a3b670669", actual.digest());
    assertEquals('\n', actual.bytes()[actual.bytes().length - 1]);
  }

  @Test
  void framesUnicodeAndDelimitersByUtf8ByteLength() {
    PlaceLimitOrderInput input =
        new PlaceLimitOrderInput("交易|对", BigInteger.ONE, "BUY:NOW", BigInteger.ONE, BigInteger.ONE);
    String history =
        new String(
            new M00Canonicalizer().canonicalize(List.of(input)).bytes(), StandardCharsets.UTF_8);
    assertTrue(history.contains("instrumentId=10:交易|对"));
    assertTrue(history.contains("side=7:BUY:NOW"));
    assertFalse(history.contains("M00C1"));
  }
}
