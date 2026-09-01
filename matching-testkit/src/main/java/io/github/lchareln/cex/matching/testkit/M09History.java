package io.github.lchareln.cex.matching.testkit;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Canonical high-level M09 history shared by generation, replay, and mutant shrinking. */
record M09History(String lane, int index, long seed, List<Operation> operations) {
  M09History {
    operations = List.copyOf(operations);
    if (lane.isBlank() || index < 0 || operations.isEmpty()) {
      throw new IllegalArgumentException("invalid M09 history");
    }
  }

  byte[] canonicalBytes() {
    StringBuilder value = new StringBuilder();
    value.append(lane).append('|').append(index).append('|').append(seed);
    for (Operation operation : operations) {
      value.append('|').append(operation.kind()).append(':').append(operation.argument());
    }
    value.append('\n');
    return value.toString().getBytes(StandardCharsets.UTF_8);
  }

  record Operation(Kind kind, long argument) {
    Operation {
      if (kind == null) {
        throw new IllegalArgumentException("M09 operation kind is required");
      }
    }
  }

  enum Kind {
    SUBMIT,
    DUPLICATE,
    CONFLICT,
    SNAPSHOT,
    RESTART,
    ROLLOVER,
    RETIRE,
    CRASH
  }
}
