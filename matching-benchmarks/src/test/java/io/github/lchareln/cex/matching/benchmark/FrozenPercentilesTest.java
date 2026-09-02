package io.github.lchareln.cex.matching.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class FrozenPercentilesTest {
  @Test
  void usesNearestRankOverAnIndependentSortedCopy() {
    List<Long> raw = List.of(10L, 1L, 100L, 5L, 25L);

    assertEquals(10, FrozenPercentiles.nearestRank(raw, 0.5));
    assertEquals(100, FrozenPercentiles.nearestRank(raw, 0.95));
    assertEquals(100, FrozenPercentiles.nearestRank(raw, 0.999));
    assertEquals(List.of(10L, 1L, 100L, 5L, 25L), raw);
  }

  @Test
  void rejectsMissingMalformedOrNegativeRawSamples() {
    assertThrows(
        IllegalArgumentException.class, () -> FrozenPercentiles.nearestRank(List.of(), 0.99));
    assertThrows(
        IllegalArgumentException.class,
        () -> FrozenPercentiles.nearestRank(List.of(1L), Double.NaN));
    assertThrows(
        IllegalArgumentException.class, () -> FrozenPercentiles.nearestRank(List.of(-1L), 0.99));
    assertThrows(
        IllegalArgumentException.class, () -> FrozenPercentiles.nearestRank(List.of(1L), 0.9));
  }

  @Test
  void freezesIntegerRanksWithoutFloatingPointBoundaryDrift() {
    assertEquals(1, FrozenPercentiles.nearestRank(sequence(1), 0.999));
    assertEquals(2, FrozenPercentiles.nearestRank(sequence(2), 0.999));
    assertEquals(999, FrozenPercentiles.nearestRank(sequence(1_000), 0.999));
    assertEquals(1_000, FrozenPercentiles.nearestRank(sequence(1_001), 0.999));
    assertEquals(990, FrozenPercentiles.nearestRank(sequence(1_000), 0.99));
    assertEquals(991, FrozenPercentiles.nearestRank(sequence(1_001), 0.99));
  }

  private static List<Long> sequence(int size) {
    return java.util.stream.LongStream.rangeClosed(1, size).boxed().toList();
  }
}
