package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.reference.M06MarketRuleSetArtifact;
import io.github.lchareln.cex.matching.reference.M06ReferenceCommand;
import io.github.lchareln.cex.matching.reference.M06RuleSetIdentity;
import java.math.BigInteger;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Strict, lossless JSON form for minimized M06 replay commands. */
final class M06CommandJson {
  private M06CommandJson() {}

  static ObjectNode write(M06ReferenceCommand command) {
    ObjectNode node = JsonSupport.MAPPER.createObjectNode();
    switch (command) {
      case M06ReferenceCommand.Place place -> {
        node.put("type", "PLACE");
        node.put("entrypoint", place.entrypoint().name());
        if (place.expectedRuleSet() != null) {
          identity(node.putObject("expectedRuleSet"), place.expectedRuleSet());
        }
        ObjectNode input = node.putObject("input");
        input.put("instrumentId", place.instrumentId());
        input.put("orderId", place.orderId());
        input.put("side", place.side());
        input.put("priceTicks", place.priceTicks());
        input.put("quantityLots", place.quantityLots());
        input.put("executionPolicy", place.executionPolicy());
      }
      case M06ReferenceCommand.Cancel cancel -> {
        node.put("type", "CANCEL");
        ObjectNode input = node.putObject("input");
        input.put("instrumentId", cancel.instrumentId());
        input.put("orderId", cancel.orderId());
      }
      case M06ReferenceCommand.PrepareRuleSet prepare -> {
        node.put("type", "PREPARE_RULE_SET");
        ObjectNode input = node.putObject("input");
        identity(input.putObject("expectedActive"), prepare.expectedActive());
        artifact(input.putObject("artifact"), prepare.artifact());
      }
      case M06ReferenceCommand.ActivateRuleSet activate -> {
        node.put("type", "ACTIVATE_RULE_SET");
        ObjectNode input = node.putObject("input");
        input.put("expectedApplicationSequence", activate.expectedApplicationSequence());
        identity(input.putObject("expectedActive"), activate.expectedActive());
        identity(input.putObject("target"), activate.target());
      }
      case M06ReferenceCommand.ChangeMarketMode change -> {
        node.put("type", "CHANGE_MARKET_MODE");
        ObjectNode input = node.putObject("input");
        input.put("expectedApplicationSequence", change.expectedApplicationSequence());
        input.put("expectedMode", change.expectedMode());
        input.put("targetMode", change.targetMode());
        input.put("operatorId", change.operatorId());
      }
      case M06ReferenceCommand.MassCancel mass -> {
        node.put("type", "MASS_CANCEL");
        ObjectNode input = node.putObject("input");
        input.put("expectedApplicationSequence", mass.expectedApplicationSequence());
        input.put("expectedMode", mass.expectedMode());
        input.put("operatorId", mass.operatorId());
      }
    }
    return node;
  }

  static M06ReferenceCommand read(JsonNode node) {
    JsonNode input = node.path("input");
    return switch (node.path("type").stringValue()) {
      case "PLACE" -> readPlace(node, input);
      case "CANCEL" ->
          new M06ReferenceCommand.Cancel(
              input.path("instrumentId").stringValue(), integer(input, "orderId"));
      case "PREPARE_RULE_SET" ->
          new M06ReferenceCommand.PrepareRuleSet(
              identity(input.path("expectedActive")), artifact(input.path("artifact")));
      case "ACTIVATE_RULE_SET" ->
          new M06ReferenceCommand.ActivateRuleSet(
              integer(input, "expectedApplicationSequence"),
              identity(input.path("expectedActive")),
              identity(input.path("target")));
      case "CHANGE_MARKET_MODE" ->
          new M06ReferenceCommand.ChangeMarketMode(
              integer(input, "expectedApplicationSequence"),
              input.path("expectedMode").stringValue(),
              input.path("targetMode").stringValue(),
              input.path("operatorId").stringValue());
      case "MASS_CANCEL" ->
          new M06ReferenceCommand.MassCancel(
              integer(input, "expectedApplicationSequence"),
              input.path("expectedMode").stringValue(),
              input.path("operatorId").stringValue());
      default -> throw new IllegalArgumentException("unknown M06 replay command type");
    };
  }

  private static M06ReferenceCommand.Place readPlace(JsonNode node, JsonNode input) {
    if ("GOVERNED".equals(node.path("entrypoint").stringValue())) {
      return M06ReferenceCommand.Place.governed(
          identity(node.path("expectedRuleSet")),
          input.path("instrumentId").stringValue(),
          integer(input, "orderId"),
          input.path("side").stringValue(),
          integer(input, "priceTicks"),
          integer(input, "quantityLots"),
          input.path("executionPolicy").stringValue());
    }
    return M06ReferenceCommand.Place.legacy(
        input.path("instrumentId").stringValue(),
        integer(input, "orderId"),
        input.path("side").stringValue(),
        integer(input, "priceTicks"),
        integer(input, "quantityLots"),
        input.path("executionPolicy").stringValue());
  }

  private static void identity(ObjectNode node, M06RuleSetIdentity value) {
    node.put("version", value.version());
    node.put("contentHash", value.contentHash());
  }

  private static M06RuleSetIdentity identity(JsonNode node) {
    return new M06RuleSetIdentity(integer(node, "version"), node.path("contentHash").stringValue());
  }

  private static void artifact(ObjectNode node, M06MarketRuleSetArtifact value) {
    node.put("schemaVersion", value.schemaVersion());
    node.put("instrumentId", value.instrumentId());
    node.put("version", value.version());
    node.put("lowerInclusive", value.lowerInclusive());
    node.put("upperInclusive", value.upperInclusive());
    node.put("contentHash", value.contentHash());
  }

  private static M06MarketRuleSetArtifact artifact(JsonNode node) {
    return new M06MarketRuleSetArtifact(
        node.path("schemaVersion").stringValue(),
        node.path("instrumentId").stringValue(),
        integer(node, "version"),
        integer(node, "lowerInclusive"),
        integer(node, "upperInclusive"),
        node.path("contentHash").stringValue());
  }

  private static BigInteger integer(JsonNode node, String field) {
    return node.path(field).bigIntegerValue();
  }
}
