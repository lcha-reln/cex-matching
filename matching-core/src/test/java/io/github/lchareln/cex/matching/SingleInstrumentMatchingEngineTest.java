package io.github.lchareln.cex.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

final class SingleInstrumentMatchingEngineTest {
  @Test
  void invalidInputDoesNotMutateOrConsumeAcceptanceSequence() {
    SingleInstrumentMatchingEngine engine = new SingleInstrumentMatchingEngine();

    ExecutionBatch rejected = engine.place(input(1, "BUY", 0, 2));

    assertEquals(
        List.of(new MatchingEvent.Rejected(ValidationCode.INVALID_PRICE)), rejected.events());
    assertEquals(emptyBook(), rejected.bookAfter());

    ExecutionBatch accepted = engine.place(input(2, "BUY", 100, 2));
    MatchingEvent.Accepted event =
        assertInstanceOf(MatchingEvent.Accepted.class, accepted.events().getFirst());
    assertEquals(new AcceptanceSequence(1), event.sequence());
  }

  @Test
  void emptyAndNonCrossingOrdersRestOnBothSides() {
    SingleInstrumentMatchingEngine engine = new SingleInstrumentMatchingEngine();

    engine.place(input(10, "BUY", 100, 5));
    ExecutionBatch sell = engine.place(input(11, "SELL", 101, 4));

    assertEquals(2, sell.events().size());
    assertInstanceOf(MatchingEvent.Accepted.class, sell.events().get(0));
    assertInstanceOf(MatchingEvent.Rested.class, sell.events().get(1));
    assertEquals(List.of(100L), prices(sell.bookAfter().bids()));
    assertEquals(List.of(101L), prices(sell.bookAfter().asks()));
  }

  @Test
  void exactTouchFullyFillsAtRestingMakerPrice() {
    SingleInstrumentMatchingEngine engine = new SingleInstrumentMatchingEngine();
    engine.place(input(20, "SELL", 101, 3));

    ExecutionBatch batch = engine.place(input(21, "BUY", 101, 3));

    assertEquals(
        List.of(accepted(2, 21, Side.BUY, 101, 3), trade(1, 20, 2, 21, 101, 3)), batch.events());
    assertEquals(emptyBook(), batch.bookAfter());
  }

  @Test
  void betterPriceExecutesBeforeEarlierWorsePrice() {
    SingleInstrumentMatchingEngine engine = new SingleInstrumentMatchingEngine();
    engine.place(input(30, "SELL", 102, 1));
    engine.place(input(31, "SELL", 101, 1));

    ExecutionBatch batch = engine.place(input(32, "BUY", 102, 2));
    List<MatchingEvent.Trade> trades = trades(batch);

    assertEquals(List.of(31L, 30L), trades.stream().map(t -> t.makerOrderId().value()).toList());
    assertEquals(List.of(101L, 102L), trades.stream().map(t -> t.priceTicks().value()).toList());
    assertEquals(emptyBook(), batch.bookAfter());
  }

  @Test
  void samePriceMakersExecuteFifo() {
    SingleInstrumentMatchingEngine engine = new SingleInstrumentMatchingEngine();
    engine.place(input(40, "SELL", 100, 1));
    engine.place(input(41, "SELL", 100, 1));
    engine.place(input(42, "SELL", 100, 1));

    ExecutionBatch batch = engine.place(input(43, "BUY", 100, 3));

    assertEquals(
        List.of(40L, 41L, 42L), trades(batch).stream().map(t -> t.makerOrderId().value()).toList());
    assertEquals(emptyBook(), batch.bookAfter());
  }

  @Test
  void partiallyFilledMakerRemainsAtHeadWithOriginalSequence() {
    SingleInstrumentMatchingEngine engine = new SingleInstrumentMatchingEngine();
    engine.place(input(50, "SELL", 100, 5));

    ExecutionBatch batch = engine.place(input(51, "BUY", 101, 2));
    OrderBookSnapshot.RestingOrderView maker =
        batch.bookAfter().asks().getFirst().orders().getFirst();

    assertEquals(new AcceptanceSequence(1), maker.sequence());
    assertEquals(new OrderId(50), maker.orderId());
    assertEquals(new QuantityLots(3), maker.remainingQuantityLots());
  }

