package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lchareln.cex.matching.PlaceLimitOrderValidator;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

final class M00MutantJudgeTest {
  @TempDir Path temporaryDirectory;

  @Test
  void killsTheRequiredMutantButNotTheSystemErrorControl() throws IOException {
    M00CheckRunner.Result result =
        new M00CheckRunner().run(M00TestPaths.root(), temporaryDirectory, temporaryDirectory);
    assertTrue(result.passed());

    JsonNode mutants = JsonSupport.MAPPER.readTree(temporaryDirectory.resolve("mutants.json"));
    JsonNode required = mutants.path("candidates").path(1);
    assertEquals(M00Mutants.QUANTITY_ZERO_ACCEPTED, required.path("id").stringValue());
    assertEquals(M00CheckRunner.STUDENT_FAILURE, required.path("classification").stringValue());
    assertEquals("quantity-zero", required.path("caseId").stringValue());
    assertTrue(required.path("killed").booleanValue());

    JsonNode systemControl = mutants.path("candidates").path(2);
    assertEquals(M00CheckRunner.SYSTEM_ERROR, systemControl.path("classification").stringValue());
    assertFalse(systemControl.path("killed").booleanValue());
  }

  @Test
  void productionCandidateExceptionRemainsASystemError() throws IOException {
    Path reportDirectory = temporaryDirectory.resolve("production-system-error");
    M00CheckRunner runner =
        new M00CheckRunner(
            M00Mutants.throwingControl(),
            M00Mutants.quantityZeroAccepted(),
            M00Mutants.throwingControl());

    M00CheckRunner.Result result =
        runner.run(M00TestPaths.root(), reportDirectory, temporaryDirectory);

    assertEquals(M00CheckRunner.SYSTEM_ERROR, result.status());
    JsonNode report = JsonSupport.MAPPER.readTree(result.reportPath());
    assertEquals(M00CheckRunner.SYSTEM_ERROR, report.path("status").stringValue());
    assertEquals(
        M00CheckRunner.SYSTEM_ERROR, report.path("failure").path("classification").stringValue());
  }

  @Test
  void requiredMutantExceptionRemainsASystemError() throws IOException {
    Path reportDirectory = temporaryDirectory.resolve("mutant-system-error");
    M00CheckRunner runner =
        new M00CheckRunner(
            new PlaceLimitOrderValidator()::validate,
            M00Mutants.throwingControl(),
            M00Mutants.throwingControl());

    M00CheckRunner.Result result =
        runner.run(M00TestPaths.root(), reportDirectory, temporaryDirectory);

    assertEquals(M00CheckRunner.SYSTEM_ERROR, result.status());
    JsonNode report = JsonSupport.MAPPER.readTree(result.reportPath());
    assertEquals(M00CheckRunner.SYSTEM_ERROR, report.path("status").stringValue());
    assertEquals(
        M00CheckRunner.SYSTEM_ERROR, report.path("failure").path("classification").stringValue());
  }
}
