package io.github.lchareln.cex.matching.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeron.Publication;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster;
import io.aeron.logbuffer.Header;
import io.github.lchareln.cex.matching.local.M08Command;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.agrona.concurrent.NoOpIdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

class M11BoundedProgressTest {
  @Test
  void deadlineClosedInterruptedAndComponentFailureAllFailClosed() {
    AtomicLong clock = new AtomicLong(100);
    M11BoundedProgress deadline =
        M11BoundedProgress.testing("deadline", Duration.ofNanos(5), clock::get, () -> null);
    deadline.checkpoint(false);
    clock.set(105);
    assertTrue(
        assertThrows(IllegalStateException.class, () -> deadline.checkpoint(false))
            .getMessage()
            .contains("timed out"));

    M11BoundedProgress closed =
        M11BoundedProgress.testing("closed", Duration.ofSeconds(1), () -> 0, () -> null);
    assertTrue(
        assertThrows(IllegalStateException.class, () -> closed.checkpoint(true))
            .getMessage()
            .contains("closed"));

    IllegalArgumentException componentFailure = new IllegalArgumentException("component");
    M11BoundedProgress failed =
        M11BoundedProgress.testing(
            "failed", Duration.ofSeconds(1), () -> 0, () -> componentFailure);
    assertSame(
        componentFailure,
        assertThrows(IllegalStateException.class, () -> failed.checkpoint(false)).getCause());

    M11BoundedProgress interrupted =
        M11BoundedProgress.testing("interrupted", Duration.ofSeconds(1), () -> 0, () -> null);
    Thread.currentThread().interrupt();
    try {
      assertTrue(
          assertThrows(IllegalStateException.class, () -> interrupted.checkpoint(false))
              .getMessage()
              .contains("interrupted"));
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void persistentEgressBackpressureIsBoundedAfterBusinessResultBinding() throws Exception {
    long shard = 97;
    M11ClusteredMatchingService service =
        new M11ClusteredMatchingService(
            shard, M11ApplicationObserver.NO_OP, Duration.ofNanos(1), () -> null);
    service.onStart(cluster(), null);
    M11CommandRequest request =
        new M11RequestCodec()
            .create(
                2,
                2,
                new UUID(1, 1),
                "bounded-egress",
                1,
                shard,
                1,
                new UUID(97, 1),
                new M08Command.Cancel("BTC-USDT", BigInteger.ONE));
    byte[] encoded = new M11RequestCodec().encode(request);

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                service.onSessionMessage(
                    backpressuredSession(),
                    0,
                    new UnsafeBuffer(encoded),
                    0,
                    encoded.length,
                    new Header(0, 16).buffer(new UnsafeBuffer(new byte[128])).offset(0)));

    assertTrue(failure.getMessage().contains("timed out"));
    assertEquals(2, service.nextApplicationSequence());
  }

  private static Cluster cluster() {
    return proxy(
        Cluster.class,
        (proxy, method, arguments) ->
            method.getName().equals("idleStrategy")
                ? NoOpIdleStrategy.INSTANCE
                : defaultValue(method.getReturnType()));
  }

  private static ClientSession backpressuredSession() {
    return proxy(
        ClientSession.class,
        (proxy, method, arguments) ->
            switch (method.getName()) {
              case "id" -> 1L;
              case "isClosing" -> false;
              case "offer" -> Publication.BACK_PRESSURED;
              default -> defaultValue(method.getReturnType());
            });
  }

  @SuppressWarnings("unchecked")
  private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
    return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
  }

  private static Object defaultValue(Class<?> type) {
    if (!type.isPrimitive()) {
      return null;
    }
    if (type == boolean.class) {
      return false;
    }
    if (type == long.class) {
      return 0L;
    }
    if (type == int.class) {
      return 0;
    }
    throw new AssertionError("unsupported proxy primitive: " + type);
  }
}
