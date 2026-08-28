package io.github.lchareln.cex.matching.reference;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

final class M04LinearReferenceModelTest {
  private static final BigInteger MAXIMUM = BigInteger.valueOf(Long.MAX_VALUE);

  @Nested
  final class CompatibilityAndAdmission {
    @Test
    void fiveFieldCommandsAndAcceptedEventsRemainExplicitGtcValues() {
      ReferenceCommand.Place legacy = place("BTC-USDT", 1, "BUY", 100, 2);
      ReferenceCommand.Place explicit = policy("BTC-USDT", 1, "BUY", 100, 2, "GTC");

      assertEquals(explicit, legacy);
      assertEquals(
          new SemanticEvent.Accepted(bi(1), bi(1), "BUY", bi(100), bi(2), "GTC"),
          new SemanticEvent.Accepted(bi(1), bi(1), "BUY", bi(100), bi(2)));

      ReferenceMatcher legacyModel = new LinearReferenceModel();
      ReferenceMatcher explicitModel = new LinearReferenceModel();
      assertEquals(legacyModel.apply(legacy), explicitModel.apply(explicit));
      assertEquals(
          legacyModel.apply(cancel("BTC-USDT", 1)), explicitModel.apply(cancel("BTC-USDT", 1)));
    }

    @Test
    void validatesLegacyFieldsBeforePolicyAndDuplicateBeforeAdmission() {
      ReferenceMatcher model = new LinearReferenceModel();

      assertEquals(
          outcome(new SemanticEvent.Rejected("UNKNOWN_INSTRUMENT", "instrumentId")),
          model.apply(policy("ETH-USDT", -1, "INVALID", 0, 0, "UNKNOWN")));
      assertEquals(
          outcome(new SemanticEvent.Rejected("INVALID_QUANTITY", "quantityLots")),
          model.apply(policy("BTC-USDT", 1, "BUY", 100, 0, "UNKNOWN")));
      assertEquals(
          outcome(new SemanticEvent.Rejected("INVALID_EXECUTION_POLICY", "executionPolicy")),
          model.apply(policy("BTC-USDT", 1, "BUY", 100, 1, "UNKNOWN")));

      SemanticOutcome firstAccepted = model.apply(place("BTC-USDT", 1, "SELL", 101, 1));
      assertEquals(
          BigInteger.ONE, ((SemanticEvent.Accepted) firstAccepted.events().getFirst()).sequence());
      assertEquals(
          new SemanticOutcome(
              List.of(new SemanticEvent.PlaceRejected(bi(1), "DUPLICATE_ORDER_ID")),
              model.snapshot()),
          model.apply(policy("BTC-USDT", 1, "BUY", 100, 1, "FOK")));
    }
  }

  @Nested
  final class ImmediateOrCancel {
    @Test
    void buyIocExecutesWithinItsLimitThenCancelsThePositiveRemainder() {
      ReferenceMatcher model = new LinearReferenceModel();
      model.apply(place("BTC-USDT", 1, "SELL", 100, 2));
      model.apply(place("BTC-USDT", 2, "SELL", 101, 5));

      SemanticOutcome result = model.apply(policy("BTC-USDT", 3, "BUY", 100, 3, "IOC"));

      assertEquals(
          List.of(
              new SemanticEvent.Accepted(bi(3), bi(3), "BUY", bi(100), bi(3), "IOC"),
              new SemanticEvent.Trade(bi(1), bi(1), bi(3), bi(3), bi(100), bi(2)),
              new SemanticEvent.RemainderCanceled(
                  bi(3), bi(3), "BUY", bi(100), bi(1), "IOC_REMAINDER")),
          result.events());
      assertEquals(
          new SemanticBook(
              List.of(),
              List.of(
                  new SemanticBook.PriceLevel(
                      "SELL",
                      bi(101),
                      List.of(new SemanticBook.RestingOrder(bi(2), bi(2), bi(5)))))),
          result.bookAfter());
      assertEquals(
          new SemanticOutcome(
              List.of(new SemanticEvent.CancelRejected(bi(3), "ORDER_ALREADY_CANCELED")),
              result.bookAfter()),
          model.apply(cancel("BTC-USDT", 3)));
    }

    @Test
    void sellIocMirrorsBuyAndNeverConsumesWorsePricedLiquidity() {
      ReferenceMatcher model = new LinearReferenceModel();
      model.apply(place("BTC-USDT", 1, "BUY", 100, 2));
      model.apply(place("BTC-USDT", 2, "BUY", 99, 5));

      SemanticOutcome result = model.apply(policy("BTC-USDT", 3, "SELL", 100, 3, "IOC"));

      assertEquals(
          List.of(
              new SemanticEvent.Accepted(bi(3), bi(3), "SELL", bi(100), bi(3), "IOC"),
              new SemanticEvent.Trade(bi(1), bi(1), bi(3), bi(3), bi(100), bi(2)),
              new SemanticEvent.RemainderCanceled(
                  bi(3), bi(3), "SELL", bi(100), bi(1), "IOC_REMAINDER")),
          result.events());
      assertEquals(
          new SemanticBook(
              List.of(
                  new SemanticBook.PriceLevel(
                      "BUY", bi(99), List.of(new SemanticBook.RestingOrder(bi(2), bi(2), bi(5))))),
              List.of()),
          result.bookAfter());
    }

