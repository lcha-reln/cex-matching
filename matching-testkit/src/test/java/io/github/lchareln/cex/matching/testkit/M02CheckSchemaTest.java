package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

final class M02CheckSchemaTest {
  @Test
  void rejectsPassReportsWithUnknownFieldsAndWrongMutantOrder() throws IOException {
    String schema =
        Files.readString(
            M02TestPaths.root().resolve(M02CheckRunner.CHECK_SCHEMA_PATH), StandardCharsets.UTF_8);
    ObjectNode malformed = JsonSupport.MAPPER.createObjectNode();
    malformed.put("schemaVersion", M02CheckRunner.SCHEMA_VERSION);
    malformed.put("unit", "M02");
    malformed.put("status", M02CheckRunner.PASS);
    malformed.put("unexpected", true);

    assertThrows(
        FixtureSchemaException.class, () -> JsonSupport.validate(malformed, schema, false));
  }
}
