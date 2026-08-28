package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.CancelOrderInput;
import io.github.lchareln.cex.matching.PlaceLimitOrderInput;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Encodes every M02 command, input, ordered event, and full-depth book without host metadata. */
public final class M02Canonicalizer {
  public M02CanonicalHistory canonicalize(M02RunHistory run) {
    StringBuilder history = new StringBuilder();
    history
        .append("M02H1|scenarios=")
        .append(run.scenarios().size())
        .append("|commands=")
        .append(run.commandCount())
        .append('\n');
    for (int scenarioIndex = 0; scenarioIndex < run.scenarios().size(); scenarioIndex++) {
      M02RunHistory.ScenarioRun scenario = run.scenarios().get(scenarioIndex);
      history
          .append("M02S1|scenario=")
          .append(scenarioIndex)
          .append("|scenarioId=")
          .append(framed(scenario.scenarioId()))
          .append('\n');
      for (int commandIndex = 0; commandIndex < scenario.commands().size(); commandIndex++) {
        M02RunHistory.CommandRun command = scenario.commands().get(commandIndex);
        history.append(commandLine(scenarioIndex, commandIndex, command));
        for (int eventIndex = 0; eventIndex < command.events().size(); eventIndex++) {
          history.append(
              eventLine(scenarioIndex, commandIndex, eventIndex, command.events().get(eventIndex)));
        }
        history.append(bookLines(scenarioIndex, commandIndex, command.bookAfter()));
      }
    }
    byte[] bytes = history.toString().getBytes(StandardCharsets.UTF_8);
    return new M02CanonicalHistory(bytes, Hashing.semanticDigest(bytes), countLines(bytes));
  }

  public M02RunHistory expectedHistory(M02ScenarioPack pack) {
    List<M02RunHistory.ScenarioRun> scenarios = new ArrayList<>();
    for (M02ScenarioPack.Scenario scenario : pack.scenarios()) {
      List<M02RunHistory.CommandRun> commands = new ArrayList<>();
      for (M02ScenarioPack.Command command : scenario.commands()) {
        commands.add(
            switch (command) {
              case M02ScenarioPack.PlaceCommand place ->
                  new M02RunHistory.PlaceRun(
                      place.caseId(),
                      place.input(),
                      place.expected().events(),
                      place.expected().bookAfter());
              case M02ScenarioPack.CancelCommand cancel ->
                  new M02RunHistory.CancelRun(
                      cancel.caseId(),
                      cancel.input(),
                      cancel.expected().events(),
                      cancel.expected().bookAfter());
            });
      }
      scenarios.add(new M02RunHistory.ScenarioRun(scenario.scenarioId(), commands));
    }
    return new M02RunHistory(scenarios);
  }

  private static String commandLine(
      int scenarioIndex, int commandIndex, M02RunHistory.CommandRun command) {
    StringBuilder line =
        new StringBuilder()
            .append("M02C1|scenario=")
            .append(scenarioIndex)
            .append("|command=")
            .append(commandIndex)
            .append("|caseId=")
            .append(framed(command.caseId()))
            .append("|type=")
            .append(command.type());
    switch (command) {
      case M02RunHistory.PlaceRun place -> appendPlaceInput(line, place.input());
      case M02RunHistory.CancelRun cancel -> appendCancelInput(line, cancel.input());
    }
    return line.append('\n').toString();
  }

  private static void appendPlaceInput(StringBuilder line, PlaceLimitOrderInput input) {
    line.append("|instrumentId=")
        .append(framed(input.instrumentId()))
        .append("|orderId=")
        .append(input.orderId())
        .append("|side=")
        .append(framed(input.side()))
        .append("|priceTicks=")
        .append(input.priceTicks())
        .append("|quantityLots=")
        .append(input.quantityLots());
  }

  private static void appendCancelInput(StringBuilder line, CancelOrderInput input) {
    line.append("|instrumentId=")
        .append(framed(input.instrumentId()))
        .append("|orderId=")
        .append(input.orderId());
  }

  private static String eventLine(
      int scenarioIndex, int commandIndex, int eventIndex, M02ScenarioPack.Event event) {
    String prefix =
        "M02E1|scenario="
            + scenarioIndex
            + "|command="
            + commandIndex
            + "|event="
            + eventIndex
            + "|type="
            + event.type();
    return switch (event) {
      case M02ScenarioPack.Rejected rejected ->
          prefix + "|code=" + rejected.code() + "|field=" + rejected.field() + "\n";
      case M02ScenarioPack.PlaceRejected rejected ->
          prefix + "|orderId=" + rejected.orderId() + "|code=" + rejected.code() + "\n";
      case M02ScenarioPack.CancelRejected rejected ->
          prefix + "|orderId=" + rejected.orderId() + "|code=" + rejected.code() + "\n";
      case M02ScenarioPack.Accepted accepted ->
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
      case M02ScenarioPack.Trade trade ->
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
      case M02ScenarioPack.Rested rested ->
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
      case M02ScenarioPack.Canceled canceled ->
          prefix
              + "|sequence="
              + canceled.sequence()
              + "|orderId="
              + canceled.orderId()
              + "|side="
              + canceled.side()
              + "|priceTicks="
              + canceled.priceTicks()
              + "|canceledQuantityLots="
              + canceled.canceledQuantityLots()
              + "\n";
    };
  }

  private static String bookLines(int scenarioIndex, int commandIndex, M02ScenarioPack.Book book) {
    StringBuilder result = new StringBuilder();
    result
        .append("M02B1|scenario=")
        .append(scenarioIndex)
        .append("|command=")
        .append(commandIndex)
        .append("|bids=")
        .append(book.bids().size())
        .append("|asks=")
        .append(book.asks().size())
        .append('\n');
    appendLevels(result, scenarioIndex, commandIndex, "BUY", book.bids());
    appendLevels(result, scenarioIndex, commandIndex, "SELL", book.asks());
    return result.toString();
  }

  private static void appendLevels(
      StringBuilder result,
      int scenarioIndex,
      int commandIndex,
      String side,
      List<M02ScenarioPack.Level> levels) {
    for (int levelIndex = 0; levelIndex < levels.size(); levelIndex++) {
      M02ScenarioPack.Level level = levels.get(levelIndex);
      result
          .append("M02L1|scenario=")
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
        M02ScenarioPack.RestingOrder order = level.orders().get(queueIndex);
        result
            .append("M02O1|scenario=")
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
