package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.CancelOrderInput;
import io.github.lchareln.cex.matching.PlaceLimitOrderInput;
import java.util.List;
import java.util.Objects;

/** Stateful semantic boundary for one fresh M02 candidate engine. */
interface M02Candidate {
  Outcome place(PlaceLimitOrderInput input);

  Outcome cancel(CancelOrderInput input);

  @FunctionalInterface
  interface Factory {
    M02Candidate create();
  }

  record Outcome(List<M02ScenarioPack.Event> events, M02ScenarioPack.Book bookAfter) {
    public Outcome {
      events = List.copyOf(events);
      Objects.requireNonNull(bookAfter, "bookAfter");
    }
  }
}
