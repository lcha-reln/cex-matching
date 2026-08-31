package io.github.lchareln.cex.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class SingleInstrumentMassCancelTest {
  private static final OperatorId OPERATOR = new OperatorId("ops-mass-cancel");

  @Test
  void exactApplicationAndExpectedModePreflightBeforeHaltedPermission() {
    SingleInstrumentMatchingEngine engine = new SingleInstrumentMatchingEngine();
    engine.place(place(1, "BUY", 99, 1));
    OrderBookSnapshot before = engine.snapshot();

    assertMassRejected(
        engine.massCancel(new MassCancel(new ApplicationSequence(1), MarketMode.OPEN, OPERATOR)),
        MassCancelRejectionCode.APPLICATION_SEQUENCE_MISMATCH,
        MarketMode.OPEN);
    assertEquals(before, engine.snapshot());

    assertMassRejected(
        engine.massCancel(
            new MassCancel(new ApplicationSequence(3), MarketMode.CANCEL_ONLY, OPERATOR)),
        MassCancelRejectionCode.EXPECTED_MODE_MISMATCH,
        MarketMode.OPEN);
    assertEquals(before, engine.snapshot());

    assertMassRejected(
        engine.massCancel(new MassCancel(new ApplicationSequence(4), MarketMode.OPEN, OPERATOR)),
        MassCancelRejectionCode.MARKET_NOT_HALTED,
        MarketMode.OPEN);
    assertEquals(before, engine.snapshot());
    assertEquals(Optional.empty(), engine.marketControlSnapshot().lastMassCancelFence());
  }

  @Test
  void cancelOnlyCannotMassCancelEvenThoughCustomerCancelRemainsAllowed() {
    SingleInstrumentMatchingEngine engine = new SingleInstrumentMatchingEngine();
    engine.place(place(10, "BUY", 99, 1));
    change(engine, 2, MarketMode.OPEN, MarketMode.CANCEL_ONLY);

    assertMassRejected(
        engine.massCancel(
            new MassCancel(new ApplicationSequence(3), MarketMode.CANCEL_ONLY, OPERATOR)),
        MassCancelRejectionCode.MARKET_NOT_HALTED,
        MarketMode.CANCEL_ONLY);
    assertEquals(1, engine.snapshot().bids().size());
    assertInstanceOf(MatchingEvent.Canceled.class, engine.cancel(cancel(10)).events().getFirst());
  }

  @Test
  void successfulMassCancelUsesGlobalAcceptanceOrderAcrossSidesAndPrices() {
    SingleInstrumentMatchingEngine engine = new SingleInstrumentMatchingEngine();
    engine.place(place(101, "BUY", 95, 2));
    engine.place(place(102, "SELL", 105, 3));
    engine.place(place(103, "BUY", 96, 4));
    engine.place(place(104, "SELL", 104, 5));
    change(engine, 5, MarketMode.OPEN, MarketMode.HALTED);

    MassCancelBatch batch =
        engine.massCancel(new MassCancel(new ApplicationSequence(6), MarketMode.HALTED, OPERATOR));

    MassCancelEvent.Started started =
        assertInstanceOf(MassCancelEvent.Started.class, batch.events().getFirst());
    MassCancelEvent.Completed completed =
        assertInstanceOf(MassCancelEvent.Completed.class, batch.events().getLast());
    assertEquals(4, started.restingOrderCount());
    assertEquals(4, completed.canceledOrderCount());
    List<MassCancelEvent.OrderCanceled> canceled =
        batch.events().stream()
            .filter(MassCancelEvent.OrderCanceled.class::isInstance)
            .map(MassCancelEvent.OrderCanceled.class::cast)
            .toList();
    assertEquals(List.of(101L, 102L, 103L, 104L), orderIds(canceled));
    assertEquals(List.of(1L, 2L, 3L, 4L), sequences(canceled));
    assertEquals(List.of(2L, 3L, 4L, 5L), quantities(canceled));
    assertTrue(batch.bookAfter().bids().isEmpty());
    assertTrue(batch.bookAfter().asks().isEmpty());

    MarketControlSnapshot after = batch.controlAfter();
    assertEquals(MarketMode.HALTED, after.marketMode());
    assertEquals(1, after.modeRevision());
    assertEquals(new AcceptanceSequence(5), after.nextAcceptanceSequence());
    MassCancelFence fence = after.lastMassCancelFence().orElseThrow();
    assertEquals(new ApplicationSequence(6), fence.appliedCommandSequence());
    assertEquals(Optional.of(new AcceptanceSequence(1)), fence.firstCanceledSequence());
    assertEquals(Optional.of(new AcceptanceSequence(4)), fence.lastCanceledSequence());
    assertEquals(4, fence.canceledOrderCount());

    MatchingEvent.CancelRejected blockedWhileHalted =
        assertInstanceOf(
            MatchingEvent.CancelRejected.class, engine.cancel(cancel(101)).events().getFirst());
    assertEquals(CancelRejectionCode.MARKET_NOT_CANCELABLE, blockedWhileHalted.code());
    change(engine, 8, MarketMode.HALTED, MarketMode.CANCEL_ONLY);
    MatchingEvent.CancelRejected terminal =
        assertInstanceOf(
            MatchingEvent.CancelRejected.class, engine.cancel(cancel(101)).events().getFirst());
    assertEquals(CancelRejectionCode.ORDER_ALREADY_CANCELED, terminal.code());
    MatchingEvent.PlaceRejected duplicate =
        assertInstanceOf(
            MatchingEvent.PlaceRejected.class,
            engine.place(place(101, "BUY", 90, 1)).events().getFirst());
    assertEquals(PlaceRejectionCode.DUPLICATE_ORDER_ID, duplicate.code());
  }

  @Test
  void emptyBookMassCancelStillHasStartedCompletedAndRetainedFence() {
    SingleInstrumentMatchingEngine engine = new SingleInstrumentMatchingEngine();
    change(engine, 1, MarketMode.OPEN, MarketMode.HALTED);

    MassCancelBatch batch =
        engine.massCancel(new MassCancel(new ApplicationSequence(2), MarketMode.HALTED, OPERATOR));

    assertEquals(2, batch.events().size());
    assertEquals(
        0,
        assertInstanceOf(MassCancelEvent.Started.class, batch.events().getFirst())
            .restingOrderCount());
    assertEquals(
        0,
        assertInstanceOf(MassCancelEvent.Completed.class, batch.events().getLast())
            .canceledOrderCount());
    MassCancelFence fence = batch.controlAfter().lastMassCancelFence().orElseThrow();
    assertEquals(0, fence.canceledOrderCount());
    assertEquals(Optional.empty(), fence.firstCanceledSequence());
    assertEquals(Optional.empty(), fence.lastCanceledSequence());
    assertEquals(MarketMode.HALTED, batch.controlAfter().marketMode());
  }

  @Test
  void massCancelCarriesHistoricalAdmissionAndCurrentExecutionRuleAttribution() {
    SingleInstrumentMatchingEngine engine = new SingleInstrumentMatchingEngine();
    RuleSetIdentity bootstrap = MarketRuleSetArtifact.bootstrapIdentity();
    MarketRuleSetArtifact v1 = artifact(1, 90, 110);

    engine.place(place(201, "BUY", 99, 1));
    engine.prepareRuleSet(new PrepareRuleSet(bootstrap, v1));
    engine.activateRuleSet(
        new ActivateRuleSet(new ApplicationSequence(3), bootstrap, v1.identity()));
    engine.placeGoverned(
        new GovernedPlaceLimitOrderRequest(
            new PlaceLimitOrderRequest(place(202, "SELL", 105, 1), "GTC"), v1.identity()));
    change(engine, 5, MarketMode.OPEN, MarketMode.HALTED);

    MassCancelBatch batch =
        engine.massCancel(new MassCancel(new ApplicationSequence(6), MarketMode.HALTED, OPERATOR));
    List<MassCancelEvent.OrderCanceled> canceled =
        batch.events().stream()
            .filter(MassCancelEvent.OrderCanceled.class::isInstance)
            .map(MassCancelEvent.OrderCanceled.class::cast)
            .toList();

    assertEquals(bootstrap, canceled.getFirst().admissionRuleSet());
    assertEquals(v1.identity(), canceled.getFirst().executionRuleSet());
    assertEquals(v1.identity(), canceled.getLast().admissionRuleSet());
    assertEquals(v1.identity(), canceled.getLast().executionRuleSet());
    assertEquals(v1.identity(), batch.controlAfter().activeIdentity());
    assertEquals(1, batch.controlAfter().controlRevision());
  }

  @Test
  void resultGrammarRejectsDuplicateOrderIdentityAcrossTheFrozenBatch() {
    SingleInstrumentMatchingEngine engine = new SingleInstrumentMatchingEngine();
    engine.place(place(301, "BUY", 99, 1));
    engine.place(place(302, "SELL", 101, 1));
    change(engine, 3, MarketMode.OPEN, MarketMode.HALTED);
    MassCancelBatch valid =
        engine.massCancel(new MassCancel(new ApplicationSequence(4), MarketMode.HALTED, OPERATOR));
    MassCancelEvent.OrderCanceled first =
        assertInstanceOf(MassCancelEvent.OrderCanceled.class, valid.events().get(1));
    MassCancelEvent.OrderCanceled second =
        assertInstanceOf(MassCancelEvent.OrderCanceled.class, valid.events().get(2));
    MassCancelEvent.OrderCanceled duplicateIdentity =
        new MassCancelEvent.OrderCanceled(
            second.applicationSequence(),
            second.operatorId(),
            second.sequence(),
            first.orderId(),
            second.side(),
            second.priceTicks(),
            second.canceledQuantityLots(),
            second.admissionRuleSet(),
            second.executionRuleSet());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MassCancelBatch(
                List.of(
                    valid.events().getFirst(), first, duplicateIdentity, valid.events().getLast()),
                valid.controlAfter(),
                valid.bookAfter()));
  }

  @Test
  void massCancelFenceRequiresCountConsistentSequenceBounds() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MassCancelFence(
                new ApplicationSequence(2),
                1,
                OPERATOR,
                1,
                Optional.of(new AcceptanceSequence(1)),
                Optional.of(new AcceptanceSequence(2))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MassCancelFence(
                new ApplicationSequence(2),
                1,
                OPERATOR,
                2,
                Optional.of(new AcceptanceSequence(1)),
                Optional.of(new AcceptanceSequence(1))));
  }

  @Test
  void snapshotRejectsMassCancelAtTheCurrentRevisionOutsideHalted() {
    SingleInstrumentMatchingEngine engine = new SingleInstrumentMatchingEngine();
    change(engine, 1, MarketMode.OPEN, MarketMode.CANCEL_ONLY);
    MarketControlSnapshot current = engine.marketControlSnapshot();
    MassCancelFence impossible =
        new MassCancelFence(
            new ApplicationSequence(2),
            current.modeRevision(),
            OPERATOR,
            0,
            Optional.empty(),
            Optional.empty());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MarketControlSnapshot(
                current.activeRuleSet(),
                current.preparedRuleSet(),
                current.controlRevision(),
                current.lastActivationFence(),
                new ApplicationSequence(3),
                current.nextAcceptanceSequence(),
                current.marketMode(),
                current.modeRevision(),
                current.lastModeTransitionFence(),
                Optional.of(impossible)));
  }

  private static void assertMassRejected(
      MassCancelBatch batch, MassCancelRejectionCode code, MarketMode observedMode) {
    MassCancelEvent.Rejected rejected =
        assertInstanceOf(MassCancelEvent.Rejected.class, batch.events().getFirst());
    assertEquals(1, batch.events().size());
    assertEquals(code, rejected.code());
    assertEquals(observedMode, rejected.observedMode());
  }

  private static void change(
      SingleInstrumentMatchingEngine engine,
      long applicationSequence,
      MarketMode expected,
      MarketMode target) {
    assertInstanceOf(
        MarketControlEvent.ModeChanged.class,
        engine
            .changeMarketMode(
                new ChangeMarketMode(
                    new ApplicationSequence(applicationSequence), expected, target, OPERATOR))
            .events()
            .getFirst());
  }

  private static List<Long> orderIds(List<MassCancelEvent.OrderCanceled> events) {
    return events.stream().map(event -> event.orderId().value()).toList();
  }

  private static List<Long> sequences(List<MassCancelEvent.OrderCanceled> events) {
    return events.stream().map(event -> event.sequence().value()).toList();
  }

  private static List<Long> quantities(List<MassCancelEvent.OrderCanceled> events) {
    return events.stream().map(event -> event.canceledQuantityLots().value()).toList();
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
    return new CancelOrderInput("BTC-USDT", BigInteger.valueOf(orderId));
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
}
