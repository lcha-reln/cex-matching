package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.MarketRuleSetArtifact;
import io.github.lchareln.cex.matching.reference.M05MarketRuleSetArtifact;
import io.github.lchareln.cex.matching.reference.M05SemanticBook;
import io.github.lchareln.cex.matching.reference.M05SemanticEvent;
import io.github.lchareln.cex.matching.reference.M05SemanticMarketState;
import io.github.lchareln.cex.matching.reference.M05SemanticOutcome;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/** Executable facts for exact hashing and semantic boundaries outside the generated tick domain. */
final class M05BoundaryFacts {
  private static final BigInteger MAXIMUM = BigInteger.valueOf(Long.MAX_VALUE);
  private static final String ZERO_HASH =
      "sha256:0000000000000000000000000000000000000000000000000000000000000000";

  Result verify(Path root) {
    M05ScenarioCorpus.Corpus corpus = M05ScenarioCorpus.load(root);
    require(
        corpus.scenarios().size() == 12 && corpus.commandCount() == 54,
        "M05 frozen boundary corpus changed");
    verifyHashVectors();

    M05Command.Artifact bootstrap = M05RuleSetCanonical.BOOTSTRAP;
    M05Command.Artifact wide = artifact(1, 90, 110);
    M05Command.Artifact narrow = artifact(1, 95, 105);

    M05PropertyJudge.Observation hashObservation =
        pass(
            "boundary-hash",
            List.of(
                new M05Command.PrepareRuleSet(
                    bootstrap.identity(),
                    new M05Command.Artifact(
                        wide.schemaVersion(),
                        wide.instrumentId(),
                        wide.version(),
                        wide.lowerInclusive(),
                        wide.upperInclusive(),
                        ZERO_HASH)),
                new M05Command.PrepareRuleSet(bootstrap.identity(), wide),
                new M05Command.PrepareRuleSet(bootstrap.identity(), narrow)));
    boolean hashMismatch =
        rejected(hashObservation.trace().get(0).actual(), "CONTENT_HASH_MISMATCH")
            && retainedExceptApplication(
                initialState(), hashObservation.trace().get(0).actual().stateAfter());
    boolean sameVersionDifferentHash =
        rejected(hashObservation.trace().get(2).actual(), "SAME_VERSION_DIFFERENT_CONTENT")
            && hashObservation
                .trace()
                .get(2)
                .actual()
                .stateAfter()
                .preparedRuleSet()
                .orElseThrow()
                .identity()
                .equals(referenceArtifact(wide).identity());

    M05PropertyJudge.Observation inclusiveObservation =
        pass(
            "boundary-inclusive",
            List.of(
                new M05Command.PrepareRuleSet(bootstrap.identity(), wide),
                new M05Command.ActivateRuleSet(
                    BigInteger.valueOf(2), bootstrap.identity(), wide.identity()),
                governedPlace(2, "BUY", 90, "GTC", wide.identity()),
                governedPlace(3, "SELL", 110, "GTC", wide.identity())));
    boolean lowerInclusive = accepted(inclusiveObservation.trace().get(2).actual());
    boolean upperInclusive = accepted(inclusiveObservation.trace().get(3).actual());

    M05PropertyJudge.Observation maximumObservation =
        pass(
            "boundary-long-maximum",
            List.of(
                new M05Command.Place(
                    "LEGACY",
                    "BTC-USDT",
                    BigInteger.ONE,
                    "BUY",
                    MAXIMUM,
                    BigInteger.ONE,
                    "GTC",
                    null)));
    boolean longMaximumInclusive =
        accepted(maximumObservation.trace().getFirst().actual())
            && maximumObservation
                .trace()
                .getFirst()
                .actual()
                .stateAfter()
                .book()
                .bids()
                .getFirst()
                .priceTicks()
                .equals(MAXIMUM);

    M05PropertyJudge.Observation staleActivationObservation =
        pass(
            "boundary-stale-activation",
            List.of(
                new M05Command.PrepareRuleSet(bootstrap.identity(), wide),
                new M05Command.ActivateRuleSet(
                    BigInteger.ONE, bootstrap.identity(), wide.identity())));
    M05SemanticOutcome staleActivation = staleActivationObservation.trace().get(1).actual();
    boolean staleActivationFence =
        rejected(staleActivation, "APPLICATION_SEQUENCE_MISMATCH")
            && staleActivation.stateAfter().activeRuleSet().equals(referenceArtifact(bootstrap))
            && staleActivation
                .stateAfter()
                .preparedRuleSet()
                .orElseThrow()
                .equals(referenceArtifact(wide))
            && staleActivation.stateAfter().controlRevision().signum() == 0;

    M05PropertyJudge.Observation stalePlaceObservation =
        pass(
            "boundary-stale-place",
            List.of(
                new M05Command.PrepareRuleSet(bootstrap.identity(), wide),
                new M05Command.ActivateRuleSet(
                    BigInteger.valueOf(2), bootstrap.identity(), wide.identity()),
                governedPlace(4, "BUY", 100, "GTC", bootstrap.identity())));
    M05SemanticOutcome stalePlace = stalePlaceObservation.trace().get(2).actual();
    boolean stalePlaceFence =
        rejected(stalePlace, "RULE_SET_MISMATCH")
            && stalePlace.stateAfter().book().equals(M05SemanticBook.empty())
            && stalePlace.stateAfter().nextAcceptanceSequence().equals(BigInteger.ONE);

    M05PropertyJudge.Observation grandfatherObservation =
        pass(
            "boundary-grandfather",
            List.of(
                new M05Command.Place(
                    "LEGACY",
                    "BTC-USDT",
                    BigInteger.valueOf(60),
                    "SELL",
                    BigInteger.valueOf(90),
                    BigInteger.valueOf(2),
                    "GTC",
                    null),
                new M05Command.PrepareRuleSet(bootstrap.identity(), narrow),
                new M05Command.ActivateRuleSet(
                    BigInteger.valueOf(3), bootstrap.identity(), narrow.identity()),
                governedPlace(61, "BUY", 100, "FOK", narrow.identity())));
    M05SemanticOutcome afterActivation = grandfatherObservation.trace().get(2).actual();
    M05SemanticOutcome afterTrade = grandfatherObservation.trace().get(3).actual();
    boolean grandfatherExistingOrders =
        hasResting(afterActivation.stateAfter().book(), BigInteger.valueOf(60), 90, 2)
            && afterTrade.events().stream()
                .filter(M05SemanticEvent.Trade.class::isInstance)
                .map(M05SemanticEvent.Trade.class::cast)
                .anyMatch(
                    trade ->
                        trade.makerOrderId().equals(BigInteger.valueOf(60))
                            && trade.priceTicks().equals(BigInteger.valueOf(90))
                            && trade
                                .makerAdmissionRuleSet()
                                .equals(referenceArtifact(bootstrap).identity())
                            && trade
                                .takerAdmissionRuleSet()
                                .equals(referenceArtifact(narrow).identity()));

    require(hashMismatch, "M05 hash mismatch did not fail closed");
    require(sameVersionDifferentHash, "M05 same-version conflict did not fail closed");
    require(lowerInclusive && upperInclusive, "M05 inclusive tick boundary changed");
    require(longMaximumInclusive, "M05 Long.MAX_VALUE bootstrap tick changed");
    require(staleActivationFence, "M05 stale activation fence did not fail closed");
    require(stalePlaceFence, "M05 stale Place fence did not fail closed");
    require(grandfatherExistingOrders, "M05 activation revalidated an existing order");
    return new Result(
        "M05RS1",
        frozenHashVectors().size(),
        hashMismatch,
        sameVersionDifferentHash,
        lowerInclusive,
        upperInclusive,
        longMaximumInclusive,
        staleActivationFence,
        stalePlaceFence,
        grandfatherExistingOrders);
  }

