package io.github.lchareln.cex.matching.benchmark;

import java.math.BigInteger;

/** Exact rational schedule used to retain producer scheduler delay and coordinated omission. */
public final class ScheduledArrival {
  private static final BigInteger NANOS_PER_SECOND = BigInteger.valueOf(1_000_000_000L);

  private ScheduledArrival() {}

  public static long at(long originNanos, long zeroBasedOperation, long offeredRatePerSecond) {
    if (zeroBasedOperation < 0 || offeredRatePerSecond <= 0) {
      throw new IllegalArgumentException("operation index must be non-negative and rate positive");
    }
    BigInteger offset =
        BigInteger.valueOf(zeroBasedOperation)
            .multiply(NANOS_PER_SECOND)
            .divide(BigInteger.valueOf(offeredRatePerSecond));
    return Math.addExact(originNanos, offset.longValueExact());
  }

  public static long operationsFor(long offeredRatePerSecond, long durationNanos) {
    if (offeredRatePerSecond <= 0 || durationNanos <= 0) {
      throw new IllegalArgumentException("rate and duration must be positive");
    }
    BigInteger product =
        BigInteger.valueOf(offeredRatePerSecond).multiply(BigInteger.valueOf(durationNanos));
    return product.divide(NANOS_PER_SECOND).longValueExact();
  }
}
