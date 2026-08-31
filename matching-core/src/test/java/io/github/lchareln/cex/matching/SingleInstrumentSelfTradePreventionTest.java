package io.github.lchareln.cex.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

final class SingleInstrumentSelfTradePreventionTest {
  private static final String INSTRUMENT = "BTC-USDT";
  private static final RuleSetIdentity BOOTSTRAP = MarketRuleSetArtifact.bootstrapIdentity();

  @Test
  void rawValidationPrecedesDuplicateAndLegacyEntrypointsMapToZeroNone() {
    SingleInstrumentMatchingEngine engine = new SingleInstrumentMatchingEngine();
    ExecutionBatch legacy = engine.place(input(1, "BUY", 99, 1));
    MatchingEvent.Accepted accepted =
        assertInstanceOf(MatchingEvent.Accepted.class, legacy.events().getFirst());
    OrderBookSnapshot.RestingOrderView resting =
        legacy.bookAfter().bids().getFirst().orders().getFirst();
    assertEquals(0, accepted.participantGroupId());
    assertEquals(SelfTradePreventionPolicy.NONE, accepted.selfTradePreventionPolicy());
    assertEquals(0, resting.participantGroupId());
    assertEquals(SelfTradePreventionPolicy.NONE, resting.selfTradePreventionPolicy());

    assertRejected(
        engine.placeStp(stp(1, "SELL", 99, 1, "UNKNOWN", -1, "bad")),
        ValidationCode.INVALID_EXECUTION_POLICY);
    assertRejected(
        engine.placeStp(stp(1, "SELL", 99, 1, "GTC", -1, "bad")),
        ValidationCode.INVALID_STP_GROUP_ID);
    assertRejected(
        engine.placeStp(stp(1, "SELL", 99, 1, "GTC", 7, "bad")), ValidationCode.INVALID_STP_POLICY);
    assertRejected(
        engine.placeStp(stp(1, "SELL", 99, 1, "GTC", 0, "CANCEL_TAKER")),
        ValidationCode.INVALID_STP_INSTRUCTION);
  }

  @Test
  void differentPositiveGroupsTradeNormally() {
    SingleInstrumentMatchingEngine engine = new SingleInstrumentMatchingEngine();
    engine.placeStp(stp(10, "SELL", 100, 2, "GTC", 7, "CANCEL_TAKER"));

    ExecutionBatch batch = engine.placeStp(stp(20, "BUY", 100, 2, "GTC", 8, "CANCEL_BOTH"));

    assertEquals(2, batch.events().size());
    MatchingEvent.Trade trade =
        assertInstanceOf(MatchingEvent.Trade.class, batch.events().getLast());
    assertEquals(10, trade.makerOrderId().value());
    assertEquals(2, trade.quantityLots().value());
    assertTrue(batch.bookAfter().asks().isEmpty());
  }

  @Test
  void cancelTakerLeavesMakerAndUsesStpAsTheOnlyTerminalEvent() {
    SingleInstrumentMatchingEngine engine = new SingleInstrumentMatchingEngine();
    engine.placeStp(stp(10, "SELL", 100, 2, "GTC", 7, "CANCEL_MAKER"));

    ExecutionBatch batch = engine.placeStp(stp(20, "BUY", 100, 3, "IOC", 7, "CANCEL_TAKER"));

    assertEquals(2, batch.events().size());
    MatchingEvent.SelfTradePrevented prevented =
        assertInstanceOf(MatchingEvent.SelfTradePrevented.class, batch.events().getLast());
    assertEquals(SelfTradePreventionPolicy.CANCEL_TAKER, prevented.policy());
    assertEquals(0, prevented.makerCanceledQuantityLots());
    assertEquals(3, prevented.takerCanceledQuantityLots());
    assertEquals(2, prevented.wouldTradeQuantityLots().value());
    assertEquals(List.of(10L), orderIds(batch.bookAfter().asks()));
    assertEquals(
        CancelRejectionCode.ORDER_ALREADY_CANCELED,
        assertInstanceOf(
                MatchingEvent.CancelRejected.class,
                engine.cancel(cancelInput(20)).events().getFirst())
            .code());
  }

