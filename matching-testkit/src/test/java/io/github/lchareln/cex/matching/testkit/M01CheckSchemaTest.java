package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

final class M01CheckSchemaTest {
  @Test
  void nonPassReportsContainOnlyTheFailureContract() {
    String schema =
        readString(M01TestPaths.root().resolve("schemas/matching.m01.check.v2.schema.json"));
    ObjectNode failure = JsonSupport.MAPPER.createObjectNode();
    failure.put("schemaVersion", M01CheckRunner.SCHEMA_VERSION);
    failure.put("unit", "M01");
    failure.put("status", M01CheckRunner.STUDENT_FAILURE);
    failure.putObject("failure").put("message", "business assertion failed");

    assertDoesNotThrow(() -> JsonSupport.validate(failure, schema, false));

    ObjectNode stale = failure.deepCopy();
    stale.put("contractPlanVersion", "0.3");
    assertThrows(FixtureSchemaException.class, () -> JsonSupport.validate(stale, schema, false));

    ObjectNode staleMutants = failure.deepCopy();
    staleMutants.putArray("requiredMutants");
    assertThrows(
        FixtureSchemaException.class, () -> JsonSupport.validate(staleMutants, schema, false));
  }

  private static String readString(java.nio.file.Path path) {
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException("cannot read " + path, exception);
    }
  }
}
