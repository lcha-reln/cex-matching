package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

final class M04StartCheckRunnerTest {
  @Test
  void writesTheStrictStructuredRedBoundary(@TempDir Path temporary) throws Exception {
    Path reports = temporary.resolve("reports/m04");
    M04StartCheckRunner.Result result =
        new M04StartCheckRunner().run(M04TestPaths.root(), reports, temporary);

    assertEquals("GOAL_NOT_IMPLEMENTED", result.status());
    JsonNode report = JsonSupport.parse(Files.readAllBytes(result.reportPath()));
    JsonSupport.validate(
        report,
        Files.readString(M04TestPaths.root().resolve("schemas/matching.m04.check.v1.schema.json")),
        false);
    assertEquals(14, report.path("fixedCorpus").path("scenarios").intValue());
    assertEquals(48, report.path("fixedCorpus").path("commands").intValue());
    assertEquals(192, report.path("generator").path("histories").intValue());
    assertEquals(12_288, report.path("generator").path("totalCommands").intValue());
    assertEquals(
        "placeRequest(PlaceLimitOrderRequest)",
        report.path("executionContract").path("requestMethod").stringValue());
    assertEquals(
        "INVALID_EXECUTION_POLICY",
        report.path("executionContract").path("unknownPolicyCode").stringValue());
    assertEquals(8, report.path("requiredMutants").size());
    assertFalse(Files.exists(reports.resolve(".inherited-m03")));
  }

  @Test
  void freezesBothInputDigests() throws Exception {
    Path root = M04TestPaths.root();
    assertEquals(
        M04StartCheckRunner.FIXED_CORPUS_SHA256,
        Hashing.sha256Hex(Files.readAllBytes(root.resolve(M04StartCheckRunner.FIXED_CORPUS_PATH))));
    assertEquals(
        M04StartCheckRunner.GENERATOR_SHA256,
        Hashing.sha256Hex(Files.readAllBytes(root.resolve(M04StartCheckRunner.GENERATOR_PATH))));
  }
}
