package io.github.lchareln.cex.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.math.BigInteger;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

final class SingleInstrumentTerminalHistoryGrowthTest {
  private static final int RETAINED_IOC_ORDERS = 25_000;

  @Test
  void retainedTerminalHistoryDoesNotReintroduceAWholeRegistryCommandPathAudit() {
    assertTimeoutPreemptively(
        Duration.ofSeconds(30),
        () -> {
          SingleInstrumentMatchingEngine engine = new SingleInstrumentMatchingEngine();
          ExecutionBatch last = null;
          for (int orderId = 1; orderId <= RETAINED_IOC_ORDERS; orderId++) {
            last =
                engine.placeRequest(
                    new PlaceLimitOrderRequest(
                        new PlaceLimitOrderInput(
                            "BTC-USDT",
                            BigInteger.valueOf(orderId),
                            "BUY",
                            BigInteger.valueOf(100),
                            BigInteger.ONE),
                        "IOC"));
          }

          assertEquals(List.of(), last.bookAfter().bids());
          assertEquals(List.of(), last.bookAfter().asks());
          assertInstanceOf(MatchingEvent.RemainderCanceled.class, last.events().getLast());

          // The complete audit remains available at an explicit cold boundary, and terminal
          // identities remain durable state rather than being pruned to make the test pass.
          engine.assertConsistentState();
          assertEquals(RETAINED_IOC_ORDERS, engine.stateImage().orders().size());
          assertEquals(
              List.of(
                  new MatchingEvent.CancelRejected(
                      new OrderId(1), CancelRejectionCode.ORDER_ALREADY_CANCELED)),
              engine.cancel(new CancelOrderInput("BTC-USDT", BigInteger.ONE)).events());
        });
  }
}
