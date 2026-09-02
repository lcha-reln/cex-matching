package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

final class M10StartCheckRunnerTest {
  @TempDir Path temporary;

  @Test
  void writesOnlyTheFrozenStructuredRedReport() throws Exception {
    Path root = root();
    Path reports = temporary.resolve("reports/m10");
    M10StartCheckRunner.Result result = new M10StartCheckRunner().run(root, reports, temporary);
    assertEquals(M10StartCheckRunner.STATUS, result.status());
    JsonNode report = JsonSupport.parse(Files.readAllBytes(result.reportPath()));
    JsonSupport.validate(
        report, Files.readString(root.resolve(M10StartCheckRunner.CHECK_SCHEMA_PATH)), false);
    assertEquals(20, report.path("workloadProfile").path("fixedScenarios").intValue());
    assertEquals(64, report.path("workloadProfile").path("generatedHistories").intValue());
    assertEquals(16_384, report.path("workloadProfile").path("generatedActions").intValue());
    assertEquals(28, report.path("coverageObligations").size());
    assertEquals(12, report.path("requiredMutants").size());
    assertEquals(5, report.path("tutorialPermalinks").size());
    assertEquals(
        "EXACTLY_ONE_SUBMISSION_RESULT_OR_EXPLICIT_FAILURE",
        report.path("admissionContract").path("acceptedCompletion").stringValue());
    assertFalse(
        report
            .path("measurementContract")
            .path("ciSmoke")
            .path("eligibleForReleaseEvidence")
            .booleanValue());
    assertEquals(
        "matching-0.5.0", report.path("releaseTarget").path("productRelease").stringValue());
    assertFalse(Files.exists(temporary.resolve("lab-evidence/M10")));
  }

  @Test
  void frozenWorkloadHashMatchesRepositoryBytes() throws Exception {
    Path root = root();
    assertEquals(
        M10StartCheckRunner.WORKLOAD_SHA256,
        Hashing.sha256Hex(Files.readAllBytes(root.resolve(M10StartCheckRunner.WORKLOAD_PATH))));
  }

  @Test
  void workloadSchemaRejectsPromotingCiSmokeToReleaseEvidence() throws Exception {
    Path root = root();
    JsonNode workload =
        JsonSupport.parse(Files.readAllBytes(root.resolve(M10StartCheckRunner.WORKLOAD_PATH)));
    ObjectNode promoted = (ObjectNode) workload.deepCopy();
    ((ObjectNode) promoted.path("ciSmoke")).put("eligibleForReleaseEvidence", true);
    String schema = Files.readString(root.resolve(M10StartCheckRunner.WORKLOAD_SCHEMA_PATH));
    assertThrows(FixtureSchemaException.class, () -> JsonSupport.validate(promoted, schema, false));
  }

  private static Path root() {
    return Path.of(System.getProperty("matching.repositoryRoot")).toAbsolutePath().normalize();
  }
}
