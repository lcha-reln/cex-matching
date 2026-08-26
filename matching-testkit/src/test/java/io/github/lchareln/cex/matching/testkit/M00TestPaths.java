package io.github.lchareln.cex.matching.testkit;

import java.nio.file.Path;

final class M00TestPaths {
  private M00TestPaths() {}

  static Path root() {
    return Path.of(System.getProperty("m00.repositoryRoot"));
  }

  static Path fixture() {
    return root().resolve("matching-testkit/src/test/resources/m00/fixtures/history-v1.json");
  }

  static Path fixtureSchema() {
    return root().resolve("schemas/matching.m00.fixture.v1.schema.json");
  }
}
