package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lchareln.cex.matching.reference.LinearReferenceModel;
import io.github.lchareln.cex.matching.reference.ReferenceCommand;
import io.github.lchareln.cex.matching.reference.ReferenceMatcher;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

final class M03CounterexampleReplayTest {
  @Test
  void persistedSixMutantCorpusReplaysExactReferenceOutcomesAndFingerprints() {
    ObjectNode persisted = persistedCorpus();
    byte[] bytes = JsonSupport.prettyBytes(persisted);
    JsonNode parsed = JsonSupport.parse(bytes);

    M03CounterexampleReplay.ReplayReport replay =
        new M03CounterexampleReplay().replaySemanticOnly(parsed, mutantFactories());
    assertTrue(replay.allPassed());
    assertEquals(6, replay.scenarios().size());
    assertTrue(replay.scenarios().stream().allMatch(result -> result.referenceOutcomesExact()));
    assertTrue(replay.scenarios().stream().allMatch(result -> result.actualOutcomeExact()));
    assertTrue(replay.scenarios().stream().allMatch(result -> result.actualFingerprint() != null));

    M03CounterexampleCanonicalizer canonicalizer = new M03CounterexampleCanonicalizer();
    var first = canonicalizer.canonicalize(persisted);
    var roundTrip = canonicalizer.canonicalize(parsed);
    assertArrayEquals(first.bytes(), roundTrip.bytes());
    assertEquals(first.digest(), roundTrip.digest());
    assertEquals(6, first.scenarioCount());
    assertEquals(15, first.originalCommandCount());
    assertTrue(first.commandCount() > 0);
    assertTrue(new String(first.bytes(), StandardCharsets.UTF_8).startsWith("M03X1|"));
  }

  @Test
  void replayFailsClosedWhenPersistedFingerprintOrExpectedOutcomeDrifts() {
    ObjectNode wrongFingerprint = persistedCorpus();
    ((ObjectNode) wrongFingerprint.path("scenarios").get(0)).put("propertyId", "MAKER_PRICE");
    M03CounterexampleReplay.ReplayReport fingerprintReplay =
        new M03CounterexampleReplay().replaySemanticOnly(wrongFingerprint, mutantFactories());
    assertFalse(fingerprintReplay.allPassed());
    assertFalse(fingerprintReplay.scenarios().getFirst().passed());

    ObjectNode wrongExpected = persistedCorpus();
    ObjectNode firstExpected =
        (ObjectNode)
            wrongExpected.path("scenarios").get(0).path("commands").get(0).path("expected");
    ((ArrayNode) firstExpected.path("events")).removeAll();
    M03CounterexampleReplay.ReplayReport outcomeReplay =
        new M03CounterexampleReplay().replaySemanticOnly(wrongExpected, mutantFactories());
    assertFalse(outcomeReplay.allPassed());
    assertFalse(outcomeReplay.scenarios().getFirst().referenceOutcomesExact());
  }

  private static ObjectNode persistedCorpus() {
    ObjectNode root = JsonSupport.MAPPER.createObjectNode();
    root.put("schemaVersion", "matching.m03.counterexamples.v1");
    root.put("profileSha256", "3e051347b9bd42aac431d02949c0c1b72daa667d10a03cc8aeb09a6b5a74d24e");
    root.put("generatorAlgorithm", "splitmix64-v1");
    root.put("seedDerivation", M03CommandCanonicalizer.SEED_DERIVATION);
    root.put("modelVersion", "linear-scan-reference-v1");
    ArrayNode scenarios = root.putArray("scenarios");
    for (CaseSpec spec : specifications()) {
      M03Shrinker.Fingerprint fingerprint =
          new M03Shrinker.Fingerprint(spec.propertyId(), spec.divergenceKind());
      M03Shrinker.Result minimized =
          new M03Shrinker()
              .shrink(
                  spec.scenarioId(),
                  "0000000000001aa8",
                  spec.original(),
                  spec.factory(),
                  fingerprint);
      ObjectNode scenario = scenarios.addObject();
      scenario.put("scenarioId", spec.scenarioId());
      scenario.put("mutantId", spec.mutantId());
      scenario.put("classification", M03PropertyJudge.STUDENT_FAILURE);
      scenario.put("propertyId", spec.propertyId());
      scenario.put("divergenceKind", spec.divergenceKind());
      scenario.put("historyIndex", 0);
      scenario.put("lane", "BEST_PRICE");
      scenario.put("seed", "0000000000001aa8");
      scenario.put("originalCommandCount", spec.original().size());
      scenario.put("minimizedCommandCount", minimized.commands().size());
      scenario.put("firstFailingCommandIndex", minimized.observation().failure().commandIndex());
      scenario.put("oneMinimal", minimized.oneMinimal());
      scenario.put("shrinkTrials", minimized.trials());
      scenario.set("originalCommands", M03Json.commands(spec.original()));
      ArrayNode commands = scenario.putArray("commands");
      ReferenceMatcher reference = new LinearReferenceModel();
      for (int index = 0; index < minimized.commands().size(); index++) {
        ReferenceCommand command = minimized.commands().get(index);
        commands.add(
            M03Json.replayCommand(
                spec.scenarioId() + "-" + (index + 1), command, reference.apply(command)));
      }
      scenario.set("actualAtFailure", M03Json.outcome(minimized.observation().failure().actual()));
    }
    return root;
  }

