package io.github.lchareln.cex.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

final class SingleInstrumentExecutionPolicyTest {

  @Test
  void explicitRequestBoundaryRejectsNullBeforeAnyStateTransition() {
    SingleInstrumentMatchingEngine engine = new SingleInstrumentMatchingEngine();

    assertThrows(NullPointerException.class, () -> engine.placeRequest(null));
    assertEquals(1, acceptedSequence(engine.place(input(1, "BUY", 99, 1))));
  }

  @Test
  void legacyPlaceRemainsAnExplicitGtcRequest() {
    SingleInstrumentMatchingEngine engine = new SingleInstrumentMatchingEngine();

    ExecutionBatch batch = engine.place(input(1, "BUY", 99, 2));

    MatchingEvent.Accepted accepted =
        assertInstanceOf(MatchingEvent.Accepted.class, batch.events().getFirst());
    assertEquals(ExecutionPolicy.GTC, accepted.executionPolicy());
    assertInstanceOf(MatchingEvent.Rested.class, batch.events().getLast());
  }

  @Test
  void frozenInputValidationPrecedesPolicyAndPolicyRejectionDoesNotConsumeIdentity() {
    SingleInstrumentMatchingEngine engine = new SingleInstrumentMatchingEngine();

    ExecutionBatch invalidInput =
        engine.placeRequest(
            new PlaceLimitOrderRequest(
                new PlaceLimitOrderInput(
                    "ETH-USDT", BigInteger.ZERO, "NO_SIDE", BigInteger.ZERO, BigInteger.ZERO),
                "UNKNOWN"));
    MatchingEvent.Rejected inputRejected =
        assertInstanceOf(MatchingEvent.Rejected.class, invalidInput.events().getFirst());
    assertEquals(ValidationCode.UNKNOWN_INSTRUMENT, inputRejected.code());

    ExecutionBatch invalidPolicy = engine.placeRequest(request(1, "BUY", 99, 2, "UNKNOWN"));
    MatchingEvent.Rejected policyRejected =
        assertInstanceOf(MatchingEvent.Rejected.class, invalidPolicy.events().getFirst());
    assertEquals(ValidationCode.INVALID_EXECUTION_POLICY, policyRejected.code());

    ExecutionBatch accepted = engine.place(input(1, "BUY", 99, 2));
    assertEquals(1, acceptedSequence(accepted));
  }

  @Test
  void iocZeroFillIsAcceptedThenCanceledWithoutResting() {
    SingleInstrumentMatchingEngine engine = new SingleInstrumentMatchingEngine();

    ExecutionBatch batch = engine.placeRequest(request(1, "BUY", 99, 3, "IOC"));

    assertEquals(2, batch.events().size());
    assertEquals(ExecutionPolicy.IOC, accepted(batch).executionPolicy());
    MatchingEvent.RemainderCanceled canceled =
        assertInstanceOf(MatchingEvent.RemainderCanceled.class, batch.events().getLast());
    assertEquals(3, canceled.canceledQuantityLots().value());
    assertEquals(RemainderCancelReason.IOC_REMAINDER, canceled.reason());
    assertTrue(batch.bookAfter().bids().isEmpty());
    assertTrue(batch.bookAfter().asks().isEmpty());

    MatchingEvent.CancelRejected lateCancel =
        assertInstanceOf(
            MatchingEvent.CancelRejected.class, engine.cancel(cancelInput(1)).events().getFirst());
    assertEquals(CancelRejectionCode.ORDER_ALREADY_CANCELED, lateCancel.code());
    assertEquals(
        PlaceRejectionCode.DUPLICATE_ORDER_ID,
        assertInstanceOf(
                MatchingEvent.PlaceRejected.class,
                engine.placeRequest(request(1, "BUY", 99, 3, "IOC")).events().getFirst())
            .code());
  }

