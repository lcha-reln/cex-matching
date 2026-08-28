package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.ExecutionBatch;
import io.github.lchareln.cex.matching.MatchingEvent;
import io.github.lchareln.cex.matching.OrderBookSnapshot;
import io.github.lchareln.cex.matching.PlaceLimitOrderInput;
import io.github.lchareln.cex.matching.SingleInstrumentMatchingEngine;
import java.util.ArrayList;
import java.util.List;

/** Adapts the production single-instrument engine to immutable testkit semantic values. */
final class M01ProductionCandidate implements M01Candidate {
  private final SingleInstrumentMatchingEngine engine = new SingleInstrumentMatchingEngine();

  @Override
  public Outcome place(PlaceLimitOrderInput input) {
    ExecutionBatch batch = engine.place(input);
    return new Outcome(events(batch.events()), book(batch.bookAfter()));
  }

  private static List<M01ScenarioPack.Event> events(List<MatchingEvent> source) {
    List<M01ScenarioPack.Event> events = new ArrayList<>(source.size());
    for (MatchingEvent event : source) {
      events.add(
          switch (event) {
            case MatchingEvent.Rejected rejected ->
                new M01ScenarioPack.Rejected(rejected.code().name(), rejected.field());
            case MatchingEvent.Accepted accepted ->
                new M01ScenarioPack.Accepted(
                    accepted.sequence().value(),
                    accepted.orderId().value(),
                    accepted.side().name(),
                    accepted.priceTicks().value(),
                    accepted.quantityLots().value());
            case MatchingEvent.Trade trade ->
                new M01ScenarioPack.Trade(
                    trade.makerSequence().value(),
                    trade.makerOrderId().value(),
                    trade.takerSequence().value(),
                    trade.takerOrderId().value(),
                    trade.priceTicks().value(),
                    trade.quantityLots().value());
            case MatchingEvent.Rested rested ->
                new M01ScenarioPack.Rested(
                    rested.sequence().value(),
                    rested.orderId().value(),
                    rested.side().name(),
                    rested.priceTicks().value(),
                    rested.remainingQuantityLots().value());
            case MatchingEvent.PlaceRejected rejected -> throw unexpectedM02Event(rejected);
            case MatchingEvent.CancelRejected rejected -> throw unexpectedM02Event(rejected);
            case MatchingEvent.Canceled canceled -> throw unexpectedM02Event(canceled);
          });
    }
    return List.copyOf(events);
  }

  private static IllegalStateException unexpectedM02Event(MatchingEvent event) {
    return new IllegalStateException(
        "M01 candidate emitted an M02 lifecycle event: " + event.getClass().getSimpleName());
  }

  private static M01ScenarioPack.Book book(OrderBookSnapshot snapshot) {
    return new M01ScenarioPack.Book(levels(snapshot.bids()), levels(snapshot.asks()));
  }

  private static List<M01ScenarioPack.Level> levels(List<OrderBookSnapshot.PriceLevel> source) {
    List<M01ScenarioPack.Level> levels = new ArrayList<>(source.size());
    for (OrderBookSnapshot.PriceLevel level : source) {
      List<M01ScenarioPack.RestingOrder> orders = new ArrayList<>(level.orders().size());
      for (OrderBookSnapshot.RestingOrderView order : level.orders()) {
        orders.add(
            new M01ScenarioPack.RestingOrder(
                order.sequence().value(),
                order.orderId().value(),
                order.remainingQuantityLots().value()));
      }
      levels.add(new M01ScenarioPack.Level(level.priceTicks().value(), orders));
    }
    return List.copyOf(levels);
  }
}
