package io.github.lchareln.cex.matching.local;

import io.github.lchareln.cex.matching.ActivateRuleSet;
import io.github.lchareln.cex.matching.ApplicationSequence;
import io.github.lchareln.cex.matching.CancelOrderInput;
import io.github.lchareln.cex.matching.ChangeMarketMode;
import io.github.lchareln.cex.matching.ExecutionBatch;
import io.github.lchareln.cex.matching.GovernedPlaceLimitOrderRequest;
import io.github.lchareln.cex.matching.GovernedStpPlaceLimitOrderRequest;
import io.github.lchareln.cex.matching.MarketControlBatch;
import io.github.lchareln.cex.matching.MassCancel;
import io.github.lchareln.cex.matching.MassCancelBatch;
import io.github.lchareln.cex.matching.OperatorId;
import io.github.lchareln.cex.matching.PlaceLimitOrderInput;
import io.github.lchareln.cex.matching.PlaceLimitOrderRequest;
import io.github.lchareln.cex.matching.PrepareRuleSet;
import io.github.lchareln.cex.matching.SingleInstrumentMatchingEngine;
import io.github.lchareln.cex.matching.StpPlaceLimitOrderRequest;
import java.util.List;
import java.util.Objects;

/** Applies every M00-M07 journal command to one private matching core. */
final class MatchingCoreCommandApplier implements CommandApplier {
  private static final String TRANSCRIPT_DOMAIN = "M08T1_GENESIS_REPLAY_TRANSCRIPT";

  private final SingleInstrumentMatchingEngine engine;
  private final M08CommandCodec commandCodec = new M08CommandCodec();
  private String transcriptDigest = genesisTranscriptDigest();

  MatchingCoreCommandApplier() {
    this(new SingleInstrumentMatchingEngine());
  }

  MatchingCoreCommandApplier(SingleInstrumentMatchingEngine engine) {
    this.engine = Objects.requireNonNull(engine, "engine");
  }

  @Override
  public boolean supports(M08Command command) {
    return true;
  }

  @Override
  public long nextApplicationSequence() {
    return engine.marketControlSnapshot().nextApplicationSequence().value();
  }

  @Override
  public CanonicalResult apply(M08Command command) {
    long expected = nextApplicationSequence();
    AppliedOutcome outcome =
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
    if (outcome.applicationSequence() != expected || nextApplicationSequence() != expected + 1) {
      throw new IllegalStateException("core apply and application sequence disagree");
    }
    String publicCoreStateDigest = publicCoreStateDigest();
    transcriptDigest = advanceTranscript(transcriptDigest, command, outcome, publicCoreStateDigest);
    return CanonicalResult.create(
        outcome.resultType(),
        outcome.applicationSequence(),
        outcome.events(),
        outcome.context(),
        combinedSemanticDigest(publicCoreStateDigest));
  }

  @Override
  public String semanticStateDigest() {
    return combinedSemanticDigest(publicCoreStateDigest());
  }

  private ExecutionBatch applyPlace(M08Command.Place place) {
    if (!place.usesLegacyStpMapping()) {
      PlaceLimitOrderRequest request = placeRequest(place);
      StpPlaceLimitOrderRequest stpRequest =
          new StpPlaceLimitOrderRequest(request, place.participantGroupId(), place.stpPolicy());
      return place.expectedActive().isPresent()
          ? engine.placeGovernedStp(
              new GovernedStpPlaceLimitOrderRequest(
                  stpRequest, place.expectedActive().orElseThrow()))
          : engine.placeStp(stpRequest);
    }
    PlaceLimitOrderRequest request = placeRequest(place);
    return place.expectedActive().isPresent()
        ? engine.placeGoverned(
            new GovernedPlaceLimitOrderRequest(request, place.expectedActive().orElseThrow()))
        : engine.placeRequest(request);
  }

  private static PlaceLimitOrderRequest placeRequest(M08Command.Place place) {
    return new PlaceLimitOrderRequest(
        new PlaceLimitOrderInput(
            place.instrumentId(),
            place.orderId(),
            place.side(),
            place.priceTicks(),
            place.quantityLots()),
        place.executionPolicy());
  }

  private AppliedOutcome fromExecution(ExecutionBatch batch) {
    long sequence = batch.context().applicationSequence().orElseThrow().value();
    return new AppliedOutcome(
        "EXECUTION", sequence, describe(batch.events()), batch.context().toString());
  }

  private AppliedOutcome fromControl(MarketControlBatch batch) {
    long sequence = batch.events().getFirst().applicationSequence().value();
    return new AppliedOutcome(
        "MARKET_CONTROL", sequence, describe(batch.events()), batch.controlAfter().toString());
  }

  private AppliedOutcome fromMassCancel(MassCancelBatch batch) {
    long sequence = batch.events().getFirst().applicationSequence().value();
    return new AppliedOutcome(
        "MASS_CANCEL", sequence, describe(batch.events()), batch.controlAfter().toString());
  }

  private String publicCoreStateDigest() {
    return CanonicalResult.semanticDigest(
        engine.marketControlSnapshot().toString(), engine.snapshot().toString());
  }

  private String combinedSemanticDigest(String publicCoreStateDigest) {
    // This is a genesis-replay transcript commitment, not a snapshot format. It deliberately
    // commits terminal identities that are absent from the public resting-book projection.
    return CanonicalResult.semanticDigest(publicCoreStateDigest, transcriptDigest);
  }

  private String advanceTranscript(
      String previous, M08Command command, AppliedOutcome outcome, String publicCoreStateDigest) {
    BinaryEncoding.Writer writer = new BinaryEncoding.Writer();
    writer.putString(TRANSCRIPT_DOMAIN);
    writer.putString(previous);
    writer.putByteArray(commandCodec.encode(command));
    writer.putString(outcome.resultType());
    writer.putLong(outcome.applicationSequence());
    writer.putInt(outcome.events().size());
    outcome.events().forEach(writer::putString);
    writer.putString(outcome.context());
    writer.putString(publicCoreStateDigest);
    return Sha256.hex(writer.toByteArray());
  }

  private static String genesisTranscriptDigest() {
    BinaryEncoding.Writer writer = new BinaryEncoding.Writer();
    writer.putString(TRANSCRIPT_DOMAIN);
    writer.putString("GENESIS");
    return Sha256.hex(writer.toByteArray());
  }

  private static List<String> describe(List<?> events) {
    return events.stream().map(event -> event.getClass().getName() + ":" + event).toList();
  }

  private record AppliedOutcome(
      String resultType, long applicationSequence, List<String> events, String context) {
    private AppliedOutcome {
      events = List.copyOf(events);
    }
  }
}
