package io.github.lchareln.cex.matching.cluster;

/** Stable client-side reasons for ending an M12 invocation without an acknowledgement. */
public enum M12UnknownReason {
  OFFER_TIMEOUT,
  PUBLICATION_CLOSED,
  PUBLICATION_MAX_POSITION,
  PUBLICATION_FAILED,
  RESPONSE_TIMEOUT,
  SESSION_CLOSED,
  LEADER_CHANGED,
  PROCESS_EXITED,
  CLIENT_COMPONENT_FAILED,
  CLUSTER_COMPONENT_FAILED,
  INVALID_EGRESS,
  ABANDONED
}
