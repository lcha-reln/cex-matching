package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.reference.M06MarketRuleSetArtifact;
import io.github.lchareln.cex.matching.reference.M06RuleSetIdentity;
import io.github.lchareln.cex.matching.reference.M07ReferenceCommand;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** LF/UTF-8 canonical encodings used only as finite-corpus reproducibility evidence. */
final class M07Canonical {
  private M07Canonical() {}

  static Canonical fixed(M07Corpus.Fixed corpus) {
    List<String> lines = new ArrayList<>();
    lines.add("M07F1");
    for (M07Corpus.Scenario scenario : corpus.scenarios()) {
      for (M07Corpus.Case item : scenario.cases()) {
        lines.add(
            "scenario="
                + text(scenario.id())
                + "|case="
                + text(item.id())
                + "|command="
                + command(item.command()));
      }
    }
    return canonical(lines, corpus.commands());
  }

  static Canonical generated(List<M07GeneratedSuite.History> histories) {
    List<String> lines = new ArrayList<>();
    lines.add("M07H1");
    int commandCount = 0;
    for (M07GeneratedSuite.History history : histories) {
      for (int index = 0; index < history.commands().size(); index++) {
        lines.add(
            "history="
                + history.index()
                + "|lane="
                + text(history.lane())
                + "|seed="
                + history.seedHex()
                + "|commandIndex="
                + index
                + "|command="
                + command(history.commands().get(index)));
        commandCount++;
      }
    }
    return canonical(lines, commandCount);
  }

  static String command(M07ReferenceCommand command) {
    return switch (command) {
      case M07ReferenceCommand.Place place ->
          String.join(
              "|",
              "PLACE",
              place.entrypoint().name(),
              identity(place.expectedRuleSet()),
              text(place.instrumentId()),
              place.orderId().toString(),
              place.side(),
              place.priceTicks().toString(),
              place.quantityLots().toString(),
              place.executionPolicy(),
              place.participantGroupId().toString(),
              text(place.stpPolicy()));
      case M07ReferenceCommand.Cancel cancel ->
          String.join("|", "CANCEL", text(cancel.instrumentId()), cancel.orderId().toString());
      case M07ReferenceCommand.PrepareRuleSet prepare ->
          String.join(
              "|",
              "PREPARE_RULE_SET",
              identity(prepare.expectedActive()),
              artifact(prepare.artifact()));
      case M07ReferenceCommand.ActivateRuleSet activate ->
          String.join(
              "|",
              "ACTIVATE_RULE_SET",
              activate.expectedApplicationSequence().toString(),
              identity(activate.expectedActive()),
              identity(activate.target()));
      case M07ReferenceCommand.ChangeMarketMode change ->
          String.join(
              "|",
              "CHANGE_MARKET_MODE",
              change.expectedApplicationSequence().toString(),
              change.expectedMode(),
              change.targetMode(),
              text(change.operatorId()));
      case M07ReferenceCommand.MassCancel mass ->
          String.join(
              "|",
              "MASS_CANCEL",
              mass.expectedApplicationSequence().toString(),
              mass.expectedMode(),
              text(mass.operatorId()));
    };
  }

  static Canonical canonical(List<String> lines, int commandCount) {
    String value = String.join("\n", lines) + "\n";
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    return new Canonical(bytes, Hashing.sha256Hex(bytes), lines.size(), commandCount);
  }

  private static String artifact(M06MarketRuleSetArtifact value) {
    return String.join(
        ",",
        text(value.schemaVersion()),
        text(value.instrumentId()),
        value.version().toString(),
        value.lowerInclusive().toString(),
        value.upperInclusive().toString(),
        text(value.contentHash()));
  }

  private static String identity(M06RuleSetIdentity value) {
    return value == null ? "-" : value.version() + "," + text(value.contentHash());
  }

  private static String text(String value) {
    return value.length() + ":" + value;
  }

  record Canonical(byte[] bytes, String digest, int lines, int commands) {
    Canonical {
      bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
      return bytes.clone();
    }
  }
}
