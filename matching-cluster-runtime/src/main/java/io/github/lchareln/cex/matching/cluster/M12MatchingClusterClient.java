package io.github.lchareln.cex.matching.cluster;

import io.aeron.Publication;
import io.aeron.cluster.client.AeronCluster;
import io.aeron.cluster.client.EgressListener;
import io.aeron.cluster.codecs.EventCode;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.logbuffer.Header;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Single-thread-owned M12 client with an explicit accepted-but-unacknowledged UNKNOWN boundary.
 *
 * <p>The M11 request and response codecs remain unchanged. All M12 invocation state and authority
 * metadata stays outside the replicated business protocol.
 */
public final class M12MatchingClusterClient implements AutoCloseable {
  private static final Supplier<Throwable> NO_CLUSTER_FAILURE = () -> null;

  private final long clientGeneration;
  private final Supplier<? extends Throwable> clusterFailure;
  private final ConcurrentLinkedQueue<Throwable> componentErrors;
  private final MediaDriver mediaDriver;
  private final AeronCluster cluster;
  private final Listener listener;
  private final IdleStrategy idleStrategy = new BackoffIdleStrategy();
  private final M11RequestCodec requestCodec = new M11RequestCodec();
  private final Map<UUID, M12InvocationAttempt> active = new HashMap<>();
  private final Set<UUID> seenCorrelations = new HashSet<>();
  private long ingressOffersAccepted;
  private long egressResponsesDecoded;
  private long rejectedEgressResponses;
  private boolean closed;

  private M12MatchingClusterClient(
      long clientGeneration,
      Supplier<? extends Throwable> clusterFailure,
      ConcurrentLinkedQueue<Throwable> componentErrors,
      MediaDriver mediaDriver,
      AeronCluster cluster,
      Listener listener) {
    this.clientGeneration = clientGeneration;
    this.clusterFailure = clusterFailure;
    this.componentErrors = componentErrors;
    this.mediaDriver = mediaDriver;
    this.cluster = cluster;
    this.listener = listener;
  }

  public static M12MatchingClusterClient connect(
      Path clientAeronDirectory,
      String ingressEndpoints,
      Duration messageTimeout,
      long clientGeneration) {
    return connect(
        clientAeronDirectory,
        ingressEndpoints,
        messageTimeout,
        clientGeneration,
        NO_CLUSTER_FAILURE);
  }

  public static M12MatchingClusterClient connect(
      Path clientAeronDirectory,
      String ingressEndpoints,
      Duration messageTimeout,
      long clientGeneration,
      Supplier<? extends Throwable> clusterFailure) {
    Objects.requireNonNull(clientAeronDirectory, "clientAeronDirectory");
    Objects.requireNonNull(ingressEndpoints, "ingressEndpoints");
    Objects.requireNonNull(messageTimeout, "messageTimeout");
    Objects.requireNonNull(clusterFailure, "clusterFailure");
    if (ingressEndpoints.isBlank()) {
      throw new IllegalArgumentException("ingressEndpoints must not be blank");
    }
    if (messageTimeout.isZero() || messageTimeout.isNegative()) {
      throw new IllegalArgumentException("messageTimeout must be positive");
    }
    if (clientGeneration <= 0) {
      throw new IllegalArgumentException("clientGeneration must be positive");
    }

    ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
    Listener listener = new Listener(clientGeneration);
    MediaDriver mediaDriver =
        MediaDriver.launchEmbedded(
            new MediaDriver.Context()
                .aeronDirectoryName(clientAeronDirectory.toAbsolutePath().normalize().toString())
                .dirDeleteOnStart(true)
                .dirDeleteOnShutdown(true)
                .threadingMode(ThreadingMode.SHARED)
                .errorHandler(errors::add));
    try {
      AeronCluster cluster =
          AeronCluster.connect(
              new AeronCluster.Context()
                  .aeronDirectoryName(clientAeronDirectory.toAbsolutePath().normalize().toString())
                  .ingressEndpoints(ingressEndpoints)
                  .ingressChannel("aeron:udp")
                  .egressChannel("aeron:udp?endpoint=127.0.0.1:0")
                  .messageTimeoutNs(messageTimeout.toNanos())
                  .egressListener(listener)
                  .errorHandler(errors::add));
      listener.installInitialAuthority(
          authority(
              clientGeneration,
              cluster.clusterSessionId(),
              cluster.leadershipTermId(),
              cluster.leaderMemberId()));
      return new M12MatchingClusterClient(
          clientGeneration, clusterFailure, errors, mediaDriver, cluster, listener);
    } catch (RuntimeException | Error failure) {
      mediaDriver.close();
      throw failure;
    }
  }

