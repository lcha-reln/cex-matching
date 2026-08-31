package io.github.lchareln.cex.matching.local;

import java.io.IOException;

/**
 * Injectable deterministic failure seam. It models injected failures, not proof about real power
 * loss.
 */
@FunctionalInterface
public interface FaultInjector {
  FaultInjector NONE = ignored -> {};

  void hit(FaultPoint point) throws IOException;
}