  static List<HashVector> frozenHashVectors() {
    return List.of(
        new HashVector(
            "bootstrap-v0-unbounded",
            BigInteger.ZERO,
            BigInteger.ONE,
            MAXIMUM,
            "sha256:d9928c52e99b8611cb95fb0d2792b6901cf9336825e19a7f593393b0d2b99c04"),
        new HashVector(
            "v1-wide",
            BigInteger.ONE,
            BigInteger.valueOf(90),
            BigInteger.valueOf(110),
            "sha256:dbb75b3983480a8ece058736766411f80eb5c62e10eb24de72b74853d5377f91"),
        new HashVector(
            "v1-narrow",
            BigInteger.ONE,
            BigInteger.valueOf(95),
            BigInteger.valueOf(105),
            "sha256:1e5934c44343fe92741732bc5af56c019fc0e785815ff8848ed810ad52247372"),
        new HashVector(
            "v2-wider",
            BigInteger.valueOf(2),
            BigInteger.valueOf(80),
            BigInteger.valueOf(120),
            "sha256:d7d0a8e3a2d1882012f8ba6d7318ecf02e378f4766c26badff272a97e1e21f7d"));
  }

  private static void verifyHashVectors() {
    for (HashVector vector : frozenHashVectors()) {
      byte[] independent =
          M05RuleSetCanonical.bytes(
              vector.version(), vector.lowerInclusive(), vector.upperInclusive());
      require(
          independent.length > 0 && independent[independent.length - 1] == '\n',
          "M05RS1 must end in LF");
      require(
          new String(independent, StandardCharsets.UTF_8).startsWith("M05RS1\n"),
          "M05RS1 header changed");
      require(
          vector
              .contentHash()
              .equals(
                  M05RuleSetCanonical.contentHash(
                      vector.version(), vector.lowerInclusive(), vector.upperInclusive())),
          "testkit M05RS1 hash vector changed: " + vector.id());

      MarketRuleSetArtifact core =
          new MarketRuleSetArtifact(
              vector.version().longValueExact(),
              vector.lowerInclusive().longValueExact(),
              vector.upperInclusive().longValueExact(),
              vector.contentHash());
      require(
          Arrays.equals(independent, core.canonicalBytes()),
          "core M05RS1 bytes changed: " + vector.id());
      require(
          vector.contentHash().equals(core.computedContentHash()),
          "core M05RS1 hash changed: " + vector.id());

      M05MarketRuleSetArtifact reference =
          M05MarketRuleSetArtifact.canonical(
              vector.version(), vector.lowerInclusive(), vector.upperInclusive());
      require(
          Arrays.equals(independent, reference.canonicalBytes()),
          "reference M05RS1 bytes changed: " + vector.id());
      require(
          vector.contentHash().equals(reference.contentHash()),
          "reference M05RS1 hash changed: " + vector.id());
    }
  }

