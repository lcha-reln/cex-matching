package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

final class M05StartCheckRunnerTest {
  @Test
  void writesTheStrictStructuredRedBoundary(@TempDir Path temporary) throws Exception {
    Path reports = temporary.resolve("reports/m05");
    M05StartCheckRunner.Result result =
        new M05StartCheckRunner().run(M05TestPaths.root(), reports, temporary);

    assertEquals("GOAL_NOT_IMPLEMENTED", result.status());
    JsonNode report = JsonSupport.parse(Files.readAllBytes(result.reportPath()));
    JsonSupport.validate(
        report,
        Files.readString(M05TestPaths.root().resolve("schemas/matching.m05.check.v1.schema.json")),
        false);
    assertEquals(12, report.path("fixedCorpus").path("scenarios").intValue());
    assertEquals(54, report.path("fixedCorpus").path("commands").intValue());
    assertEquals(160, report.path("generator").path("histories").intValue());
    assertEquals(10_240, report.path("generator").path("totalCommands").intValue());
    assertEquals("M05RS1", report.path("ruleContract").path("canonicalFormat").stringValue());
    assertEquals(8, report.path("requiredMutants").size());
    assertFalse(Files.exists(reports.resolve(".inherited-m04")));
  }

  @Test
  void freezesBothInputDigests() throws Exception {
    Path root = M05TestPaths.root();
    assertEquals(
        M05StartCheckRunner.FIXED_CORPUS_SHA256,
        Hashing.sha256Hex(Files.readAllBytes(root.resolve(M05StartCheckRunner.FIXED_CORPUS_PATH))));
    assertEquals(
        M05StartCheckRunner.GENERATOR_SHA256,
        Hashing.sha256Hex(Files.readAllBytes(root.resolve(M05StartCheckRunner.GENERATOR_PATH))));
  }
}
