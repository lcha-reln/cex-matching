package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.ExecutionBatch;
import io.github.lchareln.cex.matching.GovernedPlaceLimitOrderRequest;
import io.github.lchareln.cex.matching.MarketRuleSetArtifact;
import io.github.lchareln.cex.matching.MatchingEvent;
import io.github.lchareln.cex.matching.PlaceLimitOrderInput;
import io.github.lchareln.cex.matching.PlaceLimitOrderRequest;
import io.github.lchareln.cex.matching.SelfTradePreventionPolicy;
import io.github.lchareln.cex.matching.SingleInstrumentMatchingEngine;
import java.math.BigInteger;
import java.util.List;

/** Executes all three pre-M07 place entrypoints and proves their exact zero/NONE mapping. */
final class M07LegacyEntrypointRegression {
  Result run() {
    PlaceLimitOrderInput input =
        new PlaceLimitOrderInput(
            "BTC-USDT",
            BigInteger.valueOf(7_000_001L),
            "BUY",
            BigInteger.valueOf(99L),
            BigInteger.valueOf(2L));
    PlaceLimitOrderRequest request = new PlaceLimitOrderRequest(input, "GTC");

    ExecutionBatch direct = new SingleInstrumentMatchingEngine().place(input);
    ExecutionBatch requestBatch = new SingleInstrumentMatchingEngine().placeRequest(request);
    ExecutionBatch governed =
        new SingleInstrumentMatchingEngine()
            .placeGoverned(
                new GovernedPlaceLimitOrderRequest(
                    request, MarketRuleSetArtifact.bootstrapIdentity()));

    require(equal(direct, requestBatch), "place and placeRequest legacy mappings differ");
    require(equal(direct, governed), "place and placeGoverned legacy mappings differ");
    for (ExecutionBatch batch : List.of(direct, requestBatch, governed)) {
      MatchingEvent.Accepted accepted =
          batch.events().stream()
              .filter(MatchingEvent.Accepted.class::isInstance)
              .map(MatchingEvent.Accepted.class::cast)
              .findFirst()
              .orElseThrow();
      MatchingEvent.Rested rested =
          batch.events().stream()
              .filter(MatchingEvent.Rested.class::isInstance)
              .map(MatchingEvent.Rested.class::cast)
              .findFirst()
              .orElseThrow();
      var view = batch.bookAfter().bids().getFirst().orders().getFirst();
      require(
          accepted.participantGroupId() == 0
              && accepted.selfTradePreventionPolicy() == SelfTradePreventionPolicy.NONE,
          "legacy Accepted did not map to zero/NONE");
      require(
          rested.participantGroupId() == 0
              && rested.selfTradePreventionPolicy() == SelfTradePreventionPolicy.NONE,
          "legacy Rested did not map to zero/NONE");
      require(
          view.participantGroupId() == 0
              && view.selfTradePreventionPolicy() == SelfTradePreventionPolicy.NONE,
          "legacy book state did not map to zero/NONE");
    }
    return new Result(3, true, 0, "NONE");
  }

  private static boolean equal(ExecutionBatch left, ExecutionBatch right) {
    return left.events().equals(right.events())
        && left.context().equals(right.context())
        && left.bookAfter().equals(right.bookAfter());
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }

  record Result(
      int entrypoints,
      boolean byteEquivalentSemantics,
      long participantGroupId,
      String stpPolicy) {}
}
