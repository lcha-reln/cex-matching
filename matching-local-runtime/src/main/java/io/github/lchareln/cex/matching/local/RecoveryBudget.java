package io.github.lchareln.cex.matching.local;

/** Hard bound on records and bytes replayed after the latest published M09S1 snapshot. */
public record RecoveryBudget(long maxSuffixRecords, long maxSuffixBytes) {
  public static final RecoveryBudget M09_DEFAULT = new RecoveryBudget(64, 1_048_576);
  static final RecoveryBudget LEGACY_M08_UNBOUNDED =
      new RecoveryBudget(Long.MAX_VALUE, Long.MAX_VALUE);

  public RecoveryBudget {
    if (maxSuffixRecords <= 0 || maxSuffixBytes <= 0) {
      throw new IllegalArgumentException("recovery budget bounds must be positive");
    }
  }

  boolean accepts(long currentRecords, long currentBytes, int nextRecordBytes) {
    if (currentRecords < 0 || currentBytes < 0 || nextRecordBytes <= 0) {
      throw new IllegalArgumentException("invalid recovery budget usage");
    }
    try {
      return Math.incrementExact(currentRecords) <= maxSuffixRecords
          && Math.addExact(currentBytes, nextRecordBytes) <= maxSuffixBytes;
    } catch (ArithmeticException exhausted) {
      return false;
    }
  }
}
