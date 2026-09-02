package io.github.lchareln.cex.matching.local;

import java.util.Objects;

/** Configuration for the bounded local admission service. */
public record ServiceConfig(int queueCapacity, String workerName) {
  public static final int QUALIFICATION_QUEUE_CAPACITY = 64;

  /** Caps retained envelope ownership at 256 MiB before owner/runtime working copies. */
  public static final int MAX_QUEUE_CAPACITY = 256;

  public static final String DEFAULT_WORKER_NAME = "matching-local-runtime-worker";

  public ServiceConfig {
    if (queueCapacity <= 0 || queueCapacity > MAX_QUEUE_CAPACITY) {
      throw new IllegalArgumentException(
          "queueCapacity must be between 1 and " + MAX_QUEUE_CAPACITY);
    }
    workerName = Objects.requireNonNull(workerName, "workerName");
    if (workerName.isBlank()) {
      throw new IllegalArgumentException("workerName must not be blank");
    }
  }

  public ServiceConfig(int queueCapacity) {
    this(queueCapacity, DEFAULT_WORKER_NAME);
  }

  /** The exact bounded queue capacity frozen by the M10 qualification workload. */
  public static ServiceConfig qualification() {
    return new ServiceConfig(QUALIFICATION_QUEUE_CAPACITY);
  }
}
