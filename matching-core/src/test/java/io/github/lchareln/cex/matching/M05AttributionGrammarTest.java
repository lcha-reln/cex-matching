package io.github.lchareln.cex.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class M05AttributionGrammarTest {
  @Test
  void m04CompatibilityConstructorsNormalizeAttributionToBootstrap() {
    RuleSetIdentity bootstrap = MarketRuleSetArtifact.bootstrapIdentity();
    MatchingEvent.Accepted accepted =
        new MatchingEvent.Accepted(
            sequence(1), orderId(1), Side.BUY, price(100), quantity(2), ExecutionPolicy.GTC);
    MatchingEvent.Rested rested =
        new MatchingEvent.Rested(sequence(1), orderId(1), Side.BUY, price(100), quantity(2));
    MatchingEvent.Trade trade =
        new MatchingEvent.Trade(
            sequence(2), orderId(2), sequence(1), orderId(1), price(99), quantity(2));
    MatchingEvent.RemainderCanceled remainder =
        new MatchingEvent.RemainderCanceled(
            sequence(1),
            orderId(1),
            Side.BUY,
            price(100),
            quantity(1),
            RemainderCancelReason.IOC_REMAINDER);
    MatchingEvent.Canceled canceled =
        new MatchingEvent.Canceled(sequence(1), orderId(1), Side.BUY, price(100), quantity(2));
    OrderBookSnapshot.RestingOrderView view =
        new OrderBookSnapshot.RestingOrderView(sequence(1), orderId(1), quantity(2));

    assertEquals(bootstrap, accepted.admissionRuleSet());
    assertEquals(bootstrap, rested.admissionRuleSet());
    assertEquals(bootstrap, trade.makerAdmissionRuleSet());
    assertEquals(bootstrap, trade.takerAdmissionRuleSet());
    assertEquals(bootstrap, trade.executionRuleSet());
    assertEquals(bootstrap, remainder.admissionRuleSet());
    assertEquals(bootstrap, canceled.admissionRuleSet());
    assertEquals(bootstrap, canceled.executionRuleSet());
    assertEquals(bootstrap, view.admissionRuleSet());

    ExecutionBatch legacyBatch =
        new ExecutionBatch(List.of(accepted, rested), new OrderBookSnapshot(List.of(), List.of()));
    assertEquals(bootstrap, legacyBatch.context().activeRuleSet());
    assertEquals(0, legacyBatch.context().controlRevision());
    assertEquals(Optional.empty(), legacyBatch.context().applicationSequence());
  }

  @Test
  void executionBatchFailsClosedWhenEventAndBatchRuleAttributionDisagree() {
    RuleSetIdentity versionOne = artifact(1, 90, 110).identity();
    MarketExecutionContext context =
        new MarketExecutionContext(versionOne, 1, new ApplicationSequence(7));
    OrderBookSnapshot empty = new OrderBookSnapshot(List.of(), List.of());

    MatchingEvent.Accepted bootstrapAccepted =
        new MatchingEvent.Accepted(
            sequence(1), orderId(1), Side.BUY, price(100), quantity(1), ExecutionPolicy.GTC);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ExecutionBatch(
                List.of(bootstrapAccepted, rested(bootstrapAccepted)), empty, context));

    MatchingEvent.Accepted governedAccepted =
        new MatchingEvent.Accepted(
            sequence(1),
            orderId(1),
            Side.BUY,
            price(100),
            quantity(1),
            ExecutionPolicy.FOK,
            versionOne);
    MatchingEvent.Trade wrongExecution =
        new MatchingEvent.Trade(
            sequence(2),
            orderId(2),
            sequence(1),
            orderId(1),
            price(99),
            quantity(1),
            MarketRuleSetArtifact.bootstrapIdentity(),
            versionOne,
            MarketRuleSetArtifact.bootstrapIdentity());
    assertThrows(
        IllegalArgumentException.class,
        () -> new ExecutionBatch(List.of(governedAccepted, wrongExecution), empty, context));

    MatchingEvent.Canceled wrongCancelExecution =
        new MatchingEvent.Canceled(
            sequence(1),
            orderId(1),
            Side.BUY,
            price(100),
            quantity(1),
            MarketRuleSetArtifact.bootstrapIdentity(),
            MarketRuleSetArtifact.bootstrapIdentity());
    assertThrows(
        IllegalArgumentException.class,
        () -> new ExecutionBatch(List.of(wrongCancelExecution), empty, context));
  }

  @Test
  void controlValuesBindEventSequenceActiveIdentityAndActivationFence() {
    MarketRuleSetArtifact active = artifact(1, 90, 110);
    ActivationFence fence =
        new ActivationFence(new ApplicationSequence(5), 1, new AcceptanceSequence(3));
    MarketControlSnapshot after =
        new MarketControlSnapshot(
            active,
            Optional.empty(),
            1,
            Optional.of(fence),
            new ApplicationSequence(6),
            new AcceptanceSequence(3));
    MarketControlEvent.RuleSetActivated event =
        new MarketControlEvent.RuleSetActivated(
            new ApplicationSequence(5),
            MarketRuleSetArtifact.bootstrapIdentity(),
            active.identity(),
            fence);

    new MarketControlBatch(List.of(event), after, new OrderBookSnapshot(List.of(), List.of()));

    MarketControlSnapshot wrongNext =
        new MarketControlSnapshot(
            active,
            Optional.empty(),
            1,
            Optional.of(fence),
            new ApplicationSequence(7),
            new AcceptanceSequence(3));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MarketControlBatch(
                List.of(event), wrongNext, new OrderBookSnapshot(List.of(), List.of())));
  }

  private static MatchingEvent.Rested rested(MatchingEvent.Accepted accepted) {
    return new MatchingEvent.Rested(
        accepted.sequence(),
        accepted.orderId(),
        accepted.side(),
        accepted.priceTicks(),
        accepted.quantityLots(),
        accepted.admissionRuleSet());
  }

  private static MarketRuleSetArtifact artifact(long version, long lower, long upper) {
    MarketRuleSetArtifact unhashed =
        new MarketRuleSetArtifact(
            version,
            lower,
            upper,
            "sha256:0000000000000000000000000000000000000000000000000000000000000000");
    return new MarketRuleSetArtifact(version, lower, upper, unhashed.computedContentHash());
  }

  private static AcceptanceSequence sequence(long value) {
    return new AcceptanceSequence(value);
  }

  private static OrderId orderId(long value) {
    return new OrderId(value);
  }

  private static PriceTicks price(long value) {
    return new PriceTicks(value);
  }

  private static QuantityLots quantity(long value) {
    return new QuantityLots(value);
  }
}
