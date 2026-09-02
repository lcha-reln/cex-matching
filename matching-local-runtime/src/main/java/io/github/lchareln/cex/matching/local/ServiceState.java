package io.github.lchareln.cex.matching.local;

/** Admission and worker lifecycle of one {@link LocalMatchingService}. */
public enum ServiceState {
  ACCEPTING,
  QUIESCING,
  FAILED_CLOSED,
  CLOSED
}
