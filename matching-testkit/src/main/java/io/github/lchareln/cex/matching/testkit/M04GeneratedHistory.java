package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.reference.ReferenceCommand;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** One isolated M04 generated command history. */
record M04GeneratedHistory(
    int historyIndex, long seed, String laneId, List<ReferenceCommand> commands) {
  M04GeneratedHistory {
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
