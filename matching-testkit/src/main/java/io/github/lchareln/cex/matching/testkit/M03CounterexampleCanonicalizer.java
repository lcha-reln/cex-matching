package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.reference.ReferenceCommand;
import io.github.lchareln.cex.matching.reference.SemanticBook;
import io.github.lchareln.cex.matching.reference.SemanticEvent;
import io.github.lchareln.cex.matching.reference.SemanticOutcome;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.JsonNode;

/** Host-independent M03X1 encoding of persisted generated counterexamples. */
final class M03CounterexampleCanonicalizer {
  CanonicalCounterexamples canonicalize(JsonNode persisted) {
    Objects.requireNonNull(persisted, "persisted");
    JsonNode scenarios = requiredArray(persisted, "scenarios");
    StringBuilder canonical = new StringBuilder();
    canonical
        .append("M03X1|schemaVersion=")
        .append(framed(requiredText(persisted, "schemaVersion")))
        .append("|profileSha256=")
        .append(requiredText(persisted, "profileSha256"))
        .append("|generatorAlgorithm=")
        .append(framed(requiredText(persisted, "generatorAlgorithm")))
        .append("|seedDerivation=")
        .append(framed(requiredText(persisted, "seedDerivation")))
        .append("|modelVersion=")
        .append(framed(requiredText(persisted, "modelVersion")))
        .append("|scenarios=")
        .append(scenarios.size())
        .append('\n');

    int originalCommandCount = 0;
    int commandCount = 0;
    for (int scenarioIndex = 0; scenarioIndex < scenarios.size(); scenarioIndex++) {
      JsonNode scenario = scenarios.get(scenarioIndex);
      JsonNode originalNodes = requiredArray(scenario, "originalCommands");
      JsonNode commandNodes = requiredArray(scenario, "commands");
      canonical
          .append("M03XS1|scenario=")
          .append(scenarioIndex)
          .append("|scenarioId=")
          .append(framed(requiredText(scenario, "scenarioId")))
          .append("|mutantId=")
          .append(framed(requiredText(scenario, "mutantId")))
          .append("|classification=")
          .append(framed(requiredText(scenario, "classification")))
          .append("|propertyId=")
          .append(framed(requiredText(scenario, "propertyId")))
          .append("|divergenceKind=")
          .append(framed(requiredText(scenario, "divergenceKind")))
          .append("|historyIndex=")
          .append(requiredIntegerText(scenario, "historyIndex"))
          .append("|lane=")
          .append(framed(requiredText(scenario, "lane")))
          .append("|seed=")
          .append(requiredText(scenario, "seed"))
          .append("|originalCommandCount=")
          .append(requiredIntegerText(scenario, "originalCommandCount"))
          .append("|minimizedCommandCount=")
          .append(requiredIntegerText(scenario, "minimizedCommandCount"))
          .append("|firstFailingCommandIndex=")
          .append(requiredIntegerText(scenario, "firstFailingCommandIndex"))
          .append("|oneMinimal=")
          .append(requiredBoolean(scenario, "oneMinimal"))
          .append("|shrinkTrials=")
          .append(requiredIntegerText(scenario, "shrinkTrials"))
          .append('\n');

      for (int commandIndex = 0; commandIndex < originalNodes.size(); commandIndex++) {
        appendCommand(
            canonical,
            "M03XO1",
            scenarioIndex,
            commandIndex,
            null,
            M03Json.command(originalNodes.get(commandIndex)));
        originalCommandCount++;
      }
      for (int commandIndex = 0; commandIndex < commandNodes.size(); commandIndex++) {
        JsonNode commandNode = commandNodes.get(commandIndex);
        appendCommand(
            canonical,
            "M03XC1",
            scenarioIndex,
            commandIndex,
            requiredText(commandNode, "caseId"),
            M03Json.command(commandNode));
        appendOutcome(
            canonical,
            "EXPECTED",
            scenarioIndex,
            commandIndex,
            M03Json.outcome(requiredObject(commandNode, "expected")));
        commandCount++;
      }
      appendOutcome(
          canonical,
          "ACTUAL",
          scenarioIndex,
          requiredInteger(scenario, "firstFailingCommandIndex"),
          M03Json.outcome(requiredObject(scenario, "actualAtFailure")));
    }

    byte[] bytes = canonical.toString().getBytes(StandardCharsets.UTF_8);
    return new CanonicalCounterexamples(
        bytes, Hashing.semanticDigest(bytes), scenarios.size(), originalCommandCount, commandCount);
  }

