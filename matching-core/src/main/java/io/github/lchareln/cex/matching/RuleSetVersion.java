package io.github.lchareln.cex.matching;

/** Monotonic non-negative version of one immutable market rule-set artifact. */
public record RuleSetVersion(long value) implements Comparable<RuleSetVersion> {
  public RuleSetVersion {
    if (value < 0) {
      throw new IllegalArgumentException("rule-set version must be non-negative");
    }
  }

  @Override
  public int compareTo(RuleSetVersion other) {
    return Long.compare(value, other.value);
  }
}
