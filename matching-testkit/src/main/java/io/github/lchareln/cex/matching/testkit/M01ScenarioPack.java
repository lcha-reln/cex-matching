package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.PlaceLimitOrderInput;
import java.util.List;
import java.util.Objects;

/** Strict, immutable representation of the frozen M01 scenario corpus. */
public record M01ScenarioPack(List<Scenario> scenarios) {
  public M01ScenarioPack {
    scenarios = List.copyOf(scenarios);
  }

  public int caseCount() {
    return scenarios.stream().mapToInt(scenario -> scenario.cases().size()).sum();
  }

  public record Scenario(String scenarioId, List<Case> cases) {
    public Scenario {
      Objects.requireNonNull(scenarioId, "scenarioId");
      cases = List.copyOf(cases);
    }
  }

  public record Case(String caseId, PlaceLimitOrderInput input, Expected expected) {
    public Case {
      Objects.requireNonNull(caseId, "caseId");
      Objects.requireNonNull(input, "input");
      Objects.requireNonNull(expected, "expected");
    }
  }

  public record Expected(List<Event> events, Book bookAfter) {
    public Expected {
      events = List.copyOf(events);
      Objects.requireNonNull(bookAfter, "bookAfter");
    }
  }

  public sealed interface Event permits Rejected, Accepted, Trade, Rested {
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
