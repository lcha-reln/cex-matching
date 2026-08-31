package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

final class M07StartCheckRunnerTest {
  @Test
  void writesTheStrictStructuredRedBoundaryWithoutCompletionClaims(@TempDir Path temporary)
      throws Exception {
    Path reports = temporary.resolve("reports/m07");
    M07StartCheckRunner.Result result =
        new M07StartCheckRunner().run(M07TestPaths.root(), reports, temporary);

    assertEquals("GOAL_NOT_IMPLEMENTED", result.status());
    JsonNode report = JsonSupport.parse(Files.readAllBytes(result.reportPath()));
    JsonSupport.validate(
        report,
        Files.readString(M07TestPaths.root().resolve("schemas/matching.m07.check.v1.schema.json")),
        false);
    assertEquals("READY", report.path("courseDeclaration").path("lifecycle").stringValue());
    assertEquals(16, report.path("fixedCorpus").path("scenarios").intValue());
    assertEquals(72, report.path("fixedCorpus").path("commands").intValue());
    assertEquals(24, report.path("fixedCorpus").path("proofObligations").intValue());
    assertEquals(160, report.path("generator").path("histories").intValue());
    assertEquals(10_240, report.path("generator").path("totalCommands").intValue());
    assertEquals(
        "READ_ONLY_STP_AWARE", report.path("stpContract").path("fokPreflight").stringValue());
    assertEquals(8, report.path("requiredMutants").size());
    assertEquals(5, report.path("tutorialPermalinks").size());
    assertFalse(report.path("fixedCorpus").has("canonicalDigest"));
    assertFalse(report.path("generator").has("canonicalDigest"));
    assertFalse(report.has("mutants"));
    assertFalse(Files.exists(temporary.resolve("lab-evidence/M07")));
  }

  @Test
  void freezesBothDeclaredInputDigests() throws Exception {
    Path root = M07TestPaths.root();
    assertEquals(
        M07StartCheckRunner.FIXED_CORPUS_SHA256,
        Hashing.sha256Hex(Files.readAllBytes(root.resolve(M07StartCheckRunner.FIXED_CORPUS_PATH))));
    assertEquals(
        M07StartCheckRunner.GENERATOR_SHA256,
        Hashing.sha256Hex(Files.readAllBytes(root.resolve(M07StartCheckRunner.GENERATOR_PATH))));
  }
}
