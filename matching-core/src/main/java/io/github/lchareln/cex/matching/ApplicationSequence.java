package io.github.lchareln.cex.matching;

/** Positive in-memory serialization boundary for one completed matcher command. */
public record ApplicationSequence(long value) {
  public ApplicationSequence {
    if (value <= 0) {
      throw new IllegalArgumentException("application sequence must be positive");
    }
  }
}
