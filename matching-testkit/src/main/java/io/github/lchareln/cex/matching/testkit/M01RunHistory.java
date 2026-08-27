package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.PlaceLimitOrderInput;
import java.util.List;
import java.util.Objects;

/** Actual event and order-book history produced by one fresh replay of every scenario. */
public record M01RunHistory(List<ScenarioRun> scenarios) {
  public M01RunHistory {
    scenarios = List.copyOf(scenarios);
  }

  public int caseCount() {
    return scenarios.stream().mapToInt(scenario -> scenario.cases().size()).sum();
  }

  public record ScenarioRun(String scenarioId, List<CaseRun> cases) {
    public ScenarioRun {
      Objects.requireNonNull(scenarioId, "scenarioId");
      cases = List.copyOf(cases);
    }
  }

  public record CaseRun(
      String caseId,
      PlaceLimitOrderInput input,
      List<M01ScenarioPack.Event> events,
      M01ScenarioPack.Book bookAfter) {
    public CaseRun {
      Objects.requireNonNull(caseId, "caseId");
      Objects.requireNonNull(input, "input");
      events = List.copyOf(events);
      Objects.requireNonNull(bookAfter, "bookAfter");
    }
  }
}
