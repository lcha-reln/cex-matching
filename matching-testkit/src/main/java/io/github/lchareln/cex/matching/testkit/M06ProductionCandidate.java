package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.AcceptanceSequence;
import io.github.lchareln.cex.matching.ActivateRuleSet;
import io.github.lchareln.cex.matching.CancelOrderInput;
import io.github.lchareln.cex.matching.ChangeMarketMode;
import io.github.lchareln.cex.matching.ExecutionBatch;
import io.github.lchareln.cex.matching.GovernedPlaceLimitOrderRequest;
import io.github.lchareln.cex.matching.MarketControlBatch;
import io.github.lchareln.cex.matching.MarketControlEvent;
import io.github.lchareln.cex.matching.MarketControlSnapshot;
import io.github.lchareln.cex.matching.MarketMode;
import io.github.lchareln.cex.matching.MarketRuleSetArtifact;
import io.github.lchareln.cex.matching.MassCancel;
import io.github.lchareln.cex.matching.MassCancelBatch;
import io.github.lchareln.cex.matching.MassCancelEvent;
import io.github.lchareln.cex.matching.MatchingEvent;
import io.github.lchareln.cex.matching.OperatorId;
import io.github.lchareln.cex.matching.OrderBookSnapshot;
import io.github.lchareln.cex.matching.PlaceLimitOrderInput;
import io.github.lchareln.cex.matching.PlaceLimitOrderRequest;
import io.github.lchareln.cex.matching.PrepareRuleSet;
import io.github.lchareln.cex.matching.RuleSetIdentity;
import io.github.lchareln.cex.matching.RuleSetVersion;
import io.github.lchareln.cex.matching.SingleInstrumentMatchingEngine;
import io.github.lchareln.cex.matching.reference.M06MarketRuleSetArtifact;
import io.github.lchareln.cex.matching.reference.M06ReferenceCommand;
import io.github.lchareln.cex.matching.reference.M06RuleSetIdentity;
import io.github.lchareln.cex.matching.reference.M06SemanticBook;
import io.github.lchareln.cex.matching.reference.M06SemanticEvent;
import io.github.lchareln.cex.matching.reference.M06SemanticMarketState;
import io.github.lchareln.cex.matching.reference.M06SemanticOutcome;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Projects the production matcher into the neutral M06 semantic vocabulary. */
final class M06ProductionCandidate implements M06Candidate {
  private final SingleInstrumentMatchingEngine engine = new SingleInstrumentMatchingEngine();

  @Override
  public M06SemanticOutcome apply(M06ReferenceCommand command) {
    return switch (command) {
      case M06ReferenceCommand.Place place -> applyPlace(place);
      case M06ReferenceCommand.Cancel cancel ->
          execution(engine.cancel(new CancelOrderInput(cancel.instrumentId(), cancel.orderId())));
      case M06ReferenceCommand.PrepareRuleSet prepare -> applyPrepare(prepare);
      case M06ReferenceCommand.ActivateRuleSet activate -> applyActivate(activate);
      case M06ReferenceCommand.ChangeMarketMode change -> applyChangeMode(change);
      case M06ReferenceCommand.MassCancel massCancel -> applyMassCancel(massCancel);
    };
  }

  @Override
  public M06SemanticMarketState snapshot() {
    return state(engine.marketControlSnapshot(), engine.snapshot());
  }

  private M06SemanticOutcome applyPlace(M06ReferenceCommand.Place place) {
    PlaceLimitOrderInput input =
        new PlaceLimitOrderInput(
            place.instrumentId(),
            place.orderId(),
            place.side(),
            place.priceTicks(),
            place.quantityLots());
    PlaceLimitOrderRequest request = new PlaceLimitOrderRequest(input, place.executionPolicy());
    if (place.entrypoint() == M06ReferenceCommand.PlaceEntrypoint.GOVERNED) {
      return execution(
          engine.placeGoverned(
              new GovernedPlaceLimitOrderRequest(request, identity(place.expectedRuleSet()))));
    }
    return execution(engine.placeRequest(request));
  }

  private M06SemanticOutcome applyPrepare(M06ReferenceCommand.PrepareRuleSet command) {
    MarketControlSnapshot before = engine.marketControlSnapshot();
    MarketControlBatch batch =
        engine.prepareRuleSet(
            new PrepareRuleSet(identity(command.expectedActive()), artifact(command.artifact())));
    return control(batch, before);
  }

