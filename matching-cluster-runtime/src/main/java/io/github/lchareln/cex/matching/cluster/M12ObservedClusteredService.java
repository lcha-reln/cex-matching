package io.github.lchareln.cex.matching.cluster;

import io.aeron.ExclusivePublication;
import io.aeron.Image;
import io.aeron.cluster.codecs.CloseReason;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster;
import io.aeron.cluster.service.ClusteredService;
import io.aeron.logbuffer.Header;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.agrona.DirectBuffer;

/** Delegates the M11 service while publishing non-influencing, read-only M12 lifecycle samples. */
final class M12ObservedClusteredService implements ClusteredService {
  private final M11ClusteredMatchingService delegate;
  private volatile String role = "STARTING";
  private volatile long leadershipTermId = -1;
  private volatile long logPosition = -1;
  private Cluster cluster;

  M12ObservedClusteredService(M11ClusteredMatchingService delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
  }

  @Override
  public void onStart(Cluster cluster, Image snapshotImage) {
    this.cluster = Objects.requireNonNull(cluster, "cluster");
    delegate.onStart(cluster, snapshotImage);
    role = cluster.role().name();
    sampleLogPosition();
  }

  @Override
  public void onSessionOpen(ClientSession session, long timestamp) {
    delegate.onSessionOpen(session, timestamp);
    sampleLogPosition();
  }

  @Override
  public void onSessionClose(ClientSession session, long timestamp, CloseReason closeReason) {
    delegate.onSessionClose(session, timestamp, closeReason);
    sampleLogPosition();
  }

  @Override
  public void onSessionMessage(
      ClientSession session,
      long timestamp,
      DirectBuffer buffer,
      int offset,
      int length,
      Header header) {
    delegate.onSessionMessage(session, timestamp, buffer, offset, length, header);
    sampleLogPosition();
  }

  @Override
  public void onTimerEvent(long correlationId, long timestamp) {
    delegate.onTimerEvent(correlationId, timestamp);
    sampleLogPosition();
  }

  @Override
  public void onTakeSnapshot(ExclusivePublication snapshotPublication) {
    delegate.onTakeSnapshot(snapshotPublication);
    sampleLogPosition();
  }

  @Override
  public void onRoleChange(Cluster.Role newRole) {
    delegate.onRoleChange(newRole);
    role = newRole.name();
    sampleLogPosition();
  }

  @Override
  public void onTerminate(Cluster cluster) {
    delegate.onTerminate(cluster);
    sampleLogPosition();
  }

  @Override
  public void onNewLeadershipTermEvent(
      long leadershipTermId,
      long logPosition,
      long timestamp,
      long termBaseLogPosition,
      int leaderMemberId,
      int logSessionId,
      TimeUnit timeUnit,
      int appVersion) {
    delegate.onNewLeadershipTermEvent(
        leadershipTermId,
        logPosition,
        timestamp,
        termBaseLogPosition,
        leaderMemberId,
        logSessionId,
        timeUnit,
        appVersion);
    this.leadershipTermId = leadershipTermId;
    sampleLogPosition();
  }

  @Override
  public int doBackgroundWork(long nowNs) {
    int workCount = delegate.doBackgroundWork(nowNs);
    sampleLogPosition();
    return workCount;
  }

  M11ClusteredMatchingService delegate() {
    return delegate;
  }

  String role() {
    return role;
  }

  long leadershipTermId() {
    return leadershipTermId;
  }

  long logPosition() {
    return logPosition;
  }

  private void sampleLogPosition() {
    Cluster current = cluster;
    if (current != null) {
      logPosition = current.logPosition();
    }
  }
}
