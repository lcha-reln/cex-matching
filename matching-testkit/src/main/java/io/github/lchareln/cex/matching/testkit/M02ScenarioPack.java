package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.CancelOrderInput;
import io.github.lchareln.cex.matching.PlaceLimitOrderInput;
import java.util.List;
import java.util.Objects;

/** Strict, immutable representation of the frozen M02 lifecycle scenario corpus. */
public record M02ScenarioPack(List<Scenario> scenarios) {
  public M02ScenarioPack {
    scenarios = List.copyOf(scenarios);
  }

  public int commandCount() {
    return scenarios.stream().mapToInt(scenario -> scenario.commands().size()).sum();
  }

  public long placeCommandCount() {
    return scenarios.stream()
        .flatMap(scenario -> scenario.commands().stream())
        .filter(command -> command instanceof PlaceCommand)
        .count();
  }

  public long cancelCommandCount() {
    return scenarios.stream()
        .flatMap(scenario -> scenario.commands().stream())
        .filter(command -> command instanceof CancelCommand)
        .count();
  }

  public record Scenario(String scenarioId, List<Command> commands) {
    public Scenario {
      Objects.requireNonNull(scenarioId, "scenarioId");
      commands = List.copyOf(commands);
    }
  }

  public sealed interface Command permits PlaceCommand, CancelCommand {
    String caseId();

    Expected expected();

    String type();
  }

  public record PlaceCommand(String caseId, PlaceLimitOrderInput input, Expected expected)
      implements Command {
    public PlaceCommand {
      Objects.requireNonNull(caseId, "caseId");
      Objects.requireNonNull(input, "input");
      Objects.requireNonNull(expected, "expected");
    }

    @Override
    public String type() {
      return "PLACE";
    }
  }

  public record CancelCommand(String caseId, CancelOrderInput input, Expected expected)
      implements Command {
    public CancelCommand {
      Objects.requireNonNull(caseId, "caseId");
      Objects.requireNonNull(input, "input");
      Objects.requireNonNull(expected, "expected");
    }

    @Override
    public String type() {
      return "CANCEL";
    }
  }

  public record Expected(List<Event> events, Book bookAfter) {
    public Expected {
      events = List.copyOf(events);
      Objects.requireNonNull(bookAfter, "bookAfter");
    }
  }

  public sealed interface Event
      permits Rejected, PlaceRejected, CancelRejected, Accepted, Trade, Rested, Canceled {
    String type();
  }

  public record Rejected(String code, String field) implements Event {
    public Rejected {
      Objects.requireNonNull(code, "code");
      Objects.requireNonNull(field, "field");
    }

    @Override
    public String type() {
      return "REJECTED";
    }
  }

  public record PlaceRejected(long orderId, String code) implements Event {
    public PlaceRejected {
      Objects.requireNonNull(code, "code");
    }

    @Override
    public String type() {
      return "PLACE_REJECTED";
    }
  }

  public record CancelRejected(long orderId, String code) implements Event {
    public CancelRejected {
      Objects.requireNonNull(code, "code");
    }

    @Override
    public String type() {
      return "CANCEL_REJECTED";
    }
  }

  public record Accepted(
      long sequence, long orderId, String side, long priceTicks, long quantityLots)
      implements Event {
    public Accepted {
      Objects.requireNonNull(side, "side");
    }

    @Override
    public String type() {
      return "ACCEPTED";
    }
  }

  public record Trade(
      long makerSequence,
      long makerOrderId,
      long takerSequence,
      long takerOrderId,
      long priceTicks,
      long quantityLots)
      implements Event {
    @Override
    public String type() {
      return "TRADE";
    }
  }

  public record Rested(
      long sequence, long orderId, String side, long priceTicks, long remainingQuantityLots)
      implements Event {
    public Rested {
      Objects.requireNonNull(side, "side");
    }

    @Override
    public String type() {
      return "RESTED";
    }
  }

  public record Canceled(
      long sequence, long orderId, String side, long priceTicks, long canceledQuantityLots)
      implements Event {
    public Canceled {
      Objects.requireNonNull(side, "side");
    }

    @Override
    public String type() {
      return "CANCELED";
    }
  }

  public record Book(List<Level> bids, List<Level> asks) {
    public Book {
      bids = List.copyOf(bids);
      asks = List.copyOf(asks);
    }

    public static Book empty() {
      return new Book(List.of(), List.of());
    }
  }

  public record Level(long priceTicks, List<RestingOrder> orders) {
    public Level {
      orders = List.copyOf(orders);
    }
  }

  public record RestingOrder(long sequence, long orderId, long remainingQuantityLots) {}
}
