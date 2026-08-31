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

  private static MatchingEvent.Accepted accepted(
      ExecutionPolicy executionPolicy, SelfTradePreventionPolicy stpPolicy) {
    return new MatchingEvent.Accepted(
        TAKER_SEQUENCE,
        TAKER_ID,
        Side.BUY,
        PRICE,
        new QuantityLots(3),
        executionPolicy,
        RULES,
        7,
        stpPolicy);
  }

  private static MatchingEvent.SelfTradePrevented prevented(
      SelfTradePreventionPolicy policy, long makerCanceled, long takerCanceled) {
    return new MatchingEvent.SelfTradePrevented(
        MAKER_SEQUENCE,
        MAKER_ID,
        TAKER_SEQUENCE,
        TAKER_ID,
        PRICE,
        new QuantityLots(2),
        7,
        policy,
        makerCanceled,
        takerCanceled,
        RULES,
        RULES,
        RULES);
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
