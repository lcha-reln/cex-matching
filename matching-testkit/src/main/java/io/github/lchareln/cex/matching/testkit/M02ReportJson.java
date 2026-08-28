package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.CancelOrderInput;
import io.github.lchareln.cex.matching.PlaceLimitOrderInput;
import java.util.List;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Stable JSON encoders for the M02 semantic evidence artifacts. */
final class M02ReportJson {
  private M02ReportJson() {}

  static ObjectNode regression(M02M01Regression.Result result) {
    ObjectNode report = base("matching.m02.m00-m01-regression.v1");
    report.put("status", result.passed() ? "PASS" : "STUDENT_FAILURE");
    report.put("m01CheckStatus", result.passed() ? "PASS" : "STUDENT_FAILURE");
    report.put("m01Scenarios", result.m01Scenarios());
    report.put("m01Commands", result.m01Commands());
    putNullable(report, "m01CanonicalDigest", result.m01Digest());
    M01M00Regression.Result m00 = result.m00();
    ObjectNode inherited = report.putObject("m00Regression");
    inherited.put("status", m00 != null && m00.passed() ? "PASS" : "STUDENT_FAILURE");
    inherited.put("records", m00 == null ? 0 : m00.records());
    inherited.put("completedReplays", m00 == null ? 0 : m00.completedReplays());
    inherited.put("distinctDigests", m00 == null ? 0 : m00.distinctDigests());
    putNullable(inherited, "canonicalDigest", m00 == null ? null : m00.digest());
    report.put("message", result.message());
    return report;
  }

  static ObjectNode eventBatches(String fixtureSha256, M02RunHistory history) {
    ObjectNode report = base("matching.m02.cancel-event-batches.v1");
    report.put("status", "PASS");
    report.put("fixtureSha256", fixtureSha256);
    ArrayNode scenarios = report.putArray("scenarios");
    for (M02RunHistory.ScenarioRun scenario : history.scenarios()) {
      ObjectNode scenarioNode = scenarios.addObject();
      scenarioNode.put("scenarioId", scenario.scenarioId());
      ArrayNode cases = scenarioNode.putArray("cases");
      for (M02RunHistory.CommandRun command : scenario.commands()) {
        ObjectNode node = cases.addObject();
        node.put("caseId", command.caseId());
        node.put("type", command.type());
        node.set("input", input(command));
        ArrayNode events = node.putArray("events");
        command.events().forEach(event -> events.add(event(event)));
        node.set("bookAfter", book(command.bookAfter()));
      }
    }
    return report;
  }

  static ObjectNode lifecycle(
      M02Assertions.Metrics metrics, M02StatefulPriorityProbes.Result priorityProbes) {
    ObjectNode report = base("matching.m02.lifecycle.v1");
    report.put("status", "PASS");
    report.put("commands", metrics.commands());
    report.put("placeCommands", metrics.placeCommands());
    report.put("cancelCommands", metrics.cancelCommands());
    report.put("accepted", metrics.accepted());
    report.put("validationRejected", metrics.validationRejected());
    report.put("placeRejected", metrics.placeRejected());
    report.put("cancelRejected", metrics.cancelRejected());
    report.put("canceled", metrics.canceled());
    report.put("trades", metrics.trades());
    ObjectNode checks = report.putObject("checks");
    checks.put("eventBatchGrammar", metrics.eventBatchChecks());
    checks.put("independentBookTransition", metrics.bookTransitionChecks());
    checks.put("lifecycleTransition", metrics.lifecycleChecks());
    ObjectNode priority = report.putObject("statefulValidationPriority");
    priority.put("status", priorityProbes.passed() ? "PASS" : "STUDENT_FAILURE");
    priority.put("checks", priorityProbes.checks());
    priority.put("message", priorityProbes.message());
    return report;
  }

  static ObjectNode registryInvariants(M02Assertions.Metrics metrics) {
    ObjectNode report = base("matching.m02.registry-invariants.v1");
    report.put("status", "PASS");
    ObjectNode checks = report.putObject("checks");
    checks.put("registryBookBijection", metrics.registryBookChecks());
    checks.put("quantityPartition", metrics.quantityPartitionChecks());
    checks.put("terminalAbsentFromBook", metrics.terminalAbsenceChecks());
    checks.put("bookStructureAndNoCross", metrics.bookStructureChecks());
    report.put("quantityArithmetic", "BigInteger");
    report.put("lifecycleAuthority", "INDEPENDENT_TESTKIT_LEDGER");
    return report;
  }

  static ObjectNode mutants(
      M02Assertions.Observation production,
      List<MutantObservation> required,
      M02Assertions.Observation systemErrorControl) {
    ObjectNode report = base("matching.m02.mutants.v1");
    report.set("productionControl", observation("M02-PRODUCTION-CONTROL", production));
    ArrayNode mutants = report.putArray("requiredMutants");
    for (MutantObservation mutant : required) {
      ObjectNode node = observation(mutant.id(), mutant.observation());
      node.put(
          "killed", M02Assertions.STUDENT_FAILURE.equals(mutant.observation().classification()));
      mutants.add(node);
    }
    report.set("systemErrorControl", observation(M02Mutants.SYSTEM_ERROR_ID, systemErrorControl));
    return report;
  }

