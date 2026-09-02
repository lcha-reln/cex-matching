package io.github.lchareln.cex.matching.local;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Callback-free observation handle for one admitted owner-worker operation.
 *
 * <p>The handle deliberately does not implement {@link java.util.concurrent.Future} or {@link
 * java.util.concurrent.CompletionStage}. In particular, callers cannot attach an inline
 * continuation to the latency-critical owner worker, complete the handle themselves, or cancel
 * accepted work.
 */
public final class CompletionHandle<T> {
  private static final Object INCOMPLETE = new Object();

  private final AtomicReference<Object> result = new AtomicReference<>(INCOMPLETE);
  private final CountDownLatch completed = new CountDownLatch(1);

  CompletionHandle() {}

  /** Returns whether the terminal value has been published. */
  public boolean isDone() {
    return completed.getCount() == 0;
  }

  /** Waits interruptibly until the terminal value is published. */
  public T get() throws InterruptedException {
    completed.await();
    return completedValue();
  }

  /** Waits interruptibly for at most {@code timeout}; zero performs an immediate observation. */
  public T await(Duration timeout) throws InterruptedException, TimeoutException {
    Objects.requireNonNull(timeout, "timeout");
    if (timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must not be negative");
    }
    final long timeoutNanos;
    try {
      timeoutNanos = timeout.toNanos();
    } catch (ArithmeticException overflow) {
      throw new IllegalArgumentException("timeout is too large", overflow);
    }
    if (!completed.await(timeoutNanos, TimeUnit.NANOSECONDS)) {
      throw new TimeoutException("completion was not published within " + timeout);
    }
    return completedValue();
  }

  void complete(T value) {
    Objects.requireNonNull(value, "value");
    if (!result.compareAndSet(INCOMPLETE, value)) {
      throw new IllegalStateException("completion handle already has a terminal value");
    }
    completed.countDown();
  }

  @SuppressWarnings("unchecked")
  private T completedValue() {
    Object value = result.get();
    if (value == INCOMPLETE) {
      throw new IllegalStateException("completion latch opened without a terminal value");
    }
    return (T) value;
  }
}
