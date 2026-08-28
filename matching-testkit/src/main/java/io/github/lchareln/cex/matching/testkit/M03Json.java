package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.reference.ReferenceCommand;
import io.github.lchareln.cex.matching.reference.SemanticBook;
import io.github.lchareln.cex.matching.reference.SemanticEvent;
import io.github.lchareln.cex.matching.reference.SemanticOutcome;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Lossless JSON binding for the reference-owned M03 command and outcome vocabulary. */
final class M03Json {
  private M03Json() {}

  static ObjectNode command(ReferenceCommand command) {
    Objects.requireNonNull(command, "command");
    ObjectNode node = JsonSupport.MAPPER.createObjectNode();
    ObjectNode input = node.putObject("input");
    switch (command) {
      case ReferenceCommand.Place place -> {
        node.put("type", "PLACE");
        input.put("instrumentId", place.instrumentId());
        input.put("orderId", place.orderId());
        input.put("side", place.side());
        input.put("priceTicks", place.priceTicks());
        input.put("quantityLots", place.quantityLots());
      }
      case ReferenceCommand.Cancel cancel -> {
        node.put("type", "CANCEL");
        input.put("instrumentId", cancel.instrumentId());
        input.put("orderId", cancel.orderId());
      }
    }
    return node;
  }

  static ReferenceCommand command(JsonNode node) {
    requireObject(node, "command");
    JsonNode input = requiredObject(node, "input");
    return switch (requiredText(node, "type")) {
      case "PLACE" ->
          new ReferenceCommand.Place(
              requiredText(input, "instrumentId"),
              requiredInteger(input, "orderId"),
              requiredText(input, "side"),
              requiredInteger(input, "priceTicks"),
              requiredInteger(input, "quantityLots"));
      case "CANCEL" ->
          new ReferenceCommand.Cancel(
              requiredText(input, "instrumentId"), requiredInteger(input, "orderId"));
      default -> throw malformed("unknown M03 command type");
    };
  }

  static ArrayNode commands(List<ReferenceCommand> commands) {
    ArrayNode result = JsonSupport.MAPPER.createArrayNode();
    List.copyOf(commands).forEach(command -> result.add(command(command)));
    return result;
  }

  static List<ReferenceCommand> commands(JsonNode node) {
    requireArray(node, "commands");
    List<ReferenceCommand> commands = new ArrayList<>(node.size());
    node.forEach(command -> commands.add(command(command)));
    return List.copyOf(commands);
  }

  static ObjectNode replayCommand(
      String caseId, ReferenceCommand command, SemanticOutcome expected) {
    ObjectNode node = command(command);
    node.put("caseId", Objects.requireNonNull(caseId, "caseId"));
    node.set("expected", outcome(expected));
    return node;
  }

  static ObjectNode event(SemanticEvent event) {
    Objects.requireNonNull(event, "event");
    ObjectNode node = JsonSupport.MAPPER.createObjectNode();
    switch (event) {
      case SemanticEvent.Rejected rejected -> {
        node.put("type", "REJECTED");
        node.put("code", rejected.code());
        node.put("field", rejected.field());
      }
      case SemanticEvent.PlaceRejected rejected -> {
        node.put("type", "PLACE_REJECTED");
        node.put("orderId", rejected.orderId());
        node.put("code", rejected.code());
      }
      case SemanticEvent.CancelRejected rejected -> {
        node.put("type", "CANCEL_REJECTED");
        node.put("orderId", rejected.orderId());
        node.put("code", rejected.code());
      }
      case SemanticEvent.Accepted accepted -> {
        node.put("type", "ACCEPTED");
        node.put("sequence", accepted.sequence());
        node.put("orderId", accepted.orderId());
        node.put("side", accepted.side());
        node.put("priceTicks", accepted.priceTicks());
        node.put("quantityLots", accepted.quantityLots());
      }
      case SemanticEvent.Trade trade -> {
        node.put("type", "TRADE");
        node.put("makerSequence", trade.makerSequence());
        node.put("makerOrderId", trade.makerOrderId());
        node.put("takerSequence", trade.takerSequence());
        node.put("takerOrderId", trade.takerOrderId());
        node.put("priceTicks", trade.priceTicks());
        node.put("quantityLots", trade.quantityLots());
      }
      case SemanticEvent.Rested rested -> {
        node.put("type", "RESTED");
        node.put("sequence", rested.sequence());
        node.put("orderId", rested.orderId());
        node.put("side", rested.side());
        node.put("priceTicks", rested.priceTicks());
        node.put("remainingQuantityLots", rested.remainingQuantityLots());
      }
      case SemanticEvent.Canceled canceled -> {
        node.put("type", "CANCELED");
        node.put("sequence", canceled.sequence());
        node.put("orderId", canceled.orderId());
        node.put("side", canceled.side());
        node.put("priceTicks", canceled.priceTicks());
        node.put("canceledQuantityLots", canceled.canceledQuantityLots());
      }
    }
    return node;
  }

