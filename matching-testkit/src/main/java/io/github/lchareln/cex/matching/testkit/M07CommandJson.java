package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.reference.M06MarketRuleSetArtifact;
import io.github.lchareln.cex.matching.reference.M06RuleSetIdentity;
import io.github.lchareln.cex.matching.reference.M07ReferenceCommand;
import java.math.BigInteger;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Strict, lossless JSON form for minimized M07 replay commands. */
final class M07CommandJson {
  private M07CommandJson() {}

  static ObjectNode write(M07ReferenceCommand command) {
    ObjectNode node = JsonSupport.MAPPER.createObjectNode();
    switch (command) {
      case M07ReferenceCommand.Place place -> {
        node.put("type", "PLACE");
        node.put("entrypoint", place.entrypoint().name());
        if (place.expectedRuleSet() != null) {
          identity(node.putObject("expectedRuleSet"), place.expectedRuleSet());
        }
        if (place.entrypoint() == M07ReferenceCommand.PlaceEntrypoint.STP
            || place.entrypoint() == M07ReferenceCommand.PlaceEntrypoint.GOVERNED_STP) {
          node.put("participantGroupId", place.participantGroupId());
          node.put("stpPolicy", place.stpPolicy());
        }
        ObjectNode input = node.putObject("input");
        input.put("instrumentId", place.instrumentId());
        input.put("orderId", place.orderId());
        input.put("side", place.side());
        input.put("priceTicks", place.priceTicks());
        input.put("quantityLots", place.quantityLots());
        input.put("executionPolicy", place.executionPolicy());
      }
      case M07ReferenceCommand.Cancel cancel -> {
        node.put("type", "CANCEL");
        ObjectNode input = node.putObject("input");
        input.put("instrumentId", cancel.instrumentId());
        input.put("orderId", cancel.orderId());
      }
      case M07ReferenceCommand.PrepareRuleSet prepare -> {
        node.put("type", "PREPARE_RULE_SET");
        ObjectNode input = node.putObject("input");
        identity(input.putObject("expectedActive"), prepare.expectedActive());
        artifact(input.putObject("artifact"), prepare.artifact());
      }
      case M07ReferenceCommand.ActivateRuleSet activate -> {
        node.put("type", "ACTIVATE_RULE_SET");
        ObjectNode input = node.putObject("input");
        input.put("expectedApplicationSequence", activate.expectedApplicationSequence());
        identity(input.putObject("expectedActive"), activate.expectedActive());
        identity(input.putObject("target"), activate.target());
      }
      case M07ReferenceCommand.ChangeMarketMode change -> {
        node.put("type", "CHANGE_MARKET_MODE");
        ObjectNode input = node.putObject("input");
        input.put("expectedApplicationSequence", change.expectedApplicationSequence());
        input.put("expectedMode", change.expectedMode());
        input.put("targetMode", change.targetMode());
        input.put("operatorId", change.operatorId());
      }
      case M07ReferenceCommand.MassCancel mass -> {
        node.put("type", "MASS_CANCEL");
        ObjectNode input = node.putObject("input");
        input.put("expectedApplicationSequence", mass.expectedApplicationSequence());
        input.put("expectedMode", mass.expectedMode());
        input.put("operatorId", mass.operatorId());
      }
    }
    return node;
  }

  static M07ReferenceCommand read(JsonNode node) {
    JsonNode input = node.path("input");
    return switch (node.path("type").stringValue()) {
      case "PLACE" -> readPlace(node, input);
      case "CANCEL" ->
          new M07ReferenceCommand.Cancel(
              input.path("instrumentId").stringValue(), integer(input, "orderId"));
      case "PREPARE_RULE_SET" ->
          new M07ReferenceCommand.PrepareRuleSet(
              identity(input.path("expectedActive")), artifact(input.path("artifact")));
      case "ACTIVATE_RULE_SET" ->
          new M07ReferenceCommand.ActivateRuleSet(
              integer(input, "expectedApplicationSequence"),
              identity(input.path("expectedActive")),
              identity(input.path("target")));
      case "CHANGE_MARKET_MODE" ->
          new M07ReferenceCommand.ChangeMarketMode(
              integer(input, "expectedApplicationSequence"),
              input.path("expectedMode").stringValue(),
              input.path("targetMode").stringValue(),
              input.path("operatorId").stringValue());
      case "MASS_CANCEL" ->
          new M07ReferenceCommand.MassCancel(
              integer(input, "expectedApplicationSequence"),
              input.path("expectedMode").stringValue(),
              input.path("operatorId").stringValue());
      default -> throw new IllegalArgumentException("unknown M07 replay command type");
    };
  }

  private static M07ReferenceCommand.Place readPlace(JsonNode node, JsonNode input) {
    String instrument = input.path("instrumentId").stringValue();
    BigInteger orderId = integer(input, "orderId");
    String side = input.path("side").stringValue();
    BigInteger price = integer(input, "priceTicks");
    BigInteger quantity = integer(input, "quantityLots");
    String executionPolicy = input.path("executionPolicy").stringValue();
    return switch (node.path("entrypoint").stringValue()) {
      case "LEGACY" ->
          M07ReferenceCommand.Place.legacy(
              instrument, orderId, side, price, quantity, executionPolicy);
      case "GOVERNED" ->
          M07ReferenceCommand.Place.governed(
              identity(node.path("expectedRuleSet")),
              instrument,
              orderId,
              side,
              price,
              quantity,
              executionPolicy);
      case "STP" ->
          M07ReferenceCommand.Place.stp(
              instrument,
              orderId,
              side,
              price,
              quantity,
              executionPolicy,
              integer(node, "participantGroupId"),
              node.path("stpPolicy").stringValue());
      case "GOVERNED_STP" ->
          M07ReferenceCommand.Place.governedStp(
              identity(node.path("expectedRuleSet")),
              instrument,
              orderId,
              side,
              price,
              quantity,
              executionPolicy,
              integer(node, "participantGroupId"),
              node.path("stpPolicy").stringValue());
      default -> throw new IllegalArgumentException("unknown M07 replay place entrypoint");
    };
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
