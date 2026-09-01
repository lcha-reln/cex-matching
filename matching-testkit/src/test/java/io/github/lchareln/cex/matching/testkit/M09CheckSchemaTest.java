package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

final class M09CheckSchemaTest {
  @Test
  void failureReportCannotRetainAnyPassOnlySection() throws IOException {
    String schema = Files.readString(root().resolve(M09CheckRunner.CHECK_SCHEMA_PATH));
    ObjectNode failure = JsonSupport.MAPPER.createObjectNode();
    failure.put("schemaVersion", "matching.m09.check.v2");
    failure.put("unit", "M09");
    failure.put("status", M09CheckRunner.SYSTEM_ERROR);
    failure.put("contractPlanVersion", "0.11");
    failure.put("detail", "counterfactual system failure");
    ObjectNode release = failure.putObject("releaseTarget");
    release.put("unitTag", "course/m09-complete");
    release.putNull("productRelease");
    release.put("verification", "M09_EVIDENCE_ONLY");
    assertDoesNotThrow(() -> JsonSupport.validate(failure, schema, false));

    for (String section :
        List.of(
            "inheritedM08",
            "inputs",
            "fixed",
            "generator",
            "snapshotRecovery",
            "coverage",
            "faultEvidence",
            "mutants",
            "architecture")) {
      ObjectNode partial = failure.deepCopy();
      partial.putObject(section);
      assertThrows(
          RuntimeException.class,
          () -> JsonSupport.validate(partial, schema, false),
          section + " leaked into a non-PASS report");
    }
  }

  private static Path root() {
    return Path.of(System.getProperty("matching.repositoryRoot")).toAbsolutePath().normalize();
  }
}
