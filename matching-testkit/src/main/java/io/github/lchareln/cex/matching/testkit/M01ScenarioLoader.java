package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.PlaceLimitOrderInput;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Strict JSON, Draft 2020-12, and lexical loader for M01 price-time scenarios. */
public final class M01ScenarioLoader {
  public M01ScenarioPack load(Path fixturePath, Path schemaPath) {
    try {
      return load(
          Files.readAllBytes(fixturePath), Files.readString(schemaPath, StandardCharsets.UTF_8));
    } catch (IOException exception) {
      throw new FixtureSchemaException("cannot read M01 scenario or schema", exception);
    }
  }

  M01ScenarioPack load(byte[] fixtureBytes, String schemaSource) {
    JsonNode root = JsonSupport.parse(fixtureBytes);
    JsonSupport.validate(root, schemaSource, false);

    List<M01ScenarioPack.Scenario> scenarios = new ArrayList<>();
    Set<String> scenarioIds = new HashSet<>();
    Set<String> caseIds = new HashSet<>();
    for (JsonNode scenarioNode : root.path("scenarios")) {
      String scenarioId = scalarString(scenarioNode.path("scenarioId"), "scenarioId");
      requireUnique(scenarioIds, scenarioId, "scenarioId");
      List<M01ScenarioPack.Case> cases = new ArrayList<>();
      for (JsonNode caseNode : scenarioNode.path("commands")) {
        String caseId = scalarString(caseNode.path("caseId"), "caseId");
        requireUnique(caseIds, caseId, "caseId");
        JsonNode inputNode = caseNode.path("input");
        requireLexicalInteger(inputNode, "orderId");
        requireLexicalInteger(inputNode, "priceTicks");
        requireLexicalInteger(inputNode, "quantityLots");
        PlaceLimitOrderInput input =
            new PlaceLimitOrderInput(
                scalarString(inputNode.path("instrumentId"), "instrumentId"),
                integer(inputNode, "orderId"),
                scalarString(inputNode.path("side"), "side"),
                integer(inputNode, "priceTicks"),
                integer(inputNode, "quantityLots"));

        JsonNode expectedNode = caseNode.path("expected");
        List<M01ScenarioPack.Event> events = new ArrayList<>();
        for (JsonNode eventNode : expectedNode.path("events")) {
          events.add(event(eventNode));
        }
        cases.add(
            new M01ScenarioPack.Case(
                caseId,
                input,
                new M01ScenarioPack.Expected(events, book(expectedNode.path("bookAfter")))));
      }
      scenarios.add(new M01ScenarioPack.Scenario(scenarioId, cases));
    }
    return new M01ScenarioPack(scenarios);
  }

  private static M01ScenarioPack.Event event(JsonNode node) {
    return switch (node.path("type").stringValue()) {
      case "REJECTED" ->
          new M01ScenarioPack.Rejected(
              node.path("code").stringValue(), node.path("field").stringValue());
      case "ACCEPTED" ->
          new M01ScenarioPack.Accepted(
              positiveLong(node, "sequence"),
              positiveLong(node, "orderId"),
              node.path("side").stringValue(),
              positiveLong(node, "priceTicks"),
              positiveLong(node, "quantityLots"));
      case "TRADE" ->
          new M01ScenarioPack.Trade(
              positiveLong(node, "makerSequence"),
              positiveLong(node, "makerOrderId"),
              positiveLong(node, "takerSequence"),
              positiveLong(node, "takerOrderId"),
              positiveLong(node, "priceTicks"),
              positiveLong(node, "quantityLots"));
      case "RESTED" ->
          new M01ScenarioPack.Rested(
              positiveLong(node, "sequence"),
              positiveLong(node, "orderId"),
              node.path("side").stringValue(),
              positiveLong(node, "priceTicks"),
              positiveLong(node, "remainingQuantityLots"));
      default -> throw new FixtureSchemaException("unknown M01 event type");
    };
  }

  private static M01ScenarioPack.Book book(JsonNode node) {
    return new M01ScenarioPack.Book(levels(node.path("bids")), levels(node.path("asks")));
  }

  private static List<M01ScenarioPack.Level> levels(JsonNode nodes) {
    List<M01ScenarioPack.Level> levels = new ArrayList<>();
    for (JsonNode node : nodes) {
      List<M01ScenarioPack.RestingOrder> orders = new ArrayList<>();
      for (JsonNode order : node.path("orders")) {
        orders.add(
            new M01ScenarioPack.RestingOrder(
                positiveLong(order, "sequence"),
                positiveLong(order, "orderId"),
                positiveLong(order, "remainingQuantityLots")));
      }
      levels.add(new M01ScenarioPack.Level(positiveLong(node, "priceTicks"), orders));
    }
    return List.copyOf(levels);
  }

  private static BigInteger integer(JsonNode node, String field) {
    return node.path(field).bigIntegerValue();
  }

  private static long positiveLong(JsonNode node, String field) {
    requireLexicalInteger(node, field);
    try {
      return node.path(field).bigIntegerValue().longValueExact();
    } catch (ArithmeticException exception) {
      throw new FixtureSchemaException(field + " is outside long range", exception);
    }
  }

  private static void requireLexicalInteger(JsonNode node, String field) {
    if (!node.path(field).isIntegralNumber()) {
      throw new FixtureSchemaException(field + " must use an integer JSON token");
    }
  }

  private static String scalarString(JsonNode node, String field) {
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
    return value;
  }

  private static void requireUnique(Set<String> values, String value, String field) {
    if (!values.add(value)) {
      throw new FixtureSchemaException("duplicate " + field + ": " + value);
    }
  }
}
