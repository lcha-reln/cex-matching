package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.CancelOrderInput;
import io.github.lchareln.cex.matching.PlaceLimitOrderInput;
import java.util.List;
import java.util.Objects;

/** Actual command, event, and full-book history from one fresh M02 replay. */
public record M02RunHistory(List<ScenarioRun> scenarios) {
  public M02RunHistory {
    scenarios = List.copyOf(scenarios);
  }

  public int commandCount() {
    return scenarios.stream().mapToInt(scenario -> scenario.commands().size()).sum();
  }

  public record ScenarioRun(String scenarioId, List<CommandRun> commands) {
    public ScenarioRun {
      Objects.requireNonNull(scenarioId, "scenarioId");
      commands = List.copyOf(commands);
    }
  }

  public sealed interface CommandRun permits PlaceRun, CancelRun {
    String caseId();

    List<M02ScenarioPack.Event> events();

    M02ScenarioPack.Book bookAfter();

    String type();
  }

  public record PlaceRun(
      String caseId,
      PlaceLimitOrderInput input,
      List<M02ScenarioPack.Event> events,
      M02ScenarioPack.Book bookAfter)
      implements CommandRun {
    public PlaceRun {
      Objects.requireNonNull(caseId, "caseId");
      Objects.requireNonNull(input, "input");
      events = List.copyOf(events);
      Objects.requireNonNull(bookAfter, "bookAfter");
    }

    @Override
    public String type() {
      return "PLACE";
    }
  }

  public record CancelRun(
      String caseId,
      CancelOrderInput input,
      List<M02ScenarioPack.Event> events,
      M02ScenarioPack.Book bookAfter)
      implements CommandRun {
    public CancelRun {
      Objects.requireNonNull(caseId, "caseId");
      Objects.requireNonNull(input, "input");
      events = List.copyOf(events);
      Objects.requireNonNull(bookAfter, "bookAfter");
    }

    @Override
    public String type() {
      return "CANCEL";
    }
  }
}