  private M06SemanticOutcome applyActivate(M06ReferenceCommand.ActivateRuleSet command) {
    MarketControlSnapshot before = engine.marketControlSnapshot();
    MarketControlBatch batch =
        engine.activateRuleSet(
            new ActivateRuleSet(
                application(command.expectedApplicationSequence()),
                identity(command.expectedActive()),
                identity(command.target())));
    return control(batch, before);
  }

  private M06SemanticOutcome applyChangeMode(M06ReferenceCommand.ChangeMarketMode command) {
    MarketControlSnapshot before = engine.marketControlSnapshot();
    MarketControlBatch batch =
        engine.changeMarketMode(
            new ChangeMarketMode(
                application(command.expectedApplicationSequence()),
                MarketMode.valueOf(command.expectedMode()),
                MarketMode.valueOf(command.targetMode()),
                new OperatorId(command.operatorId())));
    return control(batch, before);
  }

  private M06SemanticOutcome applyMassCancel(M06ReferenceCommand.MassCancel command) {
    MassCancelBatch batch =
        engine.massCancel(
            new MassCancel(
                application(command.expectedApplicationSequence()),
                MarketMode.valueOf(command.expectedMode()),
                new OperatorId(command.operatorId())));
    BigInteger applicationSequence = value(batch.events().getFirst().applicationSequence().value());
    List<M06SemanticEvent> events =
        batch.events().stream().map(M06ProductionCandidate::event).toList();
    return new M06SemanticOutcome(
        applicationSequence, events, state(batch.controlAfter(), batch.bookAfter()));
  }

  private M06SemanticOutcome execution(ExecutionBatch batch) {
    BigInteger applicationSequence =
        value(batch.context().applicationSequence().orElseThrow().value());
    M06RuleSetIdentity executionRuleSet = identity(batch.context().activeRuleSet());
    List<M06SemanticEvent> events =
        batch.events().stream().map(event -> event(event, executionRuleSet)).toList();
    return new M06SemanticOutcome(
        applicationSequence, events, state(engine.marketControlSnapshot(), batch.bookAfter()));
  }

  private M06SemanticOutcome control(MarketControlBatch batch, MarketControlSnapshot before) {
    MarketControlEvent event = batch.events().getFirst();
    Optional<M06RuleSetIdentity> superseded =
        event instanceof MarketControlEvent.RuleSetPrepared prepared
                && prepared.status()
                    == io.github.lchareln.cex.matching.PrepareRuleSetStatus.SUPERSEDED
            ? before.preparedIdentity().map(M06ProductionCandidate::identity)
            : Optional.empty();
    return new M06SemanticOutcome(
        value(event.applicationSequence().value()),
        List.of(event(event, superseded)),
        state(batch.controlAfter(), batch.bookAfter()));
  }

  private static M06SemanticEvent event(MatchingEvent event, M06RuleSetIdentity executionRuleSet) {
    return switch (event) {
      case MatchingEvent.Rejected rejected ->
          new M06SemanticEvent.Rejected(rejected.code().name(), rejected.field());
      case MatchingEvent.PlaceRejected rejected ->
          new M06SemanticEvent.PlaceRejected(
              value(rejected.orderId().value()), rejected.code().name(), executionRuleSet);
      case MatchingEvent.CancelRejected rejected ->
          new M06SemanticEvent.CancelRejected(
              value(rejected.orderId().value()), rejected.code().name(), executionRuleSet);
      case MatchingEvent.Accepted accepted ->
          new M06SemanticEvent.Accepted(
              value(accepted.sequence().value()),
              value(accepted.orderId().value()),
              accepted.side().name(),
              value(accepted.priceTicks().value()),
              value(accepted.quantityLots().value()),
              accepted.executionPolicy().name(),
              identity(accepted.admissionRuleSet()),
              executionRuleSet);
      case MatchingEvent.Trade trade ->
          new M06SemanticEvent.Trade(
              value(trade.makerSequence().value()),
              value(trade.makerOrderId().value()),
              value(trade.takerSequence().value()),
              value(trade.takerOrderId().value()),
              value(trade.priceTicks().value()),
              value(trade.quantityLots().value()),
              identity(trade.makerAdmissionRuleSet()),
              identity(trade.takerAdmissionRuleSet()),
              identity(trade.executionRuleSet()));
      case MatchingEvent.Rested rested ->
          new M06SemanticEvent.Rested(
              value(rested.sequence().value()),
              value(rested.orderId().value()),
              rested.side().name(),
              value(rested.priceTicks().value()),
              value(rested.remainingQuantityLots().value()),
              identity(rested.admissionRuleSet()),
              executionRuleSet);
      case MatchingEvent.RemainderCanceled canceled ->
          new M06SemanticEvent.RemainderCanceled(
              value(canceled.sequence().value()),
              value(canceled.orderId().value()),
              canceled.side().name(),
              value(canceled.priceTicks().value()),
              value(canceled.canceledQuantityLots().value()),
              canceled.reason().name(),
              identity(canceled.admissionRuleSet()),
              executionRuleSet);
      case MatchingEvent.SelfTradePrevented prevented ->
          throw new IllegalStateException(
              "M06 projection received an M07 STP event: " + prevented.takerOrderId().value());
      case MatchingEvent.Canceled canceled ->
          new M06SemanticEvent.Canceled(
              value(canceled.sequence().value()),
              value(canceled.orderId().value()),
              canceled.side().name(),
              value(canceled.priceTicks().value()),
              value(canceled.canceledQuantityLots().value()),
              identity(canceled.admissionRuleSet()),
              identity(canceled.executionRuleSet()));
    };
  }

