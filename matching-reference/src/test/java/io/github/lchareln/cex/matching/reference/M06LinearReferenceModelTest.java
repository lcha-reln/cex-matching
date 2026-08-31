package io.github.lchareln.cex.matching.reference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class M06LinearReferenceModelTest {
  private static final String BOOTSTRAP_HASH =
      "sha256:d9928c52e99b8611cb95fb0d2792b6901cf9336825e19a7f593393b0d2b99c04";
  private static final String OPERATOR = "ops-reference";

  @Test
  void inheritedRuleArtifactKeepsTheExactM05CanonicalIdentity() {
    M06MarketRuleSetArtifact v1 = artifact(1, 90, 110);

    assertEquals(BOOTSTRAP_HASH, M06MarketRuleSetArtifact.bootstrap().contentHash());
    assertEquals(
        "sha256:dbb75b3983480a8ece058736766411f80eb5c62e10eb24de72b74853d5377f91",
        v1.contentHash());
    assertEquals(
        "M05RS1\n",
        new String(v1.canonicalBytes(), StandardCharsets.UTF_8).lines().findFirst().orElseThrow()
            + "\n");
  }

  @Test
  void bootstrapIsOpenAndCancelOnlyRejectsPlaceButAllowsCustomerCancel() {
    M06LinearReferenceModel model = new M06LinearReferenceModel();
    M06RuleSetIdentity bootstrap = M06MarketRuleSetArtifact.bootstrap().identity();

    M06SemanticMarketState initial = model.snapshot();
    assertEquals("OPEN", initial.marketMode());
    assertEquals(BigInteger.ZERO, initial.modeRevision());
    assertEquals(Optional.empty(), initial.lastModeTransitionFence());
    assertEquals(Optional.empty(), initial.lastMassCancelFence());

    model.apply(legacy(1, "BUY", 99, 2, "GTC"));
    M06SemanticOutcome changed = model.apply(change(2, "OPEN", "CANCEL_ONLY"));
    M06SemanticMarketState.ModeTransitionFence fence =
        new M06SemanticMarketState.ModeTransitionFence(bi(2), bi(1), "OPEN", "CANCEL_ONLY", bi(2));
    assertEquals(
        List.of(new M06SemanticEvent.ModeChanged(OPERATOR, "OPEN", "CANCEL_ONLY", fence)),
        changed.events());
    assertEquals("CANCEL_ONLY", changed.stateAfter().marketMode());
    assertEquals(bi(1), changed.stateAfter().modeRevision());
    assertEquals(bootstrap, changed.activeRuleSet());

    assertPlaceRejected("MARKET_NOT_OPEN", model.apply(legacy(2, "BUY", 98, 1, "GTC")));
    M06SemanticOutcome canceled = model.apply(cancel(1));
    assertInstanceOf(M06SemanticEvent.Canceled.class, canceled.events().getFirst());
    assertEquals(M06SemanticBook.empty(), canceled.bookAfter());
  }

  @Test
  void transitionFencesRejectStaleExpectedAndDirectHaltedReopen() {
    M06LinearReferenceModel model = new M06LinearReferenceModel();

    assertModeRejected("APPLICATION_SEQUENCE_MISMATCH", model.apply(change(2, "OPEN", "HALTED")));
    assertModeRejected("EXPECTED_MODE_MISMATCH", model.apply(change(2, "CANCEL_ONLY", "HALTED")));
    assertModeRejected("NO_MODE_CHANGE", model.apply(change(3, "OPEN", "OPEN")));
    assertInstanceOf(
        M06SemanticEvent.ModeChanged.class,
        model.apply(change(4, "OPEN", "HALTED")).events().getFirst());
    assertModeRejected("INVALID_TRANSITION", model.apply(change(5, "HALTED", "OPEN")));
    assertInstanceOf(
        M06SemanticEvent.ModeChanged.class,
        model.apply(change(6, "HALTED", "CANCEL_ONLY")).events().getFirst());
    assertInstanceOf(
        M06SemanticEvent.ModeChanged.class,
        model.apply(change(7, "CANCEL_ONLY", "OPEN")).events().getFirst());

    assertEquals("OPEN", model.snapshot().marketMode());
    assertEquals(bi(3), model.snapshot().modeRevision());
    assertEquals(bi(8), model.snapshot().nextApplicationSequence());
  }

  @Test
  void malformedOperatorOrModeFailsBeforeAnApplicationBoundary() {
    M06LinearReferenceModel model = new M06LinearReferenceModel();

    assertThrows(
        IllegalArgumentException.class,
        () -> new M06ReferenceCommand.ChangeMarketMode(bi(1), "OPEN", "UNKNOWN", OPERATOR));
    assertThrows(
        IllegalArgumentException.class,
        () -> new M06ReferenceCommand.MassCancel(bi(1), "OPEN", " "));
    assertEquals(bi(1), model.snapshot().nextApplicationSequence());
  }

  @Test
  void massCancelPreflightIsExactAndNeverMutatesTheBookOnRejection() {
    M06LinearReferenceModel model = new M06LinearReferenceModel();
    model.apply(legacy(10, "BUY", 99, 1, "GTC"));
    M06SemanticBook before = model.snapshot().book();

    assertMassRejected("APPLICATION_SEQUENCE_MISMATCH", model.apply(massCancel(1, "OPEN")), "OPEN");
    assertEquals(before, model.snapshot().book());
    assertMassRejected("EXPECTED_MODE_MISMATCH", model.apply(massCancel(3, "CANCEL_ONLY")), "OPEN");
    assertEquals(before, model.snapshot().book());
    assertMassRejected("MARKET_NOT_HALTED", model.apply(massCancel(4, "OPEN")), "OPEN");
    assertEquals(before, model.snapshot().book());

    model.apply(change(5, "OPEN", "CANCEL_ONLY"));
    assertMassRejected(
        "MARKET_NOT_HALTED", model.apply(massCancel(6, "CANCEL_ONLY")), "CANCEL_ONLY");
    assertEquals(before, model.snapshot().book());
    assertEquals(Optional.empty(), model.snapshot().lastMassCancelFence());
  }

  @Test
  void haltedMassCancelOrdersEverySideAndPriceByGlobalAcceptanceSequence() {
    M06LinearReferenceModel model = new M06LinearReferenceModel();
    model.apply(legacy(101, "BUY", 95, 2, "GTC"));
    model.apply(legacy(102, "SELL", 105, 3, "GTC"));
    model.apply(legacy(103, "BUY", 96, 4, "GTC"));
    model.apply(legacy(104, "SELL", 104, 5, "GTC"));
    model.apply(change(5, "OPEN", "HALTED"));

    M06SemanticOutcome outcome = model.apply(massCancel(6, "HALTED"));
    assertInstanceOf(M06SemanticEvent.MassCancelStarted.class, outcome.events().getFirst());
    assertInstanceOf(M06SemanticEvent.MassCancelCompleted.class, outcome.events().getLast());
    List<M06SemanticEvent.MassOrderCanceled> canceled =
        outcome.events().stream()
            .filter(M06SemanticEvent.MassOrderCanceled.class::isInstance)
            .map(M06SemanticEvent.MassOrderCanceled.class::cast)
            .toList();
    assertEquals(List.of(bi(101), bi(102), bi(103), bi(104)), orderIds(canceled));
    assertEquals(List.of(bi(1), bi(2), bi(3), bi(4)), sequences(canceled));
    assertEquals(List.of(bi(2), bi(3), bi(4), bi(5)), quantities(canceled));
    assertEquals(M06SemanticBook.empty(), outcome.bookAfter());
    assertEquals("HALTED", outcome.stateAfter().marketMode());
    M06SemanticMarketState.MassCancelFence fence =
        outcome.stateAfter().lastMassCancelFence().orElseThrow();
    assertEquals(bi(6), fence.applicationSequence());
    assertEquals(bi(4), fence.canceledOrderCount());
    assertEquals(Optional.of(bi(1)), fence.firstCanceledSequence());
    assertEquals(Optional.of(bi(4)), fence.lastCanceledSequence());

    assertCancelRejected("MARKET_NOT_CANCELABLE", model.apply(cancel(101)));
    model.apply(change(8, "HALTED", "CANCEL_ONLY"));
    assertCancelRejected("ORDER_ALREADY_CANCELED", model.apply(cancel(101)));
    assertPlaceRejected("DUPLICATE_ORDER_ID", model.apply(legacy(101, "BUY", 90, 1, "GTC")));
  }

  @Test
  void emptyHaltedMassCancelIsACompletedBoundaryWithEmptySequenceBounds() {
    M06LinearReferenceModel model = new M06LinearReferenceModel();
    model.apply(change(1, "OPEN", "HALTED"));

    M06SemanticOutcome outcome = model.apply(massCancel(2, "HALTED"));

    assertEquals(2, outcome.events().size());
    assertEquals(
        BigInteger.ZERO,
        assertInstanceOf(M06SemanticEvent.MassCancelStarted.class, outcome.events().getFirst())
            .restingOrderCount());
    assertEquals(
        BigInteger.ZERO,
        assertInstanceOf(M06SemanticEvent.MassCancelCompleted.class, outcome.events().getLast())
            .canceledOrderCount());
    M06SemanticMarketState.MassCancelFence fence =
        outcome.stateAfter().lastMassCancelFence().orElseThrow();
    assertEquals(Optional.empty(), fence.firstCanceledSequence());
    assertEquals(Optional.empty(), fence.lastCanceledSequence());
  }

  @Test
  void massCancelKeepsHistoricalAdmissionAndCurrentExecutionRuleAttribution() {
    M06LinearReferenceModel model = new M06LinearReferenceModel();
    M06RuleSetIdentity bootstrap = M06MarketRuleSetArtifact.bootstrap().identity();
    M06MarketRuleSetArtifact v1 = artifact(1, 90, 110);

    model.apply(legacy(201, "BUY", 99, 1, "GTC"));
    model.apply(new M06ReferenceCommand.PrepareRuleSet(bootstrap, v1));
    model.apply(new M06ReferenceCommand.ActivateRuleSet(bi(3), bootstrap, v1.identity()));
    model.apply(governed(v1.identity(), 202, "SELL", 105, 1, "GTC"));
    model.apply(change(5, "OPEN", "HALTED"));
    M06SemanticOutcome outcome = model.apply(massCancel(6, "HALTED"));

    List<M06SemanticEvent.MassOrderCanceled> canceled =
        outcome.events().stream()
            .filter(M06SemanticEvent.MassOrderCanceled.class::isInstance)
            .map(M06SemanticEvent.MassOrderCanceled.class::cast)
            .toList();
    assertEquals(bootstrap, canceled.getFirst().admissionRuleSet());
    assertEquals(v1.identity(), canceled.getFirst().executionRuleSet());
    assertEquals(v1.identity(), canceled.getLast().admissionRuleSet());
    assertEquals(v1.identity(), canceled.getLast().executionRuleSet());
    assertEquals(v1.identity(), outcome.activeRuleSet());
    assertEquals(bi(1), outcome.controlRevision());
  }

  @Test
  void inheritedIocFokAndPostOnlySemanticsRemainAvailableWhileOpen() {
    M06LinearReferenceModel model = new M06LinearReferenceModel();
    model.apply(legacy(301, "SELL", 100, 2, "GTC"));

    M06SemanticOutcome ioc = model.apply(legacy(302, "BUY", 100, 3, "IOC"));
    assertEquals(3, ioc.events().size());
    assertInstanceOf(M06SemanticEvent.Accepted.class, ioc.events().get(0));
    assertInstanceOf(M06SemanticEvent.Trade.class, ioc.events().get(1));
    assertInstanceOf(M06SemanticEvent.RemainderCanceled.class, ioc.events().get(2));
    assertPlaceRejected("FOK_NOT_FILLABLE", model.apply(legacy(303, "BUY", 100, 1, "FOK")));

    M06SemanticOutcome postOnly = model.apply(legacy(304, "BUY", 99, 1, "POST_ONLY"));
    assertInstanceOf(M06SemanticEvent.Accepted.class, postOnly.events().getFirst());
    assertInstanceOf(M06SemanticEvent.Rested.class, postOnly.events().getLast());
    assertTrue(postOnly.bookAfter().bids().size() == 1);
  }

  private static void assertModeRejected(String code, M06SemanticOutcome outcome) {
    assertEquals(
        code,
        assertInstanceOf(M06SemanticEvent.ModeChangeRejected.class, outcome.events().getFirst())
            .code());
  }

  private static void assertMassRejected(
      String code, M06SemanticOutcome outcome, String observedMode) {
    M06SemanticEvent.MassCancelRejected rejected =
        assertInstanceOf(M06SemanticEvent.MassCancelRejected.class, outcome.events().getFirst());
    assertEquals(code, rejected.code());
    assertEquals(observedMode, rejected.observedMode());
  }

  private static void assertPlaceRejected(String code, M06SemanticOutcome outcome) {
    assertEquals(
        code,
        assertInstanceOf(M06SemanticEvent.PlaceRejected.class, outcome.events().getFirst()).code());
  }

  private static void assertCancelRejected(String code, M06SemanticOutcome outcome) {
    assertEquals(
        code,
        assertInstanceOf(M06SemanticEvent.CancelRejected.class, outcome.events().getFirst())
            .code());
  }

  private static List<BigInteger> orderIds(List<M06SemanticEvent.MassOrderCanceled> events) {
    return events.stream().map(M06SemanticEvent.MassOrderCanceled::orderId).toList();
  }

  private static List<BigInteger> sequences(List<M06SemanticEvent.MassOrderCanceled> events) {
    return events.stream().map(M06SemanticEvent.MassOrderCanceled::acceptanceSequence).toList();
  }

  private static List<BigInteger> quantities(List<M06SemanticEvent.MassOrderCanceled> events) {
    return events.stream().map(M06SemanticEvent.MassOrderCanceled::canceledQuantityLots).toList();
  }

  private static M06ReferenceCommand.Place legacy(
      long orderId, String side, long price, long quantity, String policy) {
    return M06ReferenceCommand.Place.legacy(
        "BTC-USDT", bi(orderId), side, bi(price), bi(quantity), policy);
  }

  private static M06ReferenceCommand.Place governed(
      M06RuleSetIdentity expected,
      long orderId,
      String side,
      long price,
      long quantity,
      String policy) {
    return M06ReferenceCommand.Place.governed(
        expected, "BTC-USDT", bi(orderId), side, bi(price), bi(quantity), policy);
  }

  private static M06ReferenceCommand.Cancel cancel(long orderId) {
    return new M06ReferenceCommand.Cancel("BTC-USDT", bi(orderId));
  }

  private static M06ReferenceCommand.ChangeMarketMode change(
      long expectedSequence, String expectedMode, String targetMode) {
    return new M06ReferenceCommand.ChangeMarketMode(
        bi(expectedSequence), expectedMode, targetMode, OPERATOR);
  }

  private static M06ReferenceCommand.MassCancel massCancel(
      long expectedSequence, String expectedMode) {
    return new M06ReferenceCommand.MassCancel(bi(expectedSequence), expectedMode, OPERATOR);
  }

  private static M06MarketRuleSetArtifact artifact(long version, long lower, long upper) {
    return M06MarketRuleSetArtifact.canonical(bi(version), bi(lower), bi(upper));
  }

  private static BigInteger bi(long value) {
    return BigInteger.valueOf(value);
  }
}
