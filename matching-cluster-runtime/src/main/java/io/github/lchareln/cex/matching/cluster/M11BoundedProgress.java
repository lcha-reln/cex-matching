package io.github.lchareln.cex.matching.cluster;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Wall-clock-only liveness guard for Aeron transport work outside deterministic business state. */
final class M11BoundedProgress {
  static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);

  private static final Supplier<Throwable> NO_COMPONENT_FAILURE = () -> null;

  private final String operation;
  private final long startedAtNanos;
  private final long timeoutNanos;
  private final LongSupplier nanoClock;
  private final Supplier<? extends Throwable> componentFailure;

  private M11BoundedProgress(
      String operation,
      Duration timeout,
      LongSupplier nanoClock,
      Supplier<? extends Throwable> componentFailure) {
    this.operation = requireOperation(operation);
    Objects.requireNonNull(timeout, "timeout");
    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("M11 progress timeout must be positive");
    }
    try {
      timeoutNanos = timeout.toNanos();
    } catch (ArithmeticException failure) {
      throw new IllegalArgumentException("M11 progress timeout is too large", failure);
    }
    this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    this.componentFailure = Objects.requireNonNull(componentFailure, "componentFailure");
    startedAtNanos = nanoClock.getAsLong();
  }

  static M11BoundedProgress start(
      String operation, Duration timeout, Supplier<? extends Throwable> componentFailure) {
    return new M11BoundedProgress(operation, timeout, System::nanoTime, componentFailure);
  }

  static M11BoundedProgress start(String operation, Duration timeout) {
    return start(operation, timeout, NO_COMPONENT_FAILURE);
  }

  static M11BoundedProgress testing(
      String operation,
      Duration timeout,
      LongSupplier nanoClock,
      Supplier<? extends Throwable> componentFailure) {
    return new M11BoundedProgress(operation, timeout, nanoClock, componentFailure);
  }

  void checkpoint(boolean closed) {
    if (Thread.currentThread().isInterrupted()) {
      throw new IllegalStateException(operation + " was interrupted");
    }
    Throwable failure = componentFailure.get();
    if (failure != null) {
      throw new IllegalStateException(operation + " observed an Aeron component failure", failure);
    }
    if (closed) {
      throw new IllegalStateException(operation + " observed a closed Aeron resource");
    }
    if (nanoClock.getAsLong() - startedAtNanos >= timeoutNanos) {
      throw new IllegalStateException(operation + " timed out without completion");
    }
  }

  private static String requireOperation(String operation) {
    Objects.requireNonNull(operation, "operation");
    if (operation.isBlank()) {
      throw new IllegalArgumentException("M11 progress operation must not be blank");
    }
    return operation;
  }
}
