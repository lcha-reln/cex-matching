package io.github.lchareln.cex.matching.cluster;

import io.aeron.ExclusivePublication;
import io.aeron.Image;
import io.aeron.Publication;
import io.aeron.cluster.codecs.CloseReason;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster;
import io.aeron.cluster.service.ClusteredService;
import io.aeron.logbuffer.Header;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

/** Single-owner Aeron Cluster adapter for the deterministic matching application. */
public final class M11ClusteredMatchingService implements ClusteredService {
  private final long expectedShard;
  private final M11ApplicationObserver observer;
  private final M11RequestCodec requestCodec = new M11RequestCodec();
  private final M11ResponseCodec responseCodec = new M11ResponseCodec();
  private final M11SnapshotCodec snapshotCodec = new M11SnapshotCodec();
  private final M11RuntimeStateCodec runtimeStateCodec = new M11RuntimeStateCodec();
  private final M11AeronSnapshotTransport snapshotTransport = new M11AeronSnapshotTransport();
  private final AtomicLong protocolRejections = new AtomicLong();
  private final AtomicReference<RuntimeException> observerFailure = new AtomicReference<>();
  private final AtomicReference<Cluster.Role> role = new AtomicReference<>();
  private final AtomicReference<M11ApplicationSnapshotWitness> lastWrittenSnapshot =
      new AtomicReference<>();
  private final AtomicReference<M11ApplicationSnapshotWitness> lastLoadedSnapshot =
      new AtomicReference<>();
  private DirectM11MatchingRuntime runtime = new DirectM11MatchingRuntime();
  private final AtomicReference<M11RuntimeState> publishedState =
      new AtomicReference<>(runtime.stateImage());

  private Cluster cluster;

  public M11ClusteredMatchingService(long expectedShard) {
    this(expectedShard, M11ApplicationObserver.NO_OP);
  }

  public M11ClusteredMatchingService(long expectedShard, M11ApplicationObserver observer) {
    if (expectedShard <= 0) {
      throw new IllegalArgumentException("expectedShard must be positive");
    }
    this.expectedShard = expectedShard;
    this.observer = Objects.requireNonNull(observer, "observer");
  }

  @Override
  public void onStart(Cluster cluster, Image snapshotImage) {
    this.cluster = Objects.requireNonNull(cluster, "cluster");
    if (snapshotImage == null) {
      return;
    }
    try {
      M11AeronSnapshotTransport.LoadedSnapshot loaded =
          snapshotTransport.read(snapshotImage, cluster.idleStrategy());
      M11Snapshot snapshot = snapshotCodec.decodeCanonical(loaded.canonicalBytes());
      long expectedSnapshotSequence = snapshot.state().nextApplicationSequence() - 1;
      if (loaded.snapshotSequence() != expectedSnapshotSequence) {
        throw new M11ProtocolException(
            M11ProtocolException.Code.INVALID_VALUE,
            "snapshot transport sequence and application state disagree");
      }
      runtime = DirectM11MatchingRuntime.restore(snapshot.state());
      publishedState.set(runtime.stateImage());
      lastLoadedSnapshot.set(applicationSnapshotWitness(loaded.canonicalBytes(), snapshot.state()));
    } catch (M11ProtocolException | IllegalArgumentException failure) {
      throw new IllegalStateException("M11 Cluster snapshot recovery failed closed", failure);
    }
  }

  @Override
  public void onSessionOpen(ClientSession session, long timestamp) {}

  @Override
  public void onSessionClose(ClientSession session, long timestamp, CloseReason closeReason) {}

  @Override
  public void onSessionMessage(
      ClientSession session,
      long timestamp,
      DirectBuffer buffer,
      int offset,
      int length,
      Header header) {
    byte[] encoded = new byte[length];
    buffer.getBytes(offset, encoded);
    final M11CommandRequest request;
    try {
      request = requestCodec.decodeCanonical(encoded, expectedShard);
    } catch (M11ProtocolException failure) {
      protocolRejections.incrementAndGet();
      return;
    }

    M11ApplicationResult application = runtime.submit(request);
    publishedState.set(runtime.stateImage());
    observe(
        new M11ServiceObservation(
            session == null ? -1 : session.id(), timestamp, header.position(), application));
    if (session != null) {
      send(session, responseCodec.encode(application.response()));
    }
  }

  @Override
  public void onTimerEvent(long correlationId, long timestamp) {}

  @Override
  public void onTakeSnapshot(ExclusivePublication snapshotPublication) {
    M11RuntimeState state = runtime.stateImage();
    byte[] encoded = snapshotCodec.encodeCurrent(state);
    snapshotTransport.write(
        snapshotPublication,
        encoded,
        runtime.nextApplicationSequence() - 1,
        cluster.idleStrategy());
    lastWrittenSnapshot.set(applicationSnapshotWitness(encoded, state));
  }

  @Override
  public void onRoleChange(Cluster.Role newRole) {
    role.set(newRole);
  }

  @Override
  public void onTerminate(Cluster cluster) {}

  public long nextApplicationSequence() {
    return publishedState.get().nextApplicationSequence();
  }

  public String semanticStateDigest() {
    return publishedState.get().commandState().semanticStateDigest();
  }

  public M11RuntimeState stateImage() {
    return publishedState.get();
  }

  public long protocolRejections() {
    return protocolRejections.get();
  }

  public RuntimeException observerFailure() {
    return observerFailure.get();
  }

  public Cluster.Role role() {
    return role.get();
  }

  public M11ApplicationSnapshotWitness lastWrittenSnapshot() {
    return lastWrittenSnapshot.get();
  }

  public M11ApplicationSnapshotWitness lastLoadedSnapshot() {
    return lastLoadedSnapshot.get();
  }

  private void send(ClientSession session, byte[] encoded) {
    UnsafeBuffer response = new UnsafeBuffer(encoded);
    cluster.idleStrategy().reset();
    while (true) {
      long result = session.offer(response, 0, encoded.length);
      if (result >= 0) {
        return;
      }
      if (result != Publication.BACK_PRESSURED && result != Publication.ADMIN_ACTION) {
        throw new IllegalStateException("M11 egress failed: " + Publication.errorString(result));
      }
      cluster.idleStrategy().idle();
    }
  }

  private void observe(M11ServiceObservation observation) {
    try {
      observer.onApplication(observation);
    } catch (RuntimeException failure) {
      observerFailure.compareAndSet(null, failure);
    }
  }

  private M11ApplicationSnapshotWitness applicationSnapshotWitness(
      byte[] snapshotBytes, M11RuntimeState state) {
    return new M11ApplicationSnapshotWitness(
        state.nextApplicationSequence() - 1,
        M11Digests.sha256Hex(snapshotBytes),
        runtimeStateCodec.identityTableDigest(state.identityBindings()),
        state.commandState().semanticStateDigest(),
        state.nextApplicationSequence());
  }
}
