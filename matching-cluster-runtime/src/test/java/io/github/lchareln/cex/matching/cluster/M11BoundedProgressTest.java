package io.github.lchareln.cex.matching.cluster;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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
  void persistentEgressBackpressureBecomesUndeliveredWitnessAfterBusinessResultBinding()
      throws Exception {
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

    assertDoesNotThrow(
        () ->
            service.onSessionMessage(
                backpressuredSession(), 0, new UnsafeBuffer(encoded), 0, encoded.length, header()));

    assertEquals(2, service.nextApplicationSequence());
    assertEquals(1, service.undeliveredEgressResponses());
    assertEquals("PROGRESS_TIMEOUT", service.lastUndeliveredEgressReason());
  }

  @Test
  void healthyTransientBackpressureStillRetriesUntilEgressSucceeds() throws Exception {
    M11ClusteredMatchingService service = new M11ClusteredMatchingService(97);
    service.onStart(cluster(), null);
    AtomicInteger offers = new AtomicInteger();
    ClientSession session =
        session(() -> offers.getAndIncrement() == 0 ? Publication.BACK_PRESSURED : 128L, false);

    send(service, request(new UUID(2, 1), new UUID(97, 2), 1), session);

    assertEquals(2, offers.get());
    assertEquals(0, service.undeliveredEgressResponses());
    assertEquals(2, service.nextApplicationSequence());
  }

  @Test
  void terminalPublicationAndClosingSessionDoNotCrashCommittedService() throws Exception {
    M11ClusteredMatchingService service = new M11ClusteredMatchingService(97);
    service.onStart(cluster(), null);

    send(
        service,
        request(new UUID(3, 1), new UUID(97, 3), 1),
        session(() -> Publication.NOT_CONNECTED, false));
    assertEquals("PUBLICATION_NOT_CONNECTED", service.lastUndeliveredEgressReason());

    send(service, request(new UUID(3, 2), new UUID(97, 4), 2), session(() -> 256L, true));
    assertEquals("SESSION_CLOSING", service.lastUndeliveredEgressReason());
    assertEquals(2, service.undeliveredEgressResponses());
    assertEquals(3, service.nextApplicationSequence());
  }

  @Test
  void closedMaxPositionAndComponentFailureAreDiagnosticsNotServiceFailures() throws Exception {
    assertUndelivered(Publication.CLOSED, "PUBLICATION_CLOSED");
    assertUndelivered(Publication.MAX_POSITION_EXCEEDED, "PUBLICATION_MAX_POSITION_EXCEEDED");

    IllegalArgumentException component = new IllegalArgumentException("driver stopped");
    M11ClusteredMatchingService failedComponent =
        new M11ClusteredMatchingService(
            97, M11ApplicationObserver.NO_OP, Duration.ofSeconds(1), () -> component);
    failedComponent.onStart(cluster(), null);
    send(
        failedComponent, request(new UUID(31, 1), new UUID(97, 31), 1), session(() -> 256L, false));
    assertEquals(
        "COMPONENT_FAILURE:IllegalArgumentException",
        failedComponent.lastUndeliveredEgressReason());
    assertEquals(1, failedComponent.undeliveredEgressResponses());
    assertEquals(2, failedComponent.nextApplicationSequence());
  }

  @Test
  void retryAfterUndeliveredEgressReplaysTheBoundResultWithoutSecondEffect() throws Exception {
    List<M11ApplicationResult> applications = new ArrayList<>();
    M11ClusteredMatchingService service =
        new M11ClusteredMatchingService(
            97, observation -> applications.add(observation.applicationResult()));
    service.onStart(cluster(), null);
    M11CommandRequest first = request(new UUID(4, 1), new UUID(97, 5), 1);

    send(service, first, session(() -> Publication.NOT_CONNECTED, false));
    send(service, first.withCorrelationId(new UUID(4, 2)), session(() -> 512L, false));

    assertEquals(2, applications.size());
    assertEquals(M11ResponseStatus.NEW_APPLIED, applications.get(0).response().status());
    assertEquals(M11ResponseStatus.DUPLICATE_REPLAYED, applications.get(1).response().status());
    assertEquals(
        applications.get(0).response().applicationSequence(),
        applications.get(1).response().applicationSequence());
    assertEquals(
        applications.get(0).response().resultDigest(),
        applications.get(1).response().resultDigest());
    assertEquals(2, service.nextApplicationSequence());
    assertEquals(1, service.undeliveredEgressResponses());
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

  private static ClientSession session(java.util.function.LongSupplier offer, boolean closing) {
    return proxy(
        ClientSession.class,
        (proxy, method, arguments) ->
            switch (method.getName()) {
              case "id" -> 1L;
              case "isClosing" -> closing;
              case "offer" -> offer.getAsLong();
              default -> defaultValue(method.getReturnType());
            });
  }

  private static M11CommandRequest request(UUID correlation, UUID command, long sequence)
      throws M11ProtocolException {
    return new M11RequestCodec()
        .create(
            2,
            2,
            correlation,
            "egress-witness",
            1,
            97,
            sequence,
            command,
            new M08Command.Cancel("BTC-USDT", BigInteger.valueOf(sequence)));
  }

  private static void assertUndelivered(long publicationResult, String reason) throws Exception {
    M11ClusteredMatchingService service = new M11ClusteredMatchingService(97);
    service.onStart(cluster(), null);
    send(
        service,
        request(
            new UUID(30, Math.abs(publicationResult)),
            new UUID(97, Math.abs(publicationResult)),
            1),
        session(() -> publicationResult, false));
    assertEquals(reason, service.lastUndeliveredEgressReason());
    assertEquals(1, service.undeliveredEgressResponses());
    assertEquals(2, service.nextApplicationSequence());
  }

  private static void send(
      M11ClusteredMatchingService service, M11CommandRequest request, ClientSession session) {
    byte[] encoded = new M11RequestCodec().encode(request);
    assertDoesNotThrow(
        () ->
            service.onSessionMessage(
                session, 0, new UnsafeBuffer(encoded), 0, encoded.length, header()));
  }

  private static Header header() {
    return new Header(0, 16).buffer(new UnsafeBuffer(new byte[128])).offset(0);
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
