package io.github.lchareln.cex.matching.testkit;

import java.nio.file.Path;

final class M02TestPaths {
  private M02TestPaths() {}

  static Path root() {
    return Path.of(System.getProperty("matching.repositoryRoot"));
  }

  static Path fixture() {
    return root().resolve(M02CheckRunner.FIXTURE_PATH);
  }

  static Path fixtureSchema() {
    return root().resolve(M02CheckRunner.FIXTURE_SCHEMA_PATH);
  }

  static M02ScenarioPack load() {
    return new M02ScenarioLoader().load(fixture(), fixtureSchema());
  }
}
