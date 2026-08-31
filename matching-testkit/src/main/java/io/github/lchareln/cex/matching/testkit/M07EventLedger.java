package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.reference.M06MarketRuleSetArtifact;
import io.github.lchareln.cex.matching.reference.M06RuleSetIdentity;
import io.github.lchareln.cex.matching.reference.M07ReferenceCommand;
import io.github.lchareln.cex.matching.reference.M07SemanticBook;
import io.github.lchareln.cex.matching.reference.M07SemanticEvent;
import io.github.lchareln.cex.matching.reference.M07SemanticMarketState;
import io.github.lchareln.cex.matching.reference.M07SemanticOutcome;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Third M07 oracle: reconstructs state only from commands and emitted events, without inspecting
 * either candidate implementation.
 */
final class M07EventLedger {
  private BigInteger nextApplication = BigInteger.ONE;
  private BigInteger nextAcceptance = BigInteger.ONE;
  private BigInteger controlRevision = BigInteger.ZERO;
  private M06MarketRuleSetArtifact active = M06MarketRuleSetArtifact.bootstrap();
  private M06MarketRuleSetArtifact prepared;
  private M07SemanticMarketState.ActivationFence activationFence;
  private String marketMode = "OPEN";
  private BigInteger modeRevision = BigInteger.ZERO;
  private M07SemanticMarketState.ModeTransitionFence modeFence;
  private M07SemanticMarketState.MassCancelFence massCancelFence;
  private final Map<BigInteger, Order> orders = new LinkedHashMap<>();

  void apply(M07ReferenceCommand command, M07SemanticOutcome outcome) {
    require(
        outcome.applicationSequence().equals(nextApplication),
        "event ledger observed a non-contiguous application sequence");
    Batch batch = new Batch();
    for (M07SemanticEvent event : outcome.events()) {
      applyEvent(command, event, batch);
    }
    finalizeBatch(command, batch);
    nextApplication = nextApplication.add(BigInteger.ONE);
    require(
        snapshot().equals(outcome.stateAfter()), "event ledger and candidate snapshot disagree");
  }

  M07SemanticMarketState snapshot() {
    return new M07SemanticMarketState(
        nextApplication,
        nextAcceptance,
        controlRevision,
        active,
        Optional.ofNullable(prepared),
        Optional.ofNullable(activationFence),
        marketMode,
        modeRevision,
        Optional.ofNullable(modeFence),
        Optional.ofNullable(massCancelFence),
        book());
  }

  private void applyEvent(M07ReferenceCommand command, M07SemanticEvent event, Batch batch) {
    switch (event) {
      case M07SemanticEvent.Rejected ignored -> batch.rejected = true;
      case M07SemanticEvent.PlaceRejected ignored -> batch.rejected = true;
      case M07SemanticEvent.CancelRejected ignored -> batch.rejected = true;
      case M07SemanticEvent.Accepted accepted -> accepted(accepted, batch);
      case M07SemanticEvent.Trade trade -> trade(trade);
      case M07SemanticEvent.SelfTradePrevented prevented -> selfTradePrevented(prevented);
      case M07SemanticEvent.Rested rested -> rested(rested);
      case M07SemanticEvent.RemainderCanceled canceled -> terminate(canceled.orderId());
      case M07SemanticEvent.Canceled canceled -> terminate(canceled.orderId());
      case M07SemanticEvent.RuleSetPrepared preparedEvent -> prepared(command, preparedEvent);
      case M07SemanticEvent.PrepareRuleSetRejected ignored -> batch.rejected = true;
      case M07SemanticEvent.RuleSetActivated activatedEvent -> activated(command, activatedEvent);
      case M07SemanticEvent.ActivateRuleSetRejected ignored -> batch.rejected = true;
      case M07SemanticEvent.ModeChanged changedEvent -> modeChanged(changedEvent);
      case M07SemanticEvent.ModeChangeRejected ignored -> batch.rejected = true;
      case M07SemanticEvent.MassCancelStarted started -> massStarted(started, batch);
      case M07SemanticEvent.MassOrderCanceled canceled -> massCanceled(canceled, batch);
      case M07SemanticEvent.MassCancelCompleted completed -> massCompleted(completed, batch);
      case M07SemanticEvent.MassCancelRejected ignored -> batch.rejected = true;
    }
  }

