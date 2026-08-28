package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.CancelOrderInput;
import io.github.lchareln.cex.matching.ExecutionBatch;
import io.github.lchareln.cex.matching.MatchingEvent;
import io.github.lchareln.cex.matching.OrderBookSnapshot;
import io.github.lchareln.cex.matching.PlaceLimitOrderInput;
import io.github.lchareln.cex.matching.PlaceLimitOrderRequest;
import io.github.lchareln.cex.matching.SingleInstrumentMatchingEngine;
import io.github.lchareln.cex.matching.reference.ReferenceCommand;
import io.github.lchareln.cex.matching.reference.SemanticBook;
import io.github.lchareln.cex.matching.reference.SemanticEvent;
import io.github.lchareln.cex.matching.reference.SemanticOutcome;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/** One-way adapter from the public M04 matcher to reference-owned semantic values. */
final class M04ProductionCandidate implements M04Candidate {
  private final SingleInstrumentMatchingEngine engine = new SingleInstrumentMatchingEngine();

  @Override
  public SemanticOutcome apply(ReferenceCommand command) {
    if (command instanceof ReferenceCommand.Place place) {
      PlaceLimitOrderInput input =
          new PlaceLimitOrderInput(
              place.instrumentId(),
              place.orderId(),
              place.side(),
              place.priceTicks(),
              place.quantityLots());
      return outcome(
          engine.placeRequest(new PlaceLimitOrderRequest(input, place.executionPolicy())));
    }
    if (command instanceof ReferenceCommand.Cancel cancel) {
      return outcome(engine.cancel(new CancelOrderInput(cancel.instrumentId(), cancel.orderId())));
    }
    throw new IllegalArgumentException("unsupported M04 production command");
  }

  private static SemanticOutcome outcome(ExecutionBatch batch) {
    return new SemanticOutcome(events(batch.events()), book(batch.bookAfter()));
  }

  private static List<SemanticEvent> events(List<MatchingEvent> source) {
    List<SemanticEvent> result = new ArrayList<>(source.size());
    for (MatchingEvent event : source) {
      result.add(
          switch (event) {
            case MatchingEvent.Rejected rejected ->
                new SemanticEvent.Rejected(rejected.code().name(), rejected.field());
            case MatchingEvent.PlaceRejected rejected ->
                new SemanticEvent.PlaceRejected(
                    value(rejected.orderId().value()), rejected.code().name());
            case MatchingEvent.CancelRejected rejected ->
                new SemanticEvent.CancelRejected(
                    value(rejected.orderId().value()), rejected.code().name());
            case MatchingEvent.Accepted accepted ->
                new SemanticEvent.Accepted(
                    value(accepted.sequence().value()),
                    value(accepted.orderId().value()),
                    accepted.side().name(),
                    value(accepted.priceTicks().value()),
                    value(accepted.quantityLots().value()),
                    accepted.executionPolicy().name());
            case MatchingEvent.Trade trade ->
                new SemanticEvent.Trade(
                    value(trade.makerSequence().value()),
                    value(trade.makerOrderId().value()),
                    value(trade.takerSequence().value()),
                    value(trade.takerOrderId().value()),
                    value(trade.priceTicks().value()),
                    value(trade.quantityLots().value()));
            case MatchingEvent.Rested rested ->
                new SemanticEvent.Rested(
                    value(rested.sequence().value()),
                    value(rested.orderId().value()),
                    rested.side().name(),
                    value(rested.priceTicks().value()),
                    value(rested.remainingQuantityLots().value()));
            case MatchingEvent.RemainderCanceled canceled ->
                new SemanticEvent.RemainderCanceled(
                    value(canceled.sequence().value()),
                    value(canceled.orderId().value()),
                    canceled.side().name(),
                    value(canceled.priceTicks().value()),
                    value(canceled.canceledQuantityLots().value()),
                    canceled.reason().name());
            case MatchingEvent.Canceled canceled ->
                new SemanticEvent.Canceled(
                    value(canceled.sequence().value()),
                    value(canceled.orderId().value()),
                    canceled.side().name(),
                    value(canceled.priceTicks().value()),
                    value(canceled.canceledQuantityLots().value()));
          });
    }
    return List.copyOf(result);
  }

  private static SemanticBook book(OrderBookSnapshot snapshot) {
    return new SemanticBook(levels(snapshot.bids()), levels(snapshot.asks()));
  }

  private static List<SemanticBook.PriceLevel> levels(List<OrderBookSnapshot.PriceLevel> source) {
    List<SemanticBook.PriceLevel> levels = new ArrayList<>(source.size());
    for (OrderBookSnapshot.PriceLevel level : source) {
      List<SemanticBook.RestingOrder> orders = new ArrayList<>(level.orders().size());
      for (OrderBookSnapshot.RestingOrderView order : level.orders()) {
        orders.add(
            new SemanticBook.RestingOrder(
                value(order.sequence().value()),
                value(order.orderId().value()),
                value(order.remainingQuantityLots().value())));
      }
      levels.add(
          new SemanticBook.PriceLevel(
              level.side().name(), value(level.priceTicks().value()), orders));
    }
    return List.copyOf(levels);
  }

  private static BigInteger value(long value) {
    return BigInteger.valueOf(value);
  }
}
