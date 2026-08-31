package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.AcceptanceSequence;
import io.github.lchareln.cex.matching.ActivateRuleSet;
import io.github.lchareln.cex.matching.CancelOrderInput;
import io.github.lchareln.cex.matching.ExecutionBatch;
import io.github.lchareln.cex.matching.GovernedPlaceLimitOrderRequest;
import io.github.lchareln.cex.matching.MarketControlBatch;
import io.github.lchareln.cex.matching.MarketControlEvent;
import io.github.lchareln.cex.matching.MarketControlSnapshot;
import io.github.lchareln.cex.matching.MarketRuleSetArtifact;
import io.github.lchareln.cex.matching.MatchingEvent;
import io.github.lchareln.cex.matching.OrderBookSnapshot;
import io.github.lchareln.cex.matching.PlaceLimitOrderInput;
import io.github.lchareln.cex.matching.PlaceLimitOrderRequest;
import io.github.lchareln.cex.matching.PrepareRuleSet;
import io.github.lchareln.cex.matching.RuleSetIdentity;
import io.github.lchareln.cex.matching.RuleSetVersion;
import io.github.lchareln.cex.matching.SingleInstrumentMatchingEngine;
import io.github.lchareln.cex.matching.reference.M05MarketRuleSetArtifact;
import io.github.lchareln.cex.matching.reference.M05RuleSetIdentity;
import io.github.lchareln.cex.matching.reference.M05SemanticBook;
import io.github.lchareln.cex.matching.reference.M05SemanticEvent;
import io.github.lchareln.cex.matching.reference.M05SemanticMarketState;
import io.github.lchareln.cex.matching.reference.M05SemanticOutcome;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Production matcher adapter that projects core values into the neutral M05 semantic model. */
final class M05ProductionCandidate implements M05Candidate {
  private final SingleInstrumentMatchingEngine engine = new SingleInstrumentMatchingEngine();

  @Override
  public M05SemanticOutcome apply(M05Command command) {
    return switch (command) {
      case M05Command.Place place -> applyPlace(place);
      case M05Command.Cancel cancel ->
          execution(engine.cancel(new CancelOrderInput(cancel.instrumentId(), cancel.orderId())));
      case M05Command.PrepareRuleSet prepare -> applyPrepare(prepare);
      case M05Command.ActivateRuleSet activate -> applyActivate(activate);
    };
  }

  @Override
  public M05SemanticMarketState snapshot() {
    return state(engine.marketControlSnapshot(), engine.snapshot());
  }

  private M05SemanticOutcome applyPlace(M05Command.Place place) {
    PlaceLimitOrderInput input =
        new PlaceLimitOrderInput(
            place.instrumentId(),
            place.orderId(),
            place.side(),
            place.priceTicks(),
            place.quantityLots());
    PlaceLimitOrderRequest request = new PlaceLimitOrderRequest(input, place.executionPolicy());
    if ("GOVERNED".equals(place.entrypoint())) {
      return execution(
          engine.placeGoverned(
              new GovernedPlaceLimitOrderRequest(request, identity(place.expectedRuleSet()))));
    }
    return execution(engine.placeRequest(request));
  }

  private M05SemanticOutcome applyPrepare(M05Command.PrepareRuleSet command) {
    MarketControlSnapshot before = engine.marketControlSnapshot();
    MarketControlBatch batch =
        engine.prepareRuleSet(
            new PrepareRuleSet(identity(command.expectedActive()), artifact(command.artifact())));
    return control(batch, before);
  }

  private M05SemanticOutcome applyActivate(M05Command.ActivateRuleSet command) {
    MarketControlSnapshot before = engine.marketControlSnapshot();
    MarketControlBatch batch =
        engine.activateRuleSet(
            new ActivateRuleSet(
                new io.github.lchareln.cex.matching.ApplicationSequence(
                    command.expectedApplicationSequence().longValueExact()),
                identity(command.expectedActive()),
                identity(command.target())));
    return control(batch, before);
  }

