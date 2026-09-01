package io.github.lchareln.cex.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;

class MatchingStateImageTest {
  @Test
  void restoresRestingFilledAndCanceledOrdersWithoutRevivingTerminalIdentity() {
    SingleInstrumentMatchingEngine original = new SingleInstrumentMatchingEngine();
    original.place(order(1, "SELL", 101, 2));
    original.place(order(2, "BUY", 101, 2));
    original.place(order(3, "SELL", 102, 4));
    original.cancel(cancel(3));

    MatchingStateImage image = original.stateImage();
    assertEquals(
        java.util.List.of(
            MatchingStateImage.Lifecycle.FILLED,
            MatchingStateImage.Lifecycle.FILLED,
            MatchingStateImage.Lifecycle.CANCELED),
        image.orders().stream().map(MatchingStateImage.OrderImage::lifecycle).toList());

    SingleInstrumentMatchingEngine restored = SingleInstrumentMatchingEngine.restore(image);
    assertEquals(image, restored.stateImage());
    assertEquals(original.snapshot(), restored.snapshot());
    assertEquals(original.marketControlSnapshot(), restored.marketControlSnapshot());
    assertEquals(original.place(order(1, "BUY", 99, 1)), restored.place(order(1, "BUY", 99, 1)));
    assertEquals(original.cancel(cancel(3)), restored.cancel(cancel(3)));
  }

  private static PlaceLimitOrderInput order(
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
}
