package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

final class M01CanonicalizerTest {
  @Test
  void productionAndFrozenExpectationProduceTheSameCanonicalBytes() {
    M01ScenarioPack pack = M01TestPaths.load();
    M01Assertions.Observation production =
        new M01Assertions().judge(pack, M01ProductionCandidate::new);
    M01Canonicalizer canonicalizer = new M01Canonicalizer();

    M01CanonicalHistory expected = canonicalizer.canonicalize(canonicalizer.expectedHistory(pack));
    M01CanonicalHistory actual = canonicalizer.canonicalize(production.history());

    assertArrayEquals(expected.bytes(), actual.bytes());
    assertEquals(expected.digest(), actual.digest());
    assertEquals(expected.lineCount(), actual.lineCount());
    assertTrue(actual.bytes().length > 0);
    assertEquals('\n', actual.bytes()[actual.bytes().length - 1]);
    assertFalse(new String(actual.bytes(), StandardCharsets.UTF_8).startsWith("\ufeff"));
    assertArrayEquals(
        readBytes(
            M01TestPaths.root()
                .resolve(
                    "matching-testkit/src/test/resources/m01/golden/price-time-v1.canonical.txt")),
        actual.bytes());
    assertEquals(
        M01CheckRunner.EXPECTED_DIGEST,
        readString(
                M01TestPaths.root()
                    .resolve("matching-testkit/src/test/resources/m01/golden/price-time-v1.sha256"))
            .strip());
  }

  private static byte[] readBytes(java.nio.file.Path path) {
    try {
      return Files.readAllBytes(path);
    } catch (java.io.IOException exception) {
      throw new IllegalStateException("cannot read " + path, exception);
    }
  }

  private static String readString(java.nio.file.Path path) {
    return new String(readBytes(path), StandardCharsets.UTF_8);
  }
}