  private static void appendCommand(
      StringBuilder result,
      String record,
      int scenarioIndex,
      int commandIndex,
      String caseId,
      ReferenceCommand command) {
    result
        .append(record)
        .append("|scenario=")
        .append(scenarioIndex)
        .append("|command=")
        .append(commandIndex);
    if (caseId != null) {
      result.append("|caseId=").append(framed(caseId));
    }
    switch (command) {
      case ReferenceCommand.Place place ->
          result
              .append("|type=PLACE|instrumentId=")
              .append(framed(place.instrumentId()))
              .append("|orderId=")
              .append(place.orderId())
              .append("|side=")
              .append(framed(place.side()))
              .append("|priceTicks=")
              .append(place.priceTicks())
              .append("|quantityLots=")
              .append(place.quantityLots())
              .append('\n');
      case ReferenceCommand.Cancel cancel ->
          result
              .append("|type=CANCEL|instrumentId=")
              .append(framed(cancel.instrumentId()))
              .append("|orderId=")
              .append(cancel.orderId())
              .append('\n');
    }
  }

  private static void appendOutcome(
      StringBuilder result,
      String scope,
      int scenarioIndex,
      int commandIndex,
      SemanticOutcome outcome) {
    result
        .append("M03XR1|scope=")
        .append(scope)
        .append("|scenario=")
        .append(scenarioIndex)
        .append("|command=")
        .append(commandIndex)
        .append("|events=")
        .append(outcome.events().size())
        .append('\n');
    for (int eventIndex = 0; eventIndex < outcome.events().size(); eventIndex++) {
      appendEvent(
          result, scope, scenarioIndex, commandIndex, eventIndex, outcome.events().get(eventIndex));
    }
    appendBook(result, scope, scenarioIndex, commandIndex, outcome.bookAfter());
  }

  private static void appendEvent(
      StringBuilder result,
      String scope,
      int scenarioIndex,
      int commandIndex,
      int eventIndex,
      SemanticEvent event) {
    result
        .append("M03XE1|scope=")
        .append(scope)
        .append("|scenario=")
        .append(scenarioIndex)
        .append("|command=")
        .append(commandIndex)
        .append("|event=")
        .append(eventIndex);
    switch (event) {
      case SemanticEvent.Rejected rejected ->
          result
              .append("|type=REJECTED|code=")
              .append(framed(rejected.code()))
              .append("|field=")
              .append(framed(rejected.field()));
      case SemanticEvent.PlaceRejected rejected ->
          result
              .append("|type=PLACE_REJECTED|orderId=")
              .append(rejected.orderId())
              .append("|code=")
              .append(framed(rejected.code()));
      case SemanticEvent.CancelRejected rejected ->
          result
              .append("|type=CANCEL_REJECTED|orderId=")
              .append(rejected.orderId())
              .append("|code=")
              .append(framed(rejected.code()));
      case SemanticEvent.Accepted accepted ->
          result
              .append("|type=ACCEPTED|sequence=")
              .append(accepted.sequence())
              .append("|orderId=")
              .append(accepted.orderId())
              .append("|side=")
              .append(framed(accepted.side()))
              .append("|priceTicks=")
              .append(accepted.priceTicks())
              .append("|quantityLots=")
              .append(accepted.quantityLots());
      case SemanticEvent.Trade trade ->
          result
              .append("|type=TRADE|makerSequence=")
              .append(trade.makerSequence())
              .append("|makerOrderId=")
              .append(trade.makerOrderId())
              .append("|takerSequence=")
              .append(trade.takerSequence())
              .append("|takerOrderId=")
              .append(trade.takerOrderId())
              .append("|priceTicks=")
              .append(trade.priceTicks())
              .append("|quantityLots=")
              .append(trade.quantityLots());
      case SemanticEvent.Rested rested ->
          result
              .append("|type=RESTED|sequence=")
              .append(rested.sequence())
              .append("|orderId=")
              .append(rested.orderId())
              .append("|side=")
              .append(framed(rested.side()))
              .append("|priceTicks=")
              .append(rested.priceTicks())
              .append("|remainingQuantityLots=")
              .append(rested.remainingQuantityLots());
      case SemanticEvent.RemainderCanceled canceled ->
          throw new IllegalStateException(
              "M03 GTC canonicalizer received M04 policy event: "
                  + canceled.getClass().getSimpleName());
      case SemanticEvent.Canceled canceled ->
          result
              .append("|type=CANCELED|sequence=")
              .append(canceled.sequence())
              .append("|orderId=")
              .append(canceled.orderId())
              .append("|side=")
              .append(framed(canceled.side()))
              .append("|priceTicks=")
              .append(canceled.priceTicks())
              .append("|canceledQuantityLots=")
              .append(canceled.canceledQuantityLots());
    }
    result.append('\n');
  }

