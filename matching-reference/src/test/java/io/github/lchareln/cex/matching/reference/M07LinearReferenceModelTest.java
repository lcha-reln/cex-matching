package io.github.lchareln.cex.matching.reference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;

final class M07LinearReferenceModelTest {
  private static final String INSTRUMENT = M06MarketRuleSetArtifact.INSTRUMENT;
  private static final M06RuleSetIdentity BOOTSTRAP =
      M06MarketRuleSetArtifact.bootstrap().identity();

  @Test
  void validationOrderAndLegacyMappingAreExplicit() {
    M07LinearReferenceModel model = new M07LinearReferenceModel();
    M07SemanticOutcome legacy = model.apply(legacy(1, "BUY", 99, 1, "GTC"));
    M07SemanticEvent.Accepted accepted =
        assertInstanceOf(M07SemanticEvent.Accepted.class, legacy.events().getFirst());
    assertEquals(BigInteger.ZERO, accepted.participantGroupId());
    assertEquals("NONE", accepted.stpPolicy());
    assertEquals("NONE", legacy.bookAfter().bids().getFirst().orders().getFirst().stpPolicy());

    assertRejected(
        model.apply(stp(1, "SELL", 99, 1, "BAD", -1, "bad")), "INVALID_EXECUTION_POLICY");
    assertRejected(model.apply(stp(1, "SELL", 99, 1, "GTC", -1, "bad")), "INVALID_STP_GROUP_ID");
    assertRejected(model.apply(stp(1, "SELL", 99, 1, "GTC", 7, "bad")), "INVALID_STP_POLICY");
    assertRejected(
        model.apply(stp(1, "SELL", 99, 1, "GTC", 0, "CANCEL_TAKER")), "INVALID_STP_INSTRUCTION");
  }

  @Test
  void cancelTakerLeavesMakerAndTerminatesIocWithoutRemainderEvent() {
    M07LinearReferenceModel model = new M07LinearReferenceModel();
    model.apply(stp(10, "SELL", 100, 2, "GTC", 7, "CANCEL_MAKER"));

    M07SemanticOutcome outcome = model.apply(stp(20, "BUY", 100, 3, "IOC", 7, "CANCEL_TAKER"));

    assertEquals(2, outcome.events().size());
    M07SemanticEvent.SelfTradePrevented prevented =
        assertInstanceOf(M07SemanticEvent.SelfTradePrevented.class, outcome.events().getLast());
    assertEquals(BigInteger.ZERO, prevented.makerCanceledQuantityLots());
    assertEquals(BigInteger.valueOf(3), prevented.takerCanceledQuantityLots());
    assertEquals(
        BigInteger.TEN, outcome.bookAfter().asks().getFirst().orders().getFirst().orderId());
  }

  @Test
  void cancelMakerScansPriceTimeAcrossLevelsAndIgnoresMakerPolicy() {
    M07LinearReferenceModel model = new M07LinearReferenceModel();
    model.apply(stp(10, "SELL", 100, 1, "GTC", 8, "CANCEL_TAKER"));
    model.apply(stp(11, "SELL", 100, 2, "GTC", 7, "CANCEL_TAKER"));
    model.apply(stp(12, "SELL", 101, 2, "GTC", 9, "CANCEL_BOTH"));

    M07SemanticOutcome outcome = model.apply(stp(20, "BUY", 101, 3, "GTC", 7, "CANCEL_MAKER"));

    assertEquals(4, outcome.events().size());
    assertEquals(
        BigInteger.TEN,
        assertInstanceOf(M07SemanticEvent.Trade.class, outcome.events().get(1)).makerOrderId());
    M07SemanticEvent.SelfTradePrevented prevented =
        assertInstanceOf(M07SemanticEvent.SelfTradePrevented.class, outcome.events().get(2));
    assertEquals(BigInteger.valueOf(11), prevented.makerOrderId());
    assertEquals(BigInteger.valueOf(2), prevented.makerCanceledQuantityLots());
    assertEquals(
        BigInteger.valueOf(12),
        assertInstanceOf(M07SemanticEvent.Trade.class, outcome.events().get(3)).makerOrderId());
    assertTrue(outcome.bookAfter().asks().isEmpty());
  }

  @Test
  void cancelBothPreservesEarlierExternalTradeThenCancelsBothRemainders() {
    M07LinearReferenceModel model = new M07LinearReferenceModel();
    model.apply(stp(10, "SELL", 99, 1, "GTC", 8, "CANCEL_TAKER"));
    model.apply(stp(11, "SELL", 100, 2, "GTC", 7, "CANCEL_MAKER"));

    M07SemanticOutcome outcome = model.apply(stp(20, "BUY", 100, 4, "GTC", 7, "CANCEL_BOTH"));

    assertInstanceOf(M07SemanticEvent.Trade.class, outcome.events().get(1));
    M07SemanticEvent.SelfTradePrevented prevented =
        assertInstanceOf(M07SemanticEvent.SelfTradePrevented.class, outcome.events().getLast());
    assertEquals(BigInteger.valueOf(2), prevented.makerCanceledQuantityLots());
    assertEquals(BigInteger.valueOf(3), prevented.takerCanceledQuantityLots());
    assertTrue(outcome.bookAfter().asks().isEmpty());
    assertTrue(outcome.bookAfter().bids().isEmpty());
  }