  static SemanticEvent event(JsonNode node) {
    requireObject(node, "event");
    return switch (requiredText(node, "type")) {
      case "REJECTED" ->
          new SemanticEvent.Rejected(requiredText(node, "code"), requiredText(node, "field"));
      case "PLACE_REJECTED" ->
          new SemanticEvent.PlaceRejected(
              requiredInteger(node, "orderId"), requiredText(node, "code"));
      case "CANCEL_REJECTED" ->
          new SemanticEvent.CancelRejected(
              requiredInteger(node, "orderId"), requiredText(node, "code"));
      case "ACCEPTED" ->
          new SemanticEvent.Accepted(
              requiredInteger(node, "sequence"),
              requiredInteger(node, "orderId"),
              requiredText(node, "side"),
              requiredInteger(node, "priceTicks"),
              requiredInteger(node, "quantityLots"));
      case "TRADE" ->
          new SemanticEvent.Trade(
              requiredInteger(node, "makerSequence"),
              requiredInteger(node, "makerOrderId"),
              requiredInteger(node, "takerSequence"),
              requiredInteger(node, "takerOrderId"),
              requiredInteger(node, "priceTicks"),
              requiredInteger(node, "quantityLots"));
      case "RESTED" ->
          new SemanticEvent.Rested(
              requiredInteger(node, "sequence"),
              requiredInteger(node, "orderId"),
              requiredText(node, "side"),
              requiredInteger(node, "priceTicks"),
              requiredInteger(node, "remainingQuantityLots"));
      case "CANCELED" ->
          new SemanticEvent.Canceled(
              requiredInteger(node, "sequence"),
              requiredInteger(node, "orderId"),
              requiredText(node, "side"),
              requiredInteger(node, "priceTicks"),
              requiredInteger(node, "canceledQuantityLots"));
      default -> throw malformed("unknown M03 event type");
    };
  }

  static ObjectNode book(SemanticBook book) {
    Objects.requireNonNull(book, "book");
    ObjectNode node = JsonSupport.MAPPER.createObjectNode();
    node.set("bids", levels(book.bids()));
    node.set("asks", levels(book.asks()));
    return node;
  }

  static SemanticBook book(JsonNode node) {
    requireObject(node, "book");
    return new SemanticBook(
        levels(requiredArray(node, "bids"), "BUY"), levels(requiredArray(node, "asks"), "SELL"));
  }

  static ObjectNode outcome(SemanticOutcome outcome) {
    Objects.requireNonNull(outcome, "outcome");
    ObjectNode node = JsonSupport.MAPPER.createObjectNode();
    ArrayNode events = node.putArray("events");
    outcome.events().forEach(event -> events.add(event(event)));
    node.set("bookAfter", book(outcome.bookAfter()));
    return node;
  }

  static SemanticOutcome outcome(JsonNode node) {
    requireObject(node, "outcome");
    JsonNode eventNodes = requiredArray(node, "events");
    List<SemanticEvent> events = new ArrayList<>(eventNodes.size());
    eventNodes.forEach(event -> events.add(event(event)));
    return new SemanticOutcome(events, book(requiredObject(node, "bookAfter")));
  }

  private static ArrayNode levels(List<SemanticBook.PriceLevel> levels) {
    ArrayNode result = JsonSupport.MAPPER.createArrayNode();
    for (SemanticBook.PriceLevel level : levels) {
      ObjectNode levelNode = result.addObject();
      levelNode.put("priceTicks", level.priceTicks());
      ArrayNode orders = levelNode.putArray("orders");
      for (SemanticBook.RestingOrder order : level.orders()) {
        ObjectNode orderNode = orders.addObject();
        orderNode.put("sequence", order.sequence());
        orderNode.put("orderId", order.orderId());
        orderNode.put("remainingQuantityLots", order.remainingQuantityLots());
      }
    }
    return result;
  }

  private static List<SemanticBook.PriceLevel> levels(JsonNode nodes, String side) {
    requireArray(nodes, side + " levels");
    List<SemanticBook.PriceLevel> levels = new ArrayList<>(nodes.size());
    for (JsonNode node : nodes) {
      JsonNode orderNodes = requiredArray(node, "orders");
      List<SemanticBook.RestingOrder> orders = new ArrayList<>(orderNodes.size());
      for (JsonNode order : orderNodes) {
        orders.add(
            new SemanticBook.RestingOrder(
                requiredInteger(order, "sequence"),
                requiredInteger(order, "orderId"),
                requiredInteger(order, "remainingQuantityLots")));
      }
      levels.add(new SemanticBook.PriceLevel(side, requiredInteger(node, "priceTicks"), orders));
    }
    return List.copyOf(levels);
  }

  private static JsonNode requiredObject(JsonNode node, String field) {
    JsonNode value = node.path(field);
    requireObject(value, field);
    return value;
  }

  private static JsonNode requiredArray(JsonNode node, String field) {
    JsonNode value = node.path(field);
    requireArray(value, field);
    return value;
  }

  private static String requiredText(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (!value.isString()) {
      throw malformed(field + " must be a string");
    }
    return value.stringValue();
  }

  private static BigInteger requiredInteger(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (!value.isIntegralNumber()) {
      throw malformed(field + " must be an integer");
    }
    return value.bigIntegerValue();
  }

  private static void requireObject(JsonNode node, String field) {
    if (node == null || !node.isObject()) {
      throw malformed(field + " must be an object");
    }
  }

  private static void requireArray(JsonNode node, String field) {
    if (node == null || !node.isArray()) {
      throw malformed(field + " must be an array");
    }
  }

  private static FixtureSchemaException malformed(String message) {
    return new FixtureSchemaException("malformed M03 counterexample: " + message);
  }
}
