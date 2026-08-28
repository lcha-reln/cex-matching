package io.github.lchareln.cex.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class SingleInstrumentOrderLifecycleTest {
  @Test
  void invalidAndUnknownCancelsDoNotMutateOrConsumeAcceptanceSequence() {
    SingleInstrumentMatchingEngine engine = new SingleInstrumentMatchingEngine();
    engine.place(place(1, "BUY", 99, 2));
    OrderBookSnapshot before = engine.snapshot();

    assertEquals(
        List.of(new MatchingEvent.Rejected(ValidationCode.UNKNOWN_INSTRUMENT)),
        engine.cancel(cancel("ETH-USDT", 0)).events());
    assertEquals(
        List.of(new MatchingEvent.Rejected(ValidationCode.INVALID_ORDER_ID)),
        engine.cancel(cancel("BTC-USDT", 0)).events());
    assertEquals(
        List.of(
            new MatchingEvent.CancelRejected(
                new OrderId(999), CancelRejectionCode.ORDER_NOT_FOUND)),
        engine.cancel(cancel(999)).events());
    assertEquals(before, engine.snapshot());

    MatchingEvent.Accepted accepted =
        assertInstanceOf(
            MatchingEvent.Accepted.class, engine.place(place(2, "BUY", 98, 1)).events().getFirst());
    assertEquals(new AcceptanceSequence(2), accepted.sequence());
    engine.assertConsistentState();
  }

  @Test
  void cancelOnlyRestingOrderRemovesTheEmptyPriceLevel() {
    SingleInstrumentMatchingEngine engine = new SingleInstrumentMatchingEngine();
    engine.place(place(10, "SELL", 101, 4));

    ExecutionBatch batch = engine.cancel(cancel(10));

    assertEquals(List.of(canceled(1, 10, Side.SELL, 101, 4)), batch.events());
    assertEquals(emptyBook(), batch.bookAfter());
    engine.assertConsistentState();
  }

  @Test
  void cancelMiddleOrderPreservesTheRelativeFifoOfItsNeighbors() {
    SingleInstrumentMatchingEngine engine = new SingleInstrumentMatchingEngine();
    engine.place(place(20, "SELL", 100, 1));
    engine.place(place(21, "SELL", 100, 1));
    engine.place(place(22, "SELL", 100, 1));

    engine.cancel(cancel(21));
    assertEquals(
        List.of(20L, 22L),
        engine.snapshot().asks().getFirst().orders().stream()
            .map(order -> order.orderId().value())
            .toList());

    ExecutionBatch takeNeighbors = engine.place(place(23, "BUY", 100, 2));
    assertEquals(
        List.of(20L, 22L),
        trades(takeNeighbors).stream().map(trade -> trade.makerOrderId().value()).toList());
    assertEquals(emptyBook(), takeNeighbors.bookAfter());
    engine.assertConsistentState();
  }

  @Test
  void cancelPartiallyFilledMakerReportsAndRemovesOnlyItsCurrentRemainder() {
    SingleInstrumentMatchingEngine engine = new SingleInstrumentMatchingEngine();
    engine.place(place(30, "SELL", 100, 5));
    engine.place(place(31, "BUY", 100, 2));

    ExecutionBatch canceled = engine.cancel(cancel(30));

    assertEquals(List.of(canceled(1, 30, Side.SELL, 100, 3)), canceled.events());
    assertEquals(emptyBook(), canceled.bookAfter());
    engine.assertConsistentState();
  }

  @Test
  void lateAndRepeatedCancelsHaveDistinctStableTerminalResults() {
    SingleInstrumentMatchingEngine engine = new SingleInstrumentMatchingEngine();
    engine.place(place(40, "SELL", 100, 2));
    engine.place(place(41, "BUY", 100, 2));

    assertCancelRejected(engine, 40, CancelRejectionCode.ORDER_ALREADY_FILLED);
    assertCancelRejected(engine, 41, CancelRejectionCode.ORDER_ALREADY_FILLED);

    engine.place(place(42, "BUY", 99, 3));
    assertEquals(List.of(canceled(3, 42, Side.BUY, 99, 3)), engine.cancel(cancel(42)).events());
    assertCancelRejected(engine, 42, CancelRejectionCode.ORDER_ALREADY_CANCELED);
    assertCancelRejected(engine, 42, CancelRejectionCode.ORDER_ALREADY_CANCELED);
    engine.assertConsistentState();
  }

  @Test
  void duplicateIdentityIsRejectedWhileActiveFilledOrCanceledAndNeverConsumesSequence() {
    SingleInstrumentMatchingEngine engine = new SingleInstrumentMatchingEngine();
    engine.place(place(50, "BUY", 99, 1));
    assertDuplicate(engine, place(50, "SELL", 101, 9));

    MatchingEvent.Accepted fillSequence =
        assertInstanceOf(
            MatchingEvent.Accepted.class,
            engine.place(place(51, "SELL", 99, 1)).events().getFirst());
    assertEquals(new AcceptanceSequence(2), fillSequence.sequence());
    assertDuplicate(engine, place(50, "BUY", 99, 1));
    assertDuplicate(engine, place(51, "SELL", 99, 1));

    MatchingEvent.Accepted cancelSequence =
        assertInstanceOf(
            MatchingEvent.Accepted.class,
            engine.place(place(52, "BUY", 98, 2)).events().getFirst());
    assertEquals(new AcceptanceSequence(3), cancelSequence.sequence());
    engine.cancel(cancel(52));
    assertDuplicate(engine, place(52, "BUY", 98, 2));

    MatchingEvent.Accepted next =
        assertInstanceOf(
            MatchingEvent.Accepted.class,
            engine.place(place(53, "BUY", 97, 1)).events().getFirst());
    assertEquals(new AcceptanceSequence(4), next.sequence());
    engine.assertConsistentState();
  }

  @Test
  void frozenPlaceValidationPrecedesDuplicateIdentityLookup() {
    SingleInstrumentMatchingEngine engine = new SingleInstrumentMatchingEngine();
    engine.place(place(55, "BUY", 99, 1));
    PlaceLimitOrderInput duplicateWithInvalidSide =
        new PlaceLimitOrderInput(
            "BTC-USDT", BigInteger.valueOf(55), "HOLD", BigInteger.ZERO, BigInteger.ZERO);

    ExecutionBatch rejected = engine.place(duplicateWithInvalidSide);

    assertEquals(
        List.of(new MatchingEvent.Rejected(ValidationCode.INVALID_SIDE)), rejected.events());
    MatchingEvent.Accepted next =
        assertInstanceOf(
            MatchingEvent.Accepted.class,
            engine.place(place(56, "BUY", 98, 1)).events().getFirst());
    assertEquals(new AcceptanceSequence(2), next.sequence());
    engine.assertConsistentState();
  }

  @Test
  void duplicateIdentityCheckPrecedesSequenceExhaustion() throws ReflectiveOperationException {
    SingleInstrumentMatchingEngine engine = new SingleInstrumentMatchingEngine();
    engine.place(place(60, "BUY", 99, 1));
    Field nextSequence =
        SingleInstrumentMatchingEngine.class.getDeclaredField("nextAcceptanceSequence");
    nextSequence.setAccessible(true);
    nextSequence.setLong(engine, Long.MAX_VALUE);
    OrderBookSnapshot before = engine.snapshot();

    assertDuplicate(engine, place(60, "BUY", 99, 1));
    IllegalStateException exhausted =
        assertThrows(IllegalStateException.class, () -> engine.place(place(61, "BUY", 98, 1)));

    assertTrue(exhausted.getMessage().contains("before state mutation"));
    assertEquals(before, engine.snapshot());
  }

  @Test
  void nullCommandsFailBeforeBusinessValidation() {
    SingleInstrumentMatchingEngine engine = new SingleInstrumentMatchingEngine();

    assertThrows(NullPointerException.class, () -> engine.place(null));
    assertThrows(NullPointerException.class, () -> engine.cancel(null));
    assertEquals(emptyBook(), engine.snapshot());
  }

  @Test
  void registryCorruptionFailsClosedBeforeTheNextCommandMutatesTheBook()
      throws ReflectiveOperationException {
    SingleInstrumentMatchingEngine engine = new SingleInstrumentMatchingEngine();
    engine.place(place(70, "BUY", 99, 2));

    Field registryField = SingleInstrumentMatchingEngine.class.getDeclaredField("ordersById");
    registryField.setAccessible(true);
    Map<?, ?> registry = assertInstanceOf(Map.class, registryField.get(engine));
    registry.clear();
    Field bidsField = SingleInstrumentMatchingEngine.class.getDeclaredField("bids");
    bidsField.setAccessible(true);
    Map<?, ?> bids = assertInstanceOf(Map.class, bidsField.get(engine));

    IllegalStateException failure =
        assertThrows(IllegalStateException.class, () -> engine.cancel(cancel(70)));

    assertTrue(failure.getMessage().contains("disagree"));
    assertEquals(1, bids.size());
  }

  private static void assertDuplicate(
      SingleInstrumentMatchingEngine engine, PlaceLimitOrderInput input) {
    ExecutionBatch batch = engine.place(input);
    assertEquals(
        List.of(
            new MatchingEvent.PlaceRejected(
                new OrderId(input.orderId().longValueExact()),
                PlaceRejectionCode.DUPLICATE_ORDER_ID)),
        batch.events());
  }

  private static void assertCancelRejected(
      SingleInstrumentMatchingEngine engine, long orderId, CancelRejectionCode code) {
    assertEquals(
        List.of(new MatchingEvent.CancelRejected(new OrderId(orderId), code)),
        engine.cancel(cancel(orderId)).events());
  }

  private static PlaceLimitOrderInput place(
      long orderId, String side, long priceTicks, long quantityLots) {
    return new PlaceLimitOrderInput(
        "BTC-USDT",
        BigInteger.valueOf(orderId),
        side,
        BigInteger.valueOf(priceTicks),
        BigInteger.valueOf(quantityLots));
  }

  private static CancelOrderInput cancel(long orderId) {
    return cancel("BTC-USDT", orderId);
  }

  private static CancelOrderInput cancel(String instrumentId, long orderId) {
    return new CancelOrderInput(instrumentId, BigInteger.valueOf(orderId));
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

  private static List<MatchingEvent.Trade> trades(ExecutionBatch batch) {
    return batch.events().stream()
        .filter(MatchingEvent.Trade.class::isInstance)
        .map(MatchingEvent.Trade.class::cast)
        .toList();
  }

  private static OrderBookSnapshot emptyBook() {
    return new OrderBookSnapshot(List.of(), List.of());
  }
}
