package io.github.lchareln.cex.matching;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

final class ExecutionBatchPolicyGrammarTest {

  private static final AcceptanceSequence SEQUENCE = new AcceptanceSequence(1);
  private static final OrderId ORDER_ID = new OrderId(1);
  private static final PriceTicks PRICE = new PriceTicks(100);
  private static final QuantityLots QUANTITY = new QuantityLots(3);

  @Test
  void iocRequiresItsPositiveRemainderToBeCanceledNotRested() {
    MatchingEvent.Accepted accepted = accepted(ExecutionPolicy.IOC);

    assertThrows(
        IllegalArgumentException.class,
        () -> new ExecutionBatch(List.of(accepted, rested()), emptyBook()));
    assertDoesNotThrow(
        () -> new ExecutionBatch(List.of(accepted, remainderCanceled()), emptyBook()));
  }

  @Test
  void fokCannotBeAcceptedWithoutACompleteFill() {
    MatchingEvent.Accepted accepted = accepted(ExecutionPolicy.FOK);

    assertThrows(
        IllegalArgumentException.class,
        () -> new ExecutionBatch(List.of(accepted, remainderCanceled()), emptyBook()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ExecutionBatch(List.of(accepted, rested()), emptyBook()));
  }

  @Test
  void postOnlyCannotTradeAndMustRestItsFullQuantity() {
    MatchingEvent.Accepted accepted = accepted(ExecutionPolicy.POST_ONLY);
    MatchingEvent.Trade trade =
        new MatchingEvent.Trade(
            new AcceptanceSequence(2),
            new OrderId(2),
            SEQUENCE,
            ORDER_ID,
            PRICE,
            new QuantityLots(1));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ExecutionBatch(
                List.of(
                    accepted,
                    trade,
                    new MatchingEvent.Rested(
                        SEQUENCE, ORDER_ID, Side.BUY, PRICE, new QuantityLots(2))),
                emptyBook()));
    assertDoesNotThrow(() -> new ExecutionBatch(List.of(accepted, rested()), emptyBook()));
  }

  private static MatchingEvent.Accepted accepted(ExecutionPolicy policy) {
    return new MatchingEvent.Accepted(SEQUENCE, ORDER_ID, Side.BUY, PRICE, QUANTITY, policy);
  }

  private static MatchingEvent.Rested rested() {
    return new MatchingEvent.Rested(SEQUENCE, ORDER_ID, Side.BUY, PRICE, QUANTITY);
  }

  private static MatchingEvent.RemainderCanceled remainderCanceled() {
    return new MatchingEvent.RemainderCanceled(
        SEQUENCE, ORDER_ID, Side.BUY, PRICE, QUANTITY, RemainderCancelReason.IOC_REMAINDER);
  }

  private static OrderBookSnapshot emptyBook() {
    return new OrderBookSnapshot(List.of(), List.of());
  }
}