  /** Offers a new attempt, returning only after it is accepted or known not to be submitted. */
  public M12InvocationAttempt offer(
      M11CommandRequest request, long attemptOrdinal, Duration timeout) {
    return offer(M12InvocationAttempt.first(request, attemptOrdinal, clientGeneration), timeout);
  }

  /** Offers an existing first/retry attempt created for this client generation. */
  public M12InvocationAttempt offer(M12InvocationAttempt attempt, Duration timeout) {
    requireOwner(attempt);
    long deadline = deadline(timeout);
    if (attempt.phase() != M12InvocationPhase.OFFERING) {
      throw new IllegalArgumentException("attempt has already crossed its offer boundary");
    }
    if (!seenCorrelations.add(attempt.correlationId())) {
      throw new IllegalArgumentException("correlationId must be fresh within a client generation");
    }
    active.put(attempt.correlationId(), attempt);
    byte[] encoded = requestCodec.encode(attempt.request());
    UnsafeBuffer buffer = new UnsafeBuffer(encoded);
    idleStrategy.reset();
    while (attempt.phase() == M12InvocationPhase.OFFERING) {
      Optional<M12UnknownReason> preOfferFailure = failureReason();
      if (preOfferFailure.isPresent()) {
        finish(attempt, preOfferFailure.orElseThrow());
        break;
      }
      final long offered;
      try {
        offered = cluster.offer(buffer, 0, encoded.length);
      } catch (RuntimeException failure) {
        componentErrors.add(failure);
        finish(attempt, M12UnknownReason.CLIENT_COMPONENT_FAILED);
        break;
      }
      if (offered >= 0) {
        attempt.onOfferAccepted(offered, listener.currentAuthority());
        ingressOffersAccepted++;
        break;
      }
      if (offered == Publication.CLOSED) {
        finish(attempt, M12UnknownReason.PUBLICATION_CLOSED);
        break;
      }
      if (offered == Publication.MAX_POSITION_EXCEEDED) {
        finish(attempt, M12UnknownReason.PUBLICATION_MAX_POSITION);
        break;
      }
      if (offered != Publication.BACK_PRESSURED
          && offered != Publication.ADMIN_ACTION
          && offered != Publication.NOT_CONNECTED) {
        finish(attempt, M12UnknownReason.PUBLICATION_FAILED);
        break;
      }
      pollAndDispatch();
      if (System.nanoTime() >= deadline) {
        finish(attempt, M12UnknownReason.OFFER_TIMEOUT);
        break;
      }
      idleStrategy.idle();
    }
    cleanupTerminal(attempt);
    return attempt;
  }

  /**
   * Polls until a valid current-authority response is buffered or the accepted invocation becomes
   * UNKNOWN. Buffering alone is intentionally not an acknowledgement.
   */
  public boolean awaitResponseBuffered(M12InvocationAttempt attempt, Duration timeout) {
    requireRegistered(attempt);
    long deadline = deadline(timeout);
    idleStrategy.reset();
    while (true) {
      if (attempt.responseBuffered()) {
        return true;
      }
      if (attempt.outcome().isPresent()) {
        cleanupTerminal(attempt);
        return false;
      }
      pollAndDispatch();
      if (attempt.responseBuffered()) {
        return true;
      }
      if (attempt.outcome().isPresent()) {
        cleanupTerminal(attempt);
        return false;
      }
      if (System.nanoTime() >= deadline) {
        finish(attempt, M12UnknownReason.RESPONSE_TIMEOUT);
        cleanupTerminal(attempt);
        return false;
      }
      idleStrategy.idle();
    }
  }

  /** Delivers a buffered response to the caller and crosses the ACKNOWLEDGED boundary. */
  public M12InvocationOutcome acknowledge(M12InvocationAttempt attempt) {
    requireRegistered(attempt);
    M12InvocationOutcome outcome = attempt.acknowledgeBuffered();
    cleanupTerminal(attempt);
    return outcome;
  }

  /** Abandons delivery; an accepted offer remains UNKNOWN even when a response was buffered. */
  public M12InvocationOutcome abandon(M12InvocationAttempt attempt) {
    requireRegistered(attempt);
    M12InvocationOutcome outcome = attempt.abandon();
    cleanupTerminal(attempt);
    return outcome;
  }

  public M12InvocationOutcome submit(
      M11CommandRequest request,
      long attemptOrdinal,
      Duration offerTimeout,
      Duration responseTimeout) {
    M12InvocationAttempt attempt = offer(request, attemptOrdinal, offerTimeout);
    if (!attempt.offerAccepted()) {
      return attempt.outcome().orElseThrow();
    }
    if (!awaitResponseBuffered(attempt, responseTimeout)) {
      return attempt.outcome().orElseThrow();
    }
    return acknowledge(attempt);
  }

