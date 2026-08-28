package io.github.lchareln.cex.matching.reference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

final class LinearReferenceModelTest {
  private static final BigInteger MAXIMUM = BigInteger.valueOf(Long.MAX_VALUE);

  @Test
  void validatesRawFieldsInFrozenPriorityWithoutConsumingSequence() {
    ReferenceMatcher model = new LinearReferenceModel();

    assertEquals(
        outcome(new SemanticEvent.Rejected("UNKNOWN_INSTRUMENT", "instrumentId")),
        model.apply(place("ETH-USDT", -1, "INVALID", 0, 0)));
    assertEquals(
        outcome(new SemanticEvent.Rejected("INVALID_ORDER_ID", "orderId")),
        model.apply(
            new ReferenceCommand.Place(
                "BTC-USDT",
                MAXIMUM.add(BigInteger.ONE),
                "INVALID",
                BigInteger.ZERO,
                BigInteger.ZERO)));
    assertEquals(
        outcome(new SemanticEvent.Rejected("INVALID_SIDE", "side")),
        model.apply(place("BTC-USDT", 1, "buy", 0, 0)));
    assertEquals(
        outcome(new SemanticEvent.Rejected("INVALID_PRICE", "priceTicks")),
        model.apply(place("BTC-USDT", 1, "BUY", 0, 0)));
    assertEquals(
        outcome(new SemanticEvent.Rejected("INVALID_PRICE", "priceTicks")),
        model.apply(
            new ReferenceCommand.Place(
                "BTC-USDT", BigInteger.ONE, "BUY", MAXIMUM.add(BigInteger.ONE), BigInteger.ONE)));
    assertEquals(
        outcome(new SemanticEvent.Rejected("INVALID_QUANTITY", "quantityLots")),
        model.apply(place("BTC-USDT", 1, "BUY", 100, 0)));
    assertEquals(
        outcome(new SemanticEvent.Rejected("INVALID_QUANTITY", "quantityLots")),
        model.apply(
            new ReferenceCommand.Place(
                "BTC-USDT", BigInteger.ONE, "BUY", BigInteger.ONE, MAXIMUM.add(BigInteger.ONE))));

    SemanticOutcome accepted = model.apply(place("BTC-USDT", 1, "BUY", 100, 2));
    assertEquals(
        BigInteger.ONE, ((SemanticEvent.Accepted) accepted.events().getFirst()).sequence());
  }

  @Test
  void choosesBestPriceThenFifoAndExecutesAtMakerPrice() {
    ReferenceMatcher model = new LinearReferenceModel();
    model.apply(place("BTC-USDT", 1, "SELL", 101, 5));
    model.apply(place("BTC-USDT", 2, "SELL", 100, 2));
    model.apply(place("BTC-USDT", 3, "SELL", 100, 3));

    SemanticOutcome outcome = model.apply(place("BTC-USDT", 4, "BUY", 101, 6));

    assertEquals(
        List.of(
            new SemanticEvent.Accepted(bi(4), bi(4), "BUY", bi(101), bi(6)),
            new SemanticEvent.Trade(bi(2), bi(2), bi(4), bi(4), bi(100), bi(2)),
            new SemanticEvent.Trade(bi(3), bi(3), bi(4), bi(4), bi(100), bi(3)),
            new SemanticEvent.Trade(bi(1), bi(1), bi(4), bi(4), bi(101), bi(1))),
        outcome.events());
    assertEquals(
        new SemanticBook(
            List.of(),
            List.of(
                new SemanticBook.PriceLevel(
                    "SELL", bi(101), List.of(new SemanticBook.RestingOrder(bi(1), bi(1), bi(4)))))),
        outcome.bookAfter());
  }

  @Test
  void cancelRetainsTerminalIdentityAndDoesNotConsumeSequence() {
    ReferenceMatcher model = new LinearReferenceModel();
    model.apply(place("BTC-USDT", 7, "BUY", 99, 4));

    assertEquals(
        outcome(new SemanticEvent.Canceled(bi(1), bi(7), "BUY", bi(99), bi(4))),
        model.apply(cancel("BTC-USDT", 7)));
    assertEquals(
        outcome(new SemanticEvent.CancelRejected(bi(7), "ORDER_ALREADY_CANCELED")),
        model.apply(cancel("BTC-USDT", 7)));
    assertEquals(
        outcome(new SemanticEvent.PlaceRejected(bi(7), "DUPLICATE_ORDER_ID")),
        model.apply(place("BTC-USDT", 7, "BUY", 99, 4)));

    SemanticOutcome next = model.apply(place("BTC-USDT", 8, "SELL", 101, 1));
    assertEquals(bi(2), ((SemanticEvent.Accepted) next.events().getFirst()).sequence());
  }

