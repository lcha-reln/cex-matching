package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

final class M09StartCheckRunnerTest {
  @TempDir Path temporary;

  @Test
  void writesOnlyTheFrozenStructuredRedReport() throws Exception {
    Path root = root();
    Path reports = temporary.resolve("reports/m09");
    M09StartCheckRunner.Result result = new M09StartCheckRunner().run(root, reports, temporary);
    assertEquals(M09StartCheckRunner.STATUS, result.status());
    JsonNode report = JsonSupport.parse(Files.readAllBytes(result.reportPath()));
    JsonSupport.validate(
        report, Files.readString(root.resolve(M09StartCheckRunner.CHECK_SCHEMA_PATH)), false);
    assertEquals(22, report.path("fixedCorpus").path("primaryCount").intValue());
    assertEquals(88, report.path("fixedCorpus").path("operations").intValue());
    assertEquals(96, report.path("generator").path("primaryCount").intValue());
    assertEquals(3_840, report.path("generator").path("generatedOperations").intValue());
    assertEquals(32, report.path("generator").path("coverageObligations").intValue());
    assertEquals(7, report.path("generator").path("crashWindows").intValue());
    assertEquals(8, report.path("generator").path("failureSeams").intValue());
    assertEquals(12, report.path("requiredMutants").size());
    assertEquals(5, report.path("tutorialPermalinks").size());
    assertFalse(report.path("snapshotContract").path("formatEvolution").booleanValue());
    assertFalse(Files.exists(temporary.resolve("lab-evidence/M09")));
  }

  @Test
  void frozenHashesMatchRepositoryBytes() throws Exception {
    Path root = root();
    assertEquals(
        M09StartCheckRunner.FIXED_CORPUS_SHA256,
        Hashing.sha256Hex(Files.readAllBytes(root.resolve(M09StartCheckRunner.FIXED_CORPUS_PATH))));
    assertEquals(
        M09StartCheckRunner.GENERATOR_SHA256,
        Hashing.sha256Hex(Files.readAllBytes(root.resolve(M09StartCheckRunner.GENERATOR_PATH))));
  }

  private static Path root() {
    return Path.of(System.getProperty("matching.repositoryRoot")).toAbsolutePath().normalize();
  }
}
