package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

final class M03CheckSchemaTest {
  @Test
  void rejectsPassReportsWithMissingProofAndUnknownFields() throws IOException {
    String schema =
        Files.readString(
            M02TestPaths.root().resolve(M03CheckRunner.CHECK_SCHEMA_PATH), StandardCharsets.UTF_8);
    ObjectNode malformed = JsonSupport.MAPPER.createObjectNode();
    malformed.put("schemaVersion", M03CheckRunner.SCHEMA_VERSION);
    malformed.put("unit", "M03");
    malformed.put("status", M03CheckRunner.PASS);
    malformed.put("unexpected", true);

    assertThrows(
        FixtureSchemaException.class, () -> JsonSupport.validate(malformed, schema, false));
  }
}
