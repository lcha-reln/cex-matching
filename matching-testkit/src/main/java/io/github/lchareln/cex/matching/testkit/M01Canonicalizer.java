package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.PlaceLimitOrderInput;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Encodes M01 commands, ordered event batches, and full-depth books without host metadata. */
public final class M01Canonicalizer {
  public M01CanonicalHistory canonicalize(M01RunHistory run) {
    StringBuilder history = new StringBuilder();
    history
        .append("M01H1|scenarios=")
        .append(run.scenarios().size())
        .append("|cases=")
        .append(run.caseCount())
        .append('\n');

    for (int scenarioIndex = 0; scenarioIndex < run.scenarios().size(); scenarioIndex++) {
      M01RunHistory.ScenarioRun scenario = run.scenarios().get(scenarioIndex);
      history
          .append("M01S1|scenario=")
          .append(scenarioIndex)
          .append("|scenarioId=")
          .append(framed(scenario.scenarioId()))
          .append('\n');
      for (int caseIndex = 0; caseIndex < scenario.cases().size(); caseIndex++) {
        M01RunHistory.CaseRun caseRun = scenario.cases().get(caseIndex);
        history.append(commandLine(scenarioIndex, caseIndex, caseRun));
        for (int eventIndex = 0; eventIndex < caseRun.events().size(); eventIndex++) {
          history.append(
              eventLine(scenarioIndex, caseIndex, eventIndex, caseRun.events().get(eventIndex)));
        }
        history.append(bookLines(scenarioIndex, caseIndex, caseRun.bookAfter()));
      }
    }

    byte[] bytes = history.toString().getBytes(StandardCharsets.UTF_8);
    return new M01CanonicalHistory(bytes, Hashing.semanticDigest(bytes), countLines(bytes));
  }

  public M01RunHistory expectedHistory(M01ScenarioPack pack) {
    List<M01RunHistory.ScenarioRun> scenarios = new ArrayList<>();
    for (M01ScenarioPack.Scenario scenario : pack.scenarios()) {
      List<M01RunHistory.CaseRun> cases = new ArrayList<>();
      for (M01ScenarioPack.Case caseRecord : scenario.cases()) {
        cases.add(
            new M01RunHistory.CaseRun(
                caseRecord.caseId(),
                caseRecord.input(),
                caseRecord.expected().events(),
                caseRecord.expected().bookAfter()));
      }
      scenarios.add(new M01RunHistory.ScenarioRun(scenario.scenarioId(), cases));
    }
    return new M01RunHistory(scenarios);
  }

  private static String commandLine(
      int scenarioIndex, int caseIndex, M01RunHistory.CaseRun caseRun) {
    PlaceLimitOrderInput input = caseRun.input();
    return new StringBuilder()
        .append("M01C1|scenario=")
        .append(scenarioIndex)
        .append("|case=")
        .append(caseIndex)
        .append("|caseId=")
        .append(framed(caseRun.caseId()))
        .append("|instrumentId=")
        .append(framed(input.instrumentId()))
        .append("|orderId=")
        .append(input.orderId())
        .append("|side=")
        .append(framed(input.side()))
        .append("|priceTicks=")
        .append(input.priceTicks())
        .append("|quantityLots=")
        .append(input.quantityLots())
        .append('\n')
        .toString();
  }

  private static String eventLine(
      int scenarioIndex, int caseIndex, int eventIndex, M01ScenarioPack.Event event) {
    String prefix =
        "M01E1|scenario="
            + scenarioIndex
            + "|case="
            + caseIndex
            + "|event="
            + eventIndex
            + "|type="
            + event.type();
    return switch (event) {
      case M01ScenarioPack.Rejected rejected ->
          prefix + "|code=" + rejected.code() + "|field=" + rejected.field() + "\n";
      case M01ScenarioPack.Accepted accepted ->
          prefix
              + "|sequence="
              + accepted.sequence()
              + "|orderId="
              + accepted.orderId()
              + "|side="
              + accepted.side()
              + "|priceTicks="
              + accepted.priceTicks()
              + "|quantityLots="
              + accepted.quantityLots()
              + "\n";
      case M01ScenarioPack.Trade trade ->
          prefix
              + "|makerSequence="
              + trade.makerSequence()
              + "|makerOrderId="
              + trade.makerOrderId()
              + "|takerSequence="
              + trade.takerSequence()
              + "|takerOrderId="
              + trade.takerOrderId()
              + "|priceTicks="
              + trade.priceTicks()
              + "|quantityLots="
              + trade.quantityLots()
              + "\n";
      case M01ScenarioPack.Rested rested ->
          prefix
              + "|sequence="
              + rested.sequence()
              + "|orderId="
              + rested.orderId()
              + "|side="
              + rested.side()
              + "|priceTicks="
              + rested.priceTicks()
              + "|remainingQuantityLots="
              + rested.remainingQuantityLots()
              + "\n";
    };
  }

  private static String bookLines(int scenarioIndex, int caseIndex, M01ScenarioPack.Book book) {
    StringBuilder result = new StringBuilder();
    result
        .append("M01B1|scenario=")
        .append(scenarioIndex)
        .append("|case=")
        .append(caseIndex)
        .append("|bids=")
        .append(book.bids().size())
        .append("|asks=")
        .append(book.asks().size())
        .append('\n');
    appendLevels(result, scenarioIndex, caseIndex, "BUY", book.bids());
    appendLevels(result, scenarioIndex, caseIndex, "SELL", book.asks());
    return result.toString();
  }

  private static void appendLevels(
      StringBuilder result,
      int scenarioIndex,
      int caseIndex,
      String side,
      List<M01ScenarioPack.Level> levels) {
    for (int levelIndex = 0; levelIndex < levels.size(); levelIndex++) {
      M01ScenarioPack.Level level = levels.get(levelIndex);
      result
          .append("M01L1|scenario=")
          .append(scenarioIndex)
          .append("|case=")
          .append(caseIndex)
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
        M01ScenarioPack.RestingOrder order = level.orders().get(queueIndex);
        result
            .append("M01O1|scenario=")
            .append(scenarioIndex)
            .append("|case=")
            .append(caseIndex)
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
    return value.getBytes(StandardCharsets.UTF_8).length + ":" + value;
  }

  private static int countLines(byte[] bytes) {
    int lines = 0;
    for (byte value : bytes) {
      if (value == '\n') {
        lines++;
      }
    }
    return lines;
  }
}
