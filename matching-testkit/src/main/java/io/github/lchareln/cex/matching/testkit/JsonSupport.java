package io.github.lchareln.cex.matching.testkit;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.nio.charset.StandardCharsets;
import java.util.List;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

final class JsonSupport {
  static final ObjectMapper MAPPER =
      JsonMapper.builder(
              JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private JsonSupport() {}

  static JsonNode parse(byte[] bytes) {
    try {
      return MAPPER.readTree(bytes);
    } catch (RuntimeException exception) {
      throw new FixtureSchemaException("fixture is not strict JSON", exception);
    }
  }

  static void validate(JsonNode document, String schemaSource, boolean assertFormats) {
    try {
      Schema schema =
          SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
              .getSchema(schemaSource, InputFormat.JSON);
      List<com.networknt.schema.Error> errors =
          schema.validate(
              document,
              context ->
                  context.executionConfig(config -> config.formatAssertionsEnabled(assertFormats)));
      if (!errors.isEmpty()) {
        throw new FixtureSchemaException("JSON Schema rejected document: " + errors);
      }
    } catch (FixtureSchemaException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new FixtureSchemaException("JSON Schema validation failed", exception);
    }
  }

  static byte[] prettyBytes(JsonNode document) {
    try {
      String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(document);
      return (json + "\n").getBytes(StandardCharsets.UTF_8);
    } catch (RuntimeException exception) {
      throw new IllegalStateException("cannot serialize JSON report", exception);
    }
  }
}
