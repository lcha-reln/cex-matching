package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.PlaceLimitOrderInput;
import java.util.List;
import java.util.Objects;

/** Stateful testkit boundary for one fresh M01 candidate engine. */
@FunctionalInterface
interface M01Candidate {
  Outcome place(PlaceLimitOrderInput input);

  @FunctionalInterface
  interface Factory {
    M01Candidate create();
  }

  record Outcome(List<M01ScenarioPack.Event> events, M01ScenarioPack.Book bookAfter) {
    public Outcome {
      events = List.copyOf(events);
      Objects.requireNonNull(bookAfter, "bookAfter");
    }
  }
}