  private M05SemanticOutcome execution(ExecutionBatch batch) {
    BigInteger applicationSequence =
        BigInteger.valueOf(batch.context().applicationSequence().orElseThrow().value());
    M05RuleSetIdentity executionRuleSet = identity(batch.context().activeRuleSet());
    List<M05SemanticEvent> events =
        batch.events().stream().map(event -> event(event, executionRuleSet)).toList();
    return new M05SemanticOutcome(
        applicationSequence, events, state(engine.marketControlSnapshot(), batch.bookAfter()));
  }

  private M05SemanticOutcome control(MarketControlBatch batch, MarketControlSnapshot before) {
    MarketControlEvent event = batch.events().getFirst();
    Optional<M05RuleSetIdentity> superseded =
        event instanceof MarketControlEvent.RuleSetPrepared prepared
                && prepared.status()
                    == io.github.lchareln.cex.matching.PrepareRuleSetStatus.SUPERSEDED
            ? before.preparedIdentity().map(M05ProductionCandidate::identity)
            : Optional.empty();
    return new M05SemanticOutcome(
        BigInteger.valueOf(event.applicationSequence().value()),
        List.of(controlEvent(event, superseded)),
        state(batch.controlAfter(), batch.bookAfter()));
  }

  private static M05SemanticEvent event(MatchingEvent event, M05RuleSetIdentity executionRuleSet) {
    return switch (event) {
      case MatchingEvent.Rejected rejected ->
          new M05SemanticEvent.Rejected(rejected.code().name(), rejected.field());
      case MatchingEvent.PlaceRejected rejected ->
          new M05SemanticEvent.PlaceRejected(
              value(rejected.orderId().value()), rejected.code().name(), executionRuleSet);
      case MatchingEvent.CancelRejected rejected ->
          new M05SemanticEvent.CancelRejected(
              value(rejected.orderId().value()), rejected.code().name(), executionRuleSet);
      case MatchingEvent.Accepted accepted ->
          new M05SemanticEvent.Accepted(
              value(accepted.sequence()),
              value(accepted.orderId().value()),
              accepted.side().name(),
              value(accepted.priceTicks().value()),
              value(accepted.quantityLots().value()),
              accepted.executionPolicy().name(),
              identity(accepted.admissionRuleSet()),
              executionRuleSet);
      case MatchingEvent.Trade trade ->
          new M05SemanticEvent.Trade(
              value(trade.makerSequence()),
              value(trade.makerOrderId().value()),
              value(trade.takerSequence()),
              value(trade.takerOrderId().value()),
              value(trade.priceTicks().value()),
              value(trade.quantityLots().value()),
              identity(trade.makerAdmissionRuleSet()),
              identity(trade.takerAdmissionRuleSet()),
              identity(trade.executionRuleSet()));
      case MatchingEvent.Rested rested ->
          new M05SemanticEvent.Rested(
              value(rested.sequence()),
              value(rested.orderId().value()),
              rested.side().name(),
              value(rested.priceTicks().value()),
              value(rested.remainingQuantityLots().value()),
              identity(rested.admissionRuleSet()),
              executionRuleSet);
      case MatchingEvent.RemainderCanceled canceled ->
          new M05SemanticEvent.RemainderCanceled(
              value(canceled.sequence()),
              value(canceled.orderId().value()),
              canceled.side().name(),
              value(canceled.priceTicks().value()),
              value(canceled.canceledQuantityLots().value()),
              canceled.reason().name(),
              identity(canceled.admissionRuleSet()),
              executionRuleSet);
      case MatchingEvent.Canceled canceled ->
          new M05SemanticEvent.Canceled(
              value(canceled.sequence()),
              value(canceled.orderId().value()),
              canceled.side().name(),
              value(canceled.priceTicks().value()),
              value(canceled.canceledQuantityLots().value()),
              identity(canceled.admissionRuleSet()),
              identity(canceled.executionRuleSet()));
    };
  }

