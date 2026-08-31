package io.github.lchareln.cex.matching;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

final class MatchingSemanticValuesTest {
  @Test
  void acceptanceSequenceMustBePositive() {
    assertThrows(IllegalArgumentException.class, () -> new AcceptanceSequence(0));
    assertDoesNotThrow(() -> new AcceptanceSequence(Long.MAX_VALUE));
  }

  @Test
  void executionBatchEnforcesPlaceAndCancelEventGrammar() {
    OrderBookSnapshot empty = new OrderBookSnapshot(List.of(), List.of());
    MatchingEvent.Accepted accepted = accepted(2, 1, Side.BUY, 100, 2);
    MatchingEvent.Trade trade = trade(1, 2, 2, 1, 99, 1);
    MatchingEvent.Rested rested = rested(2, 1, Side.BUY, 100, 1);
    MatchingEvent.PlaceRejected duplicate =
        new MatchingEvent.PlaceRejected(new OrderId(1), PlaceRejectionCode.DUPLICATE_ORDER_ID);
    MatchingEvent.CancelRejected notFound =
        new MatchingEvent.CancelRejected(new OrderId(2), CancelRejectionCode.ORDER_NOT_FOUND);
    MatchingEvent.Canceled canceled = canceled(2, 2, Side.SELL, 99, 1);

    assertThrows(IllegalArgumentException.class, () -> new ExecutionBatch(List.of(), empty));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ExecutionBatch(
                List.of(new MatchingEvent.Rejected(ValidationCode.INVALID_PRICE), accepted),
                empty));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ExecutionBatch(List.of(duplicate, accepted), empty));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ExecutionBatch(List.of(notFound, accepted), empty));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ExecutionBatch(List.of(canceled, accepted), empty));
    assertThrows(IllegalArgumentException.class, () -> new ExecutionBatch(List.of(trade), empty));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ExecutionBatch(List.of(accepted, rested, trade), empty));
    assertThrows(
        IllegalArgumentException.class, () -> new ExecutionBatch(List.of(accepted, trade), empty));
    assertThrows(
        IllegalArgumentException.class, () -> new ExecutionBatch(List.of(accepted), empty));
    assertDoesNotThrow(() -> new ExecutionBatch(List.of(accepted, trade, rested), empty));
    assertDoesNotThrow(() -> new ExecutionBatch(List.of(duplicate), empty));
    assertDoesNotThrow(() -> new ExecutionBatch(List.of(notFound), empty));
    assertDoesNotThrow(() -> new ExecutionBatch(List.of(canceled), empty));
  }

  @Test
  void executionBatchRejectsEventsForAnotherTaker() {
    OrderBookSnapshot empty = new OrderBookSnapshot(List.of(), List.of());
    MatchingEvent.Accepted accepted = accepted(1, 1, Side.BUY, 100, 2);
    MatchingEvent.Trade wrongTaker = trade(2, 2, 3, 3, 99, 1);

    assertThrows(
        IllegalArgumentException.class,
        () -> new ExecutionBatch(List.of(accepted, wrongTaker), empty));
  }

  @Test
  void snapshotEnforcesPriceOrderFifoSidesAndNoCross() {
    OrderBookSnapshot.RestingOrderView first = resting(1, 1, 1);
    OrderBookSnapshot.RestingOrderView second = resting(2, 2, 1);
    OrderBookSnapshot.PriceLevel bid100 = level(Side.BUY, 100, first, second);
    OrderBookSnapshot.PriceLevel bid99 = level(Side.BUY, 99, resting(3, 3, 1));
    OrderBookSnapshot.PriceLevel ask101 = level(Side.SELL, 101, resting(4, 4, 1));

    assertDoesNotThrow(() -> new OrderBookSnapshot(List.of(bid100, bid99), List.of(ask101)));
    assertDoesNotThrow(
        () ->
            new OrderBookSnapshot(
                List.of(level(Side.BUY, Long.MAX_VALUE, resting(5, 5, 1))), List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new OrderBookSnapshot(List.of(bid99, bid100), List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new OrderBookSnapshot(List.of(bid100), List.of(level(Side.SELL, 100, second))));
    assertThrows(
        IllegalArgumentException.class,
        () -> new OrderBookSnapshot(List.of(level(Side.SELL, 99, first)), List.of(ask101)));
    assertThrows(IllegalArgumentException.class, () -> level(Side.BUY, 100, second, first));
  }

  @Test
  void rejectedCodeAndFieldMustAgree() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new MatchingEvent.Rejected(ValidationCode.INVALID_PRICE, "quantityLots"));
  }

  private static MatchingEvent.Accepted accepted(
      long sequence, long orderId, Side side, long priceTicks, long quantityLots) {
    return new MatchingEvent.Accepted(
        new AcceptanceSequence(sequence),
        new OrderId(orderId),
        side,
        new PriceTicks(priceTicks),
        new QuantityLots(quantityLots));
  }

  private static MatchingEvent.Trade trade(
      long makerSequence,
      long makerOrderId,
      long takerSequence,
      long takerOrderId,
      long priceTicks,
      long quantityLots) {
    return new MatchingEvent.Trade(
        new AcceptanceSequence(makerSequence),
        new OrderId(makerOrderId),
        new AcceptanceSequence(takerSequence),
        new OrderId(takerOrderId),
        new PriceTicks(priceTicks),
        new QuantityLots(quantityLots));
  }

  private static MatchingEvent.Rested rested(
      long sequence, long orderId, Side side, long priceTicks, long remainingQuantityLots) {
    return new MatchingEvent.Rested(
        new AcceptanceSequence(sequence),
        new OrderId(orderId),
        side,
        new PriceTicks(priceTicks),
        new QuantityLots(remainingQuantityLots));
  }

  private static MatchingEvent.Canceled canceled(
      long sequence, long orderId, Side side, long priceTicks, long canceledQuantityLots) {
    return new MatchingEvent.Canceled(
        new AcceptanceSequence(sequence),
        new OrderId(orderId),
        side,
        new PriceTicks(priceTicks),
        new QuantityLots(canceledQuantityLots));
  }

  private static OrderBookSnapshot.RestingOrderView resting(
      long sequence, long orderId, long remainingQuantityLots) {
    return new OrderBookSnapshot.RestingOrderView(
        new AcceptanceSequence(sequence),
        new OrderId(orderId),
        new QuantityLots(remainingQuantityLots));
  }

  private static OrderBookSnapshot.PriceLevel level(
      Side side, long priceTicks, OrderBookSnapshot.RestingOrderView... orders) {
    return new OrderBookSnapshot.PriceLevel(side, new PriceTicks(priceTicks), List.of(orders));
  }
}