  static ObjectNode architecture(M02ArchitectureGate.Report architecture) {
    ObjectNode report = base("matching.m02.architecture.v1");
    report.put("status", architecture.passed() ? "PASS" : "STUDENT_FAILURE");
    report.put("sourceFiles", architecture.sourceFiles());
    ArrayNode violations = report.putArray("violations");
    architecture.violations().forEach(violations::add);
    return report;
  }

  private static ObjectNode input(M02RunHistory.CommandRun command) {
    return switch (command) {
      case M02RunHistory.PlaceRun place -> input(place.input());
      case M02RunHistory.CancelRun cancel -> input(cancel.input());
    };
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

  static ObjectNode input(CancelOrderInput input) {
    ObjectNode node = JsonSupport.MAPPER.createObjectNode();
    node.put("instrumentId", input.instrumentId());
    node.put("orderId", input.orderId());
    return node;
  }

  static ObjectNode event(M02ScenarioPack.Event event) {
    ObjectNode node = JsonSupport.MAPPER.createObjectNode();
    node.put("type", event.type());
    switch (event) {
      case M02ScenarioPack.Rejected rejected -> {
        node.put("code", rejected.code());
        node.put("field", rejected.field());
      }
      case M02ScenarioPack.PlaceRejected rejected -> {
        node.put("orderId", rejected.orderId());
        node.put("code", rejected.code());
      }
      case M02ScenarioPack.CancelRejected rejected -> {
        node.put("orderId", rejected.orderId());
        node.put("code", rejected.code());
      }
      case M02ScenarioPack.Accepted accepted -> {
        node.put("sequence", accepted.sequence());
        node.put("orderId", accepted.orderId());
        node.put("side", accepted.side());
        node.put("priceTicks", accepted.priceTicks());
        node.put("quantityLots", accepted.quantityLots());
      }
      case M02ScenarioPack.Trade trade -> {
        node.put("makerSequence", trade.makerSequence());
        node.put("makerOrderId", trade.makerOrderId());
        node.put("takerSequence", trade.takerSequence());
        node.put("takerOrderId", trade.takerOrderId());
        node.put("priceTicks", trade.priceTicks());
        node.put("quantityLots", trade.quantityLots());
      }
      case M02ScenarioPack.Rested rested -> {
        node.put("sequence", rested.sequence());
        node.put("orderId", rested.orderId());
        node.put("side", rested.side());
        node.put("priceTicks", rested.priceTicks());
        node.put("remainingQuantityLots", rested.remainingQuantityLots());
      }
      case M02ScenarioPack.Canceled canceled -> {
        node.put("sequence", canceled.sequence());
        node.put("orderId", canceled.orderId());
        node.put("side", canceled.side());
        node.put("priceTicks", canceled.priceTicks());
        node.put("canceledQuantityLots", canceled.canceledQuantityLots());
      }
    }
    return node;
  }

  static ObjectNode book(M02ScenarioPack.Book book) {
    ObjectNode node = JsonSupport.MAPPER.createObjectNode();
    node.set("bids", levels(book.bids()));
    node.set("asks", levels(book.asks()));
    return node;
  }

  private static ArrayNode levels(List<M02ScenarioPack.Level> source) {
    ArrayNode result = JsonSupport.MAPPER.createArrayNode();
    for (M02ScenarioPack.Level level : source) {
      ObjectNode levelNode = result.addObject();
      levelNode.put("priceTicks", level.priceTicks());
      ArrayNode orders = levelNode.putArray("orders");
      for (M02ScenarioPack.RestingOrder order : level.orders()) {
        ObjectNode orderNode = orders.addObject();
        orderNode.put("sequence", order.sequence());
        orderNode.put("orderId", order.orderId());
        orderNode.put("remainingQuantityLots", order.remainingQuantityLots());
      }
    }
    return result;
  }

  private static ObjectNode observation(String id, M02Assertions.Observation observation) {
    ObjectNode node = JsonSupport.MAPPER.createObjectNode();
    node.put("id", id);
    node.put("classification", observation.classification());
    putNullable(node, "scenarioId", observation.scenarioId());
    putNullable(node, "caseId", observation.caseId());
    node.put("message", observation.message());
    return node;
  }

  private static void putNullable(ObjectNode node, String field, String value) {
    if (value == null) {
      node.putNull(field);
    } else {
      node.put(field, value);
    }
  }

  private static ObjectNode base(String schemaVersion) {
    ObjectNode report = JsonSupport.MAPPER.createObjectNode();
    report.put("schemaVersion", schemaVersion);
    report.put("unit", "M02");
    return report;
  }

  record MutantObservation(String id, M02Assertions.Observation observation) {}
}
