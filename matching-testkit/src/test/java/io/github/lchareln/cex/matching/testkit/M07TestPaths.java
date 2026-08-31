package io.github.lchareln.cex.matching.testkit;

import java.nio.file.Path;

final class M07TestPaths {
  private M07TestPaths() {}

  static Path root() {
    String configured = System.getProperty("matching.repositoryRoot");
    if (configured == null || configured.isBlank()) {
      throw new IllegalStateException("matching.repositoryRoot is not configured");
    }
    return Path.of(configured).toAbsolutePath().normalize();
  }
}