  @Test
  void takerPolicyWinsAndCancelMakerContinuesAcrossPriceLevels() {
    SingleInstrumentMatchingEngine engine = new SingleInstrumentMatchingEngine();
    engine.placeStp(stp(10, "SELL", 100, 1, "GTC", 8, "CANCEL_TAKER"));
    engine.placeStp(stp(11, "SELL", 100, 2, "GTC", 7, "CANCEL_TAKER"));
    engine.placeStp(stp(12, "SELL", 101, 2, "GTC", 9, "CANCEL_BOTH"));

    ExecutionBatch batch = engine.placeStp(stp(20, "BUY", 101, 3, "GTC", 7, "CANCEL_MAKER"));

    assertEquals(4, batch.events().size());
    assertEquals(
        10,
        assertInstanceOf(MatchingEvent.Trade.class, batch.events().get(1)).makerOrderId().value());
    MatchingEvent.SelfTradePrevented prevented =
        assertInstanceOf(MatchingEvent.SelfTradePrevented.class, batch.events().get(2));
    assertEquals(11, prevented.makerOrderId().value());
    assertEquals(2, prevented.makerCanceledQuantityLots());
    assertEquals(0, prevented.takerCanceledQuantityLots());
    assertEquals(
        12,
        assertInstanceOf(MatchingEvent.Trade.class, batch.events().get(3)).makerOrderId().value());
    assertTrue(batch.bookAfter().asks().isEmpty());
  }

  @Test
  void cancelBothTerminatesAfterEarlierExternalTradeAndCancelsBothRemainders() {
    SingleInstrumentMatchingEngine engine = new SingleInstrumentMatchingEngine();
    engine.placeStp(stp(10, "SELL", 99, 1, "GTC", 8, "CANCEL_TAKER"));
    engine.placeStp(stp(11, "SELL", 100, 2, "GTC", 7, "CANCEL_MAKER"));

    ExecutionBatch batch = engine.placeStp(stp(20, "BUY", 100, 4, "GTC", 7, "CANCEL_BOTH"));

    assertEquals(3, batch.events().size());
    assertInstanceOf(MatchingEvent.Trade.class, batch.events().get(1));
    MatchingEvent.SelfTradePrevented prevented =
        assertInstanceOf(MatchingEvent.SelfTradePrevented.class, batch.events().getLast());
    assertEquals(2, prevented.makerCanceledQuantityLots());
    assertEquals(3, prevented.takerCanceledQuantityLots());
    assertTrue(batch.bookAfter().asks().isEmpty());
    assertTrue(batch.bookAfter().bids().isEmpty());
  }

  @Test
  void fokCancelTakerRejectsBeforeAcceptanceAndDoesNotMutateEarlierLiquidity() {
    SingleInstrumentMatchingEngine engine = new SingleInstrumentMatchingEngine();
    engine.placeStp(stp(10, "SELL", 99, 1, "GTC", 8, "CANCEL_TAKER"));
    engine.placeStp(stp(11, "SELL", 100, 2, "GTC", 7, "CANCEL_MAKER"));
    engine.placeStp(stp(12, "SELL", 101, 2, "GTC", 9, "CANCEL_BOTH"));
    OrderBookSnapshot before = engine.snapshot();

    ExecutionBatch rejected = engine.placeStp(stp(20, "BUY", 101, 3, "FOK", 7, "CANCEL_TAKER"));

    assertEquals(
        PlaceRejectionCode.FOK_NOT_FILLABLE,
        assertInstanceOf(MatchingEvent.PlaceRejected.class, rejected.events().getFirst()).code());
    assertEquals(before, engine.snapshot());
    assertEquals(
        4,
        assertInstanceOf(
                MatchingEvent.Accepted.class,
                engine
                    .placeStp(stp(20, "BUY", 98, 1, "GTC", 7, "CANCEL_TAKER"))
                    .events()
                    .getFirst())
            .sequence()
            .value());
  }

  @Test
  void fokCancelMakerSkipsSelfLiquidityInPreflightAndDeletesItOnlyAfterAcceptance() {
    SingleInstrumentMatchingEngine insufficient = new SingleInstrumentMatchingEngine();
    insufficient.placeStp(stp(10, "SELL", 100, 2, "GTC", 7, "CANCEL_TAKER"));
    insufficient.placeStp(stp(11, "SELL", 101, 1, "GTC", 8, "CANCEL_TAKER"));
    OrderBookSnapshot before = insufficient.snapshot();
    ExecutionBatch rejected =
        insufficient.placeStp(stp(20, "BUY", 101, 2, "FOK", 7, "CANCEL_MAKER"));
    assertEquals(
        PlaceRejectionCode.FOK_NOT_FILLABLE,
        assertInstanceOf(MatchingEvent.PlaceRejected.class, rejected.events().getFirst()).code());
    assertEquals(before, insufficient.snapshot());

    SingleInstrumentMatchingEngine fillable = new SingleInstrumentMatchingEngine();
    fillable.placeStp(stp(10, "SELL", 100, 2, "GTC", 7, "CANCEL_TAKER"));
    fillable.placeStp(stp(11, "SELL", 101, 2, "GTC", 8, "CANCEL_TAKER"));
    ExecutionBatch accepted = fillable.placeStp(stp(20, "BUY", 101, 2, "FOK", 7, "CANCEL_MAKER"));
    assertEquals(3, accepted.events().size());
    assertInstanceOf(MatchingEvent.SelfTradePrevented.class, accepted.events().get(1));
    assertInstanceOf(MatchingEvent.Trade.class, accepted.events().get(2));
    assertTrue(accepted.bookAfter().asks().isEmpty());
  }

