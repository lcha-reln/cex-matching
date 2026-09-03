package io.github.lchareln.cex.matching.cluster;

/** Harness observation seam that is never consulted by business decisions. */
@FunctionalInterface
public interface M11ApplicationObserver {
  M11ApplicationObserver NO_OP = observation -> {};

  void onApplication(M11ServiceObservation observation);
}