  private static M05PropertyJudge.Observation pass(String id, List<M05Command> commands) {
    M05PropertyJudge.Observation observation =
        new M05PropertyJudge().judge(id, "boundary", commands, M05ProductionCandidate::new);
    require(
        M05PropertyJudge.PASS.equals(observation.classification()),
        "M05 boundary failed: " + id + ": " + observation.message());
    return observation;
  }

  private static M05Command.Place governedPlace(
      long orderId, String side, long priceTicks, String policy, M05Command.Identity identity) {
    return new M05Command.Place(
        "GOVERNED",
        "BTC-USDT",
        BigInteger.valueOf(orderId),
        side,
        BigInteger.valueOf(priceTicks),
        BigInteger.ONE,
        policy,
        identity);
  }

  private static M05Command.Artifact artifact(long version, long lower, long upper) {
    return M05RuleSetCanonical.artifact(
        BigInteger.valueOf(version), BigInteger.valueOf(lower), BigInteger.valueOf(upper));
  }

  private static M05MarketRuleSetArtifact referenceArtifact(M05Command.Artifact artifact) {
    return new M05MarketRuleSetArtifact(
        artifact.schemaVersion(),
        artifact.instrumentId(),
        artifact.version(),
        artifact.lowerInclusive(),
        artifact.upperInclusive(),
        artifact.contentHash());
  }

  private static M05SemanticMarketState initialState() {
    return new M05ReferenceCandidate().snapshot();
  }

  private static boolean retainedExceptApplication(
      M05SemanticMarketState before, M05SemanticMarketState after) {
    return after
            .nextApplicationSequence()
            .equals(before.nextApplicationSequence().add(BigInteger.ONE))
        && after.nextAcceptanceSequence().equals(before.nextAcceptanceSequence())
        && after.controlRevision().equals(before.controlRevision())
        && after.activeRuleSet().equals(before.activeRuleSet())
        && after.preparedRuleSet().equals(before.preparedRuleSet())
        && after.lastActivationFence().equals(before.lastActivationFence())
        && after.book().equals(before.book());
  }

  private static boolean rejected(M05SemanticOutcome outcome, String code) {
    M05SemanticEvent event = outcome.events().getFirst();
    return (event instanceof M05SemanticEvent.PlaceRejected placeRejected
            && code.equals(placeRejected.code()))
        || (event instanceof M05SemanticEvent.PrepareRuleSetRejected prepareRejected
            && code.equals(prepareRejected.code()))
        || (event instanceof M05SemanticEvent.ActivateRuleSetRejected activateRejected
            && code.equals(activateRejected.code()));
  }

  private static boolean accepted(M05SemanticOutcome outcome) {
    return outcome.events().getFirst() instanceof M05SemanticEvent.Accepted;
  }

  private static boolean hasResting(
      M05SemanticBook book, BigInteger orderId, long priceTicks, long quantityLots) {
    return book.asks().stream()
        .filter(level -> level.priceTicks().equals(BigInteger.valueOf(priceTicks)))
        .flatMap(level -> level.orders().stream())
        .anyMatch(
            order ->
                order.orderId().equals(orderId)
                    && order.remainingQuantityLots().equals(BigInteger.valueOf(quantityLots)));
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }

  record HashVector(
      String id,
      BigInteger version,
      BigInteger lowerInclusive,
      BigInteger upperInclusive,
      String contentHash) {}

  record Result(
      String canonicalFormat,
      int hashVectors,
      boolean hashMismatchFailsClosed,
      boolean sameVersionDifferentHashFailsClosed,
      boolean lowerInclusive,
      boolean upperInclusive,
      boolean longMaximumInclusive,
      boolean staleActivationFenceFailsClosed,
      boolean stalePlaceFenceFailsClosed,
      boolean grandfatherExistingOrders) {}
}
