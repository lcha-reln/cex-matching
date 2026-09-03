package io.github.lchareln.cex.matching.cluster;

import io.aeron.Publication;
import io.aeron.cluster.client.AeronCluster;
import io.aeron.cluster.client.EgressListener;
import io.aeron.cluster.codecs.AdminRequestType;
import io.aeron.cluster.codecs.AdminResponseCode;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.logbuffer.Header;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;

/** Single-thread-owned Cluster client that treats only correlated egress as business completion. */
public final class M11MatchingClusterClient implements AutoCloseable {
  private final M11SingleNodeConfig config;
  private final ConcurrentLinkedQueue<Throwable> componentErrors;
  private final Supplier<? extends Throwable> nodeFailure;
  private final MediaDriver mediaDriver;
  private final AeronCluster cluster;
  private final Listener listener;
  private final IdleStrategy idleStrategy = new BackoffIdleStrategy();
  private final M11RequestCodec requestCodec = new M11RequestCodec();
  private final AtomicLong nextAdminCorrelation = new AtomicLong(1);
  private long ingressOffersAccepted;
  private long egressResponsesDecoded;
  private long snapshotAdminAccepted;

  private M11MatchingClusterClient(
      M11SingleNodeConfig config,
      ConcurrentLinkedQueue<Throwable> componentErrors,
      Supplier<? extends Throwable> nodeFailure,
      MediaDriver mediaDriver,
      AeronCluster cluster,
      Listener listener) {
    this.config = config;
    this.componentErrors = componentErrors;
    this.nodeFailure = nodeFailure;
    this.mediaDriver = mediaDriver;
    this.cluster = cluster;
    this.listener = listener;
  }

  static M11MatchingClusterClient connect(
      M11SingleNodeConfig config, Supplier<? extends Throwable> nodeFailure) {
    Objects.requireNonNull(nodeFailure, "nodeFailure");
    ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
    Listener listener = new Listener();
    MediaDriver mediaDriver =
        MediaDriver.launchEmbedded(
            new MediaDriver.Context()
                .aeronDirectoryName(config.clientAeronDirectory().toString())
                .dirDeleteOnStart(true)
                .dirDeleteOnShutdown(true)
                .threadingMode(ThreadingMode.SHARED)
                .errorHandler(errors::add));
    try {
      AeronCluster cluster =
          AeronCluster.connect(
              new AeronCluster.Context()
                  .aeronDirectoryName(config.clientAeronDirectory().toString())
                  .ingressEndpoints(config.ingressEndpoints())
                  .ingressChannel("aeron:udp")
                  .egressChannel("aeron:udp?endpoint=127.0.0.1:0")
                  .messageTimeoutNs(config.clientMessageTimeout().toNanos())
                  .egressListener(listener)
                  .errorHandler(errors::add));
      return new M11MatchingClusterClient(
          config, errors, nodeFailure, mediaDriver, cluster, listener);
    } catch (RuntimeException | Error failure) {
      mediaDriver.close();
      throw failure;
    }
  }

  public M11CommandResponse submit(M11CommandRequest request, Duration timeout) {
    Objects.requireNonNull(request, "request");
    long deadline = deadline(timeout);
    UUID correlation = request.correlationId();
    if (listener.responses.containsKey(correlation)) {
      throw new IllegalArgumentException("correlationId has already completed on this client");
    }
    byte[] encoded = requestCodec.encode(request);
    UnsafeBuffer buffer = new UnsafeBuffer(encoded);
    idleStrategy.reset();
    while (true) {
      long offered = cluster.offer(buffer, 0, encoded.length);
      if (offered >= 0) {
        ingressOffersAccepted++;
        break;
      }
      if (offered != Publication.BACK_PRESSURED
          && offered != Publication.ADMIN_ACTION
          && offered != Publication.NOT_CONNECTED) {
        throw new IllegalStateException(
            "M11 ingress publication failed: " + Publication.errorString(offered));
      }
      pollAndCheck();
      requireBefore(deadline, "M11 ingress offer timed out");
      idleStrategy.idle();
    }

    idleStrategy.reset();
    while (true) {
      pollAndCheck();
      M11CommandResponse response = listener.responses.remove(correlation);
      if (response != null) {
        egressResponsesDecoded++;
        return response;
      }
      requireBefore(deadline, "M11 correlated response timed out");
      idleStrategy.idle();
    }
  }

