package io.github.lchareln.cex.matching.testkit;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import tools.jackson.databind.JsonNode;

/** Stable M04X1 framing for the exact persisted counterexample document. */
final class M04CounterexampleCanonicalizer {
  CanonicalCounterexamples canonicalize(JsonNode persisted) {
    Objects.requireNonNull(persisted, "persisted");
    JsonNode scenarios = persisted.path("scenarios");
    if (!scenarios.isArray()) {
      throw new FixtureSchemaException("malformed M04 counterexamples: scenarios must be an array");
    }
    StringBuilder result = new StringBuilder();
    result
        .append("M04X1|schemaVersion=")
        .append(framed(text(persisted, "schemaVersion")))
        .append("|fixtureSha256=")
        .append(text(persisted, "fixtureSha256"))
        .append("|profileSha256=")
        .append(text(persisted, "profileSha256"))
        .append("|generatorAlgorithm=")
        .append(framed(text(persisted, "generatorAlgorithm")))
        .append("|seedDerivation=")
        .append(framed(text(persisted, "seedDerivation")))
        .append("|modelVersion=")
        .append(framed(text(persisted, "modelVersion")))
        .append("|scenarios=")
        .append(scenarios.size())
        .append('\n');
    int originalCommands = 0;
    int minimizedCommands = 0;
    for (int scenarioIndex = 0; scenarioIndex < scenarios.size(); scenarioIndex++) {
      JsonNode scenario = scenarios.get(scenarioIndex);
      result
          .append("M04XS1|scenario=")
          .append(scenarioIndex)
          .append("|scenarioId=")
          .append(framed(text(scenario, "scenarioId")))
          .append("|mutantId=")
          .append(framed(text(scenario, "mutantId")))
          .append("|classification=")
          .append(framed(text(scenario, "classification")))
          .append("|propertyId=")
          .append(framed(text(scenario, "propertyId")))
          .append("|divergenceKind=")
          .append(framed(text(scenario, "divergenceKind")))
          .append("|sourceKind=")
          .append(framed(text(scenario, "sourceKind")))
          .append("|historyIndex=")
          .append(integer(scenario, "historyIndex"))
          .append("|lane=")
          .append(framed(text(scenario, "lane")))
          .append("|seed=")
          .append(framed(text(scenario, "seed")))
          .append("|coverageKey=")
          .append(framed(text(scenario, "coverageKey")))
          .append("|sourceFailingCommandIndex=")
          .append(integer(scenario, "sourceFailingCommandIndex"))
          .append("|originalCommandCount=")
          .append(integer(scenario, "originalCommandCount"))
          .append("|minimizedCommandCount=")
          .append(integer(scenario, "minimizedCommandCount"))
          .append("|firstFailingCommandIndex=")
          .append(integer(scenario, "firstFailingCommandIndex"))
          .append("|oneMinimal=")
          .append(bool(scenario, "oneMinimal"))
          .append("|shrinkTrials=")
          .append(integer(scenario, "shrinkTrials"))
          .append('\n');
      JsonNode original = array(scenario, "originalCommands");
      for (int commandIndex = 0; commandIndex < original.size(); commandIndex++) {
        appendJson(result, "M04XO1", scenarioIndex, commandIndex, original.get(commandIndex));
        originalCommands++;
      }
      JsonNode commands = array(scenario, "commands");
      for (int commandIndex = 0; commandIndex < commands.size(); commandIndex++) {
        appendJson(result, "M04XC1", scenarioIndex, commandIndex, commands.get(commandIndex));
        minimizedCommands++;
      }
      appendJson(result, "M04XA1", scenarioIndex, -1, scenario.path("actualAtFailure"));
    }
    byte[] bytes = result.toString().getBytes(StandardCharsets.UTF_8);
    return new CanonicalCounterexamples(
        bytes,
        Hashing.semanticDigest(bytes),
        scenarios.size(),
        originalCommands,
        minimizedCommands,
        countLines(bytes));
  }

  private static void appendJson(
      StringBuilder target, String record, int scenario, int command, JsonNode value) {
    byte[] bytes = JsonSupport.prettyBytes(value);
    target
        .append(record)
        .append("|scenario=")
        .append(scenario)
        .append("|command=")
        .append(command)
        .append("|bytes=")
        .append(bytes.length)
        .append("|sha256=")
        .append(Hashing.sha256Hex(bytes))
        .append('|')
        .append(
            new String(bytes, StandardCharsets.UTF_8).replace("\\", "\\\\").replace("\n", "\\n"))
        .append('\n');
  }

  private static JsonNode array(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (!value.isArray()) {
      throw new FixtureSchemaException(
          "malformed M04 counterexamples: " + field + " must be an array");
    }
    return value;
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (!value.isString()) {
      throw new FixtureSchemaException("malformed M04 counterexamples: " + field + " must be text");
    }
    return value.stringValue();
  }

  private static String integer(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (!value.isIntegralNumber()) {
      throw new FixtureSchemaException(
          "malformed M04 counterexamples: " + field + " must be integer");
    }
    return value.bigIntegerValue().toString();
  }

  private static boolean bool(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (!value.isBoolean()) {
      throw new FixtureSchemaException(
          "malformed M04 counterexamples: " + field + " must be boolean");
    }
    return value.booleanValue();
  }

  private static String framed(String value) {
    return value.getBytes(StandardCharsets.UTF_8).length + ":" + value;
  }

  private static int countLines(byte[] bytes) {
    int result = 0;
    for (byte value : bytes) {
      if (value == '\n') {
        result++;
      }
    }
    return result;
  }

  record CanonicalCounterexamples(
      byte[] bytes,
      String digest,
      int scenarios,
      int originalCommands,
      int minimizedCommands,
      int lines) {
    CanonicalCounterexamples {
      bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
      return bytes.clone();
    }
  }
}
