package io.github.lchareln.cex.matching;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

final class ExecutionBatchStpGrammarTest {
  private static final RuleSetIdentity RULES = MarketRuleSetArtifact.bootstrapIdentity();
  private static final AcceptanceSequence MAKER_SEQUENCE = new AcceptanceSequence(1);
  private static final AcceptanceSequence TAKER_SEQUENCE = new AcceptanceSequence(2);
  private static final OrderId MAKER_ID = new OrderId(10);
  private static final OrderId TAKER_ID = new OrderId(20);
  private static final PriceTicks PRICE = new PriceTicks(100);

  @Test
  void cancelTakerIsTerminalAndMustCancelTheCompleteRemainder() {
    MatchingEvent.Accepted accepted =
        accepted(ExecutionPolicy.IOC, SelfTradePreventionPolicy.CANCEL_TAKER);
    MatchingEvent.SelfTradePrevented correct =
        prevented(SelfTradePreventionPolicy.CANCEL_TAKER, 0, 3);
    MatchingEvent.SelfTradePrevented wrong =
        prevented(SelfTradePreventionPolicy.CANCEL_TAKER, 0, 2);

    assertDoesNotThrow(() -> batch(List.of(accepted, correct)));
    assertThrows(IllegalArgumentException.class, () -> batch(List.of(accepted, wrong)));
    assertThrows(
        IllegalArgumentException.class, () -> batch(List.of(accepted, correct, iocRemainder(3))));
  }

  @Test
  void cancelMakerCanContinueButItsInstructionAndAttributionCannotDrift() {
    MatchingEvent.Accepted accepted =
        accepted(ExecutionPolicy.GTC, SelfTradePreventionPolicy.CANCEL_MAKER);
    MatchingEvent.SelfTradePrevented prevented =
        prevented(SelfTradePreventionPolicy.CANCEL_MAKER, 2, 0);
    MatchingEvent.Rested rested =
        new MatchingEvent.Rested(
            TAKER_SEQUENCE,
            TAKER_ID,
            Side.BUY,
            PRICE,
            new QuantityLots(3),
            RULES,
            7,
            SelfTradePreventionPolicy.CANCEL_MAKER);

    assertDoesNotThrow(() -> batch(List.of(accepted, prevented, rested)));
    assertThrows(
        IllegalArgumentException.class,
        () -> batch(List.of(accepted, prevented(SelfTradePreventionPolicy.CANCEL_TAKER, 0, 3))));
  }

  @Test
  void postOnlyCannotContainStpAndFokCannotCancelItsTakerAfterAcceptance() {
    MatchingEvent.Accepted post =
        accepted(ExecutionPolicy.POST_ONLY, SelfTradePreventionPolicy.CANCEL_MAKER);
    MatchingEvent.Rested postRest =
        new MatchingEvent.Rested(
            TAKER_SEQUENCE,
            TAKER_ID,
            Side.BUY,
            PRICE,
            new QuantityLots(3),
            RULES,
            7,
            SelfTradePreventionPolicy.CANCEL_MAKER);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            batch(
                List.of(post, prevented(SelfTradePreventionPolicy.CANCEL_MAKER, 2, 0), postRest)));

