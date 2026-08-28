package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

final class M02CheckRunnerTest {
  @Test
  void writesTheCompleteReportsInFrozenOrderAndValidatesCheckV2(@TempDir Path outputRoot)
      throws IOException {
    Path reports = outputRoot.resolve("build/reports/m02");

    M02CheckRunner.Result result =
        new M02CheckRunner().run(M02TestPaths.root(), reports, outputRoot);

    assertEquals(M02CheckRunner.PASS, result.status());
    JsonNode report = JsonSupport.parse(Files.readAllBytes(result.reportPath()));
    assertEquals("matching.m02.check.v2", report.path("schemaVersion").stringValue());
    assertEquals(8, report.path("scenarioCorpus").path("schemaProbes").intValue());
    assertEquals(4, report.path("lifecycle").path("validationPriorityProbes").intValue());
    assertEquals(100, report.path("replays").path("completed").intValue());
    assertEquals(
        M02CheckRunner.EXPECTED_DIGEST, report.path("canonical").path("digest").stringValue());
    assertEquals(4, report.path("mutants").path("requiredMutants").size());
    assertEquals(
        List.of(
            "m00-m01-regression.json",
            "cancel-event-batches.json",
            "lifecycle.json",
            "registry-invariants.json",
            "canonical-history.utf8",
            "mutants.json",
            "architecture.json"),
        java.util.stream.StreamSupport.stream(report.path("artifacts").spliterator(), false)
            .map(JsonNode::stringValue)
            .toList());
    for (String output : M02CheckRunner.OUTPUTS) {
      assertTrue(Files.isRegularFile(reports.resolve(output)), output);
    }

    JsonNode batches =
        JsonSupport.parse(Files.readAllBytes(reports.resolve("cancel-event-batches.json")));
    assertEquals(
        "matching.m02.cancel-event-batches.v1", batches.path("schemaVersion").stringValue());
    JsonNode first = batches.path("scenarios").get(0).path("cases").get(0);
    assertEquals("PLACE", first.path("type").stringValue());
    assertTrue(first.has("input"));
    assertTrue(first.has("events"));
    assertTrue(first.has("bookAfter"));
    JsonSupport.validate(
        report,
        Files.readString(
            M02TestPaths.root().resolve(M02CheckRunner.CHECK_SCHEMA_PATH), StandardCharsets.UTF_8),
        false);
  }
}
