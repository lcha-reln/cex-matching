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

final class M11StartCheckRunnerTest {
  @TempDir Path temporary;

  @Test
  void writesAndValidatesOnlyTheFrozenStructuredRed() throws Exception {
    Path root = root();
    M11StartCheckRunner.Result result =
        new M11StartCheckRunner().run(root, temporary.resolve("reports/m11"), temporary);
    JsonNode report = JsonSupport.parse(Files.readAllBytes(result.reportPath()));
    JsonSupport.validate(
        report, Files.readString(root.resolve(M11StartCheckRunner.CHECK_SCHEMA_PATH)), false);
    assertEquals(M11StartCheckRunner.STATUS, result.status());
    assertEquals(22, report.path("workloadProfile").path("fixedScenarios").intValue());
    assertEquals(4096, report.path("workloadProfile").path("generatedActions").intValue());
    assertEquals(
        M11StartCheckRunner.SEGMENT_SCHEDULE,
        strings(report.path("workloadProfile").path("segmentSchedule")));
    assertFalse(report.path("workloadProfile").path("stateResetBetweenSegments").booleanValue());
    assertEquals(8192, report.path("clusterContract").path("totalActualClusterIngress").intValue());
    assertEquals(
        1536, report.path("clusterContract").path("snapshotApplicationSequence").intValue());
    assertEquals(
        1537, report.path("clusterContract").path("snapshotNextApplicationSequence").intValue());
    assertEquals(
        "PREVIOUS_NEW", report.path("clusterContract").path("firstPostRestartLane").stringValue());
    assertEquals(
        "NEW_APPLIED",
        report.path("clusterContract").path("firstPostRestartExpectedStatus").stringValue());
    assertEquals(
        512, report.path("clusterContract").path("postRestartCrossSnapshotDuplicates").intValue());
    assertEquals(5, report.path("clusterContract").path("snapshotCompletionWitnesses").size());
    assertEquals(28, report.path("coverageObligations").size());
    assertEquals(6, report.path("goldenFixtures").size());
    assertEquals(2, report.path("protocolContract").path("goldenSnapshotBindings").intValue());
    assertEquals(1, report.path("executionContract").path("structuredRedExitCode").intValue());
    assertFalse(report.path("clusterContract").path("highAvailabilityClaim").booleanValue());
    assertFalse(Files.exists(temporary.resolve("lab-evidence/M11")));
  }

  @Test
  void frozenWorkloadAndGeneratedGoldensMatchRepositoryBytes() throws Exception {
    Path root = root();
    assertEquals(
        M11StartCheckRunner.WORKLOAD_SHA256,
        Hashing.sha256Hex(Files.readAllBytes(root.resolve(M11StartCheckRunner.WORKLOAD_PATH))));
    JsonNode workload =
        JsonSupport.parse(Files.readAllBytes(root.resolve(M11StartCheckRunner.WORKLOAD_PATH)));
    assertEquals(
        M11StartCheckRunner.SEGMENT_SCHEDULE,
        strings(workload.path("generatedDifferential").path("segmentSchedule")));
    for (int index = 0; index < M11ContractGoldens.fixtures().size(); index++) {
      M11ContractGoldens.Fixture fixture = M11ContractGoldens.fixtures().get(index);
      JsonNode binding = workload.path("goldenFixtures").get(index);
      byte[] bytes = Files.readAllBytes(root.resolve(binding.path("path").stringValue()));
      assertEquals(binding.path("sha256").stringValue(), Hashing.sha256Hex(bytes));
      assertEquals(Hashing.sha256Hex(fixture.bytes()), Hashing.sha256Hex(bytes));
    }
  }

  @Test
  void checkSchemaRejectsAHighAvailabilityClaim() throws Exception {
    Path root = root();
    M11StartCheckRunner.Result result =
        new M11StartCheckRunner().run(root, temporary.resolve("schema-probe"), temporary);
    ObjectNode report =
        (ObjectNode) JsonSupport.parse(Files.readAllBytes(result.reportPath())).deepCopy();
    ((ObjectNode) report.path("clusterContract")).put("highAvailabilityClaim", true);
    String schema = Files.readString(root.resolve(M11StartCheckRunner.CHECK_SCHEMA_PATH));
    assertThrows(FixtureSchemaException.class, () -> JsonSupport.validate(report, schema, false));
  }

  private static Path root() {
    return Path.of(System.getProperty("matching.repositoryRoot")).toAbsolutePath().normalize();
  }

  private static java.util.List<String> strings(JsonNode values) {
    java.util.List<String> result = new java.util.ArrayList<>();
    values.forEach(value -> result.add(value.stringValue()));
    return java.util.List.copyOf(result);
  }
}