  private static M06SemanticEvent event(
      MarketControlEvent event, Optional<M06RuleSetIdentity> superseded) {
    return switch (event) {
      case MarketControlEvent.RuleSetPrepared prepared ->
          new M06SemanticEvent.RuleSetPrepared(
              identity(prepared.preparedRuleSet()),
              M06SemanticEvent.PrepareStatus.valueOf(prepared.status().name()),
              superseded);
      case MarketControlEvent.PrepareRejected rejected ->
          new M06SemanticEvent.PrepareRuleSetRejected(rejected.code().name());
      case MarketControlEvent.RuleSetActivated activated ->
          new M06SemanticEvent.RuleSetActivated(
              identity(activated.previousActiveRuleSet()),
              identity(activated.activeRuleSet()),
              fence(activated.activationFence()));
      case MarketControlEvent.ActivateRejected rejected ->
          new M06SemanticEvent.ActivateRuleSetRejected(rejected.code().name());
      case MarketControlEvent.ModeChanged changed ->
          new M06SemanticEvent.ModeChanged(
              changed.operatorId().value(),
              changed.previousMode().name(),
              changed.activeMode().name(),
              fence(changed.transitionFence()));
      case MarketControlEvent.ModeChangeRejected rejected ->
          new M06SemanticEvent.ModeChangeRejected(
              rejected.operatorId().value(),
              rejected.observedMode().name(),
              rejected.targetMode().name(),
              rejected.code().name());
    };
  }

  private static M06SemanticEvent event(MassCancelEvent event) {
    return switch (event) {
      case MassCancelEvent.Started started ->
          new M06SemanticEvent.MassCancelStarted(
              started.operatorId().value(),
              started.marketMode().name(),
              value(started.modeRevision()),
              value(started.restingOrderCount()));
      case MassCancelEvent.OrderCanceled canceled ->
          new M06SemanticEvent.MassOrderCanceled(
              canceled.operatorId().value(),
              value(canceled.sequence().value()),
              value(canceled.orderId().value()),
              canceled.side().name(),
              value(canceled.priceTicks().value()),
              value(canceled.canceledQuantityLots().value()),
              identity(canceled.admissionRuleSet()),
              identity(canceled.executionRuleSet()));
      case MassCancelEvent.Completed completed ->
          new M06SemanticEvent.MassCancelCompleted(
              completed.operatorId().value(),
              completed.marketMode().name(),
              value(completed.modeRevision()),
              value(completed.canceledOrderCount()));
      case MassCancelEvent.Rejected rejected ->
          new M06SemanticEvent.MassCancelRejected(
              rejected.operatorId().value(),
              rejected.observedMode().name(),
              rejected.code().name());
    };
  }