  @Test
  void iocTradesAllAvailableLiquidityInsideItsLimitAndCancelsOnlyTheRemainder() {
    SingleInstrumentMatchingEngine engine = new SingleInstrumentMatchingEngine();
    engine.place(input(10, "SELL", 100, 2));
    engine.place(input(11, "SELL", 101, 2));

    ExecutionBatch batch = engine.placeRequest(request(20, "BUY", 100, 3, "IOC"));

    assertEquals(3, batch.events().size());
    MatchingEvent.Trade trade = assertInstanceOf(MatchingEvent.Trade.class, batch.events().get(1));
    assertEquals(10, trade.makerOrderId().value());
    assertEquals(100, trade.priceTicks().value());
    assertEquals(2, trade.quantityLots().value());
    MatchingEvent.RemainderCanceled canceled =
        assertInstanceOf(MatchingEvent.RemainderCanceled.class, batch.events().getLast());
    assertEquals(1, canceled.canceledQuantityLots().value());
    assertEquals(List.of(101L), askPrices(batch.bookAfter()));
  }

  @Test
  void fullyFilledIocHasNoRemainderEvent() {
    SingleInstrumentMatchingEngine engine = new SingleInstrumentMatchingEngine();
    engine.place(input(10, "SELL", 100, 2));

    ExecutionBatch batch = engine.placeRequest(request(20, "BUY", 100, 2, "IOC"));

    assertEquals(2, batch.events().size());
    assertInstanceOf(MatchingEvent.Accepted.class, batch.events().getFirst());
    assertInstanceOf(MatchingEvent.Trade.class, batch.events().getLast());
    assertTrue(batch.bookAfter().asks().isEmpty());
  }

  @Test
  void insufficientFokHasNoBusinessEffectAndDoesNotReserveSequenceOrIdentity() {
    SingleInstrumentMatchingEngine engine = new SingleInstrumentMatchingEngine();
    engine.place(input(10, "SELL", 100, 2));
    engine.place(input(11, "SELL", 101, 2));
    OrderBookSnapshot before = engine.snapshot();

    ExecutionBatch rejected = engine.placeRequest(request(20, "BUY", 100, 3, "FOK"));

    MatchingEvent.PlaceRejected event =
        assertInstanceOf(MatchingEvent.PlaceRejected.class, rejected.events().getFirst());
    assertEquals(PlaceRejectionCode.FOK_NOT_FILLABLE, event.code());
    assertEquals(before, rejected.bookAfter());
    assertEquals(
        CancelRejectionCode.ORDER_NOT_FOUND,
        assertInstanceOf(
                MatchingEvent.CancelRejected.class,
                engine.cancel(cancelInput(20)).events().getFirst())
            .code());

    ExecutionBatch reusedIdentity = engine.place(input(20, "BUY", 99, 1));
    assertEquals(3, acceptedSequence(reusedIdentity));
  }

  @Test
  void fokPreflightSpansLevelsWithoutOverflowAndThenFillsExactly() {
    SingleInstrumentMatchingEngine engine = new SingleInstrumentMatchingEngine();
    long firstQuantity = Long.MAX_VALUE / 2 + 1;
    long secondQuantity = Long.MAX_VALUE / 2 + 1;
    engine.place(input(10, "SELL", 100, firstQuantity));
    engine.place(input(11, "SELL", 101, secondQuantity));

    ExecutionBatch batch = engine.placeRequest(request(20, "BUY", 101, Long.MAX_VALUE, "FOK"));

    assertEquals(ExecutionPolicy.FOK, accepted(batch).executionPolicy());
    assertEquals(2, batch.events().stream().filter(MatchingEvent.Trade.class::isInstance).count());
    long traded =
        batch.events().stream()
            .filter(MatchingEvent.Trade.class::isInstance)
            .map(MatchingEvent.Trade.class::cast)
            .mapToLong(event -> event.quantityLots().value())
            .reduce(0L, Math::addExact);
    assertEquals(Long.MAX_VALUE, traded);
    OrderBookSnapshot.RestingOrderView remainder =
        batch.bookAfter().asks().getFirst().orders().getFirst();
    assertEquals(11, remainder.orderId().value());
    assertEquals(1, remainder.remainingQuantityLots().value());
  }

