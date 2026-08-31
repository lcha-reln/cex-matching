package io.github.lchareln.cex.matching.reference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class M05LinearReferenceModelTest {
  private static final String BOOTSTRAP_HASH =
      "sha256:d9928c52e99b8611cb95fb0d2792b6901cf9336825e19a7f593393b0d2b99c04";
  private static final String WIDE_V1_HASH =
      "sha256:dbb75b3983480a8ece058736766411f80eb5c62e10eb24de72b74853d5377f91";
  private static final String NARROW_V1_HASH =
      "sha256:1e5934c44343fe92741732bc5af56c019fc0e785815ff8848ed810ad52247372";
  private static final String WIDE_V2_HASH =
      "sha256:d7d0a8e3a2d1882012f8ba6d7318ecf02e378f4766c26badff272a97e1e21f7d";

  @Test
  void canonicalArtifactBytesAndAllFrozenHashVectorsAreExact() {
    M05MarketRuleSetArtifact bootstrap = M05MarketRuleSetArtifact.bootstrap();
    M05MarketRuleSetArtifact wideV1 = artifact(1, 90, 110);
    M05MarketRuleSetArtifact narrowV1 = artifact(1, 95, 105);
    M05MarketRuleSetArtifact wideV2 = artifact(2, 80, 120);

    assertEquals(BOOTSTRAP_HASH, bootstrap.contentHash());
    assertEquals(WIDE_V1_HASH, wideV1.contentHash());
    assertEquals(NARROW_V1_HASH, narrowV1.contentHash());
    assertEquals(WIDE_V2_HASH, wideV2.contentHash());
    assertEquals(
        """
        M05RS1
        schemaVersion=matching.market-rule-set.v1
        instrumentId=BTC-USDT
        version=1
        lowerInclusive=90
        upperInclusive=110
        """,
        new String(wideV1.canonicalBytes(), StandardCharsets.UTF_8));
  }

  @Test
  void constructionFailuresConsumeNothingButMalformedClaimIsARejectedCommandBoundary() {
    M05LinearReferenceModel model = new M05LinearReferenceModel();
    M05RuleSetIdentity bootstrap = identity(0, BOOTSTRAP_HASH);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new M05MarketRuleSetArtifact(
                "wrong-schema", "BTC-USDT", bi(1), bi(90), bi(110), WIDE_V1_HASH));
    assertThrows(IllegalArgumentException.class, () -> new M05RuleSetIdentity(bi(1), "not-a-hash"));
    assertEquals(bi(1), model.snapshot().nextApplicationSequence());

    M05MarketRuleSetArtifact malformedClaim =
        new M05MarketRuleSetArtifact(
            M05MarketRuleSetArtifact.SCHEMA_VERSION,
            M05MarketRuleSetArtifact.INSTRUMENT,
            bi(1),
            bi(90),
            bi(110),
            "not-a-hash");
    M05SemanticOutcome rejected = model.apply(prepare(bootstrap, malformedClaim));
    assertEquals(bi(1), rejected.applicationSequence());
    assertEquals(
        List.of(new M05SemanticEvent.PrepareRuleSetRejected("MALFORMED_CONTENT_HASH")),
        rejected.events());
    assertEquals(bi(2), model.snapshot().nextApplicationSequence());
  }

  @Test
  void bootstrapLegacyPlaceAndCancelCarryVersionZeroAttributionAndDistinctSequences() {
    M05LinearReferenceModel model = new M05LinearReferenceModel();
    M05RuleSetIdentity bootstrap = identity(0, BOOTSTRAP_HASH);

    M05SemanticMarketState initial = model.snapshot();
    assertEquals(bi(1), initial.nextApplicationSequence());
    assertEquals(bi(1), initial.nextAcceptanceSequence());
    assertEquals(BigInteger.ZERO, initial.controlRevision());
    assertEquals(bootstrap, initial.activeIdentity());
    assertEquals(Optional.empty(), initial.preparedRuleSet());
    assertEquals(Optional.empty(), initial.lastActivationFence());

    M05SemanticOutcome placed = model.apply(legacy(1, "BUY", 100, 2, "GTC"));
    assertEquals(bi(1), placed.applicationSequence());
    assertEquals(
        List.of(
            new M05SemanticEvent.Accepted(
                bi(1), bi(1), "BUY", bi(100), bi(2), "GTC", bootstrap, bootstrap),
            new M05SemanticEvent.Rested(bi(1), bi(1), "BUY", bi(100), bi(2), bootstrap, bootstrap)),
        placed.events());
    assertEquals(bi(2), placed.stateAfter().nextApplicationSequence());
    assertEquals(bi(2), placed.stateAfter().nextAcceptanceSequence());

    M05SemanticOutcome canceled = model.apply(cancel(1));
    assertEquals(bi(2), canceled.applicationSequence());
    assertEquals(
        List.of(
            new M05SemanticEvent.Canceled(
                bi(1), bi(1), "BUY", bi(100), bi(2), bootstrap, bootstrap)),
        canceled.events());
    assertEquals(bi(3), canceled.stateAfter().nextApplicationSequence());
    assertEquals(bi(2), canceled.stateAfter().nextAcceptanceSequence());
    assertEquals(M05SemanticBook.empty(), canceled.bookAfter());
  }

  @Test
  void prepareRejectsTamperingThenSupportsReplayConflictAndMonotonicSupersession() {
    M05LinearReferenceModel model = new M05LinearReferenceModel();
    M05RuleSetIdentity bootstrap = identity(0, BOOTSTRAP_HASH);
    M05MarketRuleSetArtifact wideV1 = artifact(1, 90, 110);
    M05MarketRuleSetArtifact narrowV1 = artifact(1, 95, 105);
    M05MarketRuleSetArtifact wideV2 = artifact(2, 80, 120);
    M05MarketRuleSetArtifact tampered =
        new M05MarketRuleSetArtifact(
            M05MarketRuleSetArtifact.SCHEMA_VERSION,
            M05MarketRuleSetArtifact.INSTRUMENT,
            bi(1),
            bi(90),
            bi(110),
            "sha256:" + "0".repeat(64));

    assertEquals(
        List.of(new M05SemanticEvent.PrepareRuleSetRejected("CONTENT_HASH_MISMATCH")),
        model.apply(prepare(bootstrap, tampered)).events());
    assertEquals(Optional.empty(), model.snapshot().preparedRuleSet());

    assertEquals(
        List.of(
            new M05SemanticEvent.RuleSetPrepared(
                wideV1.identity(), M05SemanticEvent.PrepareStatus.PREPARED, Optional.empty())),
        model.apply(prepare(bootstrap, wideV1)).events());
    assertEquals(
        List.of(
            new M05SemanticEvent.RuleSetPrepared(
                wideV1.identity(),
                M05SemanticEvent.PrepareStatus.ALREADY_PREPARED,
                Optional.empty())),
        model.apply(prepare(bootstrap, wideV1)).events());
    assertEquals(
        List.of(new M05SemanticEvent.PrepareRuleSetRejected("SAME_VERSION_DIFFERENT_CONTENT")),
        model.apply(prepare(bootstrap, narrowV1)).events());

    assertEquals(
        List.of(
            new M05SemanticEvent.RuleSetPrepared(
                wideV2.identity(),
                M05SemanticEvent.PrepareStatus.SUPERSEDED,
                Optional.of(wideV1.identity()))),
        model.apply(prepare(bootstrap, wideV2)).events());
    assertEquals(
        List.of(new M05SemanticEvent.PrepareRuleSetRejected("VERSION_NOT_INCREASING")),
        model.apply(prepare(bootstrap, wideV1)).events());

    M05SemanticMarketState state = model.snapshot();
    assertEquals(bootstrap, state.activeIdentity());
    assertEquals(Optional.of(wideV2), state.preparedRuleSet());
    assertEquals(BigInteger.ZERO, state.controlRevision());
    assertEquals(bi(7), state.nextApplicationSequence());
  }

  @Test
  void failedActivationsConsumeBoundariesButOnlyExactFenceSwapsTheWholeArtifact() {
    M05LinearReferenceModel model = new M05LinearReferenceModel();
    M05RuleSetIdentity bootstrap = identity(0, BOOTSTRAP_HASH);
    M05MarketRuleSetArtifact wideV1 = artifact(1, 90, 110);

    assertEquals(
        List.of(new M05SemanticEvent.ActivateRuleSetRejected("NO_PREPARED_RULE_SET")),
        model.apply(activate(1, bootstrap, wideV1.identity())).events());
    model.apply(prepare(bootstrap, wideV1));

    M05RuleSetIdentity wrongTarget = identity(1, "sha256:" + "0".repeat(64));
    assertEquals(
        List.of(new M05SemanticEvent.ActivateRuleSetRejected("TARGET_RULE_SET_MISMATCH")),
        model.apply(activate(3, bootstrap, wrongTarget)).events());
    assertEquals(
        List.of(new M05SemanticEvent.ActivateRuleSetRejected("APPLICATION_SEQUENCE_MISMATCH")),
        model.apply(activate(3, bootstrap, wideV1.identity())).events());

    M05SemanticMarketState beforeSuccess = model.snapshot();
    assertEquals(bootstrap, beforeSuccess.activeIdentity());
    assertEquals(Optional.of(wideV1), beforeSuccess.preparedRuleSet());
    assertEquals(bi(5), beforeSuccess.nextApplicationSequence());

    M05SemanticMarketState.ActivationFence fence =
        new M05SemanticMarketState.ActivationFence(bi(5), bi(1), bi(1));
    M05SemanticOutcome activated = model.apply(activate(5, bootstrap, wideV1.identity()));
    assertEquals(
        List.of(new M05SemanticEvent.RuleSetActivated(bootstrap, wideV1.identity(), fence)),
        activated.events());
    assertEquals(wideV1.identity(), activated.activeRuleSet());
    assertEquals(bi(1), activated.controlRevision());
    assertEquals(Optional.empty(), activated.stateAfter().preparedRuleSet());
    assertEquals(Optional.of(fence), activated.stateAfter().lastActivationFence());
    assertEquals(bi(6), activated.stateAfter().nextApplicationSequence());
  }

  @Test
  void governedPlacePreservesFieldDuplicateFenceBandAndPolicyPriority() {
    M05LinearReferenceModel model = activatedModel(artifact(1, 95, 105));
    M05RuleSetIdentity bootstrap = identity(0, BOOTSTRAP_HASH);
    M05RuleSetIdentity active = identity(1, NARROW_V1_HASH);

    M05SemanticOutcome owner = model.apply(governed(active, 40, "BUY", 100, 1, "GTC"));
    assertEquals(bi(1), accepted(owner).acceptanceSequence());

    assertPlaceRejection(
        "DUPLICATE_ORDER_ID", model.apply(governed(bootstrap, 40, "BUY", 1, 1, "GTC")));
    assertPlaceRejection(
        "RULE_SET_MISMATCH", model.apply(governed(bootstrap, 41, "BUY", 1, 1, "GTC")));
    assertPlaceRejection(
        "PRICE_OUTSIDE_ACTIVE_BAND", model.apply(governed(active, 41, "BUY", 1, 1, "FOK")));
    assertPlaceRejection(
        "FOK_NOT_FILLABLE", model.apply(governed(active, 41, "BUY", 100, 1, "FOK")));

    M05SemanticOutcome invalid =
        model.apply(
            M05ReferenceCommand.Place.governed(
                bootstrap,
                "ETH-USDT",
                bi(41),
                "INVALID",
                BigInteger.ZERO,
                BigInteger.ZERO,
                "UNKNOWN"));
    assertEquals(
        List.of(new M05SemanticEvent.Rejected("UNKNOWN_INSTRUMENT", "instrumentId")),
        invalid.events());

    M05SemanticOutcome accepted = model.apply(governed(active, 41, "BUY", 100, 1, "GTC"));
    assertEquals(bi(2), accepted(accepted).acceptanceSequence());
    assertEquals(bi(3), accepted.stateAfter().nextAcceptanceSequence());
    assertEquals(bi(10), accepted.stateAfter().nextApplicationSequence());
  }

  @Test
  void bothSidesTouchInclusiveBoundsWhileOneTickOutsideIsRejected() {
    M05MarketRuleSetArtifact narrow = artifact(1, 95, 105);
    M05LinearReferenceModel model = activatedModel(narrow);
    M05RuleSetIdentity active = narrow.identity();

    assertEquals(
        bi(1),
        accepted(model.apply(governed(active, 1, "BUY", 95, 1, "GTC"))).acceptanceSequence());
    assertEquals(
        bi(2),
        accepted(model.apply(governed(active, 2, "SELL", 105, 1, "GTC"))).acceptanceSequence());
    assertPlaceRejection(
        "PRICE_OUTSIDE_ACTIVE_BAND", model.apply(governed(active, 3, "BUY", 94, 1, "GTC")));
    assertPlaceRejection(
        "PRICE_OUTSIDE_ACTIVE_BAND", model.apply(governed(active, 4, "SELL", 106, 1, "GTC")));
    assertPlaceRejection("PRICE_OUTSIDE_ACTIVE_BAND", model.apply(legacy(5, "BUY", 94, 1, "GTC")));
  }

  @Test
  void preparedRuleNeverGovernsAdmissionBeforeItsExactActivationBoundary() {
    M05LinearReferenceModel model = new M05LinearReferenceModel();
    M05RuleSetIdentity bootstrap = identity(0, BOOTSTRAP_HASH);
    M05MarketRuleSetArtifact narrow = artifact(1, 95, 105);

    model.apply(prepare(bootstrap, narrow));
    M05SemanticOutcome beforeActivation = model.apply(legacy(70, "SELL", 90, 1, "GTC"));
    assertEquals(bootstrap, accepted(beforeActivation).admissionRuleSet());
    assertEquals(bootstrap, beforeActivation.activeRuleSet());

    M05SemanticOutcome activated = model.apply(activate(3, bootstrap, narrow.identity()));
    assertEquals(narrow.identity(), activated.activeRuleSet());
    assertEquals(
        bi(2),
        activated.stateAfter().lastActivationFence().orElseThrow().firstAcceptanceSequence());
    assertEquals(
        bootstrap,
        activated.stateAfter().book().asks().getFirst().orders().getFirst().admissionRuleSet());
  }

  @Test
  void activationGrandfathersOldMakerAndTradeExposesAllThreeRuleIdentities() {
    M05LinearReferenceModel model = new M05LinearReferenceModel();
    M05RuleSetIdentity bootstrap = identity(0, BOOTSTRAP_HASH);
    M05MarketRuleSetArtifact narrow = artifact(1, 95, 105);
    M05RuleSetIdentity active = narrow.identity();

    model.apply(legacy(60, "SELL", 90, 2, "GTC"));
    model.apply(prepare(bootstrap, narrow));
    M05SemanticOutcome activation = model.apply(activate(3, bootstrap, active));
    assertEquals(
        bi(2),
        activation.stateAfter().lastActivationFence().orElseThrow().firstAcceptanceSequence());

    M05SemanticOutcome trade = model.apply(governed(active, 61, "BUY", 100, 1, "FOK"));
    assertEquals(
        List.of(
            new M05SemanticEvent.Accepted(
                bi(2), bi(61), "BUY", bi(100), bi(1), "FOK", active, active),
            new M05SemanticEvent.Trade(
                bi(1), bi(60), bi(2), bi(61), bi(90), bi(1), bootstrap, active, active)),
        trade.events());
    assertEquals(
        new M05SemanticBook(
            List.of(),
            List.of(
                new M05SemanticBook.PriceLevel(
                    "SELL",
                    bi(90),
                    List.of(new M05SemanticBook.RestingOrder(bi(1), bi(60), bi(1), bootstrap))))),
        trade.bookAfter());

    assertPlaceRejection(
        "POST_ONLY_WOULD_TAKE", model.apply(governed(active, 62, "BUY", 100, 1, "POST_ONLY")));
    assertPlaceRejection(
        "PRICE_OUTSIDE_ACTIVE_BAND", model.apply(governed(active, 62, "SELL", 90, 1, "GTC")));
    assertEquals(
        bi(3), model.snapshot().nextAcceptanceSequence(), "rejections must not claim order 62");
  }

  private static M05LinearReferenceModel activatedModel(M05MarketRuleSetArtifact artifact) {
    M05LinearReferenceModel model = new M05LinearReferenceModel();
    M05RuleSetIdentity bootstrap = identity(0, BOOTSTRAP_HASH);
    model.apply(prepare(bootstrap, artifact));
    model.apply(activate(2, bootstrap, artifact.identity()));
    return model;
  }

  private static M05ReferenceCommand.Place legacy(
      long orderId, String side, long price, long quantity, String policy) {
    return M05ReferenceCommand.Place.legacy(
        "BTC-USDT", bi(orderId), side, bi(price), bi(quantity), policy);
  }

  private static M05ReferenceCommand.Place governed(
      M05RuleSetIdentity expected,
      long orderId,
      String side,
      long price,
      long quantity,
      String policy) {
    return M05ReferenceCommand.Place.governed(
        expected, "BTC-USDT", bi(orderId), side, bi(price), bi(quantity), policy);
  }

  private static M05ReferenceCommand.Cancel cancel(long orderId) {
    return new M05ReferenceCommand.Cancel("BTC-USDT", bi(orderId));
  }

  private static M05ReferenceCommand.PrepareRuleSet prepare(
      M05RuleSetIdentity expectedActive, M05MarketRuleSetArtifact artifact) {
    return new M05ReferenceCommand.PrepareRuleSet(expectedActive, artifact);
  }

  private static M05ReferenceCommand.ActivateRuleSet activate(
      long expectedApplicationSequence,
      M05RuleSetIdentity expectedActive,
      M05RuleSetIdentity target) {
    return new M05ReferenceCommand.ActivateRuleSet(
        bi(expectedApplicationSequence), expectedActive, target);
  }

  private static M05MarketRuleSetArtifact artifact(long version, long lower, long upper) {
    return M05MarketRuleSetArtifact.canonical(bi(version), bi(lower), bi(upper));
  }

  private static M05RuleSetIdentity identity(long version, String hash) {
    return new M05RuleSetIdentity(bi(version), hash);
  }

  private static M05SemanticEvent.Accepted accepted(M05SemanticOutcome outcome) {
    assertTrue(outcome.events().getFirst() instanceof M05SemanticEvent.Accepted);
    return (M05SemanticEvent.Accepted) outcome.events().getFirst();
  }

  private static void assertPlaceRejection(String code, M05SemanticOutcome outcome) {
    assertTrue(outcome.events().getFirst() instanceof M05SemanticEvent.PlaceRejected);
    assertEquals(code, ((M05SemanticEvent.PlaceRejected) outcome.events().getFirst()).code());
  }

  private static BigInteger bi(long value) {
    return BigInteger.valueOf(value);
  }
}