    @Test
    void zeroAndFullIocExecutionsHaveDistinctTerminalEventGrammar() {
      ReferenceMatcher empty = new LinearReferenceModel();
      SemanticOutcome zero = empty.apply(policy("BTC-USDT", 1, "BUY", 100, 2, "IOC"));
      assertEquals(
          List.of(
              new SemanticEvent.Accepted(bi(1), bi(1), "BUY", bi(100), bi(2), "IOC"),
              new SemanticEvent.RemainderCanceled(
                  bi(1), bi(1), "BUY", bi(100), bi(2), "IOC_REMAINDER")),
          zero.events());

      ReferenceMatcher full = new LinearReferenceModel();
      full.apply(place("BTC-USDT", 1, "SELL", 100, 2));
      SemanticOutcome filled = full.apply(policy("BTC-USDT", 2, "BUY", 100, 2, "IOC"));
      assertEquals(
          List.of(
              new SemanticEvent.Accepted(bi(2), bi(2), "BUY", bi(100), bi(2), "IOC"),
              new SemanticEvent.Trade(bi(1), bi(1), bi(2), bi(2), bi(100), bi(2))),
          filled.events());
      assertEquals(
          outcome(new SemanticEvent.CancelRejected(bi(2), "ORDER_ALREADY_FILLED")),
          full.apply(cancel("BTC-USDT", 2)));
    }
  }

  @Nested
  final class FillOrKill {
    @Test
    void insufficientFokLeavesBookIdentityAndSequenceUntouched() {
      ReferenceMatcher model = new LinearReferenceModel();
      model.apply(place("BTC-USDT", 1, "SELL", 100, 2));
      SemanticBook before = model.snapshot();

      assertEquals(
          new SemanticOutcome(
              List.of(new SemanticEvent.PlaceRejected(bi(2), "FOK_NOT_FILLABLE")), before),
          model.apply(policy("BTC-USDT", 2, "BUY", 100, 3, "FOK")));
      assertEquals(before, model.snapshot());

      SemanticOutcome reused = model.apply(place("BTC-USDT", 2, "BUY", 99, 1));
      assertEquals(bi(2), ((SemanticEvent.Accepted) reused.events().getFirst()).sequence());
    }

    @Test
    void buyFokUsesExactMultiLevelLiquidityWithinItsLimit() {
      ReferenceMatcher model = new LinearReferenceModel();
      model.apply(place("BTC-USDT", 1, "SELL", 100, 2));
      model.apply(place("BTC-USDT", 2, "SELL", 101, 3));

      SemanticOutcome result = model.apply(policy("BTC-USDT", 3, "BUY", 101, 5, "FOK"));

      assertEquals(
          List.of(
              new SemanticEvent.Accepted(bi(3), bi(3), "BUY", bi(101), bi(5), "FOK"),
              new SemanticEvent.Trade(bi(1), bi(1), bi(3), bi(3), bi(100), bi(2)),
              new SemanticEvent.Trade(bi(2), bi(2), bi(3), bi(3), bi(101), bi(3))),
          result.events());
      assertEquals(SemanticBook.empty(), result.bookAfter());
    }

    @Test
    void sellFokMirrorsBuyAndExcludesLiquidityBeyondItsLimit() {
      ReferenceMatcher model = new LinearReferenceModel();
      model.apply(place("BTC-USDT", 1, "BUY", 100, 1));
      model.apply(place("BTC-USDT", 2, "BUY", 99, 5));

      SemanticBook before = model.snapshot();
      assertEquals(
          new SemanticOutcome(
              List.of(new SemanticEvent.PlaceRejected(bi(3), "FOK_NOT_FILLABLE")), before),
          model.apply(policy("BTC-USDT", 3, "SELL", 100, 2, "FOK")));

      SemanticOutcome filled = model.apply(policy("BTC-USDT", 3, "SELL", 99, 6, "FOK"));
      assertEquals(
          List.of(
              new SemanticEvent.Accepted(bi(3), bi(3), "SELL", bi(99), bi(6), "FOK"),
              new SemanticEvent.Trade(bi(1), bi(1), bi(3), bi(3), bi(100), bi(1)),
              new SemanticEvent.Trade(bi(2), bi(2), bi(3), bi(3), bi(99), bi(5))),
          filled.events());
    }