  @Test
  void postOnlyRejectsTouchWithoutEffectThenAllowsSameIdentityToRestNonCrossing() {
    SingleInstrumentMatchingEngine engine = new SingleInstrumentMatchingEngine();
    engine.place(input(10, "SELL", 100, 2));
    OrderBookSnapshot before = engine.snapshot();

    ExecutionBatch touch = engine.placeRequest(request(20, "BUY", 100, 1, "POST_ONLY"));

    MatchingEvent.PlaceRejected rejected =
        assertInstanceOf(MatchingEvent.PlaceRejected.class, touch.events().getFirst());
    assertEquals(PlaceRejectionCode.POST_ONLY_WOULD_TAKE, rejected.code());
    assertEquals(before, touch.bookAfter());

    ExecutionBatch accepted = engine.placeRequest(request(20, "BUY", 99, 1, "POST_ONLY"));
    assertEquals(2, acceptedSequence(accepted));
    assertEquals(ExecutionPolicy.POST_ONLY, accepted(accepted).executionPolicy());
    assertInstanceOf(MatchingEvent.Rested.class, accepted.events().getLast());
  }

  @Test
  void duplicateIdentityPrecedesFokAndPostOnlyBookDependentRejections() {
    SingleInstrumentMatchingEngine engine = new SingleInstrumentMatchingEngine();
    engine.place(input(10, "SELL", 100, 1));

    for (String policy : List.of("FOK", "POST_ONLY")) {
      MatchingEvent.PlaceRejected rejected =
          assertInstanceOf(
              MatchingEvent.PlaceRejected.class,
              engine.placeRequest(request(10, "BUY", 100, 2, policy)).events().getFirst());
      assertEquals(PlaceRejectionCode.DUPLICATE_ORDER_ID, rejected.code());
    }
  }

  @Test
  void sellPoliciesMirrorBuyLimitsAndTouchSemantics() {
    SingleInstrumentMatchingEngine engine = new SingleInstrumentMatchingEngine();
    engine.place(input(10, "BUY", 100, 2));
    engine.place(input(11, "BUY", 99, 2));

    MatchingEvent.PlaceRejected postOnlyTouch =
        assertInstanceOf(
            MatchingEvent.PlaceRejected.class,
            engine.placeRequest(request(20, "SELL", 100, 1, "POST_ONLY")).events().getFirst());
    assertEquals(PlaceRejectionCode.POST_ONLY_WOULD_TAKE, postOnlyTouch.code());

    MatchingEvent.PlaceRejected outsideLimit =
        assertInstanceOf(
            MatchingEvent.PlaceRejected.class,
            engine.placeRequest(request(21, "SELL", 100, 3, "FOK")).events().getFirst());
    assertEquals(PlaceRejectionCode.FOK_NOT_FILLABLE, outsideLimit.code());

    ExecutionBatch filled = engine.placeRequest(request(21, "SELL", 99, 4, "FOK"));
    assertEquals(ExecutionPolicy.FOK, accepted(filled).executionPolicy());
    assertEquals(2, filled.events().stream().filter(MatchingEvent.Trade.class::isInstance).count());
    assertTrue(filled.bookAfter().bids().isEmpty());
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

  private static PlaceLimitOrderRequest request(
      long orderId, String side, long priceTicks, long quantityLots, String policy) {
    return new PlaceLimitOrderRequest(input(orderId, side, priceTicks, quantityLots), policy);
  }

  private static CancelOrderInput cancelInput(long orderId) {
    return new CancelOrderInput("BTC-USDT", BigInteger.valueOf(orderId));
  }

  private static MatchingEvent.Accepted accepted(ExecutionBatch batch) {
    return assertInstanceOf(MatchingEvent.Accepted.class, batch.events().getFirst());
  }

  private static long acceptedSequence(ExecutionBatch batch) {
    return accepted(batch).sequence().value();
  }

  private static List<Long> askPrices(OrderBookSnapshot snapshot) {
    return snapshot.asks().stream().map(level -> level.priceTicks().value()).toList();
  }
}