  private static void appendBook(
      StringBuilder result, String scope, int scenarioIndex, int commandIndex, SemanticBook book) {
    result
        .append("M03XB1|scope=")
        .append(scope)
        .append("|scenario=")
        .append(scenarioIndex)
        .append("|command=")
        .append(commandIndex)
        .append("|bids=")
        .append(book.bids().size())
        .append("|asks=")
        .append(book.asks().size())
        .append('\n');
    appendLevels(result, scope, scenarioIndex, commandIndex, "BUY", book.bids());
    appendLevels(result, scope, scenarioIndex, commandIndex, "SELL", book.asks());
  }

  private static void appendLevels(
      StringBuilder result,
      String scope,
      int scenarioIndex,
      int commandIndex,
      String side,
      List<SemanticBook.PriceLevel> levels) {
    for (int levelIndex = 0; levelIndex < levels.size(); levelIndex++) {
      SemanticBook.PriceLevel level = levels.get(levelIndex);
      result
          .append("M03XL1|scope=")
          .append(scope)
          .append("|scenario=")
          .append(scenarioIndex)
          .append("|command=")
          .append(commandIndex)
          .append("|side=")
          .append(side)
          .append("|level=")
          .append(levelIndex)
          .append("|priceTicks=")
          .append(level.priceTicks())
          .append("|orders=")
          .append(level.orders().size())
          .append('\n');
      for (int queueIndex = 0; queueIndex < level.orders().size(); queueIndex++) {
        SemanticBook.RestingOrder order = level.orders().get(queueIndex);
        result
            .append("M03XQ1|scope=")
            .append(scope)
            .append("|scenario=")
            .append(scenarioIndex)
            .append("|command=")
            .append(commandIndex)
            .append("|side=")
            .append(side)
            .append("|level=")
            .append(levelIndex)
            .append("|queue=")
            .append(queueIndex)
            .append("|sequence=")
            .append(order.sequence())
            .append("|orderId=")
            .append(order.orderId())
            .append("|remainingQuantityLots=")
            .append(order.remainingQuantityLots())
            .append('\n');
      }
    }
  }

  private static String framed(String value) {
    Objects.requireNonNull(value, "canonical string");
    return value.getBytes(StandardCharsets.UTF_8).length + ":" + value;
  }

  private static JsonNode requiredObject(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (!value.isObject()) {
      throw malformed(field + " must be an object");
    }
    return value;
  }

  private static JsonNode requiredArray(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (!value.isArray()) {
      throw malformed(field + " must be an array");
    }
    return value;
  }

  private static String requiredText(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (!value.isString()) {
      throw malformed(field + " must be a string");
    }
    return value.stringValue();
  }

  private static int requiredInteger(JsonNode node, String field) {
    try {
      return node.path(field).bigIntegerValue().intValueExact();
    } catch (ArithmeticException exception) {
      throw malformed(field + " must fit in an int");
    }
  }

  private static String requiredIntegerText(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (!value.isIntegralNumber()) {
      throw malformed(field + " must be an integer");
    }
    return value.bigIntegerValue().toString();
  }

  private static boolean requiredBoolean(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (!value.isBoolean()) {
      throw malformed(field + " must be a boolean");
    }
    return value.booleanValue();
  }

  private static FixtureSchemaException malformed(String message) {
    return new FixtureSchemaException("malformed M03 counterexample: " + message);
  }

  static final class CanonicalCounterexamples {
    private final byte[] bytes;
    private final String digest;
    private final int scenarioCount;
    private final int originalCommandCount;
    private final int commandCount;

    private CanonicalCounterexamples(
        byte[] bytes,
        String digest,
        int scenarioCount,
        int originalCommandCount,
        int commandCount) {
      this.bytes = bytes.clone();
      this.digest = Objects.requireNonNull(digest, "digest");
      this.scenarioCount = scenarioCount;
      this.originalCommandCount = originalCommandCount;
      this.commandCount = commandCount;
    }

    byte[] bytes() {
      return bytes.clone();
    }

    String digest() {
      return digest;
    }

    int scenarioCount() {
      return scenarioCount;
    }

    int originalCommandCount() {
      return originalCommandCount;
    }

    int commandCount() {
      return commandCount;
    }
  }
}
