package io.github.lchareln.cex.matching.testkit;

import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** One fresh-engine M05 generated command history. */
record M05GeneratedHistory(int historyIndex, long seed, String laneId, List<M05Command> commands) {
  M05GeneratedHistory {
    if (historyIndex < 0) {
      throw new IllegalArgumentException("historyIndex must not be negative");
    }
    Objects.requireNonNull(laneId, "laneId");
    commands = List.copyOf(commands);
  }

  String seedHex() {
    return HexFormat.of().toHexDigits(seed);
  }
}
