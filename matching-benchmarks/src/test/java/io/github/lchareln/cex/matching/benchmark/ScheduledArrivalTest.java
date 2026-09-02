package io.github.lchareln.cex.matching.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ScheduledArrivalTest {
  @Test
  void usesAnExactRationalOffsetFromTheOriginalSchedule() {
    assertEquals(1_000, ScheduledArrival.at(1_000, 0, 3));
    assertEquals(333_334_333L, ScheduledArrival.at(1_000, 1, 3));
    assertEquals(666_667_666L, ScheduledArrival.at(1_000, 2, 3));
    assertEquals(1_000_001_000L, ScheduledArrival.at(1_000, 3, 3));
    assertEquals(45, ScheduledArrival.operationsFor(15, 3_000_000_000L));
  }

  @Test
  void rejectsInvalidScheduleInputsAndOverflow() {
    assertThrows(IllegalArgumentException.class, () -> ScheduledArrival.at(0, -1, 1));
    assertThrows(IllegalArgumentException.class, () -> ScheduledArrival.at(0, 1, 0));
    assertThrows(ArithmeticException.class, () -> ScheduledArrival.at(Long.MAX_VALUE, 1, 1));
  }

  @Test
  void interruptedScheduledWaitStopsBeforeTheNextAdmissionContinuation() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    AtomicBoolean passedWait = new AtomicBoolean();
    AtomicBoolean interruptionObserved = new AtomicBoolean();
    AtomicBoolean interruptStatusPreserved = new AtomicBoolean();
    Thread producer =
        Thread.ofPlatform()
            .unstarted(
                () -> {
                  started.countDown();
                  try {
                    LocalServiceLoadPoint.awaitScheduledArrival(
                        System.nanoTime() + Duration.ofSeconds(10).toNanos());
                    passedWait.set(true);
                  } catch (InterruptedException expected) {
                    interruptionObserved.set(true);
                    interruptStatusPreserved.set(Thread.currentThread().isInterrupted());
                  }
                });

    producer.start();
    assertTrue(started.await(1, TimeUnit.SECONDS));
    producer.interrupt();
    producer.join(Duration.ofSeconds(1));

    assertFalse(producer.isAlive());
    assertFalse(passedWait.get());
    assertTrue(interruptionObserved.get());
    assertTrue(interruptStatusPreserved.get());
  }

  @Test
  void cleanupCollectorRunsLaterActionsAndPreservesSuppressedFailures() {
    LocalServiceLoadPoint.CleanupFailures cleanup = new LocalServiceLoadPoint.CleanupFailures();
    AtomicInteger actions = new AtomicInteger();
    IllegalStateException primary = new IllegalStateException("producer cleanup failed");
    IOException secondary = new IOException("sampler cleanup failed");

    cleanup.run(
        () -> {
          actions.incrementAndGet();
          throw primary;
        });
    cleanup.run(actions::incrementAndGet);
    cleanup.run(
        () -> {
          actions.incrementAndGet();
          throw secondary;
        });

    IllegalStateException thrown = assertThrows(IllegalStateException.class, cleanup::throwIfAny);
    assertSame(primary, thrown);
    assertEquals(3, actions.get());
    assertEquals(1, thrown.getSuppressed().length);
    assertSame(secondary, thrown.getSuppressed()[0]);
  }
}
