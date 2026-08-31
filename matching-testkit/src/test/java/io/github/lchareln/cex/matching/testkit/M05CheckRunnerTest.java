package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

class M05CheckRunnerTest {
  private static final Path ROOT = Path.of(System.getProperty("matching.repositoryRoot"));

  @Test
  void writesTheStrictCompletionReportAndEveryEvidenceInput(@TempDir Path temporary)
      throws Exception {
    Path reports = temporary.resolve("reports");
    M05CheckRunner.Result result = new M05CheckRunner().run(ROOT, reports, temporary);
    assertEquals(M05CheckRunner.PASS, result.status());
    JsonNode check = JsonSupport.parse(Files.readAllBytes(result.reportPath()));
    JsonSupport.validate(
        check, Files.readString(ROOT.resolve(M05CheckRunner.CHECK_SCHEMA_PATH)), false);
    assertEquals(12, check.path("fixedCorpus").path("scenarios").intValue());
    assertEquals(54, check.path("fixedCorpus").path("commands").intValue());
    assertEquals(10_240, check.path("properties").path("commands").intValue());
    assertEquals(20, check.path("coverage").path("satisfiedObligations").intValue());
    assertEquals(8, check.path("counterexamples").path("oneMinimal").intValue());
    assertEquals(8, check.path("mutants").path("killed").intValue());
    assertTrue(check.path("releaseTarget").path("productRelease").isNull());
    for (String name : M05CheckRunner.OUTPUTS) {
      assertTrue(Files.isRegularFile(reports.resolve(name)), name);
    }
  }

  @Test
  void candidateExceptionsRemainSystemErrorsAndClearStalePassArtifacts(@TempDir Path temporary)
      throws Exception {
    Path reports = temporary.resolve("reports");
    Files.createDirectories(reports);
    Files.writeString(reports.resolve("stale-pass.json"), "{}");
    M05CheckRunner runner =
        new M05CheckRunner(
            () ->
                new M05Candidate() {
                  @Override
                  public io.github.lchareln.cex.matching.reference.M05SemanticOutcome apply(
                      M05Command command) {
                    throw new IllegalStateException("intentional check control");
                  }

                  @Override
                  public io.github.lchareln.cex.matching.reference.M05SemanticMarketState
                      snapshot() {
                    return new M05ReferenceCandidate().snapshot();
                  }
                });
    M05CheckRunner.Result result = runner.run(ROOT, reports, temporary);
    assertEquals(M05CheckRunner.SYSTEM_ERROR, result.status());
    assertFalse(Files.exists(reports.resolve("stale-pass.json")));
    assertEquals(1, Files.list(reports).count());
    JsonNode check = JsonSupport.parse(Files.readAllBytes(result.reportPath()));
    assertEquals(M05CheckRunner.SYSTEM_ERROR, check.path("status").stringValue());
  }
}
