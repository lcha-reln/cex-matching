package io.github.lchareln.cex.matching.testkit;

/** The repository-owned splitmix64-v1 algorithm, including its deterministic bounded draw. */
final class M03SplitMix64V1 {
  private static final long GAMMA = 0x9E3779B97F4A7C15L;
  private static final long MIX_ONE = 0xBF58476D1CE4E5B9L;
  private static final long MIX_TWO = 0x94D049BB133111EBL;

  private long state;

  M03SplitMix64V1(long seed) {
    state = seed;
  }

  long nextLong() {
    long value = state += GAMMA;
    value = (value ^ (value >>> 30)) * MIX_ONE;
    value = (value ^ (value >>> 27)) * MIX_TWO;
    return value ^ (value >>> 31);
  }

  int nextInt(int bound) {
    if (bound <= 0) {
      throw new IllegalArgumentException("bound must be positive");
    }
    return (int) Long.remainderUnsigned(nextLong(), bound);
  }
}
