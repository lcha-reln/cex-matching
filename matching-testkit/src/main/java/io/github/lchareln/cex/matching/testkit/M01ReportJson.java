package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.PlaceLimitOrderInput;
import java.util.List;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Stable JSON encoders for M01 semantic reports. */
final class M01ReportJson {
  private M01ReportJson() {}

  static ObjectNode m00Regression(M01M00Regression.Result result) {
    ObjectNode report = base("matching.m01.m00-regression.v1");
    report.put("status", result.passed() ? "PASS" : "STUDENT_FAILURE");
    report.put("fixtureSchemaVersion", "matching.m00.fixture.v1");
    report.put("canonicalFormat", "M00H1");
    report.put("records", result.records());
    report.put("valid", result.valid());
    report.put("invalid", result.invalid());
    report.put("engineInvalidCases", result.invalid());
    report.put("engineInvalidOutcome", "REJECTED_WITHOUT_BOOK_OR_SEQUENCE_MUTATION");
    report.put("firstValidSequenceAfterInvalids", 1);
    report.put("completedReplays", result.completedReplays());
    report.put("distinctDigests", result.distinctDigests());
    report.put("canonicalDigest", result.digest());
    report.put("message", result.message());
    return report;
  }

  static ObjectNode priceTime(String fixtureSha256, M01RunHistory history) {
    ObjectNode report = base("matching.m01.price-time.v1");
    report.put("status", "PASS");
    report.put("fixtureSha256", fixtureSha256);
    report.put("scenarios", history.scenarios().size());
    report.put("cases", history.caseCount());
    return report;
  }

  static ObjectNode eventBatches(M01RunHistory history) {
    ObjectNode report = base("matching.m01.event-books.v1");
    ArrayNode scenarios = report.putArray("scenarios");
    for (M01RunHistory.ScenarioRun scenario : history.scenarios()) {
      ObjectNode scenarioNode = scenarios.addObject();
      scenarioNode.put("scenarioId", scenario.scenarioId());
      ArrayNode cases = scenarioNode.putArray("cases");
      for (M01RunHistory.CaseRun caseRun : scenario.cases()) {
        ObjectNode caseNode = cases.addObject();
        caseNode.put("caseId", caseRun.caseId());
        caseNode.set("input", input(caseRun.input()));
        ArrayNode events = caseNode.putArray("events");
        caseRun.events().forEach(event -> events.add(event(event)));
        caseNode.set("bookAfter", book(caseRun.bookAfter()));
      }
    }
    return report;
  }

  static ObjectNode invariants(M01Assertions.Metrics metrics) {
    ObjectNode report = base("matching.m01.invariants.v1");
    report.put("status", "PASS");
    report.put("cases", metrics.cases());
    report.put("accepted", metrics.accepted());
    report.put("rejected", metrics.rejected());
    report.put("trades", metrics.trades());
    ObjectNode checks = report.putObject("checks");
    checks.put("eventBatchOrder", metrics.eventBatchChecks());
    checks.put("positiveTradeQuantity", metrics.positiveTradeChecks());
    checks.put("quantityConservation", metrics.conservationChecks());
    checks.put("makerPrice", metrics.makerPriceChecks());
    checks.put("priceTimePriority", metrics.priorityChecks());
    checks.put("bookStructureAndNoCross", metrics.bookStructureChecks());
    report.put("aggregateArithmetic", "BigInteger");
    return report;
  }

  static ObjectNode mutants(
      M01Assertions.Observation control,
      List<MutantObservation> required,
      M01Assertions.Observation systemErrorControl) {
    ObjectNode report = base("matching.m01.mutants.v1");
    report.set("productionControl", observation("M01-PRODUCTION-CONTROL", control));
    ArrayNode mutants = report.putArray("requiredMutants");
    required.forEach(item -> mutants.add(mutant(item)));
    report.set("systemErrorControl", observation(M01Mutants.SYSTEM_ERROR_ID, systemErrorControl));
    return report;
  }