  private static M05SemanticEvent controlEvent(
      MarketControlEvent event, Optional<M05RuleSetIdentity> superseded) {
    return switch (event) {
      case MarketControlEvent.RuleSetPrepared prepared ->
          new M05SemanticEvent.RuleSetPrepared(
              identity(prepared.preparedRuleSet()),
              M05SemanticEvent.PrepareStatus.valueOf(prepared.status().name()),
              superseded);
      case MarketControlEvent.PrepareRejected rejected ->
          new M05SemanticEvent.PrepareRuleSetRejected(rejected.code().name());
      case MarketControlEvent.RuleSetActivated activated ->
          new M05SemanticEvent.RuleSetActivated(
              identity(activated.previousActiveRuleSet()),
              identity(activated.activeRuleSet()),
              fence(activated.activationFence()));
      case MarketControlEvent.ActivateRejected rejected ->
          new M05SemanticEvent.ActivateRuleSetRejected(rejected.code().name());
      case MarketControlEvent.ModeChanged ignored ->
          throw new IllegalStateException("M05 projection received an M06 mode transition");
      case MarketControlEvent.ModeChangeRejected ignored ->
          throw new IllegalStateException("M05 projection received an M06 mode rejection");
    };
  }

  private static M05SemanticMarketState state(
      MarketControlSnapshot control, OrderBookSnapshot book) {
    return new M05SemanticMarketState(
        value(control.nextApplicationSequence().value()),
        value(control.nextAcceptanceSequence()),
        value(control.controlRevision()),
        artifact(control.activeRuleSet()),
        control.preparedRuleSet().map(M05ProductionCandidate::artifact),
        control.lastActivationFence().map(M05ProductionCandidate::fence),
        book(book));
  }

  private static M05SemanticBook book(OrderBookSnapshot book) {
    return new M05SemanticBook(levels(book.bids()), levels(book.asks()));
  }

  private static List<M05SemanticBook.PriceLevel> levels(
      List<OrderBookSnapshot.PriceLevel> levels) {
    List<M05SemanticBook.PriceLevel> result = new ArrayList<>(levels.size());
    for (OrderBookSnapshot.PriceLevel level : levels) {
      List<M05SemanticBook.RestingOrder> orders = new ArrayList<>(level.orders().size());
      for (OrderBookSnapshot.RestingOrderView order : level.orders()) {
        orders.add(
            new M05SemanticBook.RestingOrder(
                value(order.sequence()),
                value(order.orderId().value()),
                value(order.remainingQuantityLots().value()),
                identity(order.admissionRuleSet())));
      }
      result.add(
          new M05SemanticBook.PriceLevel(
              level.side().name(), value(level.priceTicks().value()), orders));
    }
    return List.copyOf(result);
  }

  private static MarketRuleSetArtifact artifact(M05Command.Artifact artifact) {
    return new MarketRuleSetArtifact(
        artifact.schemaVersion(),
        artifact.instrumentId(),
        new RuleSetVersion(artifact.version().longValueExact()),
        new io.github.lchareln.cex.matching.PriceTicks(artifact.lowerInclusive().longValueExact()),
        new io.github.lchareln.cex.matching.PriceTicks(artifact.upperInclusive().longValueExact()),
        artifact.contentHash());
  }

  private static M05MarketRuleSetArtifact artifact(MarketRuleSetArtifact artifact) {
    return new M05MarketRuleSetArtifact(
        artifact.schemaVersion(),
        artifact.instrumentId(),
        value(artifact.version().value()),
        value(artifact.lowerInclusive().value()),
        value(artifact.upperInclusive().value()),
        artifact.contentHash());
  }

  private static RuleSetIdentity identity(M05Command.Identity identity) {
    return new RuleSetIdentity(
        new RuleSetVersion(identity.version().longValueExact()), identity.contentHash());
  }

  private static M05RuleSetIdentity identity(RuleSetIdentity identity) {
    return new M05RuleSetIdentity(value(identity.version().value()), identity.contentHash());
  }

  private static M05SemanticMarketState.ActivationFence fence(
      io.github.lchareln.cex.matching.ActivationFence fence) {
    return new M05SemanticMarketState.ActivationFence(
        value(fence.appliedCommandSequence().value()),
        value(fence.controlRevision()),
        value(fence.firstAcceptanceSequence()));
  }

  private static BigInteger value(AcceptanceSequence sequence) {
    return value(sequence.value());
  }

  private static BigInteger value(long value) {
    return BigInteger.valueOf(value);
  }
}
