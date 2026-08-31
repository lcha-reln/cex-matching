package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.reference.M05MarketRuleSetArtifact;
import io.github.lchareln.cex.matching.reference.M05RuleSetIdentity;
import io.github.lchareln.cex.matching.reference.M05SemanticBook;
import io.github.lchareln.cex.matching.reference.M05SemanticEvent;
import io.github.lchareln.cex.matching.reference.M05SemanticMarketState;
import io.github.lchareln.cex.matching.reference.M05SemanticOutcome;
import java.util.Objects;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Lossless JSON projection for the neutral M05 command and outcome vocabulary. */
final class M05Json {
  private M05Json() {}

  static ObjectNode command(M05Command command) {
    ObjectNode node = JsonSupport.MAPPER.createObjectNode();
    ObjectNode input = node.putObject("input");
    switch (Objects.requireNonNull(command, "command")) {
      case M05Command.Place place -> {
        node.put("type", "PLACE");
        node.put("entrypoint", place.entrypoint());
        input.put("instrumentId", place.instrumentId());
        input.put("orderId", place.orderId());
        input.put("side", place.side());
        input.put("priceTicks", place.priceTicks());
        input.put("quantityLots", place.quantityLots());
        input.put("executionPolicy", place.executionPolicy());
        if (place.expectedRuleSet() != null) {
          node.set("expectedRuleSet", identity(place.expectedRuleSet()));
        }
      }
      case M05Command.Cancel cancel -> {
        node.put("type", "CANCEL");
        input.put("instrumentId", cancel.instrumentId());
        input.put("orderId", cancel.orderId());
      }
      case M05Command.PrepareRuleSet prepare -> {
        node.put("type", "PREPARE_RULE_SET");
        input.set("expectedActive", identity(prepare.expectedActive()));
        input.set("artifact", artifact(prepare.artifact()));
      }
      case M05Command.ActivateRuleSet activate -> {
        node.put("type", "ACTIVATE_RULE_SET");
        input.put("expectedApplicationSequence", activate.expectedApplicationSequence());
        input.set("expectedActive", identity(activate.expectedActive()));
        input.set("target", identity(activate.target()));
      }
    }
    return node;
  }

  static ObjectNode replayCommand(String caseId, M05Command command, M05SemanticOutcome expected) {
    ObjectNode node = command(command);
    node.put("caseId", caseId);
    node.set("expected", outcome(expected));
    return node;
  }

  static ObjectNode outcome(M05SemanticOutcome outcome) {
    ObjectNode node = JsonSupport.MAPPER.createObjectNode();
    node.put("applicationSequence", outcome.applicationSequence());
    ArrayNode events = node.putArray("events");
    outcome.events().forEach(event -> events.add(event(event)));
    node.set("stateAfter", state(outcome.stateAfter()));
    return node;
  }

  static ObjectNode event(M05SemanticEvent event) {
    ObjectNode node = JsonSupport.MAPPER.createObjectNode();
    switch (event) {
      case M05SemanticEvent.Rejected rejected -> {
        node.put("type", "REJECTED");
        node.put("code", rejected.code());
        node.put("field", rejected.field());
      }
      case M05SemanticEvent.PlaceRejected rejected -> {
        node.put("type", "PLACE_REJECTED");
        node.put("orderId", rejected.orderId());
        node.put("code", rejected.code());
        node.set("executionRuleSet", identity(rejected.executionRuleSet()));
      }
      case M05SemanticEvent.CancelRejected rejected -> {
        node.put("type", "CANCEL_REJECTED");
        node.put("orderId", rejected.orderId());
        node.put("code", rejected.code());
        node.set("executionRuleSet", identity(rejected.executionRuleSet()));
      }
      case M05SemanticEvent.Accepted accepted -> {
        node.put("type", "ACCEPTED");
        node.put("acceptanceSequence", accepted.acceptanceSequence());
        node.put("orderId", accepted.orderId());
        node.put("side", accepted.side());
        node.put("priceTicks", accepted.priceTicks());
        node.put("quantityLots", accepted.quantityLots());
        node.put("executionPolicy", accepted.executionPolicy());
        node.set("admissionRuleSet", identity(accepted.admissionRuleSet()));
        node.set("executionRuleSet", identity(accepted.executionRuleSet()));
      }
      case M05SemanticEvent.Trade trade -> {
        node.put("type", "TRADE");
        node.put("makerSequence", trade.makerSequence());
        node.put("makerOrderId", trade.makerOrderId());
        node.put("takerSequence", trade.takerSequence());
        node.put("takerOrderId", trade.takerOrderId());
        node.put("priceTicks", trade.priceTicks());
        node.put("quantityLots", trade.quantityLots());
        node.set("makerAdmissionRuleSet", identity(trade.makerAdmissionRuleSet()));
        node.set("takerAdmissionRuleSet", identity(trade.takerAdmissionRuleSet()));
        node.set("executionRuleSet", identity(trade.executionRuleSet()));
      }
      case M05SemanticEvent.Rested rested -> {
        node.put("type", "RESTED");
        node.put("acceptanceSequence", rested.acceptanceSequence());
        node.put("orderId", rested.orderId());
        node.put("side", rested.side());
        node.put("priceTicks", rested.priceTicks());
        node.put("remainingQuantityLots", rested.remainingQuantityLots());
        node.set("admissionRuleSet", identity(rested.admissionRuleSet()));
        node.set("executionRuleSet", identity(rested.executionRuleSet()));
      }
      case M05SemanticEvent.RemainderCanceled canceled -> {
        node.put("type", "REMAINDER_CANCELED");
        node.put("acceptanceSequence", canceled.acceptanceSequence());
        node.put("orderId", canceled.orderId());
        node.put("side", canceled.side());
        node.put("priceTicks", canceled.priceTicks());
        node.put("canceledQuantityLots", canceled.canceledQuantityLots());
        node.put("reason", canceled.reason());
        node.set("admissionRuleSet", identity(canceled.admissionRuleSet()));
        node.set("executionRuleSet", identity(canceled.executionRuleSet()));
      }
      case M05SemanticEvent.Canceled canceled -> {
        node.put("type", "CANCELED");
        node.put("acceptanceSequence", canceled.acceptanceSequence());
        node.put("orderId", canceled.orderId());
        node.put("side", canceled.side());
        node.put("priceTicks", canceled.priceTicks());
        node.put("canceledQuantityLots", canceled.canceledQuantityLots());
        node.set("admissionRuleSet", identity(canceled.admissionRuleSet()));
        node.set("executionRuleSet", identity(canceled.executionRuleSet()));
      }
      case M05SemanticEvent.RuleSetPrepared prepared -> {
        node.put("type", "RULE_SET_PREPARED");
        node.set("identity", identity(prepared.identity()));
        node.put("status", prepared.status().name());
        prepared
            .supersededIdentity()
            .ifPresent(value -> node.set("supersededIdentity", identity(value)));
      }
      case M05SemanticEvent.PrepareRuleSetRejected rejected -> {
        node.put("type", "PREPARE_RULE_SET_REJECTED");
        node.put("code", rejected.code());
      }
      case M05SemanticEvent.RuleSetActivated activated -> {
        node.put("type", "RULE_SET_ACTIVATED");
        node.set("previousActive", identity(activated.previousActive()));
        node.set("active", identity(activated.active()));
        node.set("fence", fence(activated.fence()));
      }
      case M05SemanticEvent.ActivateRuleSetRejected rejected -> {
        node.put("type", "ACTIVATE_RULE_SET_REJECTED");
        node.put("code", rejected.code());
      }
    }
    return node;
  }