  private void accepted(M07SemanticEvent.Accepted event, Batch batch) {
    require(
        event.acceptanceSequence().equals(nextAcceptance), "acceptance sequence is not contiguous");
    require(!orders.containsKey(event.orderId()), "event ledger observed duplicate acceptance");
    require(event.admissionRuleSet().equals(active.identity()), "accepted admission rule changed");
    orders.put(
        event.orderId(),
        new Order(
            event.acceptanceSequence(),
            event.orderId(),
            event.side(),
            event.priceTicks(),
            event.quantityLots(),
            event.admissionRuleSet(),
            event.participantGroupId(),
            event.stpPolicy(),
            false,
            false));
    nextAcceptance = nextAcceptance.add(BigInteger.ONE);
    batch.acceptedOrder = event.orderId();
  }

  private void trade(M07SemanticEvent.Trade event) {
    Order maker = requiredOrder(event.makerOrderId());
    Order taker = requiredOrder(event.takerOrderId());
    require(maker.resting, "trade maker was not resting in event ledger");
    require(
        maker.acceptanceSequence.equals(event.makerSequence())
            && taker.acceptanceSequence.equals(event.takerSequence()),
        "trade sequence attribution changed");
    require(
        maker.admissionRule.equals(event.makerAdmissionRuleSet())
            && taker.admissionRule.equals(event.takerAdmissionRuleSet())
            && event.executionRuleSet().equals(active.identity()),
        "trade rule attribution changed");
    maker.remaining = subtract(maker.remaining, event.quantityLots());
    taker.remaining = subtract(taker.remaining, event.quantityLots());
    if (maker.remaining.signum() == 0) {
      maker.resting = false;
      maker.terminal = true;
    }
  }

  private void rested(M07SemanticEvent.Rested event) {
    Order order = requiredOrder(event.orderId());
    require(order.acceptanceSequence.equals(event.acceptanceSequence()), "rested sequence changed");
    require(order.remaining.equals(event.remainingQuantityLots()), "rested quantity changed");
    require(order.admissionRule.equals(event.admissionRuleSet()), "rested rule changed");
    require(
        order.participantGroupId.equals(event.participantGroupId())
            && order.stpPolicy.equals(event.stpPolicy()),
        "rested STP attribution changed");
    require(order.remaining.signum() > 0, "zero quantity rested");
    order.resting = true;
  }

  private void selfTradePrevented(M07SemanticEvent.SelfTradePrevented event) {
    Order maker = requiredOrder(event.makerOrderId());
    Order taker = requiredOrder(event.takerOrderId());
    require(maker.resting, "STP maker was not resting in event ledger");
    require(
        maker.acceptanceSequence.equals(event.makerSequence())
            && taker.acceptanceSequence.equals(event.takerSequence()),
        "STP sequence attribution changed");
    require(
        maker.participantGroupId.signum() > 0
            && maker.participantGroupId.equals(taker.participantGroupId)
            && taker.participantGroupId.equals(event.participantGroupId())
            && taker.stpPolicy.equals(event.stpPolicy()),
        "STP participant attribution changed");
    require(
        maker.admissionRule.equals(event.makerAdmissionRuleSet())
            && taker.admissionRule.equals(event.takerAdmissionRuleSet())
            && active.identity().equals(event.executionRuleSet()),
        "STP rule attribution changed");
    require(
        event.wouldTradeQuantityLots().equals(maker.remaining.min(taker.remaining)),
        "STP would-trade quantity changed");
    if (event.makerCanceledQuantityLots().signum() > 0) {
      require(
          event.makerCanceledQuantityLots().equals(maker.remaining),
          "STP maker did not cancel its complete remainder");
      terminate(maker.orderId);
    }
    if (event.takerCanceledQuantityLots().signum() > 0) {
      require(
          event.takerCanceledQuantityLots().equals(taker.remaining),
          "STP taker did not cancel its complete remainder");
      terminate(taker.orderId);
    }
  }

  private void prepared(M07ReferenceCommand command, M07SemanticEvent.RuleSetPrepared event) {
    require(
        command instanceof M07ReferenceCommand.PrepareRuleSet, "prepare event command mismatch");
    M07ReferenceCommand.PrepareRuleSet prepare = (M07ReferenceCommand.PrepareRuleSet) command;
    require(event.identity().equals(prepare.artifact().identity()), "prepared identity changed");
    if (event.status() != M07SemanticEvent.PrepareStatus.ALREADY_PREPARED) {
      prepared = prepare.artifact();
    }
  }

