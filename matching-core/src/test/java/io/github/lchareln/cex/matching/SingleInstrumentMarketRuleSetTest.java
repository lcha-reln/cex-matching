package io.github.lchareln.cex.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class SingleInstrumentMarketRuleSetTest {
  private static final RuleSetIdentity BOOTSTRAP = MarketRuleSetArtifact.bootstrapIdentity();

  @Test
  void bootstrapPreservesLegacyAdmissionWhileEveryBusinessResultConsumesApplicationSequence() {
    SingleInstrumentMatchingEngine engine = new SingleInstrumentMatchingEngine();

    MarketControlSnapshot initial = engine.marketControlSnapshot();
    assertEquals(MarketRuleSetArtifact.bootstrap(), initial.activeRuleSet());
    assertEquals(Optional.empty(), initial.preparedRuleSet());
    assertEquals(0, initial.controlRevision());
    assertEquals(new ApplicationSequence(1), initial.nextApplicationSequence());
    assertEquals(new AcceptanceSequence(1), initial.nextAcceptanceSequence());
    assertEquals(initial, engine.marketControlSnapshot());

    ExecutionBatch invalid = engine.place(input(1, "BUY", 0, 1));
    assertEquals(new ApplicationSequence(1), appliedSequence(invalid));
    assertEquals(BOOTSTRAP, invalid.context().activeRuleSet());
    assertEquals(0, invalid.context().controlRevision());
    assertEquals(
        new AcceptanceSequence(1), engine.marketControlSnapshot().nextAcceptanceSequence());

    ExecutionBatch missingCancel = engine.cancel(cancelInput(1));
    assertEquals(new ApplicationSequence(2), appliedSequence(missingCancel));
    assertInstanceOf(MatchingEvent.CancelRejected.class, missingCancel.events().getFirst());

    assertThrows(NullPointerException.class, () -> engine.placeGoverned(null));
    assertEquals(
        new ApplicationSequence(3), engine.marketControlSnapshot().nextApplicationSequence());

    ExecutionBatch accepted = engine.place(input(1, "BUY", 99, 1));
    assertEquals(new ApplicationSequence(3), appliedSequence(accepted));
    assertEquals(
        BOOTSTRAP,
        assertInstanceOf(MatchingEvent.Accepted.class, accepted.events().getFirst())
            .admissionRuleSet());
    assertEquals(
        new ApplicationSequence(4), engine.marketControlSnapshot().nextApplicationSequence());
  }

  @Test
  void prepareValidatesExpectedIdentityHashAndMonotonicSingleSlotWithoutChangingAdmission() {
    SingleInstrumentMatchingEngine engine = new SingleInstrumentMatchingEngine();
    MarketRuleSetArtifact v1 = artifact(1, 90, 110);
    MarketRuleSetArtifact v1Different = artifact(1, 95, 105);
    MarketRuleSetArtifact v2 = artifact(2, 80, 120);
    MarketRuleSetArtifact malformed = new MarketRuleSetArtifact(3, 70, 130, "not-a-hash");
    MarketRuleSetArtifact mismatched =
        new MarketRuleSetArtifact(
            3, 70, 130, "sha256:0000000000000000000000000000000000000000000000000000000000000000");

    MarketControlEvent.PrepareRejected expectedMismatch =
        prepareRejected(engine.prepareRuleSet(new PrepareRuleSet(v1.identity(), malformed)));
    assertEquals(
        PrepareRuleSetRejectionCode.EXPECTED_ACTIVE_RULE_SET_MISMATCH, expectedMismatch.code());
    assertEquals(new ApplicationSequence(1), expectedMismatch.applicationSequence());

    assertEquals(
        PrepareRuleSetRejectionCode.MALFORMED_CONTENT_HASH,
        prepareRejected(engine.prepareRuleSet(new PrepareRuleSet(BOOTSTRAP, malformed))).code());
    assertEquals(
        PrepareRuleSetRejectionCode.CONTENT_HASH_MISMATCH,
        prepareRejected(engine.prepareRuleSet(new PrepareRuleSet(BOOTSTRAP, mismatched))).code());

    MarketControlEvent.RuleSetPrepared prepared =
        prepared(engine.prepareRuleSet(new PrepareRuleSet(BOOTSTRAP, v1)));
    assertEquals(PrepareRuleSetStatus.PREPARED, prepared.status());
    assertEquals(new ApplicationSequence(4), prepared.applicationSequence());
    assertEquals(v1.identity(), engine.marketControlSnapshot().preparedIdentity().orElseThrow());
    assertEquals(BOOTSTRAP, engine.marketControlSnapshot().activeIdentity());
    assertEquals(0, engine.marketControlSnapshot().controlRevision());

    assertEquals(
        PrepareRuleSetStatus.ALREADY_PREPARED,
        prepared(engine.prepareRuleSet(new PrepareRuleSet(BOOTSTRAP, v1))).status());
    assertEquals(
        PrepareRuleSetRejectionCode.SAME_VERSION_DIFFERENT_CONTENT,
        prepareRejected(engine.prepareRuleSet(new PrepareRuleSet(BOOTSTRAP, v1Different))).code());
    assertEquals(v1, engine.marketControlSnapshot().preparedRuleSet().orElseThrow());

    assertEquals(
        PrepareRuleSetStatus.SUPERSEDED,
        prepared(engine.prepareRuleSet(new PrepareRuleSet(BOOTSTRAP, v2))).status());
    assertEquals(v2, engine.marketControlSnapshot().preparedRuleSet().orElseThrow());
    assertEquals(
        PrepareRuleSetRejectionCode.VERSION_NOT_INCREASING,
        prepareRejected(engine.prepareRuleSet(new PrepareRuleSet(BOOTSTRAP, v1))).code());
    assertEquals(v2, engine.marketControlSnapshot().preparedRuleSet().orElseThrow());
    assertTrue(engine.snapshot().bids().isEmpty());
    assertEquals(
        new AcceptanceSequence(1), engine.marketControlSnapshot().nextAcceptanceSequence());
  }

  @Test
  void activateRejectsAtItsOwnAppliedBoundaryAndOnlySuccessAdvancesRevision() {
    SingleInstrumentMatchingEngine engine = new SingleInstrumentMatchingEngine();
    MarketRuleSetArtifact v1 = artifact(1, 90, 110);
    RuleSetIdentity wrongTarget =
        new RuleSetIdentity(
            1, "sha256:0000000000000000000000000000000000000000000000000000000000000000");

    MarketControlEvent.ActivateRejected noPrepared =
        activateRejected(
            engine.activateRuleSet(
                new ActivateRuleSet(new ApplicationSequence(1), BOOTSTRAP, v1.identity())));
    assertEquals(ActivateRuleSetRejectionCode.NO_PREPARED_RULE_SET, noPrepared.code());
    assertEquals(new ApplicationSequence(1), noPrepared.applicationSequence());
    assertEquals(0, engine.marketControlSnapshot().controlRevision());

    engine.prepareRuleSet(new PrepareRuleSet(BOOTSTRAP, v1));
    MarketControlSnapshot preparedBeforeFailure = engine.marketControlSnapshot();

    MarketControlEvent.ActivateRejected wrongTargetEvent =
        activateRejected(
            engine.activateRuleSet(
                new ActivateRuleSet(new ApplicationSequence(3), BOOTSTRAP, wrongTarget)));
    assertEquals(ActivateRuleSetRejectionCode.TARGET_RULE_SET_MISMATCH, wrongTargetEvent.code());
    assertEquals(new ApplicationSequence(3), wrongTargetEvent.applicationSequence());
    assertRetainedExceptApplication(preparedBeforeFailure, engine.marketControlSnapshot(), 4);

    MarketControlEvent.ActivateRejected staleBoundary =
        activateRejected(
            engine.activateRuleSet(
                new ActivateRuleSet(new ApplicationSequence(3), BOOTSTRAP, v1.identity())));
    assertEquals(ActivateRuleSetRejectionCode.APPLICATION_SEQUENCE_MISMATCH, staleBoundary.code());
    assertEquals(new ApplicationSequence(4), staleBoundary.applicationSequence());
    assertRetainedExceptApplication(preparedBeforeFailure, engine.marketControlSnapshot(), 5);

    MarketControlEvent.RuleSetActivated activated =
        assertInstanceOf(
            MarketControlEvent.RuleSetActivated.class,
            engine
                .activateRuleSet(
                    new ActivateRuleSet(new ApplicationSequence(5), BOOTSTRAP, v1.identity()))
                .events()
                .getFirst());
    assertEquals(new ApplicationSequence(5), activated.applicationSequence());
    assertEquals(BOOTSTRAP, activated.previousActiveRuleSet());
    assertEquals(v1.identity(), activated.activeRuleSet());
    assertEquals(
        new ActivationFence(new ApplicationSequence(5), 1, new AcceptanceSequence(1)),
        activated.activationFence());

    MarketControlSnapshot after = engine.marketControlSnapshot();
    assertEquals(v1, after.activeRuleSet());
    assertEquals(Optional.empty(), after.preparedRuleSet());
    assertEquals(1, after.controlRevision());
    assertEquals(Optional.of(activated.activationFence()), after.lastActivationFence());
    assertEquals(new ApplicationSequence(6), after.nextApplicationSequence());
  }

  @Test
  void governedPlacePriorityIsFieldsPolicyDuplicateFenceBandThenPolicyPrecheck() {
    SingleInstrumentMatchingEngine engine = activatedEngine(artifact(1, 95, 105));
    RuleSetIdentity active = engine.marketControlSnapshot().activeIdentity();

    MatchingEvent.Rejected fields =
        assertInstanceOf(
            MatchingEvent.Rejected.class,
            engine
                .placeGoverned(
                    governed(
                        new PlaceLimitOrderInput(
                            "ETH-USDT",
                            BigInteger.ZERO,
                            "NO_SIDE",
                            BigInteger.ZERO,
                            BigInteger.ZERO),
                        "UNKNOWN",
                        BOOTSTRAP))
                .events()
                .getFirst());
    assertEquals(ValidationCode.UNKNOWN_INSTRUMENT, fields.code());

    MatchingEvent.Rejected policy =
        assertInstanceOf(
            MatchingEvent.Rejected.class,
            engine
                .placeGoverned(governed(input(40, "BUY", 1, 1), "UNKNOWN", BOOTSTRAP))
                .events()
                .getFirst());
    assertEquals(ValidationCode.INVALID_EXECUTION_POLICY, policy.code());

    engine.placeGoverned(governed(input(40, "BUY", 100, 1), "GTC", active));
    MatchingEvent.PlaceRejected duplicate =
        placeRejected(engine.placeGoverned(governed(input(40, "BUY", 1, 1), "GTC", BOOTSTRAP)));
    assertEquals(PlaceRejectionCode.DUPLICATE_ORDER_ID, duplicate.code());

    MatchingEvent.PlaceRejected stale =
        placeRejected(engine.placeGoverned(governed(input(41, "BUY", 1, 1), "FOK", BOOTSTRAP)));
    assertEquals(PlaceRejectionCode.RULE_SET_MISMATCH, stale.code());

    MatchingEvent.PlaceRejected outside =
        placeRejected(engine.placeGoverned(governed(input(41, "BUY", 94, 1), "FOK", active)));
    assertEquals(PlaceRejectionCode.PRICE_OUTSIDE_ACTIVE_BAND, outside.code());

    MatchingEvent.PlaceRejected postOnlyOutside =
        placeRejected(
            engine.placeRequest(
                new PlaceLimitOrderRequest(input(44, "SELL", 110, 1), "POST_ONLY")));
    assertEquals(PlaceRejectionCode.PRICE_OUTSIDE_ACTIVE_BAND, postOnlyOutside.code());

    MatchingEvent.PlaceRejected fok =
        placeRejected(engine.placeGoverned(governed(input(41, "BUY", 100, 1), "FOK", active)));
    assertEquals(PlaceRejectionCode.FOK_NOT_FILLABLE, fok.code());

    ExecutionBatch lower = engine.placeGoverned(governed(input(41, "BUY", 95, 1), "GTC", active));
    ExecutionBatch upper = engine.placeGoverned(governed(input(42, "SELL", 105, 1), "GTC", active));
    assertInstanceOf(MatchingEvent.Accepted.class, lower.events().getFirst());
    assertInstanceOf(MatchingEvent.Accepted.class, upper.events().getFirst());
    assertEquals(2, lower.bookAfter().bids().size());
    assertEquals(1, upper.bookAfter().asks().size());

    MatchingEvent.PlaceRejected legacyOutside =
        placeRejected(engine.place(input(43, "SELL", 106, 1)));
    assertEquals(PlaceRejectionCode.PRICE_OUTSIDE_ACTIVE_BAND, legacyOutside.code());
    assertEquals(
        CancelRejectionCode.ORDER_NOT_FOUND,
        assertInstanceOf(
                MatchingEvent.CancelRejected.class,
                engine.cancel(cancelInput(43)).events().getFirst())
            .code());
  }

  @Test
  void activationGrandfathersOutsideMakerAndEventsRecoverAllRuleIdentities() {
    SingleInstrumentMatchingEngine engine = new SingleInstrumentMatchingEngine();
    MarketRuleSetArtifact v1 = artifact(1, 95, 105);

    ExecutionBatch makerBatch = engine.place(input(60, "SELL", 90, 2));
    MatchingEvent.Accepted oldAccepted =
        assertInstanceOf(MatchingEvent.Accepted.class, makerBatch.events().getFirst());
    assertEquals(BOOTSTRAP, oldAccepted.admissionRuleSet());
    assertEquals(
        BOOTSTRAP, makerBatch.bookAfter().asks().getFirst().orders().getFirst().admissionRuleSet());

    engine.prepareRuleSet(new PrepareRuleSet(BOOTSTRAP, v1));
    MarketControlEvent.RuleSetActivated activated =
        assertInstanceOf(
            MarketControlEvent.RuleSetActivated.class,
            engine
                .activateRuleSet(
                    new ActivateRuleSet(new ApplicationSequence(3), BOOTSTRAP, v1.identity()))
                .events()
                .getFirst());
    assertEquals(new AcceptanceSequence(2), activated.activationFence().firstAcceptanceSequence());

    ExecutionBatch fill =
        engine.placeGoverned(governed(input(61, "BUY", 100, 1), "FOK", v1.identity()));
    MatchingEvent.Accepted taker =
        assertInstanceOf(MatchingEvent.Accepted.class, fill.events().getFirst());
    MatchingEvent.Trade trade = assertInstanceOf(MatchingEvent.Trade.class, fill.events().get(1));
    assertEquals(v1.identity(), taker.admissionRuleSet());
    assertEquals(BOOTSTRAP, trade.makerAdmissionRuleSet());
    assertEquals(v1.identity(), trade.takerAdmissionRuleSet());
    assertEquals(v1.identity(), trade.executionRuleSet());
    assertEquals(90, trade.priceTicks().value());

    OrderBookSnapshot.RestingOrderView grandfather =
        fill.bookAfter().asks().getFirst().orders().getFirst();
    assertEquals(new OrderId(60), grandfather.orderId());
    assertEquals(new QuantityLots(1), grandfather.remainingQuantityLots());
    assertEquals(BOOTSTRAP, grandfather.admissionRuleSet());

    MatchingEvent.Canceled canceled =
        assertInstanceOf(
            MatchingEvent.Canceled.class, engine.cancel(cancelInput(60)).events().getFirst());
    assertEquals(BOOTSTRAP, canceled.admissionRuleSet());
    assertEquals(v1.identity(), canceled.executionRuleSet());

    MatchingEvent.PlaceRejected outside =
        placeRejected(
            engine.placeGoverned(governed(input(62, "SELL", 90, 1), "GTC", v1.identity())));
    assertEquals(PlaceRejectionCode.PRICE_OUTSIDE_ACTIVE_BAND, outside.code());
  }

  @Test
  void applicationAndAcceptanceExhaustionFailBeforeMutationOrSequenceConsumption() {
    SingleInstrumentMatchingEngine applicationExhausted =
        new SingleInstrumentMatchingEngine(1, Long.MAX_VALUE);
    MarketControlSnapshot applicationBefore = applicationExhausted.marketControlSnapshot();

    IllegalStateException applicationFailure =
        assertThrows(
            IllegalStateException.class, () -> applicationExhausted.cancel(cancelInput(1)));
    assertTrue(applicationFailure.getMessage().contains("before state mutation"));
    assertEquals(applicationBefore, applicationExhausted.marketControlSnapshot());

    SingleInstrumentMatchingEngine acceptanceExhausted =
        new SingleInstrumentMatchingEngine(Long.MAX_VALUE, 1);
    ExecutionBatch invalid = acceptanceExhausted.place(input(1, "BUY", 0, 1));
    assertEquals(new ApplicationSequence(1), appliedSequence(invalid));
    MarketControlSnapshot beforeValid = acceptanceExhausted.marketControlSnapshot();

    IllegalStateException acceptanceFailure =
        assertThrows(
            IllegalStateException.class, () -> acceptanceExhausted.place(input(1, "BUY", 100, 1)));
    assertTrue(acceptanceFailure.getMessage().contains("before state mutation"));
    assertEquals(beforeValid, acceptanceExhausted.marketControlSnapshot());
    assertTrue(acceptanceExhausted.snapshot().bids().isEmpty());
  }

  private static void assertRetainedExceptApplication(
      MarketControlSnapshot before, MarketControlSnapshot after, long expectedNextApplication) {
    assertEquals(before.activeRuleSet(), after.activeRuleSet());
    assertEquals(before.preparedRuleSet(), after.preparedRuleSet());
    assertEquals(before.controlRevision(), after.controlRevision());
    assertEquals(before.lastActivationFence(), after.lastActivationFence());
    assertEquals(before.nextAcceptanceSequence(), after.nextAcceptanceSequence());
    assertEquals(new ApplicationSequence(expectedNextApplication), after.nextApplicationSequence());
  }

  private static SingleInstrumentMatchingEngine activatedEngine(MarketRuleSetArtifact artifact) {
    SingleInstrumentMatchingEngine engine = new SingleInstrumentMatchingEngine();
    engine.prepareRuleSet(new PrepareRuleSet(BOOTSTRAP, artifact));
    engine.activateRuleSet(
        new ActivateRuleSet(new ApplicationSequence(2), BOOTSTRAP, artifact.identity()));
    return engine;
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

  private static GovernedPlaceLimitOrderRequest governed(
      PlaceLimitOrderInput input, String policy, RuleSetIdentity expectedActive) {
    return new GovernedPlaceLimitOrderRequest(
        new PlaceLimitOrderRequest(input, policy), expectedActive);
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

  private static CancelOrderInput cancelInput(long orderId) {
    return new CancelOrderInput("BTC-USDT", BigInteger.valueOf(orderId));
  }

  private static ApplicationSequence appliedSequence(ExecutionBatch batch) {
    return batch.context().applicationSequence().orElseThrow();
  }

  private static MatchingEvent.PlaceRejected placeRejected(ExecutionBatch batch) {
    return assertInstanceOf(MatchingEvent.PlaceRejected.class, batch.events().getFirst());
  }

  private static MarketControlEvent.RuleSetPrepared prepared(MarketControlBatch batch) {
    return assertInstanceOf(MarketControlEvent.RuleSetPrepared.class, batch.events().getFirst());
  }

  private static MarketControlEvent.PrepareRejected prepareRejected(MarketControlBatch batch) {
    return assertInstanceOf(MarketControlEvent.PrepareRejected.class, batch.events().getFirst());
  }

  private static MarketControlEvent.ActivateRejected activateRejected(MarketControlBatch batch) {
    return assertInstanceOf(MarketControlEvent.ActivateRejected.class, batch.events().getFirst());
  }
}