  private static List<CaseSpec> specifications() {
    return List.of(
        new CaseSpec(
            "best-price-last",
            M03Mutants.BEST_PRICE_LAST_ID,
            List.of(place(1, "SELL", 90, 1), place(2, "SELL", 100, 1), place(3, "BUY", 100, 2)),
            M03Mutants.bestPriceLast(M03ProductionCandidate::new),
            "PRICE_TIME_PRIORITY",
            "WRONG_MAKER_ORDER"),
        new CaseSpec(
            "same-price-lifo",
            M03Mutants.SAME_PRICE_LIFO_ID,
            List.of(place(1, "SELL", 100, 1), place(2, "SELL", 100, 1), place(3, "BUY", 100, 2)),
            M03Mutants.samePriceLifo(M03ProductionCandidate::new),
            "PRICE_TIME_PRIORITY",
            "WRONG_MAKER_ORDER"),
        new CaseSpec(
            "taker-price",
            M03Mutants.TAKER_PRICE_ID,
            List.of(place(1, "SELL", 90, 1), place(2, "BUY", 100, 1)),
            M03Mutants.takerPrice(M03ProductionCandidate::new),
            "MAKER_PRICE",
            "TRADE_PRICE"),
        new CaseSpec(
            "quantity-overflow",
            M03Mutants.QUANTITY_OVERFLOW_ID,
            List.of(place(1, "SELL", 90, 1), place(2, "BUY", 100, 1)),
            M03Mutants.tradeQuantityOverflow(M03ProductionCandidate::new),
            "QUANTITY_PARTITION",
            "TRADE_EXCEEDS_REMAINDER"),
        new CaseSpec(
            "cancel-ghost",
            M03Mutants.CANCEL_GHOST_ID,
            List.of(place(1, "BUY", 100, 2), cancel(1)),
            M03Mutants.cancelGhostBook(M03ProductionCandidate::new),
            "BOOK_LIFECYCLE_BIJECTION",
            "ACTIVE_ID_SET"),
        new CaseSpec(
            "canceled-reuse",
            M03Mutants.CANCELED_REUSE_ID,
            List.of(place(1, "BUY", 100, 2), cancel(1), place(1, "BUY", 100, 2)),
            M03Mutants.canceledIdentityReuse(M03ProductionCandidate::new),
            "LIFECYCLE_IRREVERSIBILITY",
            "TERMINAL_OR_ACTIVE_ID_REUSED"));
  }

  private static Map<String, M03Candidate.Factory> mutantFactories() {
    return Map.of(
        M03Mutants.BEST_PRICE_LAST_ID,
        M03Mutants.bestPriceLast(M03ProductionCandidate::new),
        M03Mutants.SAME_PRICE_LIFO_ID,
        M03Mutants.samePriceLifo(M03ProductionCandidate::new),
        M03Mutants.TAKER_PRICE_ID,
        M03Mutants.takerPrice(M03ProductionCandidate::new),
        M03Mutants.QUANTITY_OVERFLOW_ID,
        M03Mutants.tradeQuantityOverflow(M03ProductionCandidate::new),
        M03Mutants.CANCEL_GHOST_ID,
        M03Mutants.cancelGhostBook(M03ProductionCandidate::new),
        M03Mutants.CANCELED_REUSE_ID,
        M03Mutants.canceledIdentityReuse(M03ProductionCandidate::new));
  }

  private static ReferenceCommand place(long orderId, String side, long price, long quantity) {
    return new ReferenceCommand.Place(
        "BTC-USDT",
        BigInteger.valueOf(orderId),
        side,
        BigInteger.valueOf(price),
        BigInteger.valueOf(quantity));
  }

  private static ReferenceCommand cancel(long orderId) {
    return new ReferenceCommand.Cancel("BTC-USDT", BigInteger.valueOf(orderId));
  }

  private record CaseSpec(
      String scenarioId,
      String mutantId,
      List<ReferenceCommand> original,
      M03Candidate.Factory factory,
      String propertyId,
      String divergenceKind) {}
}