  private void activated(M07ReferenceCommand command, M07SemanticEvent.RuleSetActivated event) {
    require(
        command instanceof M07ReferenceCommand.ActivateRuleSet, "activate event command mismatch");
    require(prepared != null, "activation occurred without prepared artifact");
    require(event.previousActive().equals(active.identity()), "previous active rule changed");
    require(event.active().equals(prepared.identity()), "activated rule changed");
    active = prepared;
    prepared = null;
    controlRevision = controlRevision.add(BigInteger.ONE);
    activationFence = event.fence();
    require(
        activationFence.applicationSequence().equals(nextApplication)
            && activationFence.controlRevision().equals(controlRevision)
            && activationFence.firstAcceptanceSequence().equals(nextAcceptance),
        "activation fence changed");
  }

  private void modeChanged(M07SemanticEvent.ModeChanged event) {
    require(event.previousMode().equals(marketMode), "mode transition previous mode changed");
    require(!event.activeMode().equals(marketMode), "mode transition did not change mode");
    modeRevision = modeRevision.add(BigInteger.ONE);
    marketMode = event.activeMode();
    modeFence = event.fence();
    require(
        modeFence.applicationSequence().equals(nextApplication)
            && modeFence.modeRevision().equals(modeRevision)
            && modeFence.previousMode().equals(event.previousMode())
            && modeFence.activeMode().equals(event.activeMode())
            && modeFence.nextAcceptanceSequence().equals(nextAcceptance),
        "mode transition fence changed");
  }

  private void massStarted(M07SemanticEvent.MassCancelStarted event, Batch batch) {
    require(!batch.massStarted, "duplicate Mass Cancel Started event");
    require("HALTED".equals(marketMode), "Mass Cancel started outside HALTED");
    require(event.marketMode().equals(marketMode), "Mass Cancel start mode changed");
    require(event.modeRevision().equals(modeRevision), "Mass Cancel start revision changed");
    long resting = orders.values().stream().filter(order -> order.resting).count();
    require(
        event.restingOrderCount().equals(BigInteger.valueOf(resting)),
        "Mass Cancel start count changed");
    batch.massStarted = true;
    batch.massOperator = event.operatorId();
    batch.massExpectedCount = event.restingOrderCount();
  }

  private void massCanceled(M07SemanticEvent.MassOrderCanceled event, Batch batch) {
    require(batch.massStarted && !batch.massCompleted, "Mass Cancel order event outside batch");
    require(event.operatorId().equals(batch.massOperator), "Mass Cancel operator changed");
    Order order = requiredOrder(event.orderId());
    require(order.resting, "Mass Cancel repeated a terminal order");
    require(
        order.acceptanceSequence.equals(event.acceptanceSequence()),
        "Mass Cancel sequence changed");
    require(
        order.side.equals(event.side()) && order.price.equals(event.priceTicks()),
        "Mass Cancel order identity changed");
    require(order.remaining.equals(event.canceledQuantityLots()), "Mass Cancel quantity changed");
    require(
        order.admissionRule.equals(event.admissionRuleSet())
            && active.identity().equals(event.executionRuleSet()),
        "Mass Cancel rule attribution changed");
    require(
        batch.lastMassSequence == null
            || batch.lastMassSequence.compareTo(event.acceptanceSequence()) < 0,
        "Mass Cancel events are not in global acceptance order");
    if (batch.firstMassSequence == null) {
      batch.firstMassSequence = event.acceptanceSequence();
    }
    batch.lastMassSequence = event.acceptanceSequence();
    batch.massCanceled = batch.massCanceled.add(BigInteger.ONE);
    terminate(event.orderId());
  }

  private void massCompleted(M07SemanticEvent.MassCancelCompleted event, Batch batch) {
    require(batch.massStarted && !batch.massCompleted, "Mass Cancel completion outside batch");
    require(
        event.operatorId().equals(batch.massOperator), "Mass Cancel completion operator changed");
    require(event.marketMode().equals(marketMode), "Mass Cancel completion mode changed");
    require(event.modeRevision().equals(modeRevision), "Mass Cancel completion revision changed");
    require(
        event.canceledOrderCount().equals(batch.massExpectedCount)
            && event.canceledOrderCount().equals(batch.massCanceled),
        "Mass Cancel terminal count changed");
    require(
        orders.values().stream().noneMatch(order -> order.resting),
        "Mass Cancel completed with a resting order");
    batch.massCompleted = true;
    massCancelFence =
        new M07SemanticMarketState.MassCancelFence(
            nextApplication,
            modeRevision,
            event.operatorId(),
            event.canceledOrderCount(),
            Optional.ofNullable(batch.firstMassSequence),
            Optional.ofNullable(batch.lastMassSequence));
  }

