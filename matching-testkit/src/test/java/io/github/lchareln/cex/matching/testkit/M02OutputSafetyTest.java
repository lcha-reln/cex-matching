package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

final class M02OutputSafetyTest {
  @Test
  void clearsStalePassArtifactsBeforeWritingAStudentFailure(@TempDir Path outputRoot)
      throws IOException {
    Path reports = Files.createDirectories(outputRoot.resolve("reports"));
    for (String output : M02CheckRunner.OUTPUTS) {
      Files.writeString(reports.resolve(output), "stale");
    }
    M02CheckRunner runner =
        new M02CheckRunner(
            M02Mutants.ghostRestingOrder(M02ProductionCandidate::new),
            List.of(),
            M02Mutants.throwingControl());

    M02CheckRunner.Result result = runner.run(M02TestPaths.root(), reports, outputRoot);

    assertEquals(M02CheckRunner.STUDENT_FAILURE, result.status());
    JsonNode report = JsonSupport.parse(Files.readAllBytes(result.reportPath()));
    assertEquals(M02CheckRunner.STUDENT_FAILURE, report.path("status").stringValue());
    for (String output : M02CheckRunner.OUTPUTS) {
      assertEquals("check.json".equals(output), Files.exists(reports.resolve(output)), output);
    }
  }

  @Test
  void throwingCandidateIsSystemErrorAndCannotLeavePassArtifacts(@TempDir Path outputRoot)
      throws IOException {
    Path reports = outputRoot.resolve("reports");
    M02CheckRunner runner =
        new M02CheckRunner(M02Mutants.throwingControl(), List.of(), M02Mutants.throwingControl());

    M02CheckRunner.Result result = runner.run(M02TestPaths.root(), reports, outputRoot);

    assertEquals(M02CheckRunner.SYSTEM_ERROR, result.status());
    assertTrue(Files.isRegularFile(reports.resolve("check.json")));
    assertFalse(Files.exists(reports.resolve("mutants.json")));
  }

  @Test
  void rejectsSymlinkedAndEscapingOutputsBeforeWriting(@TempDir Path outputRoot)
      throws IOException {
    Path outside = Files.createDirectories(outputRoot.resolve("outside"));
    Path build = Files.createDirectories(outputRoot.resolve("build"));
    Files.createSymbolicLink(build.resolve("reports"), outside);

    assertThrows(
        IllegalStateException.class,
        () ->
            new M02CheckRunner()
                .run(M02TestPaths.root(), build.resolve("reports/m02"), outputRoot));
    assertThrows(
        IllegalStateException.class,
        () ->
            new M02CheckRunner()
                .run(M02TestPaths.root(), outputRoot.resolve("../escape"), outputRoot));
    try (var files = Files.list(outside)) {
      assertTrue(files.findAny().isEmpty());
    }
  }
}