  private static M06SemanticMarketState state(
      MarketControlSnapshot control, OrderBookSnapshot book) {
    return new M06SemanticMarketState(
        value(control.nextApplicationSequence().value()),
        value(control.nextAcceptanceSequence().value()),
        value(control.controlRevision()),
        artifact(control.activeRuleSet()),
        control.preparedRuleSet().map(M06ProductionCandidate::artifact),
        control.lastActivationFence().map(M06ProductionCandidate::fence),
        control.marketMode().name(),
        value(control.modeRevision()),
        control.lastModeTransitionFence().map(M06ProductionCandidate::fence),
        control.lastMassCancelFence().map(M06ProductionCandidate::fence),
        book(book));
  }

  private static M06SemanticBook book(OrderBookSnapshot book) {
    return new M06SemanticBook(levels(book.bids()), levels(book.asks()));
  }

  private static List<M06SemanticBook.PriceLevel> levels(
      List<OrderBookSnapshot.PriceLevel> levels) {
    List<M06SemanticBook.PriceLevel> result = new ArrayList<>(levels.size());
    for (OrderBookSnapshot.PriceLevel level : levels) {
      List<M06SemanticBook.RestingOrder> orders = new ArrayList<>(level.orders().size());
      for (OrderBookSnapshot.RestingOrderView order : level.orders()) {
        orders.add(
            new M06SemanticBook.RestingOrder(
                value(order.sequence().value()),
                value(order.orderId().value()),
                value(order.remainingQuantityLots().value()),
                identity(order.admissionRuleSet())));
      }
      result.add(
          new M06SemanticBook.PriceLevel(
              level.side().name(), value(level.priceTicks().value()), orders));
    }
    return List.copyOf(result);
  }

  private static MarketRuleSetArtifact artifact(M06MarketRuleSetArtifact artifact) {
    return new MarketRuleSetArtifact(
        artifact.schemaVersion(),
        artifact.instrumentId(),
        new RuleSetVersion(artifact.version().longValueExact()),
        new io.github.lchareln.cex.matching.PriceTicks(artifact.lowerInclusive().longValueExact()),
        new io.github.lchareln.cex.matching.PriceTicks(artifact.upperInclusive().longValueExact()),
        artifact.contentHash());
  }

  private static M06MarketRuleSetArtifact artifact(MarketRuleSetArtifact artifact) {
    return new M06MarketRuleSetArtifact(
        artifact.schemaVersion(),
        artifact.instrumentId(),
        value(artifact.version().value()),
        value(artifact.lowerInclusive().value()),
        value(artifact.upperInclusive().value()),
        artifact.contentHash());
  }

  private static RuleSetIdentity identity(M06RuleSetIdentity identity) {
    return new RuleSetIdentity(
        new RuleSetVersion(identity.version().longValueExact()), identity.contentHash());
  }

  private static M06RuleSetIdentity identity(RuleSetIdentity identity) {
    return new M06RuleSetIdentity(value(identity.version().value()), identity.contentHash());
  }

  private static M06SemanticMarketState.ActivationFence fence(
      io.github.lchareln.cex.matching.ActivationFence fence) {
    return new M06SemanticMarketState.ActivationFence(
        value(fence.appliedCommandSequence().value()),
        value(fence.controlRevision()),
        value(fence.firstAcceptanceSequence().value()));
  }

  private static M06SemanticMarketState.ModeTransitionFence fence(
      io.github.lchareln.cex.matching.ModeTransitionFence fence) {
    return new M06SemanticMarketState.ModeTransitionFence(
        value(fence.appliedCommandSequence().value()),
        value(fence.modeRevision()),
        fence.previousMode().name(),
        fence.activeMode().name(),
        value(fence.nextAcceptanceSequence().value()));
  }

  private static M06SemanticMarketState.MassCancelFence fence(
      io.github.lchareln.cex.matching.MassCancelFence fence) {
    return new M06SemanticMarketState.MassCancelFence(
        value(fence.appliedCommandSequence().value()),
        value(fence.modeRevision()),
        fence.operatorId().value(),
        value(fence.canceledOrderCount()),
        fence
            .firstCanceledSequence()
            .map(AcceptanceSequence::value)
            .map(M06ProductionCandidate::value),
        fence
            .lastCanceledSequence()
            .map(AcceptanceSequence::value)
            .map(M06ProductionCandidate::value));
  }

  private static io.github.lchareln.cex.matching.ApplicationSequence application(BigInteger value) {
    return new io.github.lchareln.cex.matching.ApplicationSequence(value.longValueExact());
  }

  private static BigInteger value(long value) {
    return BigInteger.valueOf(value);
  }
}
