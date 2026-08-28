package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.reference.ReferenceCommand;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/** Canonical, host-independent byte encoding of every generated M03 command. */
public final class M03CommandCanonicalizer {
  public static final String SEED_DERIVATION = "splitmix64(baseSeed+historyIndex).nextLong";

  public CanonicalCommands canonicalize(
      M03GeneratorProfile profile, List<M03GeneratedHistory> histories) {
    Objects.requireNonNull(profile, "profile");
    List<M03GeneratedHistory> immutableHistories = List.copyOf(histories);
    require(
        immutableHistories.size() == profile.histories(),
        "generated history count does not match profile");

    StringBuilder canonical = new StringBuilder();
    canonical
        .append("M03G1|algorithm=")
        .append(profile.algorithm())
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
    for (int historyIndex = 0; historyIndex < immutableHistories.size(); historyIndex++) {
      M03GeneratedHistory history = immutableHistories.get(historyIndex);
      M03GeneratorProfile.Lane expectedLane = profile.laneForHistory(historyIndex);
      require(history.historyIndex() == historyIndex, "generated history index is not contiguous");
      require(
          history.seed() == M03HistoryGenerator.historySeed(profile.baseSeed(), historyIndex),
          "generated history seed does not match the frozen derivation");
      require(expectedLane.id().equals(history.laneId()), "generated lane assignment changed");
      require(
          history.commands().size() == profile.commandsPerHistory(),
          "generated command count does not match profile");
      canonical
          .append("M03H1|history=")
          .append(historyIndex)
          .append("|seed=")
          .append(history.seedHex())
          .append("|lane=")
          .append(framed(history.laneId()))
          .append("|commands=")
          .append(history.commands().size())
          .append('\n');
      for (int commandIndex = 0; commandIndex < history.commands().size(); commandIndex++) {
        canonical.append(
            commandLine(historyIndex, commandIndex, history.commands().get(commandIndex)));
        commandCount++;
      }
    }

    byte[] bytes = canonical.toString().getBytes(StandardCharsets.UTF_8);
    return new CanonicalCommands(bytes, Hashing.semanticDigest(bytes), commandCount);
  }

  private static String commandLine(int historyIndex, int commandIndex, ReferenceCommand command) {
    StringBuilder line =
        new StringBuilder()
            .append("M03C1|history=")
            .append(historyIndex)
            .append("|command=")
            .append(commandIndex);
    if (command instanceof ReferenceCommand.Place place) {
      return line.append("|type=PLACE|instrumentId=")
          .append(framed(place.instrumentId()))
          .append("|orderId=")
          .append(place.orderId())
          .append("|side=")
          .append(framed(place.side()))
          .append("|priceTicks=")
          .append(place.priceTicks())
          .append("|quantityLots=")
          .append(place.quantityLots())
          .append('\n')
          .toString();
    }
    if (command instanceof ReferenceCommand.Cancel cancel) {
      return line.append("|type=CANCEL|instrumentId=")
          .append(framed(cancel.instrumentId()))
          .append("|orderId=")
          .append(cancel.orderId())
          .append('\n')
          .toString();
    }
    throw new IllegalArgumentException("unsupported M03 command: " + command.getClass().getName());
  }

  private static String framed(String value) {
    Objects.requireNonNull(value, "canonical string");
    return value.getBytes(StandardCharsets.UTF_8).length + ":" + value;
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalArgumentException(message);
    }
  }

  /** Defensive canonical bytes, their semantic digest, and the encoded command count. */
  public static final class CanonicalCommands {
    private final byte[] bytes;
    private final String digest;
    private final int commandCount;

    CanonicalCommands(byte[] bytes, String digest, int commandCount) {
      this.bytes = bytes.clone();
      this.digest = Objects.requireNonNull(digest, "digest");
      this.commandCount = commandCount;
    }

    public byte[] bytes() {
      return bytes.clone();
    }

    public String digest() {
      return digest;
    }

    public int commandCount() {
      return commandCount;
    }
  }
}