  static ObjectNode state(M05SemanticMarketState state) {
    ObjectNode node = JsonSupport.MAPPER.createObjectNode();
    node.put("nextApplicationSequence", state.nextApplicationSequence());
    node.put("nextAcceptanceSequence", state.nextAcceptanceSequence());
    node.put("controlRevision", state.controlRevision());
    node.set("activeRuleSet", artifact(state.activeRuleSet()));
    state.preparedRuleSet().ifPresent(value -> node.set("preparedRuleSet", artifact(value)));
    state.lastActivationFence().ifPresent(value -> node.set("lastActivationFence", fence(value)));
    node.set("book", book(state.book()));
    return node;
  }

  private static ObjectNode book(M05SemanticBook book) {
    ObjectNode node = JsonSupport.MAPPER.createObjectNode();
    node.set("bids", levels(book.bids()));
    node.set("asks", levels(book.asks()));
    return node;
  }

  private static ArrayNode levels(java.util.List<M05SemanticBook.PriceLevel> levels) {
    ArrayNode result = JsonSupport.MAPPER.createArrayNode();
    for (M05SemanticBook.PriceLevel level : levels) {
      ObjectNode levelNode = result.addObject();
      levelNode.put("side", level.side());
      levelNode.put("priceTicks", level.priceTicks());
      ArrayNode orders = levelNode.putArray("orders");
      for (M05SemanticBook.RestingOrder order : level.orders()) {
        ObjectNode orderNode = orders.addObject();
        orderNode.put("acceptanceSequence", order.acceptanceSequence());
        orderNode.put("orderId", order.orderId());
        orderNode.put("remainingQuantityLots", order.remainingQuantityLots());
        orderNode.set("admissionRuleSet", identity(order.admissionRuleSet()));
      }
    }
    return result;
  }

  private static ObjectNode artifact(M05Command.Artifact artifact) {
    ObjectNode node = JsonSupport.MAPPER.createObjectNode();
    node.put("schemaVersion", artifact.schemaVersion());
    node.put("instrumentId", artifact.instrumentId());
    node.put("version", artifact.version());
    node.put("lowerInclusive", artifact.lowerInclusive());
    node.put("upperInclusive", artifact.upperInclusive());
    node.put("contentHash", artifact.contentHash());
    return node;
  }

  private static ObjectNode artifact(M05MarketRuleSetArtifact artifact) {
    ObjectNode node = JsonSupport.MAPPER.createObjectNode();
    node.put("schemaVersion", artifact.schemaVersion());
    node.put("instrumentId", artifact.instrumentId());
    node.put("version", artifact.version());
    node.put("lowerInclusive", artifact.lowerInclusive());
    node.put("upperInclusive", artifact.upperInclusive());
    node.put("contentHash", artifact.contentHash());
    return node;
  }

  private static ObjectNode identity(M05Command.Identity identity) {
    ObjectNode node = JsonSupport.MAPPER.createObjectNode();
    node.put("version", identity.version());
    node.put("contentHash", identity.contentHash());
    return node;
  }

  private static ObjectNode identity(M05RuleSetIdentity identity) {
    ObjectNode node = JsonSupport.MAPPER.createObjectNode();
    node.put("version", identity.version());
    node.put("contentHash", identity.contentHash());
    return node;
  }

  private static ObjectNode fence(M05SemanticMarketState.ActivationFence fence) {
    ObjectNode node = JsonSupport.MAPPER.createObjectNode();
    node.put("applicationSequence", fence.applicationSequence());
    node.put("controlRevision", fence.controlRevision());
    node.put("firstAcceptanceSequence", fence.firstAcceptanceSequence());
    return node;
  }
}
