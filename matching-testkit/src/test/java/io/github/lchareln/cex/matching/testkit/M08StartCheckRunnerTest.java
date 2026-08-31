package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

final class M08StartCheckRunnerTest {
  @TempDir Path temporary;

  @Test
  void writesOnlyTheFrozenStructuredRedReport() throws Exception {
    Path root = root();
    Path reports = temporary.resolve("reports/m08");
    M08StartCheckRunner.Result result = new M08StartCheckRunner().run(root, reports, temporary);
    assertEquals(M08StartCheckRunner.STATUS, result.status());
    JsonNode report = JsonSupport.parse(Files.readAllBytes(result.reportPath()));
    JsonSupport.validate(
        report, Files.readString(root.resolve(M08StartCheckRunner.CHECK_SCHEMA_PATH)), false);
    assertEquals(20, report.path("fixedCorpus").path("primaryCount").intValue());
    assertEquals(96, report.path("generator").path("primaryCount").intValue());
    assertEquals(4_608, report.path("generator").path("generatedOperations").intValue());
    assertEquals(24, report.path("generator").path("coverageObligations").intValue());
    assertEquals(10, report.path("requiredMutants").size());
    assertEquals(5, report.path("tutorialPermalinks").size());
    assertFalse(Files.exists(temporary.resolve("lab-evidence/M08")));
  }

  @Test
  void frozenHashesMatchRepositoryBytes() throws Exception {
    Path root = root();
    assertEquals(
        M08StartCheckRunner.FIXED_CORPUS_SHA256,
        Hashing.sha256Hex(Files.readAllBytes(root.resolve(M08StartCheckRunner.FIXED_CORPUS_PATH))));
    assertEquals(
        M08StartCheckRunner.GENERATOR_SHA256,
        Hashing.sha256Hex(Files.readAllBytes(root.resolve(M08StartCheckRunner.GENERATOR_PATH))));
  }

  private static Path root() {
    return Path.of(System.getProperty("matching.repositoryRoot")).toAbsolutePath().normalize();
  }
}
