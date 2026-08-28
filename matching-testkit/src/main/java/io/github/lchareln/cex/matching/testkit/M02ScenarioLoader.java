package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.CancelOrderInput;
import io.github.lchareln.cex.matching.PlaceLimitOrderInput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Strict JSON, Draft 2020-12, lexical, and identity loader for M02 scenarios. */
public final class M02ScenarioLoader {
  public M02ScenarioPack load(Path fixturePath, Path schemaPath) {
    try {
      return load(
          Files.readAllBytes(fixturePath), Files.readString(schemaPath, StandardCharsets.UTF_8));
    } catch (IOException exception) {
      throw new FixtureSchemaException("cannot read M02 scenario or schema", exception);
    }
  }

  M02ScenarioPack load(byte[] fixtureBytes, String schemaSource) {
    JsonNode root = JsonSupport.parse(fixtureBytes);
    JsonSupport.validate(root, schemaSource, false);
    requireLexicalIntegers(root);

    List<M02ScenarioPack.Scenario> scenarios = new ArrayList<>();
    Set<String> scenarioIds = new HashSet<>();
    Set<String> caseIds = new HashSet<>();
    for (JsonNode scenarioNode : root.path("scenarios")) {
      String scenarioId = scalarString(scenarioNode.path("scenarioId"), "scenarioId");
      requireUnique(scenarioIds, scenarioId, "scenarioId");
      List<M02ScenarioPack.Command> commands = new ArrayList<>();
      for (JsonNode commandNode : scenarioNode.path("commands")) {
        String caseId = scalarString(commandNode.path("caseId"), "caseId");
        requireUnique(caseIds, caseId, "caseId");
        M02ScenarioPack.Expected expected = expected(commandNode.path("expected"));
        JsonNode input = commandNode.path("input");
        commands.add(
            switch (commandNode.path("type").stringValue()) {
              case "PLACE" ->
                  new M02ScenarioPack.PlaceCommand(
                      caseId,
                      new PlaceLimitOrderInput(
                          scalarString(input.path("instrumentId"), "instrumentId"),
                          input.path("orderId").bigIntegerValue(),
                          scalarString(input.path("side"), "side"),
                          input.path("priceTicks").bigIntegerValue(),
                          input.path("quantityLots").bigIntegerValue()),
                      expected);
              case "CANCEL" ->
                  new M02ScenarioPack.CancelCommand(
                      caseId,
                      new CancelOrderInput(
                          scalarString(input.path("instrumentId"), "instrumentId"),
                          input.path("orderId").bigIntegerValue()),
                      expected);
              default -> throw new FixtureSchemaException("unknown M02 command type");
            });
      }
      scenarios.add(new M02ScenarioPack.Scenario(scenarioId, commands));
    }
    return new M02ScenarioPack(scenarios);
  }

  private static M02ScenarioPack.Expected expected(JsonNode node) {
    List<M02ScenarioPack.Event> events = new ArrayList<>();
    for (JsonNode event : node.path("events")) {
      events.add(event(event));
    }
    return new M02ScenarioPack.Expected(events, book(node.path("bookAfter")));
  }

  private static M02ScenarioPack.Event event(JsonNode node) {
    return switch (node.path("type").stringValue()) {
      case "REJECTED" ->
          new M02ScenarioPack.Rejected(
              scalarString(node.path("code"), "code"), scalarString(node.path("field"), "field"));
      case "PLACE_REJECTED" ->
          new M02ScenarioPack.PlaceRejected(
              positiveLong(node, "orderId"), scalarString(node.path("code"), "code"));
      case "CANCEL_REJECTED" ->
          new M02ScenarioPack.CancelRejected(
              positiveLong(node, "orderId"), scalarString(node.path("code"), "code"));
      case "ACCEPTED" ->
          new M02ScenarioPack.Accepted(
              positiveLong(node, "sequence"),
              positiveLong(node, "orderId"),
              scalarString(node.path("side"), "side"),
              positiveLong(node, "priceTicks"),
              positiveLong(node, "quantityLots"));
      case "TRADE" ->
          new M02ScenarioPack.Trade(
              positiveLong(node, "makerSequence"),
              positiveLong(node, "makerOrderId"),
              positiveLong(node, "takerSequence"),
              positiveLong(node, "takerOrderId"),
              positiveLong(node, "priceTicks"),
              positiveLong(node, "quantityLots"));
      case "RESTED" ->
          new M02ScenarioPack.Rested(
              positiveLong(node, "sequence"),
              positiveLong(node, "orderId"),
              scalarString(node.path("side"), "side"),
              positiveLong(node, "priceTicks"),
              positiveLong(node, "remainingQuantityLots"));
      case "CANCELED" ->
          new M02ScenarioPack.Canceled(
              positiveLong(node, "sequence"),
              positiveLong(node, "orderId"),
              scalarString(node.path("side"), "side"),
              positiveLong(node, "priceTicks"),
              positiveLong(node, "canceledQuantityLots"));
      default -> throw new FixtureSchemaException("unknown M02 event type");
    };
  }

  private static M02ScenarioPack.Book book(JsonNode node) {
    return new M02ScenarioPack.Book(levels(node.path("bids")), levels(node.path("asks")));
  }

  private static List<M02ScenarioPack.Level> levels(JsonNode nodes) {
    List<M02ScenarioPack.Level> levels = new ArrayList<>();
    for (JsonNode node : nodes) {
      List<M02ScenarioPack.RestingOrder> orders = new ArrayList<>();
      for (JsonNode order : node.path("orders")) {
        orders.add(
            new M02ScenarioPack.RestingOrder(
                positiveLong(order, "sequence"),
                positiveLong(order, "orderId"),
                positiveLong(order, "remainingQuantityLots")));
      }
      levels.add(new M02ScenarioPack.Level(positiveLong(node, "priceTicks"), orders));
    }
    return List.copyOf(levels);
  }

  private static long positiveLong(JsonNode node, String field) {
    try {
      return node.path(field).bigIntegerValue().longValueExact();
    } catch (ArithmeticException exception) {
      throw new FixtureSchemaException(field + " is outside long range", exception);
    }
  }

  private static void requireLexicalIntegers(JsonNode node) {
    if (node.isNumber() && !node.isIntegralNumber()) {
      throw new FixtureSchemaException("M02 numeric values must use integer JSON tokens");
    }
    for (JsonNode child : node) {
      requireLexicalIntegers(child);
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
