package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.lchareln.cex.matching.PlaceLimitOrderInput;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

final class M01BigIntegerConservationTest {
  @Test
  void aggregateConservationDoesNotOverflowWhenIndividualValuesAreValidLongs() {
    long maximum = Long.MAX_VALUE;
    M01ScenarioPack.RestingOrder first = new M01ScenarioPack.RestingOrder(1, 1, maximum);
    M01ScenarioPack.RestingOrder second = new M01ScenarioPack.RestingOrder(2, 2, maximum);
    M01ScenarioPack.Case firstCase =
        new M01ScenarioPack.Case(
            "maximum-one",
            input(1, maximum),
            new M01ScenarioPack.Expected(
                List.of(
                    new M01ScenarioPack.Accepted(1, 1, "BUY", 100, maximum),
                    new M01ScenarioPack.Rested(1, 1, "BUY", 100, maximum)),
                new M01ScenarioPack.Book(
                    List.of(new M01ScenarioPack.Level(100, List.of(first))), List.of())));
    M01ScenarioPack.Case secondCase =
        new M01ScenarioPack.Case(
            "maximum-two",
            input(2, maximum),
            new M01ScenarioPack.Expected(
                List.of(
                    new M01ScenarioPack.Accepted(2, 2, "BUY", 100, maximum),
                    new M01ScenarioPack.Rested(2, 2, "BUY", 100, maximum)),
                new M01ScenarioPack.Book(
                    List.of(new M01ScenarioPack.Level(100, List.of(first, second))), List.of())));
    M01ScenarioPack pack =
        new M01ScenarioPack(
            List.of(
                new M01ScenarioPack.Scenario(
                    "big-integer-conservation", List.of(firstCase, secondCase))));

    M01Assertions.Observation observation =
        new M01Assertions().judge(pack, M01ProductionCandidate::new);

    assertEquals(M01Assertions.PASS, observation.classification(), observation.message());
    assertEquals(2, observation.metrics().conservationChecks());
  }

  private static PlaceLimitOrderInput input(long orderId, long quantity) {
    return new PlaceLimitOrderInput(
        "BTC-USDT",
        BigInteger.valueOf(orderId),
        "BUY",
        BigInteger.valueOf(100),
        BigInteger.valueOf(quantity));
  }
}
