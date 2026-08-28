package io.github.lchareln.cex.matching.testkit;

import java.nio.file.Path;

final class M04TestPaths {
  private M04TestPaths() {}

  static Path root() {
    String configured = System.getProperty("matching.repositoryRoot");
    if (configured == null || configured.isBlank()) {
      throw new IllegalStateException("matching.repositoryRoot is not configured");
    }
    return Path.of(configured).toAbsolutePath().normalize();
  }
}