    @Test
    void perOrderDemandDeductionDoesNotOverflowAtLongMaximumDepth() {
      BigInteger firstQuantity = MAXIMUM.divide(BigInteger.TWO);
      BigInteger secondQuantity = MAXIMUM.subtract(firstQuantity).add(BigInteger.ONE);
      BigInteger secondTrade = MAXIMUM.subtract(firstQuantity);
      ReferenceMatcher model = new LinearReferenceModel();
      model.apply(bigPolicy(1, "SELL", bi(100), firstQuantity, "GTC"));
      model.apply(bigPolicy(2, "SELL", bi(100), secondQuantity, "GTC"));

      SemanticOutcome result = model.apply(bigPolicy(3, "BUY", bi(100), MAXIMUM, "FOK"));

      assertEquals(
          List.of(
              new SemanticEvent.Accepted(bi(3), bi(3), "BUY", bi(100), MAXIMUM, "FOK"),
              new SemanticEvent.Trade(bi(1), bi(1), bi(3), bi(3), bi(100), firstQuantity),
              new SemanticEvent.Trade(bi(2), bi(2), bi(3), bi(3), bi(100), secondTrade)),
          result.events());
      assertEquals(
          new SemanticBook(
              List.of(),
              List.of(
                  new SemanticBook.PriceLevel(
                      "SELL",
                      bi(100),
                      List.of(new SemanticBook.RestingOrder(bi(2), bi(2), BigInteger.ONE))))),
          result.bookAfter());
    }
  }

  @Nested
  final class PostOnly {
    @Test
    void buyPostOnlyRejectsTouchAndCrossWithoutClaimingIdentityOrSequence() {
      ReferenceMatcher model = new LinearReferenceModel();
      model.apply(place("BTC-USDT", 1, "SELL", 100, 2));
      SemanticBook before = model.snapshot();

      assertEquals(
          new SemanticOutcome(
              List.of(new SemanticEvent.PlaceRejected(bi(2), "POST_ONLY_WOULD_TAKE")), before),
          model.apply(policy("BTC-USDT", 2, "BUY", 100, 1, "POST_ONLY")));
      assertEquals(
          new SemanticOutcome(
              List.of(new SemanticEvent.PlaceRejected(bi(2), "POST_ONLY_WOULD_TAKE")), before),
          model.apply(policy("BTC-USDT", 2, "BUY", 101, 1, "POST_ONLY")));

      SemanticOutcome accepted = model.apply(policy("BTC-USDT", 2, "BUY", 99, 1, "POST_ONLY"));
      assertEquals(
          List.of(
              new SemanticEvent.Accepted(bi(2), bi(2), "BUY", bi(99), bi(1), "POST_ONLY"),
              new SemanticEvent.Rested(bi(2), bi(2), "BUY", bi(99), bi(1))),
          accepted.events());
    }

    @Test
    void sellPostOnlyMirrorsBuyAtTheTouchBoundary() {
      ReferenceMatcher model = new LinearReferenceModel();
      model.apply(place("BTC-USDT", 1, "BUY", 100, 2));
      SemanticBook before = model.snapshot();

      assertEquals(
          new SemanticOutcome(
              List.of(new SemanticEvent.PlaceRejected(bi(2), "POST_ONLY_WOULD_TAKE")), before),
          model.apply(policy("BTC-USDT", 2, "SELL", 100, 1, "POST_ONLY")));

      SemanticOutcome accepted = model.apply(policy("BTC-USDT", 2, "SELL", 101, 1, "POST_ONLY"));
      assertEquals(
          List.of(
              new SemanticEvent.Accepted(bi(2), bi(2), "SELL", bi(101), bi(1), "POST_ONLY"),
              new SemanticEvent.Rested(bi(2), bi(2), "SELL", bi(101), bi(1))),
          accepted.events());
    }
  }

  private static SemanticOutcome outcome(SemanticEvent event) {
    return new SemanticOutcome(List.of(event), SemanticBook.empty());
  }

  private static ReferenceCommand.Place place(
      String instrument, long orderId, String side, long price, long quantity) {
    return new ReferenceCommand.Place(instrument, bi(orderId), side, bi(price), bi(quantity));
  }

  private static ReferenceCommand.Place policy(
      String instrument,
      long orderId,
      String side,
      long price,
      long quantity,
      String executionPolicy) {
    return new ReferenceCommand.Place(
        instrument, bi(orderId), side, bi(price), bi(quantity), executionPolicy);
  }

  private static ReferenceCommand.Place bigPolicy(
      long orderId, String side, BigInteger price, BigInteger quantity, String executionPolicy) {
    return new ReferenceCommand.Place(
        "BTC-USDT", bi(orderId), side, price, quantity, executionPolicy);
  }

  private static ReferenceCommand.Cancel cancel(String instrument, long orderId) {
    return new ReferenceCommand.Cancel(instrument, bi(orderId));
  }

  private static BigInteger bi(long value) {
    return BigInteger.valueOf(value);
  }
}