  public M11SnapshotAdminAcceptance requestSnapshot(Duration timeout) {
    long deadline = deadline(timeout);
    long correlation = nextAdminCorrelation.getAndIncrement();
    idleStrategy.reset();
    while (!cluster.sendAdminRequestToTakeASnapshot(correlation)) {
      pollAndCheck();
      requireBefore(deadline, "M11 snapshot admin request timed out");
      idleStrategy.idle();
    }
    while (true) {
      pollAndCheck();
      AdminResponse response = listener.adminResponses.remove(correlation);
      if (response != null) {
        if (response.code() != AdminResponseCode.OK) {
          throw new IllegalStateException(
              "M11 snapshot admin request failed: " + response.code() + ":" + response.message());
        }
        if (response.requestType() != AdminRequestType.SNAPSHOT) {
          throw new IllegalStateException(
              "M11 snapshot correlation received another admin response type");
        }
        snapshotAdminAccepted++;
        return new M11SnapshotAdminAcceptance(
            correlation,
            M11SnapshotAdminRequestType.SNAPSHOT,
            M11SnapshotAdminResponseCode.OK,
            response.message());
      }
      requireBefore(deadline, "M11 snapshot admin response timed out");
      idleStrategy.idle();
    }
  }

  public List<Throwable> componentErrors() {
    return List.copyOf(componentErrors);
  }

  public long ingressOffersAccepted() {
    return ingressOffersAccepted;
  }

  public long egressResponsesDecoded() {
    return egressResponsesDecoded;
  }

  public long snapshotAdminAccepted() {
    return snapshotAdminAccepted;
  }

  @Override
  public void close() {
    try {
      cluster.close();
    } finally {
      mediaDriver.close();
    }
  }

  private void pollAndCheck() {
    int workCount = cluster.pollEgress();
    M11ProtocolException protocolFailure = listener.protocolFailure;
    if (protocolFailure != null) {
      throw new IllegalStateException("M11 client received an invalid response", protocolFailure);
    }
    Throwable componentFailure = componentErrors.peek();
    if (componentFailure != null) {
      throw new IllegalStateException("M11 client component failed", componentFailure);
    }
    Throwable nodeComponentFailure = nodeFailure.get();
    if (nodeComponentFailure != null) {
      throw new IllegalStateException("M11 cluster component failed", nodeComponentFailure);
    }
    idleStrategy.idle(workCount);
  }

  private static long deadline(Duration timeout) {
    Objects.requireNonNull(timeout, "timeout");
    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must be positive");
    }
    return Math.addExact(System.nanoTime(), timeout.toNanos());
  }

  private static void requireBefore(long deadline, String message) {
    if (System.nanoTime() >= deadline) {
      throw new IllegalStateException(message);
    }
  }

  private static final class Listener implements EgressListener {
    private final M11ResponseCodec responseCodec = new M11ResponseCodec();
    private final Map<UUID, M11CommandResponse> responses = new HashMap<>();
    private final Map<Long, AdminResponse> adminResponses = new HashMap<>();
    private M11ProtocolException protocolFailure;

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
        M11CommandResponse response = responseCodec.decodeCanonical(encoded);
        M11CommandResponse previous = responses.put(response.correlationId(), response);
        if (previous != null) {
          protocolFailure =
              new M11ProtocolException(
                  M11ProtocolException.Code.NON_CANONICAL, "duplicate egress response correlation");
        }
      } catch (M11ProtocolException failure) {
        protocolFailure = failure;
      }
    }

    @Override
    public void onAdminResponse(
        long clusterSessionId,
        long correlationId,
        AdminRequestType requestType,
        AdminResponseCode responseCode,
        String message,
        DirectBuffer payload,
        int payloadOffset,
        int payloadLength) {
      adminResponses.put(correlationId, new AdminResponse(requestType, responseCode, message));
    }
  }

  private record AdminResponse(
      AdminRequestType requestType, AdminResponseCode code, String message) {}
}
