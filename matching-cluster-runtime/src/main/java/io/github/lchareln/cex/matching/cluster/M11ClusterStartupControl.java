package io.github.lchareln.cex.matching.cluster;

import java.util.Objects;

/** Narrow qualification entrypoint; the normal public Cluster launcher always selects NONE. */
public final class M11ClusterStartupControl {
  private M11ClusterStartupControl() {}

  public static M11SingleNodeCluster launch(
      M11SingleNodeConfig config, M11ApplicationObserver observer, M11FaultPolicy faultPolicy) {
    Objects.requireNonNull(faultPolicy, "faultPolicy");
    if (faultPolicy.mode() != M11FaultPolicy.Mode.CLUSTER_STARTUP_SYSTEM_ERROR) {
      throw new IllegalArgumentException("startup control requires its one explicit fault mode");
    }
    return M11SingleNodeCluster.launch(config, true, observer, faultPolicy);
  }
}
