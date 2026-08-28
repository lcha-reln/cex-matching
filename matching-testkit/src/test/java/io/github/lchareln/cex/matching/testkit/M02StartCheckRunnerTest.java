package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

final class M02StartCheckRunnerTest {
  @Test
  void writesTheExactIntentionalGapAfterValidatingTheFrozenCorpus(@TempDir Path outputRoot)
      throws IOException {
    Path reports = outputRoot.resolve("build/reports/m02");

    M02StartCheckRunner.Result result =
        new M02StartCheckRunner().run(M01TestPaths.root(), reports, outputRoot);

    assertEquals(M02StartCheckRunner.STATUS, result.status());
    assertEquals(reports.resolve("check.json").toRealPath(), result.reportPath().toRealPath());
    JsonNode report = JsonSupport.parse(Files.readAllBytes(result.reportPath()));
    assertEquals("matching.m02.check.v1", report.path("schemaVersion").stringValue());
    assertEquals("M02", report.path("unit").stringValue());
    assertEquals("GOAL_NOT_IMPLEMENTED", report.path("status").stringValue());
    assertEquals(10, report.path("scenarioCorpus").path("scenarios").intValue());
    assertEquals(34, report.path("scenarioCorpus").path("commands").intValue());
    assertEquals(22, report.path("scenarioCorpus").path("placeCommands").intValue());
    assertEquals(12, report.path("scenarioCorpus").path("cancelCommands").intValue());
    assertEquals(8, report.path("scenarioCorpus").path("schemaProbes").intValue());
    assertEquals(
        M02StartCheckRunner.FROZEN_FIXTURE_SHA256,
        report.path("scenarioCorpus").path("sha256").stringValue());
    assertEquals(
        "ADDRESSABLE_LIFECYCLE_REGISTRY", report.path("missingCapabilities").get(0).stringValue());
    assertEquals("ADDRESSABLE_CANCEL", report.path("missingCapabilities").get(1).stringValue());
    assertEquals(
        "IRREVERSIBLE_TERMINAL_STATE", report.path("missingCapabilities").get(2).stringValue());
  }

  @Test
  void rejectsASymlinkedReportDirectoryBeforeWriting(@TempDir Path outputRoot) throws IOException {
    Path outside = Files.createDirectories(outputRoot.resolve("outside"));
    Path build = Files.createDirectories(outputRoot.resolve("build"));
    Files.createSymbolicLink(build.resolve("reports"), outside);

    assertThrows(
        IllegalStateException.class,
        () ->
            new M02StartCheckRunner()
                .run(M01TestPaths.root(), outputRoot.resolve("build/reports/m02"), outputRoot));
    try (var files = Files.list(outside)) {
      assertTrue(files.findAny().isEmpty());
    }
  }
}