  @Test
  void postOnlyUsesRawBookBeforeStpAndModeGatePrecedesEitherPreflight() {
    SingleInstrumentMatchingEngine postOnly = new SingleInstrumentMatchingEngine();
    postOnly.placeStp(stp(10, "SELL", 100, 2, "GTC", 7, "CANCEL_TAKER"));
    OrderBookSnapshot before = postOnly.snapshot();
    ExecutionBatch rejected =
        postOnly.placeStp(stp(20, "BUY", 100, 2, "POST_ONLY", 7, "CANCEL_MAKER"));
    assertEquals(
        PlaceRejectionCode.POST_ONLY_WOULD_TAKE,
        assertInstanceOf(MatchingEvent.PlaceRejected.class, rejected.events().getFirst()).code());
    assertEquals(before, postOnly.snapshot());

    SingleInstrumentMatchingEngine cancelOnly = new SingleInstrumentMatchingEngine();
    cancelOnly.placeStp(stp(10, "SELL", 100, 2, "GTC", 7, "CANCEL_TAKER"));
    cancelOnly.changeMarketMode(
        new ChangeMarketMode(
            new ApplicationSequence(2),
            MarketMode.OPEN,
            MarketMode.CANCEL_ONLY,
            new OperatorId("ops")));
    ExecutionBatch modeRejected =
        cancelOnly.placeStp(stp(20, "BUY", 100, 2, "FOK", 7, "CANCEL_MAKER"));
    assertEquals(
        PlaceRejectionCode.MARKET_NOT_OPEN,
        assertInstanceOf(MatchingEvent.PlaceRejected.class, modeRejected.events().getFirst())
            .code());
    assertEquals(List.of(10L), orderIds(cancelOnly.snapshot().asks()));
  }

  @Test
  void stpEventRetainsMakerTakerAndExecutionRuleAttribution() {
    SingleInstrumentMatchingEngine engine = new SingleInstrumentMatchingEngine();
    engine.placeStp(stp(10, "SELL", 100, 2, "GTC", 7, "CANCEL_TAKER"));
    MarketRuleSetArtifact v1 = artifact(1, 90, 110);
    engine.prepareRuleSet(new PrepareRuleSet(BOOTSTRAP, v1));
    engine.activateRuleSet(
        new ActivateRuleSet(new ApplicationSequence(3), BOOTSTRAP, v1.identity()));

    ExecutionBatch batch =
        engine.placeGovernedStp(
            new GovernedStpPlaceLimitOrderRequest(
                stp(20, "BUY", 100, 1, "GTC", 7, "CANCEL_TAKER"), v1.identity()));
    MatchingEvent.SelfTradePrevented prevented =
        assertInstanceOf(MatchingEvent.SelfTradePrevented.class, batch.events().getLast());
    assertEquals(BOOTSTRAP, prevented.makerAdmissionRuleSet());
    assertEquals(v1.identity(), prevented.takerAdmissionRuleSet());
    assertEquals(v1.identity(), prevented.executionRuleSet());
  }

  private static StpPlaceLimitOrderRequest stp(
      long orderId,
      String side,
      long price,
      long quantity,
      String executionPolicy,
      long groupId,
      String stpPolicy) {
    return new StpPlaceLimitOrderRequest(
        new PlaceLimitOrderRequest(input(orderId, side, price, quantity), executionPolicy),
        groupId,
        stpPolicy);
  }

  private static PlaceLimitOrderInput input(long orderId, String side, long price, long quantity) {
    return new PlaceLimitOrderInput(
        INSTRUMENT,
        BigInteger.valueOf(orderId),
        side,
        BigInteger.valueOf(price),
        BigInteger.valueOf(quantity));
  }

  private static CancelOrderInput cancelInput(long orderId) {
    return new CancelOrderInput(INSTRUMENT, BigInteger.valueOf(orderId));
  }

  private static MarketRuleSetArtifact artifact(long version, long lower, long upper) {
    MarketRuleSetArtifact unhashed =
        new MarketRuleSetArtifact(
            version,
            lower,
            upper,
            "sha256:0000000000000000000000000000000000000000000000000000000000000000");
    return new MarketRuleSetArtifact(version, lower, upper, unhashed.computedContentHash());
  }

  private static List<Long> orderIds(List<OrderBookSnapshot.PriceLevel> levels) {
    return levels.stream()
        .flatMap(level -> level.orders().stream())
        .map(order -> order.orderId().value())
        .toList();
  }

  private static void assertRejected(ExecutionBatch batch, ValidationCode code) {
    assertEquals(
        code, assertInstanceOf(MatchingEvent.Rejected.class, batch.events().getFirst()).code());
  }
}
