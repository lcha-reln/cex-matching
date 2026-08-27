package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

final class M01CheckRunnerTest {
  private static final List<String> PASS_OUTPUTS =
      List.of(
          "m00-regression.json",
          "price-time.json",
          "event-batches.json",
          "invariants.json",
          "canonical-history.utf8",
          "mutants.json",
          "architecture.json",
          "check.json");

  @Test
  void writesTheCompleteStablePassReportSet(@TempDir Path trustedRoot) {
    Path reports = trustedRoot.resolve("reports/m01");
    M01CheckRunner.Result result =
        new M01CheckRunner().run(M01TestPaths.root(), reports, trustedRoot);

    assertEquals(M01CheckRunner.PASS, result.status());
    PASS_OUTPUTS.forEach(name -> assertTrue(Files.isRegularFile(reports.resolve(name)), name));
    JsonNode check = parse(reports.resolve("check.json"));
    assertEquals("matching.m01.check.v2", check.path("schemaVersion").stringValue());
    assertEquals(100, check.path("replays").path("completed").intValue());
    assertEquals(1, check.path("replays").path("distinctDigests").intValue());
    assertEquals(
        List.of("M01-SAME-PRICE-LIFO", "M01-TAKER-PRICE", "M01-SKIP-FIRST-MAKER"),
        check
            .path("requiredMutants")
            .valueStream()
            .map(node -> node.path("id").stringValue())
            .toList());
    assertTrue(
        check
            .path("requiredMutants")
            .valueStream()
            .allMatch(
                node ->
                    "STUDENT_FAILURE".equals(node.path("classification").stringValue())
                        && node.path("killed").booleanValue()));
    JsonNode m00Regression = parse(reports.resolve("m00-regression.json"));
    assertEquals(15, m00Regression.path("engineInvalidCases").intValue());
    assertEquals(
        "REJECTED_WITHOUT_BOOK_OR_SEQUENCE_MUTATION",
        m00Regression.path("engineInvalidOutcome").stringValue());
    assertEquals(1, m00Regression.path("firstValidSequenceAfterInvalids").intValue());
    String allReports =
        PASS_OUTPUTS.stream()
            .map(reports::resolve)
            .map(M01CheckRunnerTest::readString)
            .reduce("", String::concat);
    assertFalse(allReports.contains(M01TestPaths.root().toString()));
    assertFalse(allReports.contains("generatedAt"));
  }

  @Test
  void studentAndSystemFailuresClearStalePassArtifactsAndRemainDistinct(@TempDir Path trustedRoot) {
    Path reports = trustedRoot.resolve("reports/m01");
    M01CheckRunner.Result pass =
        new M01CheckRunner().run(M01TestPaths.root(), reports, trustedRoot);
    assertEquals(M01CheckRunner.PASS, pass.status());

    M01CheckRunner studentRunner =
        new M01CheckRunner(
            M01Mutants.makerUsesTakerPrice(M01ProductionCandidate::new),
            List.of(),
            M01Mutants.throwingControl());
    M01CheckRunner.Result student = studentRunner.run(M01TestPaths.root(), reports, trustedRoot);
    assertEquals(M01CheckRunner.STUDENT_FAILURE, student.status());
    assertOnlyFailureCheckRemains(reports, M01CheckRunner.STUDENT_FAILURE);

    M01CheckRunner systemRunner =
        new M01CheckRunner(M01Mutants.throwingControl(), List.of(), M01Mutants.throwingControl());
    M01CheckRunner.Result system = systemRunner.run(M01TestPaths.root(), reports, trustedRoot);
    assertEquals(M01CheckRunner.SYSTEM_ERROR, system.status());
    assertOnlyFailureCheckRemains(reports, M01CheckRunner.SYSTEM_ERROR);
  }

  private static void assertOnlyFailureCheckRemains(Path reports, String status) {
    assertTrue(Files.isRegularFile(reports.resolve("check.json")));
    PASS_OUTPUTS.stream()
        .filter(name -> !"check.json".equals(name))
        .forEach(name -> assertFalse(Files.exists(reports.resolve(name)), name));
    JsonNode check = parse(reports.resolve("check.json"));
    assertEquals(status, check.path("status").stringValue());
    assertTrue(check.path("failure").path("message").stringValue().length() > 0);
  }

  private static JsonNode parse(Path path) {
    return JsonSupport.parse(readBytes(path));
  }

  private static String readString(Path path) {
    return new String(readBytes(path), StandardCharsets.UTF_8);
  }

  private static byte[] readBytes(Path path) {
    try {
      return Files.readAllBytes(path);
    } catch (IOException exception) {
      throw new IllegalStateException("cannot read " + path, exception);
    }
  }
}