  static ObjectNode architecture(M01ArchitectureGate.Report architecture) {
    ObjectNode report = base("matching.m01.architecture.v1");
    report.put("status", architecture.passed() ? "PASS" : "STUDENT_FAILURE");
    report.put("sourceFiles", architecture.sourceFiles());
    ArrayNode violations = report.putArray("violations");
    architecture.violations().forEach(violations::add);
    return report;
  }

  static ObjectNode input(PlaceLimitOrderInput input) {
    ObjectNode node = JsonSupport.MAPPER.createObjectNode();
    node.put("instrumentId", input.instrumentId());
    node.put("orderId", input.orderId());
    node.put("side", input.side());
    node.put("priceTicks", input.priceTicks());
    node.put("quantityLots", input.quantityLots());
    return node;
  }

  static ObjectNode event(M01ScenarioPack.Event event) {
    ObjectNode node = JsonSupport.MAPPER.createObjectNode();
    node.put("type", event.type());
    switch (event) {
      case M01ScenarioPack.Rejected rejected -> {
        node.put("code", rejected.code());
        node.put("field", rejected.field());
      }
      case M01ScenarioPack.Accepted accepted -> {
        node.put("sequence", accepted.sequence());
        node.put("orderId", accepted.orderId());
        node.put("side", accepted.side());
        node.put("priceTicks", accepted.priceTicks());
        node.put("quantityLots", accepted.quantityLots());
      }
      case M01ScenarioPack.Trade trade -> {
        node.put("makerSequence", trade.makerSequence());
        node.put("makerOrderId", trade.makerOrderId());
        node.put("takerSequence", trade.takerSequence());
        node.put("takerOrderId", trade.takerOrderId());
        node.put("priceTicks", trade.priceTicks());
        node.put("quantityLots", trade.quantityLots());
      }
      case M01ScenarioPack.Rested rested -> {
        node.put("sequence", rested.sequence());
        node.put("orderId", rested.orderId());
        node.put("side", rested.side());
        node.put("priceTicks", rested.priceTicks());
        node.put("remainingQuantityLots", rested.remainingQuantityLots());
      }
    }
    return node;
  }

  static ObjectNode book(M01ScenarioPack.Book book) {
    ObjectNode node = JsonSupport.MAPPER.createObjectNode();
    node.set("bids", levels(book.bids()));
    node.set("asks", levels(book.asks()));
    return node;
  }

  private static ArrayNode levels(List<M01ScenarioPack.Level> source) {
    ArrayNode levels = JsonSupport.MAPPER.createArrayNode();
    for (M01ScenarioPack.Level level : source) {
      ObjectNode levelNode = levels.addObject();
      levelNode.put("priceTicks", level.priceTicks());
      ArrayNode orders = levelNode.putArray("orders");
      for (M01ScenarioPack.RestingOrder order : level.orders()) {
        ObjectNode orderNode = orders.addObject();
        orderNode.put("sequence", order.sequence());
        orderNode.put("orderId", order.orderId());
        orderNode.put("remainingQuantityLots", order.remainingQuantityLots());
      }
    }
    return levels;
  }

  private static ObjectNode mutant(MutantObservation mutant) {
    ObjectNode node = observation(mutant.id(), mutant.observation());
    node.put("killed", M01Assertions.STUDENT_FAILURE.equals(mutant.observation().classification()));
    return node;
  }

  private static ObjectNode observation(String id, M01Assertions.Observation observation) {
    ObjectNode node = JsonSupport.MAPPER.createObjectNode();
    node.put("id", id);
    node.put("classification", observation.classification());
    if (observation.scenarioId() == null) {
      node.putNull("scenarioId");
    } else {
      node.put("scenarioId", observation.scenarioId());
    }
    if (observation.caseId() == null) {
      node.putNull("caseId");
    } else {
      node.put("caseId", observation.caseId());
    }
    node.put("message", observation.message());
    return node;
  }

  private static ObjectNode base(String schemaVersion) {
    ObjectNode report = JsonSupport.MAPPER.createObjectNode();
    report.put("schemaVersion", schemaVersion);
    report.put("unit", "M01");
    return report;
  }

  record MutantObservation(String id, M01Assertions.Observation observation) {}
}