    MatchingEvent.Accepted fok =
        accepted(ExecutionPolicy.FOK, SelfTradePreventionPolicy.CANCEL_TAKER);
    assertThrows(
        IllegalArgumentException.class,
        () -> batch(List.of(fok, prevented(SelfTradePreventionPolicy.CANCEL_TAKER, 0, 3))));
  }

  @Test
  void makerScanRejectsDuplicateNonCrossingAndOutOfPriceTimeOrderEvents() {
    AcceptanceSequence takerSequence = new AcceptanceSequence(4);
    MatchingEvent.Accepted accepted =
        accepted(
            takerSequence,
            Side.BUY,
            100,
            3,
            ExecutionPolicy.GTC,
            SelfTradePreventionPolicy.CANCEL_MAKER);
    MatchingEvent.SelfTradePrevented first =
        prevented(
            new AcceptanceSequence(1),
            new OrderId(10),
            takerSequence,
            99,
            1,
            SelfTradePreventionPolicy.CANCEL_MAKER,
            1,
            0);
    MatchingEvent.SelfTradePrevented second =
        prevented(
            new AcceptanceSequence(2),
            new OrderId(11),
            takerSequence,
            100,
            1,
            SelfTradePreventionPolicy.CANCEL_MAKER,
            1,
            0);
    MatchingEvent.Rested rested =
        rested(takerSequence, Side.BUY, 100, 3, SelfTradePreventionPolicy.CANCEL_MAKER);

    assertDoesNotThrow(() -> batch(List.of(accepted, first, second, rested)));
    assertThrows(
        IllegalArgumentException.class, () -> batch(List.of(accepted, first, first, rested)));

    MatchingEvent.SelfTradePrevented nonCrossing =
        prevented(
            new AcceptanceSequence(3),
            new OrderId(12),
            takerSequence,
            101,
            1,
            SelfTradePreventionPolicy.CANCEL_MAKER,
            1,
            0);
    assertThrows(
        IllegalArgumentException.class, () -> batch(List.of(accepted, nonCrossing, rested)));
    assertThrows(
        IllegalArgumentException.class, () -> batch(List.of(accepted, second, first, rested)));

    MatchingEvent.SelfTradePrevented laterSequenceAtSamePrice =
        prevented(
            new AcceptanceSequence(2),
            new OrderId(13),
            takerSequence,
            99,
            1,
            SelfTradePreventionPolicy.CANCEL_MAKER,
            1,
            0);
    assertThrows(
        IllegalArgumentException.class,
        () -> batch(List.of(accepted, laterSequenceAtSamePrice, first, rested)));
  }

  @Test
  void oneMakerCannotAppearAsBothTradeAndStpWithinOneTakerScan() {
    AcceptanceSequence takerSequence = new AcceptanceSequence(3);
    MatchingEvent.Accepted accepted =
        accepted(
            takerSequence,
            Side.BUY,
            100,
            4,
            ExecutionPolicy.GTC,
            SelfTradePreventionPolicy.CANCEL_MAKER);
    MatchingEvent.Trade trade =
        new MatchingEvent.Trade(
            MAKER_SEQUENCE,
            MAKER_ID,
            takerSequence,
            TAKER_ID,
            new PriceTicks(99),
            new QuantityLots(1),
            RULES,
            RULES,
            RULES);
    MatchingEvent.SelfTradePrevented prevented =
        prevented(
            MAKER_SEQUENCE,
            MAKER_ID,
            takerSequence,
            100,
            2,
            SelfTradePreventionPolicy.CANCEL_MAKER,
            2,
            0);
    MatchingEvent.Rested rested =
        rested(takerSequence, Side.BUY, 100, 3, SelfTradePreventionPolicy.CANCEL_MAKER);

    assertThrows(
        IllegalArgumentException.class, () -> batch(List.of(accepted, trade, prevented, rested)));
  }

  private static MatchingEvent.Accepted accepted(
      ExecutionPolicy executionPolicy, SelfTradePreventionPolicy stpPolicy) {
    return accepted(TAKER_SEQUENCE, Side.BUY, 100, 3, executionPolicy, stpPolicy);
  }

  private static MatchingEvent.Accepted accepted(
      AcceptanceSequence takerSequence,
      Side side,
      long price,
      long quantity,
      ExecutionPolicy executionPolicy,
      SelfTradePreventionPolicy stpPolicy) {
    return new MatchingEvent.Accepted(
        takerSequence,
        TAKER_ID,
        side,
        new PriceTicks(price),
        new QuantityLots(quantity),
        executionPolicy,
        RULES,
        7,
        stpPolicy);
  }

  private static MatchingEvent.SelfTradePrevented prevented(
      SelfTradePreventionPolicy policy, long makerCanceled, long takerCanceled) {
    return prevented(
        MAKER_SEQUENCE,
        MAKER_ID,
        TAKER_SEQUENCE,
        PRICE.value(),
        2,
        policy,
        makerCanceled,
        takerCanceled);
  }

  private static MatchingEvent.SelfTradePrevented prevented(
      AcceptanceSequence makerSequence,
      OrderId makerOrderId,
      AcceptanceSequence takerSequence,
      long makerPrice,
      long wouldTrade,
      SelfTradePreventionPolicy policy,
      long makerCanceled,
      long takerCanceled) {
    return new MatchingEvent.SelfTradePrevented(
        makerSequence,
        makerOrderId,
        takerSequence,
        TAKER_ID,
        new PriceTicks(makerPrice),
        new QuantityLots(wouldTrade),
        7,
        policy,
        makerCanceled,
        takerCanceled,
        RULES,
        RULES,
        RULES);
  }

  private static MatchingEvent.Rested rested(
      AcceptanceSequence takerSequence,
      Side side,
      long price,
      long remaining,
      SelfTradePreventionPolicy policy) {
    return new MatchingEvent.Rested(
        takerSequence,
        TAKER_ID,
        side,
        new PriceTicks(price),
        new QuantityLots(remaining),
        RULES,
        7,
        policy);
  }

  private static MatchingEvent.RemainderCanceled iocRemainder(long quantity) {
    return new MatchingEvent.RemainderCanceled(
        TAKER_SEQUENCE,
        TAKER_ID,
        Side.BUY,
        PRICE,
        new QuantityLots(quantity),
        RemainderCancelReason.IOC_REMAINDER,
        RULES);
  }

  private static ExecutionBatch batch(List<MatchingEvent> events) {
    return new ExecutionBatch(
        events,
        new OrderBookSnapshot(List.of(), List.of()),
        new MarketExecutionContext(RULES, 0, new ApplicationSequence(1), MarketMode.OPEN));
  }
}
