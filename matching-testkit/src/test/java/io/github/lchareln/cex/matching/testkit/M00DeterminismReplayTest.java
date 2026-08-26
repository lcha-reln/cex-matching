package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class M00DeterminismReplayTest {
  @Test
  void performsOneHundredFreshFixtureReplays() {
    M00FixtureLoader loader = new M00FixtureLoader();
    M00Fixture first = loader.load(M00TestPaths.fixture(), M00TestPaths.fixtureSchema());
    CanonicalHistory baseline =
        new M00Canonicalizer()
            .canonicalize(first.records().stream().map(M00Fixture.Record::input).toList());
    Set<String> digests = new LinkedHashSet<>();

    for (int replay = 0; replay < 100; replay++) {
      M00Fixture fresh = loader.load(M00TestPaths.fixture(), M00TestPaths.fixtureSchema());
      CanonicalHistory actual =
          new M00Canonicalizer()
              .canonicalize(fresh.records().stream().map(M00Fixture.Record::input).toList());
      assertArrayEquals(baseline.bytes(), actual.bytes(), "replay " + replay);
      assertEquals(baseline.validationResults(), actual.validationResults(), "replay " + replay);
      assertEquals(baseline.digest(), actual.digest(), "replay " + replay);
      digests.add(actual.digest());
    }
    assertEquals(1, digests.size());
  }
}
