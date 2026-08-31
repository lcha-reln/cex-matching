package io.github.lchareln.cex.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class SingleInstrumentMarketModeTest {
  private static final OperatorId OPERATOR = new OperatorId("ops-primary");

  @Test
  void bootstrapIsOpenAndModeTransitionHasIndependentRevisionAndFence() {
    SingleInstrumentMatchingEngine engine = new SingleInstrumentMatchingEngine();

    MarketControlSnapshot initial = engine.marketControlSnapshot();
    assertEquals(MarketMode.OPEN, initial.marketMode());
    assertEquals(0, initial.modeRevision());
    assertEquals(Optional.empty(), initial.lastModeTransitionFence());
    assertEquals(Optional.empty(), initial.lastMassCancelFence());

    ExecutionBatch placed = engine.place(place(1, "BUY", 99, 2));
    assertEquals(MarketMode.OPEN, placed.context().marketMode());
    OrderBookSnapshot bookBefore = engine.snapshot();

    MarketControlEvent.ModeChanged changed =
        assertInstanceOf(
            MarketControlEvent.ModeChanged.class,
            engine
                .changeMarketMode(
                    new ChangeMarketMode(
                        new ApplicationSequence(2),
                        MarketMode.OPEN,
                        MarketMode.CANCEL_ONLY,
                        OPERATOR))
                .events()
                .getFirst());

    ModeTransitionFence expectedFence =
        new ModeTransitionFence(
            new ApplicationSequence(2),
            1,
            MarketMode.OPEN,
            MarketMode.CANCEL_ONLY,
            new AcceptanceSequence(2));
    assertEquals(expectedFence, changed.transitionFence());
    MarketControlSnapshot after = engine.marketControlSnapshot();
    assertEquals(MarketMode.CANCEL_ONLY, after.marketMode());
    assertEquals(1, after.modeRevision());
    assertEquals(Optional.of(expectedFence), after.lastModeTransitionFence());
    assertEquals(0, after.controlRevision());
    assertEquals(bookBefore, engine.snapshot());
    assertEquals(new AcceptanceSequence(2), after.nextAcceptanceSequence());
  }

  @Test
  void transitionRejectionsConsumeBoundariesButRetainModeRevisionAndBook() {
    SingleInstrumentMatchingEngine engine = new SingleInstrumentMatchingEngine();
    engine.place(place(10, "SELL", 101, 1));
    MarketControlSnapshot before = engine.marketControlSnapshot();

    assertModeRejected(
        engine.changeMarketMode(
            new ChangeMarketMode(
                new ApplicationSequence(1), MarketMode.OPEN, MarketMode.HALTED, OPERATOR)),
        ChangeMarketModeRejectionCode.APPLICATION_SEQUENCE_MISMATCH,
        MarketMode.OPEN);
    assertModeRetainedExceptApplication(before, engine.marketControlSnapshot(), 3);

    assertModeRejected(
        engine.changeMarketMode(
            new ChangeMarketMode(
                new ApplicationSequence(3), MarketMode.CANCEL_ONLY, MarketMode.HALTED, OPERATOR)),
        ChangeMarketModeRejectionCode.EXPECTED_MODE_MISMATCH,
        MarketMode.OPEN);
    assertModeRetainedExceptApplication(before, engine.marketControlSnapshot(), 4);

    assertModeRejected(
        engine.changeMarketMode(
            new ChangeMarketMode(
                new ApplicationSequence(4), MarketMode.OPEN, MarketMode.OPEN, OPERATOR)),
        ChangeMarketModeRejectionCode.NO_MODE_CHANGE,
        MarketMode.OPEN);
    assertModeRetainedExceptApplication(before, engine.marketControlSnapshot(), 5);
  }

  @Test
  void haltedCannotReopenDirectlyAndMustPassThroughCancelOnly() {
    SingleInstrumentMatchingEngine engine = new SingleInstrumentMatchingEngine();

    change(engine, 1, MarketMode.OPEN, MarketMode.HALTED);
    assertModeRejected(
        engine.changeMarketMode(
            new ChangeMarketMode(
                new ApplicationSequence(2), MarketMode.HALTED, MarketMode.OPEN, OPERATOR)),
        ChangeMarketModeRejectionCode.INVALID_TRANSITION,
        MarketMode.HALTED);
    assertEquals(MarketMode.HALTED, engine.marketControlSnapshot().marketMode());
    assertEquals(1, engine.marketControlSnapshot().modeRevision());

    change(engine, 3, MarketMode.HALTED, MarketMode.CANCEL_ONLY);
    change(engine, 4, MarketMode.CANCEL_ONLY, MarketMode.OPEN);
    assertEquals(MarketMode.OPEN, engine.marketControlSnapshot().marketMode());
    assertEquals(3, engine.marketControlSnapshot().modeRevision());
  }

  @Test
  void permissionMatrixGatesCustomerActionsButKeepsRuleControlsAvailable() {
    SingleInstrumentMatchingEngine engine = new SingleInstrumentMatchingEngine();
    engine.place(place(20, "BUY", 99, 2));
    change(engine, 2, MarketMode.OPEN, MarketMode.CANCEL_ONLY);

    MatchingEvent.PlaceRejected cancelOnlyPlace =
        assertInstanceOf(
            MatchingEvent.PlaceRejected.class,
            engine.place(place(21, "BUY", 98, 1)).events().getFirst());
    assertEquals(PlaceRejectionCode.MARKET_NOT_OPEN, cancelOnlyPlace.code());
    assertEquals(MarketMode.CANCEL_ONLY, engine.cancel(cancel(20)).context().marketMode());
    assertEquals(0, engine.snapshot().bids().size());

    change(engine, 5, MarketMode.CANCEL_ONLY, MarketMode.HALTED);
    MatchingEvent.PlaceRejected haltedPlace =
        assertInstanceOf(
            MatchingEvent.PlaceRejected.class,
            engine.place(place(22, "SELL", 101, 1)).events().getFirst());
    assertEquals(PlaceRejectionCode.MARKET_NOT_OPEN, haltedPlace.code());
    MatchingEvent.CancelRejected haltedCancel =
        assertInstanceOf(
            MatchingEvent.CancelRejected.class, engine.cancel(cancel(999)).events().getFirst());
    assertEquals(CancelRejectionCode.MARKET_NOT_CANCELABLE, haltedCancel.code());

    MarketRuleSetArtifact v1 = artifact(1, 90, 110);
    assertInstanceOf(
        MarketControlEvent.RuleSetPrepared.class,
        engine
            .prepareRuleSet(new PrepareRuleSet(MarketRuleSetArtifact.bootstrapIdentity(), v1))
            .events()
            .getFirst());
    assertInstanceOf(
        MarketControlEvent.RuleSetActivated.class,
        engine
            .activateRuleSet(
                new ActivateRuleSet(
                    new ApplicationSequence(9),
                    MarketRuleSetArtifact.bootstrapIdentity(),
                    v1.identity()))
            .events()
            .getFirst());
    assertEquals(MarketMode.HALTED, engine.marketControlSnapshot().marketMode());
    assertEquals(1, engine.marketControlSnapshot().controlRevision());
    assertEquals(2, engine.marketControlSnapshot().modeRevision());
  }

  @Test
  void inheritedPlacePriorityPrecedesTheNewModeGate() {
    SingleInstrumentMatchingEngine engine = new SingleInstrumentMatchingEngine();
    engine.place(place(30, "BUY", 99, 1));
    change(engine, 2, MarketMode.OPEN, MarketMode.CANCEL_ONLY);

    MatchingEvent.Rejected invalid =
        assertInstanceOf(
            MatchingEvent.Rejected.class, engine.place(place(31, "BUY", 0, 1)).events().getFirst());
    assertEquals(ValidationCode.INVALID_PRICE, invalid.code());
    MatchingEvent.PlaceRejected duplicate =
        assertInstanceOf(
            MatchingEvent.PlaceRejected.class,
            engine.place(place(30, "SELL", 101, 1)).events().getFirst());
    assertEquals(PlaceRejectionCode.DUPLICATE_ORDER_ID, duplicate.code());
    MatchingEvent.PlaceRejected closed =
        assertInstanceOf(
            MatchingEvent.PlaceRejected.class,
            engine.place(place(31, "BUY", 98, 1)).events().getFirst());
    assertEquals(PlaceRejectionCode.MARKET_NOT_OPEN, closed.code());
  }

  @Test
  void operatorIdentityIsAuditDataWithBoundedNonBlankShape() {
    assertThrows(NullPointerException.class, () -> new OperatorId(null));
    assertThrows(IllegalArgumentException.class, () -> new OperatorId("   "));
    assertThrows(IllegalArgumentException.class, () -> new OperatorId("x".repeat(129)));
    assertEquals("ops/audit:1", new OperatorId("ops/audit:1").value());
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

  private static void assertModeRejected(
      MarketControlBatch batch, ChangeMarketModeRejectionCode code, MarketMode observedMode) {
    MarketControlEvent.ModeChangeRejected rejected =
        assertInstanceOf(MarketControlEvent.ModeChangeRejected.class, batch.events().getFirst());
    assertEquals(code, rejected.code());
    assertEquals(observedMode, rejected.observedMode());
  }

  private static void assertModeRetainedExceptApplication(
      MarketControlSnapshot expected, MarketControlSnapshot actual, long nextApplication) {
    assertEquals(expected.activeRuleSet(), actual.activeRuleSet());
    assertEquals(expected.preparedRuleSet(), actual.preparedRuleSet());
    assertEquals(expected.controlRevision(), actual.controlRevision());
    assertEquals(expected.lastActivationFence(), actual.lastActivationFence());
    assertEquals(expected.nextAcceptanceSequence(), actual.nextAcceptanceSequence());
    assertEquals(expected.marketMode(), actual.marketMode());
    assertEquals(expected.modeRevision(), actual.modeRevision());
    assertEquals(expected.lastModeTransitionFence(), actual.lastModeTransitionFence());
    assertEquals(expected.lastMassCancelFence(), actual.lastMassCancelFence());
    assertEquals(new ApplicationSequence(nextApplication), actual.nextApplicationSequence());
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
