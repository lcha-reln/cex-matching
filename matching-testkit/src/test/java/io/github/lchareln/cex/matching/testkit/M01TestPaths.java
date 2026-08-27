package io.github.lchareln.cex.matching.testkit;

import java.nio.file.Path;

final class M01TestPaths {
  private M01TestPaths() {}

  static Path root() {
    return Path.of(System.getProperty("matching.repositoryRoot"));
  }

  static Path fixture() {
    return root().resolve("matching-testkit/src/test/resources/m01/fixtures/price-time-v1.json");
  }

  static Path fixtureSchema() {
    return root().resolve("schemas/matching.m01.scenario.v1.schema.json");
  }

  static M01ScenarioPack load() {
    return new M01ScenarioLoader().load(fixture(), fixtureSchema());
  }
}
