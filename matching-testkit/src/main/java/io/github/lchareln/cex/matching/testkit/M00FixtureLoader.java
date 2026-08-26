package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.PlaceLimitOrderInput;
import io.github.lchareln.cex.matching.ValidationCode;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** Strict JSON, Draft 2020-12, and lexical boundary for the frozen M00 fixture. */
public final class M00FixtureLoader {
  public M00Fixture load(Path fixturePath, Path schemaPath) {
    try {
      return load(
          Files.readAllBytes(fixturePath), Files.readString(schemaPath, StandardCharsets.UTF_8));
    } catch (IOException exception) {
      throw new FixtureSchemaException("cannot read fixture or schema", exception);
    }
  }

  M00Fixture load(byte[] fixtureBytes, String schemaSource) {
    JsonNode root = JsonSupport.parse(fixtureBytes);
    JsonSupport.validate(root, schemaSource, false);

    List<M00Fixture.Record> records = new ArrayList<>();
    for (JsonNode record : root.path("records")) {
      requireLexicalInteger(record, "orderId");
      requireLexicalInteger(record, "priceTicks");
      requireLexicalInteger(record, "quantityLots");
      requireUnicodeScalarString(record.path("instrumentId"), "instrumentId");
      requireUnicodeScalarString(record.path("side"), "side");

      JsonNode expectedNode = record.path("expected");
      String status = expectedNode.path("status").stringValue();
      ValidationCode code =
          "VALID".equals(status)
              ? null
              : ValidationCode.valueOf(expectedNode.path("code").stringValue());
      String field = "VALID".equals(status) ? null : expectedNode.path("field").stringValue();

      records.add(
          new M00Fixture.Record(
              record.path("caseId").stringValue(),
              new PlaceLimitOrderInput(
                  record.path("instrumentId").stringValue(),
                  integer(record, "orderId"),
                  record.path("side").stringValue(),
                  integer(record, "priceTicks"),
                  integer(record, "quantityLots")),
              new M00Fixture.Expected(status, code, field)));
    }
    return new M00Fixture(records);
  }

  private static BigInteger integer(JsonNode record, String field) {
    return record.path(field).bigIntegerValue();
  }

  private static void requireLexicalInteger(JsonNode record, String field) {
    if (!record.path(field).isIntegralNumber()) {
      throw new FixtureSchemaException(field + " must use an integer JSON token");
    }
  }

  private static void requireUnicodeScalarString(JsonNode node, String field) {
    String value = node.stringValue();
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      if (Character.isHighSurrogate(current)) {
        if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
          throw new FixtureSchemaException(field + " contains an unpaired high surrogate");
        }
        index++;
      } else if (Character.isLowSurrogate(current)) {
        throw new FixtureSchemaException(field + " contains an unpaired low surrogate");
      }
    }
  }
}
