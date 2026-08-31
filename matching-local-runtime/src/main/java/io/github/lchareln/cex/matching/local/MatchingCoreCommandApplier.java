package io.github.lchareln.cex.matching.local;

import io.github.lchareln.cex.matching.ActivateRuleSet;
import io.github.lchareln.cex.matching.ApplicationSequence;
import io.github.lchareln.cex.matching.CancelOrderInput;
import io.github.lchareln.cex.matching.ChangeMarketMode;
import io.github.lchareln.cex.matching.ExecutionBatch;
import io.github.lchareln.cex.matching.GovernedPlaceLimitOrderRequest;
import io.github.lchareln.cex.matching.MarketControlBatch;
import io.github.lchareln.cex.matching.MassCancel;
import io.github.lchareln.cex.matching.MassCancelBatch;
import io.github.lchareln.cex.matching.OperatorId;
import io.github.lchareln.cex.matching.PlaceLimitOrderInput;
import io.github.lchareln.cex.matching.PlaceLimitOrderRequest;
import io.github.lchareln.cex.matching.PrepareRuleSet;
import io.github.lchareln.cex.matching.SingleInstrumentMatchingEngine;
import java.util.List;
import java.util.Objects;

/** M00-M06 core adapter plus an explicit M07 STP cherry-pick seam. */
final class MatchingCoreCommandApplier implements CommandApplier {
  private final SingleInstrumentMatchingEngine engine;
  private final StpPlaceExtension stpPlaceExtension;

  MatchingCoreCommandApplier() {
    this(new SingleInstrumentMatchingEngine(), null);
  }

  MatchingCoreCommandApplier(
      SingleInstrumentMatchingEngine engine, StpPlaceExtension stpPlaceExtension) {
    this.engine = Objects.requireNonNull(engine, "engine");
    this.stpPlaceExtension = stpPlaceExtension;
  }

  @Override
  public boolean supports(M08Command command) {
    return !(command instanceof M08Command.Place place)
        || place.usesLegacyStpMapping()
        || stpPlaceExtension != null;
  }

  @Override
  public long nextApplicationSequence() {
    return engine.marketControlSnapshot().nextApplicationSequence().value();
  }

  @Override
  public CanonicalResult apply(M08Command command) {
    long expected = nextApplicationSequence();
    CanonicalResult result =
        switch (command) {
          case M08Command.Place place -> fromExecution(applyPlace(place));
          case M08Command.Cancel cancel ->
              fromExecution(
                  engine.cancel(new CancelOrderInput(cancel.instrumentId(), cancel.orderId())));
          case M08Command.PrepareRuleSet prepare ->
              fromControl(
                  engine.prepareRuleSet(
                      new PrepareRuleSet(prepare.expectedActive(), prepare.artifact())));
          case M08Command.ActivateRuleSet activate ->
              fromControl(
                  engine.activateRuleSet(
                      new ActivateRuleSet(
                          new ApplicationSequence(activate.expectedApplicationSequence()),
                          activate.expectedActive(),
                          activate.target())));
          case M08Command.ChangeMarketMode change ->
              fromControl(
                  engine.changeMarketMode(
                      new ChangeMarketMode(
                          new ApplicationSequence(change.expectedApplicationSequence()),
                          change.expectedMode(),
                          change.targetMode(),
                          new OperatorId(change.operatorId()))));
          case M08Command.MassCancel massCancel ->
              fromMassCancel(
                  engine.massCancel(
                      new MassCancel(
                          new ApplicationSequence(massCancel.expectedApplicationSequence()),
                          massCancel.expectedMode(),
                          new OperatorId(massCancel.operatorId()))));
        };
    if (result.applicationSequence() != expected || nextApplicationSequence() != expected + 1) {
      throw new IllegalStateException("core apply and application sequence disagree");
    }
    return result;
  }

  @Override
  public String semanticStateDigest() {
    return CanonicalResult.semanticDigest(
        engine.marketControlSnapshot().toString(), engine.snapshot().toString());
  }

  private ExecutionBatch applyPlace(M08Command.Place place) {
    if (!place.usesLegacyStpMapping()) {
      if (stpPlaceExtension == null) {
        throw new IllegalStateException("M07 STP command reached the M06-only core adapter");
      }
      return stpPlaceExtension.apply(engine, place);
    }
    PlaceLimitOrderRequest request =
        new PlaceLimitOrderRequest(
            new PlaceLimitOrderInput(
                place.instrumentId(),
                place.orderId(),
                place.side(),
                place.priceTicks(),
                place.quantityLots()),
            place.executionPolicy());
    return place.expectedActive().isPresent()
        ? engine.placeGoverned(
            new GovernedPlaceLimitOrderRequest(request, place.expectedActive().orElseThrow()))
        : engine.placeRequest(request);
  }

  private CanonicalResult fromExecution(ExecutionBatch batch) {
    long sequence = batch.context().applicationSequence().orElseThrow().value();
    return CanonicalResult.create(
        "EXECUTION",
        sequence,
        describe(batch.events()),
        batch.context().toString(),
        semanticStateDigest());
  }

  private CanonicalResult fromControl(MarketControlBatch batch) {
    long sequence = batch.events().getFirst().applicationSequence().value();
    return CanonicalResult.create(
        "MARKET_CONTROL",
        sequence,
        describe(batch.events()),
        batch.controlAfter().toString(),
        semanticStateDigest());
  }

  private CanonicalResult fromMassCancel(MassCancelBatch batch) {
    long sequence = batch.events().getFirst().applicationSequence().value();
    return CanonicalResult.create(
        "MASS_CANCEL",
        sequence,
        describe(batch.events()),
        batch.controlAfter().toString(),
        semanticStateDigest());
  }

  private static List<String> describe(List<?> events) {
    return events.stream().map(event -> event.getClass().getName() + ":" + event).toList();
  }
}
