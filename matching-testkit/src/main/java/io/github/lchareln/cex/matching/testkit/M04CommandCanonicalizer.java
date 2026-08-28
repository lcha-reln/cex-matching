package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.reference.ReferenceCommand;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/** Host-independent M04H1 encoding of every generated policy-aware command. */
final class M04CommandCanonicalizer {
  static final String SEED_DERIVATION = "splitmix64(baseSeed+historyIndex).nextLong";

  CanonicalCommands canonicalize(M04GeneratorProfile profile, List<M04GeneratedHistory> histories) {
    Objects.requireNonNull(profile, "profile");
    List<M04GeneratedHistory> immutable = List.copyOf(histories);
    require(immutable.size() == profile.histories(), "M04 history count differs from profile");
    StringBuilder result = new StringBuilder();
    result
        .append("M04H1|algorithm=")
        .append(M04GeneratorProfile.ALGORITHM)
        .append("|seedDerivation=")
        .append(framed(SEED_DERIVATION))
        .append("|baseSeed=")
        .append(Long.toUnsignedString(profile.baseSeed()))
        .append("|histories=")
        .append(profile.histories())
        .append("|commandsPerHistory=")
        .append(profile.commandsPerHistory())
        .append('\n');
    int commandCount = 0;
    for (int index = 0; index < immutable.size(); index++) {
      M04GeneratedHistory history = immutable.get(index);
      require(history.historyIndex() == index, "M04 history indexes are not contiguous");
      require(
          history.seed() == M04HistoryGenerator.historySeed(profile.baseSeed(), index),
          "M04 history seed changed");
      require(
          profile.laneForHistory(index).id().equals(history.laneId()),
          "M04 lane assignment changed");
      require(
          history.commands().size() == profile.commandsPerHistory(),
          "M04 history command count changed");
      result
          .append("M04R1|history=")
          .append(index)
          .append("|seed=")
          .append(history.seedHex())
          .append("|lane=")
          .append(framed(history.laneId()))
          .append("|commands=")
          .append(history.commands().size())
          .append('\n');
      for (int commandIndex = 0; commandIndex < history.commands().size(); commandIndex++) {
        appendCommand(result, index, commandIndex, history.commands().get(commandIndex));
        commandCount++;
      }
    }
    byte[] bytes = result.toString().getBytes(StandardCharsets.UTF_8);
    return new CanonicalCommands(bytes, Hashing.semanticDigest(bytes), commandCount);
  }

  private static void appendCommand(
      StringBuilder result, int historyIndex, int commandIndex, ReferenceCommand command) {
    result.append("M04C1|history=").append(historyIndex).append("|command=").append(commandIndex);
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
              .append("|executionPolicy=")
              .append(framed(place.executionPolicy()))
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

  static String framed(String value) {
    return value.getBytes(StandardCharsets.UTF_8).length + ":" + value;
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
