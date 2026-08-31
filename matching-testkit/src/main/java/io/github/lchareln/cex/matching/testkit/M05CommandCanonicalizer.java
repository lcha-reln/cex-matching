package io.github.lchareln.cex.matching.testkit;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/** Host-independent M05F1/M05H1 encoding of commands and complete input histories. */
final class M05CommandCanonicalizer {
  static final String SEED_DERIVATION = "splitmix64(baseSeed+historyIndex).nextLong";

  CanonicalCommands fixed(M05ScenarioCorpus.Corpus corpus) {
    StringBuilder output = new StringBuilder();
    output
        .append("M05F1|scenarios=")
        .append(corpus.scenarios().size())
        .append("|commands=")
        .append(corpus.commandCount())
        .append('\n');
    int count = 0;
    for (M05ScenarioCorpus.Scenario scenario : corpus.scenarios()) {
      output
          .append("M05S1|scenario=")
          .append(framed(scenario.scenarioId()))
          .append("|commands=")
          .append(scenario.steps().size())
          .append('\n');
      for (int index = 0; index < scenario.steps().size(); index++) {
        M05ScenarioCorpus.Step step = scenario.steps().get(index);
        appendCommand(
            output,
            "M05C1|scenario="
                + framed(scenario.scenarioId())
                + "|command="
                + index
                + "|case="
                + framed(step.caseId()),
            step.command());
        count++;
      }
    }
    return result(output, count);
  }

  CanonicalCommands generated(M05GeneratorProfile profile, List<M05GeneratedHistory> histories) {
    List<M05GeneratedHistory> immutable = List.copyOf(histories);
    require(immutable.size() == profile.histories(), "M05 history count differs from profile");
    StringBuilder output = new StringBuilder();
    output
        .append("M05H1|algorithm=")
        .append(M05GeneratorProfile.ALGORITHM)
        .append("|seedDerivation=")
        .append(framed(SEED_DERIVATION))
        .append("|baseSeed=")
        .append(Long.toUnsignedString(profile.baseSeed()))
        .append("|histories=")
        .append(profile.histories())
        .append("|commandsPerHistory=")
        .append(profile.commandsPerHistory())
        .append('\n');
    int count = 0;
    for (int index = 0; index < immutable.size(); index++) {
      M05GeneratedHistory history = immutable.get(index);
      require(history.historyIndex() == index, "M05 history indexes are not contiguous");
      require(
          history.seed() == M04HistoryGenerator.historySeed(profile.baseSeed(), index),
          "M05 history seed changed");
      require(
          profile.laneForHistory(index).id().equals(history.laneId()),
          "M05 lane assignment changed");
      require(
          history.commands().size() == profile.commandsPerHistory(),
          "M05 history command count changed");
      output
          .append("M05R1|history=")
          .append(index)
          .append("|seed=")
          .append(history.seedHex())
          .append("|lane=")
          .append(framed(history.laneId()))
          .append("|commands=")
          .append(history.commands().size())
          .append('\n');
      for (int commandIndex = 0; commandIndex < history.commands().size(); commandIndex++) {
        appendCommand(
            output,
            "M05C1|history=" + index + "|command=" + commandIndex,
            history.commands().get(commandIndex));
        count++;
      }
    }
    return result(output, count);
  }

  private static void appendCommand(StringBuilder output, String prefix, M05Command command) {
    output.append(prefix);
    switch (command) {
      case M05Command.Place place -> {
        output
            .append("|type=PLACE|entrypoint=")
            .append(place.entrypoint())
            .append("|instrumentId=")
            .append(framed(place.instrumentId()))
            .append("|orderId=")
            .append(place.orderId())
            .append("|side=")
            .append(framed(place.side()))
            .append("|priceTicks=")
            .append(place.priceTicks())
            .append("|quantityLots=")
            .append(place.quantityLots())
            .append("|executionPolicy=")
            .append(framed(place.executionPolicy()));
        if (place.expectedRuleSet() != null) {
          appendIdentity(output, "expected", place.expectedRuleSet());
        }
      }
      case M05Command.Cancel cancel ->
          output
              .append("|type=CANCEL|instrumentId=")
              .append(framed(cancel.instrumentId()))
              .append("|orderId=")
              .append(cancel.orderId());
      case M05Command.PrepareRuleSet prepare -> {
        output.append("|type=PREPARE_RULE_SET");
        appendIdentity(output, "expectedActive", prepare.expectedActive());
        M05Command.Artifact artifact = prepare.artifact();
        output
            .append("|schemaVersion=")
            .append(framed(artifact.schemaVersion()))
            .append("|instrumentId=")
            .append(framed(artifact.instrumentId()))
            .append("|version=")
            .append(artifact.version())
            .append("|lowerInclusive=")
            .append(artifact.lowerInclusive())
            .append("|upperInclusive=")
            .append(artifact.upperInclusive())
            .append("|contentHash=")
            .append(framed(artifact.contentHash()));
      }
      case M05Command.ActivateRuleSet activate -> {
        output
            .append("|type=ACTIVATE_RULE_SET|expectedApplicationSequence=")
            .append(activate.expectedApplicationSequence());
        appendIdentity(output, "expectedActive", activate.expectedActive());
        appendIdentity(output, "target", activate.target());
      }
    }
    output.append('\n');
  }

  private static void appendIdentity(
      StringBuilder output, String prefix, M05Command.Identity value) {
    output
        .append('|')
        .append(prefix)
        .append("Version=")
        .append(value.version())
        .append('|')
        .append(prefix)
        .append("Hash=")
        .append(framed(value.contentHash()));
  }

  static String framed(String value) {
    return value.getBytes(StandardCharsets.UTF_8).length + ":" + value;
  }

  private static CanonicalCommands result(StringBuilder output, int count) {
    byte[] bytes = output.toString().getBytes(StandardCharsets.UTF_8);
    return new CanonicalCommands(bytes, Hashing.semanticDigest(bytes), count);
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalArgumentException(message);
    }
  }

  record CanonicalCommands(byte[] bytes, String digest, int commandCount) {
    CanonicalCommands {
      bytes = bytes.clone();
      Objects.requireNonNull(digest, "digest");
    }

    @Override
    public byte[] bytes() {
      return bytes.clone();
    }
  }
}