  @Test
  void derivesStrictPriceOrderAndSamePriceFifoFromTheFlatList() {
    ReferenceMatcher model = new LinearReferenceModel();
    model.apply(place("BTC-USDT", 1, "BUY", 99, 1));
    model.apply(place("BTC-USDT", 2, "BUY", 100, 2));
    model.apply(place("BTC-USDT", 3, "BUY", 100, 3));
    model.apply(place("BTC-USDT", 4, "SELL", 102, 4));
    model.apply(place("BTC-USDT", 5, "SELL", 101, 5));

    assertEquals(
        new SemanticBook(
            List.of(
                new SemanticBook.PriceLevel(
                    "BUY",
                    bi(100),
                    List.of(
                        new SemanticBook.RestingOrder(bi(2), bi(2), bi(2)),
                        new SemanticBook.RestingOrder(bi(3), bi(3), bi(3)))),
                new SemanticBook.PriceLevel(
                    "BUY", bi(99), List.of(new SemanticBook.RestingOrder(bi(1), bi(1), bi(1))))),
            List.of(
                new SemanticBook.PriceLevel(
                    "SELL", bi(101), List.of(new SemanticBook.RestingOrder(bi(5), bi(5), bi(5)))),
                new SemanticBook.PriceLevel(
                    "SELL", bi(102), List.of(new SemanticBook.RestingOrder(bi(4), bi(4), bi(4)))))),
        model.snapshot());
  }

  @Test
  void cancelsOnlyThePositiveRemainderOfAPartiallyFilledMaker() {
    ReferenceMatcher model = new LinearReferenceModel();
    model.apply(place("BTC-USDT", 1, "SELL", 100, 5));
    model.apply(place("BTC-USDT", 2, "BUY", 100, 2));

    assertEquals(
        outcome(new SemanticEvent.Canceled(bi(1), bi(1), "SELL", bi(100), bi(3))),
        model.apply(cancel("BTC-USDT", 1)));
    assertEquals(
        outcome(new SemanticEvent.CancelRejected(bi(1), "ORDER_ALREADY_CANCELED")),
        model.apply(cancel("BTC-USDT", 1)));
  }

  @Test
  void preservesFilledLifecycleForMakerAndTaker() {
    ReferenceMatcher model = new LinearReferenceModel();
    model.apply(place("BTC-USDT", 1, "SELL", 100, 2));
    model.apply(place("BTC-USDT", 2, "BUY", 100, 2));

    assertEquals(
        outcome(new SemanticEvent.CancelRejected(bi(1), "ORDER_ALREADY_FILLED")),
        model.apply(cancel("BTC-USDT", 1)));
    assertEquals(
        outcome(new SemanticEvent.CancelRejected(bi(2), "ORDER_ALREADY_FILLED")),
        model.apply(cancel("BTC-USDT", 2)));
    assertEquals(SemanticBook.empty(), model.snapshot());
  }

  @Test
  void unknownAndInvalidCancelsLeaveBookAndIdentityUntouched() {
    ReferenceMatcher model = new LinearReferenceModel();
    SemanticOutcome resting = model.apply(place("BTC-USDT", 1, "BUY", 100, 3));

    assertEquals(
        new SemanticOutcome(
            List.of(new SemanticEvent.Rejected("UNKNOWN_INSTRUMENT", "instrumentId")),
            resting.bookAfter()),
        model.apply(cancel("ETH-USDT", 1)));
    assertEquals(
        new SemanticOutcome(
            List.of(new SemanticEvent.Rejected("INVALID_ORDER_ID", "orderId")),
            resting.bookAfter()),
        model.apply(cancel("BTC-USDT", 0)));
    assertEquals(
        new SemanticOutcome(
            List.of(new SemanticEvent.CancelRejected(bi(9), "ORDER_NOT_FOUND")),
            resting.bookAfter()),
        model.apply(cancel("BTC-USDT", 9)));
    assertEquals(resting.bookAfter(), model.snapshot());
  }

  @Test
  void recordsAreDeeplyImmutableAtListBoundaries() {
    ReferenceMatcher model = new LinearReferenceModel();
    SemanticOutcome outcome = model.apply(place("BTC-USDT", 1, "BUY", 100, 1));

    assertThrows(
        UnsupportedOperationException.class,
        () -> outcome.events().add(new SemanticEvent.Rejected("X", "x")));
    assertThrows(
        UnsupportedOperationException.class,
        () -> outcome.bookAfter().bids().getFirst().orders().clear());
  }

  private static SemanticOutcome outcome(SemanticEvent event) {
    return new SemanticOutcome(List.of(event), SemanticBook.empty());
  }

  private static ReferenceCommand.Place place(
      String instrument, long orderId, String side, long price, long quantity) {
    return new ReferenceCommand.Place(instrument, bi(orderId), side, bi(price), bi(quantity));
  }

  private static ReferenceCommand.Cancel cancel(String instrument, long orderId) {
    return new ReferenceCommand.Cancel(instrument, bi(orderId));
  }

  private static BigInteger bi(long value) {
    return BigInteger.valueOf(value);
  }
}
