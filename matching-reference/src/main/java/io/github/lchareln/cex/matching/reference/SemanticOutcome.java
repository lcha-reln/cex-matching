package io.github.lchareln.cex.matching.reference;

import java.util.List;
import java.util.Objects;

/** Ordered semantic events and the detached full-depth book after one command. */
public record SemanticOutcome(List<SemanticEvent> events, SemanticBook bookAfter) {
  public SemanticOutcome {
    events = List.copyOf(events);
    Objects.requireNonNull(bookAfter, "bookAfter");
  }
}