  /** Marks pending attempts conservatively when the external fault controller sees a child exit. */
  public void onClusterProcessExit() {
    finishActive(M12UnknownReason.PROCESS_EXITED);
  }

  /** Polls one bounded application step so a harness can observe topology changes explicitly. */
  public int pollOnce() {
    requireOpen();
    return pollAndDispatch();
  }

  public M12TransportAuthority currentAuthority() {
    requireOpen();
    return listener.currentAuthority();
  }

  public long clientGeneration() {
    return clientGeneration;
  }

  public long ingressOffersAccepted() {
    return ingressOffersAccepted;
  }

  public long egressResponsesDecoded() {
    return egressResponsesDecoded;
  }

  public long rejectedEgressResponses() {
    return rejectedEgressResponses;
  }

  public List<Throwable> componentErrors() {
    return List.copyOf(componentErrors);
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    finishActive(M12UnknownReason.SESSION_CLOSED);
    try {
      cluster.close();
    } finally {
      mediaDriver.close();
    }
  }

  private int pollAndDispatch() {
    requireOpen();
    final int workCount;
    try {
      workCount = cluster.pollEgress();
    } catch (RuntimeException failure) {
      componentErrors.add(failure);
      finishActive(M12UnknownReason.CLIENT_COMPONENT_FAILED);
      return 0;
    }
    ListenerEvent event;
    while ((event = listener.events.pollFirst()) != null) {
      if (event instanceof ResponseEvent responseEvent) {
        onResponse(responseEvent);
      } else if (event instanceof LeaderEvent) {
        finishAccepted(M12UnknownReason.LEADER_CHANGED);
      } else if (event instanceof SessionEvent sessionEvent) {
        if (sessionEvent.code() == EventCode.CLOSED
            || sessionEvent.code() == EventCode.ERROR
            || sessionEvent.code() == EventCode.AUTHENTICATION_REJECTED) {
          finishActive(M12UnknownReason.SESSION_CLOSED);
        }
      } else if (event instanceof ProtocolEvent || event instanceof AuthorityViolationEvent) {
        rejectedEgressResponses++;
        finishAccepted(M12UnknownReason.INVALID_EGRESS);
      }
    }
    Optional<M12UnknownReason> failureReason = failureReason();
    failureReason.ifPresent(this::finishActive);
    idleStrategy.idle(workCount);
    return workCount;
  }

  private void onResponse(ResponseEvent event) {
    M12InvocationAttempt attempt = active.get(event.response().correlationId());
    if (attempt == null || attempt.phase() == M12InvocationPhase.TERMINAL) {
      rejectedEgressResponses++;
      return;
    }
    M12InvocationPhase before = attempt.phase();
    attempt.onResponse(event.response(), event.authority());
    if (attempt.phase() == M12InvocationPhase.RESPONSE_BUFFERED) {
      egressResponsesDecoded++;
    } else if (before != M12InvocationPhase.TERMINAL) {
      rejectedEgressResponses++;
      cleanupTerminal(attempt);
    }
  }

  private Optional<M12UnknownReason> failureReason() {
    if (cluster.isClosed()) {
      return Optional.of(M12UnknownReason.SESSION_CLOSED);
    }
    if (componentErrors.peek() != null) {
      return Optional.of(M12UnknownReason.CLIENT_COMPONENT_FAILED);
    }
    final Throwable observedClusterFailure;
    try {
      observedClusterFailure = clusterFailure.get();
    } catch (RuntimeException failure) {
      componentErrors.add(failure);
      return Optional.of(M12UnknownReason.CLUSTER_COMPONENT_FAILED);
    }
    return observedClusterFailure == null
        ? Optional.empty()
        : Optional.of(M12UnknownReason.CLUSTER_COMPONENT_FAILED);
  }

  private void finishAccepted(M12UnknownReason reason) {
    List.copyOf(active.values()).stream()
        .filter(M12InvocationAttempt::offerAccepted)
        .forEach(attempt -> finish(attempt, reason));
  }

  private void finishActive(M12UnknownReason reason) {
    List.copyOf(active.values()).forEach(attempt -> finish(attempt, reason));
  }

  private void finish(M12InvocationAttempt attempt, M12UnknownReason reason) {
    attempt.finishUnacknowledged(reason);
    cleanupTerminal(attempt);
  }

