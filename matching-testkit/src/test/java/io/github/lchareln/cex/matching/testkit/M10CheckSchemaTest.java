package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class M10CheckSchemaTest {
  @Test
  void failureReportRejectsPassAndSmokePromotion() throws Exception {
    Path root = Path.of(System.getProperty("matching.repositoryRoot"));
    String schema = Files.readString(root.resolve(M10CheckRunner.CHECK_SCHEMA_PATH));
    var failure = JsonSupport.MAPPER.createObjectNode();
    failure.put("schemaVersion", "matching.m10.check.v2");
    failure.put("unit", "M10");
    failure.put("status", "SYSTEM_ERROR");
    failure.put("contractPlanVersion", "0.12");
    failure.put("failure", "collector unavailable");
    var release = failure.putObject("releaseTarget");
    release.put("unitTag", "course/m10-complete");
    release.put("productRelease", "matching-0.5.0");
    release.put("verification", "FULL_RELEASE_PROFILE_AND_CLEAN_TREE_EVIDENCE");
    JsonSupport.validate(failure, schema, false);
    failure.put("status", "PASS");
    assertThrows(FixtureSchemaException.class, () -> JsonSupport.validate(failure, schema, false));
  }
}
