package io.github.lchareln.cex.matching.cluster;

/** Shared writer-side count checks paired with the strict decoder limits. */
final class M11EncodingBounds {
  private M11EncodingBounds() {}

  static void requireAtMost(int count, int maximum, String field) {
    if (count < 0 || count > maximum) {
      throw new IllegalArgumentException(field + " exceeds the M11 encoding bound");
    }
  }
}