  @Test
  void fokPreflightIsStpAwareAndFailureHasNoMakerSideEffect() {
    M07LinearReferenceModel cancelTaker = new M07LinearReferenceModel();
    cancelTaker.apply(stp(10, "SELL", 99, 1, "GTC", 8, "CANCEL_TAKER"));
    cancelTaker.apply(stp(11, "SELL", 100, 2, "GTC", 7, "CANCEL_MAKER"));
    cancelTaker.apply(stp(12, "SELL", 101, 2, "GTC", 9, "CANCEL_BOTH"));
    M07SemanticBook before = cancelTaker.snapshot().book();
    M07SemanticOutcome rejected =
        cancelTaker.apply(stp(20, "BUY", 101, 3, "FOK", 7, "CANCEL_TAKER"));
    assertPlaceRejected(rejected, "FOK_NOT_FILLABLE");
    assertEquals(before, cancelTaker.snapshot().book());

    M07LinearReferenceModel cancelMaker = new M07LinearReferenceModel();
    cancelMaker.apply(stp(10, "SELL", 100, 2, "GTC", 7, "CANCEL_TAKER"));
    cancelMaker.apply(stp(11, "SELL", 101, 2, "GTC", 8, "CANCEL_TAKER"));
    M07SemanticOutcome accepted =
        cancelMaker.apply(stp(20, "BUY", 101, 2, "FOK", 7, "CANCEL_MAKER"));
    assertInstanceOf(M07SemanticEvent.SelfTradePrevented.class, accepted.events().get(1));
    assertInstanceOf(M07SemanticEvent.Trade.class, accepted.events().get(2));
  }

  @Test
  void postOnlyUsesRawBookAndModeGateRunsBeforeStpPreflight() {
    M07LinearReferenceModel postOnly = new M07LinearReferenceModel();
    postOnly.apply(stp(10, "SELL", 100, 2, "GTC", 7, "CANCEL_TAKER"));
    M07SemanticBook before = postOnly.snapshot().book();
    assertPlaceRejected(
        postOnly.apply(stp(20, "BUY", 100, 2, "POST_ONLY", 7, "CANCEL_MAKER")),
        "POST_ONLY_WOULD_TAKE");
    assertEquals(before, postOnly.snapshot().book());

    M07LinearReferenceModel cancelOnly = new M07LinearReferenceModel();
    cancelOnly.apply(stp(10, "SELL", 100, 2, "GTC", 7, "CANCEL_TAKER"));
    cancelOnly.apply(
        new M07ReferenceCommand.ChangeMarketMode(
            BigInteger.valueOf(2), "OPEN", "CANCEL_ONLY", "ops"));
    assertPlaceRejected(
        cancelOnly.apply(stp(20, "BUY", 100, 2, "FOK", 7, "CANCEL_MAKER")), "MARKET_NOT_OPEN");
    assertEquals(
        BigInteger.TEN,
        cancelOnly.snapshot().book().asks().getFirst().orders().getFirst().orderId());
  }

  @Test
  void stpEventCarriesAdmissionAndExecutionRuleVersions() {
    M07LinearReferenceModel model = new M07LinearReferenceModel();
    model.apply(stp(10, "SELL", 100, 2, "GTC", 7, "CANCEL_TAKER"));
    M06MarketRuleSetArtifact v1 = artifact(1, 90, 110);
    model.apply(new M07ReferenceCommand.PrepareRuleSet(BOOTSTRAP, v1));
    model.apply(
        new M07ReferenceCommand.ActivateRuleSet(BigInteger.valueOf(3), BOOTSTRAP, v1.identity()));

    M07SemanticOutcome outcome =
        model.apply(
            M07ReferenceCommand.Place.governedStp(
                v1.identity(),
                INSTRUMENT,
                BigInteger.valueOf(20),
                "BUY",
                BigInteger.valueOf(100),
                BigInteger.ONE,
                "GTC",
                BigInteger.valueOf(7),
                "CANCEL_TAKER"));
    M07SemanticEvent.SelfTradePrevented prevented =
        assertInstanceOf(M07SemanticEvent.SelfTradePrevented.class, outcome.events().getLast());
    assertEquals(BOOTSTRAP, prevented.makerAdmissionRuleSet());
    assertEquals(v1.identity(), prevented.takerAdmissionRuleSet());
    assertEquals(v1.identity(), prevented.executionRuleSet());
  }

  private static M07ReferenceCommand.Place legacy(
      long id, String side, long price, long quantity, String executionPolicy) {
    return M07ReferenceCommand.Place.legacy(
        INSTRUMENT,
        BigInteger.valueOf(id),
        side,
        BigInteger.valueOf(price),
        BigInteger.valueOf(quantity),
        executionPolicy);
  }

  private static M07ReferenceCommand.Place stp(
      long id,
      String side,
      long price,
      long quantity,
      String executionPolicy,
      long groupId,
      String stpPolicy) {
    return M07ReferenceCommand.Place.stp(
        INSTRUMENT,
        BigInteger.valueOf(id),
        side,
        BigInteger.valueOf(price),
        BigInteger.valueOf(quantity),
        executionPolicy,
        BigInteger.valueOf(groupId),
        stpPolicy);
  }

  private static M06MarketRuleSetArtifact artifact(long version, long lower, long upper) {
    return M06MarketRuleSetArtifact.canonical(
        BigInteger.valueOf(version), BigInteger.valueOf(lower), BigInteger.valueOf(upper));
  }

  private static void assertRejected(M07SemanticOutcome outcome, String code) {
    assertEquals(
        code,
        assertInstanceOf(M07SemanticEvent.Rejected.class, outcome.events().getFirst()).code());
  }

  private static void assertPlaceRejected(M07SemanticOutcome outcome, String code) {
    assertEquals(
        code,
        assertInstanceOf(M07SemanticEvent.PlaceRejected.class, outcome.events().getFirst()).code());
  }
}