  @Test
  void buySweepsThreeLevelsThenRestsRemainderWithItsAcceptedSequence() {
    SingleInstrumentMatchingEngine engine = new SingleInstrumentMatchingEngine();
    engine.place(input(60, "SELL", 100, 1));
    engine.place(input(61, "SELL", 101, 1));
    engine.place(input(62, "SELL", 102, 1));

    ExecutionBatch batch = engine.place(input(63, "BUY", 102, 5));

    assertEquals(
        List.of(100L, 101L, 102L),
        trades(batch).stream().map(t -> t.priceTicks().value()).toList());
    MatchingEvent.Rested rested =
        assertInstanceOf(MatchingEvent.Rested.class, batch.events().getLast());
    assertEquals(new AcceptanceSequence(4), rested.sequence());
    assertEquals(new QuantityLots(2), rested.remainingQuantityLots());
    assertEquals(List.of(102L), prices(batch.bookAfter().bids()));
    assertTrue(batch.bookAfter().asks().isEmpty());
  }

  @Test
  void sellMirrorsBuyAndTakesHighestBidFirstAtMakerPrices() {
    SingleInstrumentMatchingEngine engine = new SingleInstrumentMatchingEngine();
    engine.place(input(70, "BUY", 99, 1));
    engine.place(input(71, "BUY", 101, 2));

    ExecutionBatch batch = engine.place(input(72, "SELL", 99, 4));
    List<MatchingEvent.Trade> trades = trades(batch);

    assertEquals(List.of(71L, 70L), trades.stream().map(t -> t.makerOrderId().value()).toList());
    assertEquals(List.of(101L, 99L), trades.stream().map(t -> t.priceTicks().value()).toList());
    MatchingEvent.Rested rested =
        assertInstanceOf(MatchingEvent.Rested.class, batch.events().getLast());
    assertEquals(new QuantityLots(1), rested.remainingQuantityLots());
    assertTrue(batch.bookAfter().bids().isEmpty());
    assertEquals(List.of(99L), prices(batch.bookAfter().asks()));
  }

  @Test
  void returnedEventsAndNestedBookListsAreImmutableDetachedValues() {
    SingleInstrumentMatchingEngine engine = new SingleInstrumentMatchingEngine();
    ExecutionBatch batch = engine.place(input(80, "BUY", 100, 2));
    OrderBookSnapshot snapshot = engine.snapshot();

    assertThrows(
        UnsupportedOperationException.class,
        () -> batch.events().add(new MatchingEvent.Rejected(ValidationCode.INVALID_PRICE)));
    assertThrows(UnsupportedOperationException.class, () -> snapshot.bids().clear());
    assertThrows(
        UnsupportedOperationException.class, () -> snapshot.bids().getFirst().orders().clear());

    engine.place(input(81, "SELL", 100, 2));
    assertEquals(
        new QuantityLots(2),
        snapshot.bids().getFirst().orders().getFirst().remainingQuantityLots());
  }

  @Test
  void sequenceExhaustionFailsBeforeAnyStateMutation() {
    SingleInstrumentMatchingEngine engine = new SingleInstrumentMatchingEngine(Long.MAX_VALUE);

    ExecutionBatch rejected = engine.place(input(90, "SELL", 0, 1));
    assertInstanceOf(MatchingEvent.Rejected.class, rejected.events().getFirst());
    OrderBookSnapshot before = engine.snapshot();

    IllegalStateException failure =
        assertThrows(IllegalStateException.class, () -> engine.place(input(91, "SELL", 100, 1)));

    assertTrue(failure.getMessage().contains("before state mutation"));
    assertEquals(before, engine.snapshot());
  }

  private static PlaceLimitOrderInput input(
      long orderId, String side, long priceTicks, long quantityLots) {
    return new PlaceLimitOrderInput(
        "BTC-USDT",
        BigInteger.valueOf(orderId),
        side,
        BigInteger.valueOf(priceTicks),
        BigInteger.valueOf(quantityLots));
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

  private static List<MatchingEvent.Trade> trades(ExecutionBatch batch) {
    return batch.events().stream()
        .filter(MatchingEvent.Trade.class::isInstance)
        .map(MatchingEvent.Trade.class::cast)
        .toList();
  }

  private static List<Long> prices(List<OrderBookSnapshot.PriceLevel> levels) {
    return levels.stream().map(level -> level.priceTicks().value()).toList();
  }

  private static OrderBookSnapshot emptyBook() {
    return new OrderBookSnapshot(List.of(), List.of());
  }
}