  private void cleanupTerminal(M12InvocationAttempt attempt) {
    if (attempt.outcome().isPresent()) {
      active.remove(attempt.correlationId(), attempt);
    }
  }

  private void requireRegistered(M12InvocationAttempt attempt) {
    requireOwner(attempt);
    if (attempt.outcome().isPresent()) {
      return;
    }
    if (active.get(attempt.correlationId()) != attempt) {
      throw new IllegalArgumentException("attempt is not active on this client");
    }
  }

  private void requireOwner(M12InvocationAttempt attempt) {
    requireOpen();
    Objects.requireNonNull(attempt, "attempt");
    if (attempt.clientGeneration() != clientGeneration) {
      throw new IllegalArgumentException("attempt belongs to another client generation");
    }
  }

  private void requireOpen() {
    if (closed) {
      throw new IllegalStateException("M12 client is closed");
    }
  }

  private static long deadline(Duration timeout) {
    Objects.requireNonNull(timeout, "timeout");
    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must be positive");
    }
    try {
      return Math.addExact(System.nanoTime(), timeout.toNanos());
    } catch (ArithmeticException failure) {
      throw new IllegalArgumentException("timeout is too large", failure);
    }
  }

  private static M12TransportAuthority authority(
      long generation, long sessionId, long leadershipTermId, int leaderMemberId) {
    return new M12TransportAuthority(generation, sessionId, leadershipTermId, leaderMemberId);
  }

  private sealed interface ListenerEvent
      permits AuthorityViolationEvent, LeaderEvent, ProtocolEvent, ResponseEvent, SessionEvent {}

  private record AuthorityViolationEvent(String detail) implements ListenerEvent {}

  private record ResponseEvent(M11CommandResponse response, M12TransportAuthority authority)
      implements ListenerEvent {}

  private record LeaderEvent(M12TransportAuthority previous, M12TransportAuthority current)
      implements ListenerEvent {}

  private record SessionEvent(EventCode code) implements ListenerEvent {}

  private record ProtocolEvent(M11ProtocolException failure) implements ListenerEvent {}

  private static final class Listener implements EgressListener {
    private final long clientGeneration;
    private final M11ResponseCodec responseCodec = new M11ResponseCodec();
    private final ArrayDeque<ListenerEvent> events = new ArrayDeque<>();
    private M12TransportAuthority currentAuthority;

    private Listener(long clientGeneration) {
      this.clientGeneration = clientGeneration;
    }

    void installInitialAuthority(M12TransportAuthority authority) {
      Objects.requireNonNull(authority, "authority");
      if (currentAuthority == null) {
        currentAuthority = authority;
      } else if (!currentAuthority.equals(authority)) {
        throw new IllegalStateException("Aeron connect authority changed before installation");
      }
    }

    M12TransportAuthority currentAuthority() {
      return Objects.requireNonNull(currentAuthority, "Aeron authority is not installed");
    }

    @Override
    public void onMessage(
        long clusterSessionId,
        long timestamp,
        DirectBuffer buffer,
        int offset,
        int length,
        Header header) {
      byte[] encoded = new byte[length];
      buffer.getBytes(offset, encoded);
      try {
        M12TransportAuthority current = currentAuthority();
        M12TransportAuthority responseAuthority =
            authority(
                clientGeneration,
                clusterSessionId,
                current.leadershipTermId(),
                current.leaderMemberId());
        events.addLast(
            new ResponseEvent(responseCodec.decodeCanonical(encoded), responseAuthority));
      } catch (M11ProtocolException failure) {
        events.addLast(new ProtocolEvent(failure));
      }
    }

    @Override
    public void onSessionEvent(
        long correlationId,
        long clusterSessionId,
        long leadershipTermId,
        int leaderMemberId,
        EventCode code,
        String detail) {
      events.addLast(new SessionEvent(code));
    }

    @Override
    public void onNewLeader(
        long clusterSessionId, long leadershipTermId, int leaderMemberId, String ingressEndpoints) {
      M12TransportAuthority next =
          authority(clientGeneration, clusterSessionId, leadershipTermId, leaderMemberId);
      if (currentAuthority == null) {
        currentAuthority = next;
        return;
      }
      M12TransportAuthority previous = currentAuthority();
      if (next.equals(previous)) {
        return;
      }
      if (next.leadershipTermId() <= previous.leadershipTermId()) {
        events.addLast(
            new AuthorityViolationEvent(
                "non-increasing leadership term "
                    + previous.leadershipTermId()
                    + " -> "
                    + next.leadershipTermId()));
        return;
      }
      currentAuthority = next;
      events.addLast(new LeaderEvent(previous, next));
    }
  }
}
