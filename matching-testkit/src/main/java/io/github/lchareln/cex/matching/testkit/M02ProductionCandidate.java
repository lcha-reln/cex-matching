package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.CancelOrderInput;
import io.github.lchareln.cex.matching.ExecutionBatch;
import io.github.lchareln.cex.matching.MatchingEvent;
import io.github.lchareln.cex.matching.OrderBookSnapshot;
import io.github.lchareln.cex.matching.PlaceLimitOrderInput;
import io.github.lchareln.cex.matching.SingleInstrumentMatchingEngine;
import java.util.ArrayList;
import java.util.List;

/** Adapts the production M02 engine to immutable testkit semantic values. */
final class M02ProductionCandidate implements M02Candidate {
  private final SingleInstrumentMatchingEngine engine = new SingleInstrumentMatchingEngine();

  @Override
  public Outcome place(PlaceLimitOrderInput input) {
    return outcome(engine.place(input));
  }

  @Override
  public Outcome cancel(CancelOrderInput input) {
    return outcome(engine.cancel(input));
  }

  private static Outcome outcome(ExecutionBatch batch) {
    return new Outcome(events(batch.events()), book(batch.bookAfter()));
  }

  private static List<M02ScenarioPack.Event> events(List<MatchingEvent> source) {
    List<M02ScenarioPack.Event> events = new ArrayList<>(source.size());
    for (MatchingEvent event : source) {
      events.add(
          switch (event) {
            case MatchingEvent.Rejected rejected ->
                new M02ScenarioPack.Rejected(rejected.code().name(), rejected.field());
            case MatchingEvent.PlaceRejected rejected ->
                new M02ScenarioPack.PlaceRejected(
                    rejected.orderId().value(), rejected.code().name());
            case MatchingEvent.CancelRejected rejected ->
                new M02ScenarioPack.CancelRejected(
                    rejected.orderId().value(), rejected.code().name());
            case MatchingEvent.Accepted accepted ->
                new M02ScenarioPack.Accepted(
                    accepted.sequence().value(),
                    accepted.orderId().value(),
                    accepted.side().name(),
                    accepted.priceTicks().value(),
                    accepted.quantityLots().value());
            case MatchingEvent.Trade trade ->
                new M02ScenarioPack.Trade(
                    trade.makerSequence().value(),
                    trade.makerOrderId().value(),
                    trade.takerSequence().value(),
                    trade.takerOrderId().value(),
                    trade.priceTicks().value(),
                    trade.quantityLots().value());
            case MatchingEvent.Rested rested ->
                new M02ScenarioPack.Rested(
                    rested.sequence().value(),
                    rested.orderId().value(),
                    rested.side().name(),
                    rested.priceTicks().value(),
                    rested.remainingQuantityLots().value());
            case MatchingEvent.RemainderCanceled canceled -> throw unexpectedM04Event(canceled);
            case MatchingEvent.Canceled canceled ->
                new M02ScenarioPack.Canceled(
                    canceled.sequence().value(),
                    canceled.orderId().value(),
                    canceled.side().name(),
                    canceled.priceTicks().value(),
                    canceled.canceledQuantityLots().value());
          });
    }
    return List.copyOf(events);
  }

  private static IllegalStateException unexpectedM04Event(MatchingEvent event) {
    return new IllegalStateException(
        "M02 GTC candidate emitted an M04 policy event: " + event.getClass().getSimpleName());
  }

  private static M02ScenarioPack.Book book(OrderBookSnapshot snapshot) {
    return new M02ScenarioPack.Book(levels(snapshot.bids()), levels(snapshot.asks()));
  }

  private static List<M02ScenarioPack.Level> levels(List<OrderBookSnapshot.PriceLevel> source) {
    List<M02ScenarioPack.Level> levels = new ArrayList<>(source.size());
    for (OrderBookSnapshot.PriceLevel level : source) {
      List<M02ScenarioPack.RestingOrder> orders = new ArrayList<>(level.orders().size());
      for (OrderBookSnapshot.RestingOrderView order : level.orders()) {
        orders.add(
            new M02ScenarioPack.RestingOrder(
                order.sequence().value(),
                order.orderId().value(),
                order.remainingQuantityLots().value()));
      }
      levels.add(new M02ScenarioPack.Level(level.priceTicks().value(), orders));
    }
    return List.copyOf(levels);
  }
}
