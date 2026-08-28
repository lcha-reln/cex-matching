package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.reference.ReferenceCommand;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** One isolated M03 command history, identified without observing any matcher state. */
public record M03GeneratedHistory(
    int historyIndex, long seed, String laneId, List<ReferenceCommand> commands) {
  public M03GeneratedHistory {
    if (historyIndex < 0) {
      throw new IllegalArgumentException("historyIndex must not be negative");
    }
    Objects.requireNonNull(laneId, "laneId");
    commands = List.copyOf(commands);
  }

  public String seedHex() {
    return HexFormat.of().toHexDigits(seed);
  }
}