  private void finalizeBatch(M07ReferenceCommand command, Batch batch) {
    if (batch.acceptedOrder != null) {
      Order order = requiredOrder(batch.acceptedOrder);
      if (!order.resting && order.remaining.signum() == 0) {
        order.terminal = true;
      }
    }
    if (command instanceof M07ReferenceCommand.MassCancel) {
      require(
          batch.rejected || (batch.massStarted && batch.massCompleted),
          "Mass Cancel event grammar is incomplete");
    } else {
      require(!batch.massStarted && !batch.massCompleted, "Mass Cancel events escaped command");
    }
  }

  private M07SemanticBook book() {
    Comparator<BigInteger> descending = Comparator.reverseOrder();
    Map<BigInteger, List<M07SemanticBook.RestingOrder>> bids = new TreeMap<>(descending);
    Map<BigInteger, List<M07SemanticBook.RestingOrder>> asks = new TreeMap<>();
    orders.values().stream()
        .filter(order -> order.resting)
        .sorted(Comparator.comparing(order -> order.acceptanceSequence))
        .forEach(
            order -> {
              Map<BigInteger, List<M07SemanticBook.RestingOrder>> levels =
                  "BUY".equals(order.side) ? bids : asks;
              levels
                  .computeIfAbsent(order.price, ignored -> new ArrayList<>())
                  .add(
                      new M07SemanticBook.RestingOrder(
                          order.acceptanceSequence,
                          order.orderId,
                          order.remaining,
                          order.admissionRule,
                          order.participantGroupId,
                          order.stpPolicy));
            });
    return new M07SemanticBook(levels("BUY", bids), levels("SELL", asks));
  }

  private static List<M07SemanticBook.PriceLevel> levels(
      String side, Map<BigInteger, List<M07SemanticBook.RestingOrder>> values) {
    List<M07SemanticBook.PriceLevel> result = new ArrayList<>();
    values.forEach(
        (price, orders) ->
            result.add(new M07SemanticBook.PriceLevel(side, price, List.copyOf(orders))));
    return List.copyOf(result);
  }

  private Order requiredOrder(BigInteger orderId) {
    Order order = orders.get(orderId);
    require(order != null, "event ledger referenced an unknown order");
    return order;
  }

  private void terminate(BigInteger orderId) {
    Order order = requiredOrder(orderId);
    order.resting = false;
    order.terminal = true;
    order.remaining = BigInteger.ZERO;
  }

  private static BigInteger subtract(BigInteger value, BigInteger quantity) {
    BigInteger result = value.subtract(quantity);
    require(result.signum() >= 0, "event quantity exceeded active remainder");
    return result;
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new LedgerFailure(message);
    }
  }

  static final class LedgerFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    LedgerFailure(String message) {
      super(message);
    }
  }

  private static final class Batch {
    private boolean rejected;
    private BigInteger acceptedOrder;
    private boolean massStarted;
    private boolean massCompleted;
    private String massOperator;
    private BigInteger massExpectedCount;
    private BigInteger massCanceled = BigInteger.ZERO;
    private BigInteger firstMassSequence;
    private BigInteger lastMassSequence;
  }

  private static final class Order {
    private final BigInteger acceptanceSequence;
    private final BigInteger orderId;
    private final String side;
    private final BigInteger price;
    private BigInteger remaining;
    private final M06RuleSetIdentity admissionRule;
    private final BigInteger participantGroupId;
    private final String stpPolicy;
    private boolean resting;
    private boolean terminal;

    private Order(
        BigInteger acceptanceSequence,
        BigInteger orderId,
        String side,
        BigInteger price,
        BigInteger remaining,
        M06RuleSetIdentity admissionRule,
        BigInteger participantGroupId,
        String stpPolicy,
        boolean resting,
        boolean terminal) {
      this.acceptanceSequence = acceptanceSequence;
      this.orderId = orderId;
      this.side = side;
      this.price = price;
      this.remaining = remaining;
      this.admissionRule = admissionRule;
      this.participantGroupId = participantGroupId;
      this.stpPolicy = stpPolicy;
      this.resting = resting;
      this.terminal = terminal;
    }
  }
}
