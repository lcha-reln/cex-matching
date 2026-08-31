package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.reference.M05LinearReferenceModel;
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

/** Named neutral-outcome mutants for the eight frozen M05 semantic faults. */
final class M05Mutants {
  static final String HASH_MISMATCH_PREPARED = "M05-HASH-MISMATCH-PREPARED";
  static final String SAME_VERSION_DIFFERENT_HASH_ACCEPTED =
      "M05-SAME-VERSION-DIFFERENT-HASH-ACCEPTED";
  static final String ACTIVATE_WITHOUT_PREPARE = "M05-ACTIVATE-WITHOUT-PREPARE";
  static final String STALE_ACTIVATION_FENCE_ACCEPTED = "M05-STALE-ACTIVATION-FENCE-ACCEPTED";
  static final String FAILED_ACTIVATION_CHANGES_ACTIVE = "M05-FAILED-ACTIVATION-CHANGES-ACTIVE";
  static final String OUT_OF_BAND_PLACE_ACCEPTED = "M05-OUT-OF-BAND-PLACE-ACCEPTED";
  static final String STALE_PLACE_RULE_ACCEPTED = "M05-STALE-PLACE-RULE-ACCEPTED";
  static final String ACTIVATION_REVALIDATES_RESTING = "M05-ACTIVATION-REVALIDATES-RESTING";

  private M05Mutants() {}

  static M05Candidate.Factory hashMismatchPrepared() {
    return () ->
        new ModelCandidate() {
          @Override
          public M05SemanticOutcome apply(M05Command command) {
            M05SemanticMarketState before = model.snapshot();
            M05SemanticOutcome reference = applyReference(command);
            if (!(command instanceof M05Command.PrepareRuleSet prepare)
                || !rejectedPrepare(reference, "CONTENT_HASH_MISMATCH")) {
              return reference;
            }
            M05MarketRuleSetArtifact candidate = artifact(prepare.artifact());
            M05SemanticEvent.PrepareStatus status;
            Optional<M05RuleSetIdentity> superseded;
            if (before.preparedRuleSet().isEmpty()) {
              status = M05SemanticEvent.PrepareStatus.PREPARED;
              superseded = Optional.empty();
            } else if (candidate
                    .version()
                    .compareTo(before.preparedRuleSet().orElseThrow().version())
                > 0) {
              status = M05SemanticEvent.PrepareStatus.SUPERSEDED;
              superseded = Optional.of(before.preparedRuleSet().orElseThrow().identity());
            } else {
              return reference;
            }
            M05SemanticEvent.RuleSetPrepared event =
                new M05SemanticEvent.RuleSetPrepared(candidate.identity(), status, superseded);
            return outcome(reference, List.of(event), state(reference.stateAfter(), candidate));
          }
        };
  }

  static M05Candidate.Factory sameVersionDifferentHashAccepted() {
    return () ->
        new ModelCandidate() {
          @Override
          public M05SemanticOutcome apply(M05Command command) {
            M05SemanticOutcome reference = applyReference(command);
            if (command instanceof M05Command.PrepareRuleSet prepare
                && rejectedPrepare(reference, "SAME_VERSION_DIFFERENT_CONTENT")) {
              M05SemanticEvent.RuleSetPrepared event =
                  new M05SemanticEvent.RuleSetPrepared(
                      identity(prepare.artifact()),
                      M05SemanticEvent.PrepareStatus.ALREADY_PREPARED,
                      Optional.empty());
              return outcome(reference, List.of(event), reference.stateAfter());
            }
            return reference;
          }
        };
  }

  static M05Candidate.Factory activateWithoutPrepare() {
    return () ->
        new ModelCandidate() {
          @Override
          public M05SemanticOutcome apply(M05Command command) {
            M05SemanticMarketState before = model.snapshot();
            M05SemanticOutcome reference = applyReference(command);
            if (command instanceof M05Command.ActivateRuleSet activate
                && rejectedActivate(reference, "NO_PREPARED_RULE_SET")) {
              M05SemanticMarketState.ActivationFence fence =
                  new M05SemanticMarketState.ActivationFence(
                      reference.applicationSequence(),
                      before.controlRevision().add(BigInteger.ONE),
                      before.nextAcceptanceSequence());
              M05SemanticEvent.RuleSetActivated event =
                  new M05SemanticEvent.RuleSetActivated(
                      before.activeIdentity(), identity(activate.target()), fence);
              return outcome(reference, List.of(event), reference.stateAfter());
            }
            return reference;
          }
        };
  }

  static M05Candidate.Factory staleActivationFenceAccepted() {
    return () ->
        new ModelCandidate() {
          @Override
          public M05SemanticOutcome apply(M05Command command) {
            M05SemanticMarketState before = model.snapshot();
            M05SemanticOutcome reference = applyReference(command);
            if (command instanceof M05Command.ActivateRuleSet
                && before.preparedRuleSet().isPresent()
                && rejectedActivate(reference, "APPLICATION_SEQUENCE_MISMATCH")) {
              M05SemanticMarketState.ActivationFence fence =
                  new M05SemanticMarketState.ActivationFence(
                      reference.applicationSequence(),
                      before.controlRevision().add(BigInteger.ONE),
                      before.nextAcceptanceSequence());
              M05SemanticEvent.RuleSetActivated event =
                  new M05SemanticEvent.RuleSetActivated(
                      before.activeIdentity(),
                      before.preparedRuleSet().orElseThrow().identity(),
                      fence);
              return outcome(reference, List.of(event), reference.stateAfter());
            }
            return reference;
          }
        };
  }

  static M05Candidate.Factory failedActivationChangesActive() {
    return () ->
        new ModelCandidate() {
          @Override
          public M05SemanticOutcome apply(M05Command command) {
            M05SemanticMarketState before = model.snapshot();
            M05SemanticOutcome reference = applyReference(command);
            if (command instanceof M05Command.ActivateRuleSet
                && before.preparedRuleSet().isPresent()
                && reference.events().getFirst()
                    instanceof M05SemanticEvent.ActivateRuleSetRejected) {
              M05MarketRuleSetArtifact illegallyActivated = before.preparedRuleSet().orElseThrow();
              M05SemanticMarketState changed =
                  new M05SemanticMarketState(
                      reference.stateAfter().nextApplicationSequence(),
                      reference.stateAfter().nextAcceptanceSequence(),
                      reference.stateAfter().controlRevision(),
                      illegallyActivated,
                      Optional.empty(),
                      reference.stateAfter().lastActivationFence(),
                      reference.bookAfter());
              return outcome(reference, reference.events(), changed);
            }
            return reference;
          }
        };
  }

  static M05Candidate.Factory outOfBandPlaceAccepted() {
    return () ->
        new ModelCandidate() {
          @Override
          public M05SemanticOutcome apply(M05Command command) {
            M05SemanticMarketState before = model.snapshot();
            M05SemanticOutcome reference = applyReference(command);
            if (command instanceof M05Command.Place place
                && rejectedPlace(reference, "PRICE_OUTSIDE_ACTIVE_BAND")) {
              return acceptedRemainder(reference, before, place);
            }
            return reference;
          }
        };
  }

  static M05Candidate.Factory stalePlaceRuleAccepted() {
    return () ->
        new ModelCandidate() {
          @Override
          public M05SemanticOutcome apply(M05Command command) {
            M05SemanticMarketState before = model.snapshot();
            M05SemanticOutcome reference = applyReference(command);
            if (command instanceof M05Command.Place place
                && rejectedPlace(reference, "RULE_SET_MISMATCH")) {
              return acceptedRemainder(reference, before, place);
            }
            return reference;
          }
        };
  }

  static M05Candidate.Factory activationRevalidatesResting() {
    return () ->
        new ModelCandidate() {
          @Override
          public M05SemanticOutcome apply(M05Command command) {
            M05SemanticOutcome reference = applyReference(command);
            if (!(command instanceof M05Command.ActivateRuleSet)
                || !(reference.events().getFirst() instanceof M05SemanticEvent.RuleSetActivated)) {
              return reference;
            }
            M05MarketRuleSetArtifact active = reference.stateAfter().activeRuleSet();
            M05SemanticBook filtered = filterToBand(reference.bookAfter(), active);
            if (filtered.equals(reference.bookAfter())) {
              return reference;
            }
            M05SemanticMarketState state =
                new M05SemanticMarketState(
                    reference.stateAfter().nextApplicationSequence(),
                    reference.stateAfter().nextAcceptanceSequence(),
                    reference.stateAfter().controlRevision(),
                    reference.stateAfter().activeRuleSet(),
                    reference.stateAfter().preparedRuleSet(),
                    reference.stateAfter().lastActivationFence(),
                    filtered);
            return outcome(reference, reference.events(), state);
          }
        };
  }

  static M05Candidate.Factory throwingControl() {
    return () ->
        new M05Candidate() {
          @Override
          public M05SemanticOutcome apply(M05Command command) {
            throw new IllegalStateException("intentional M05 throwing control");
          }

          @Override
          public M05SemanticMarketState snapshot() {
            throw new IllegalStateException("intentional M05 throwing control snapshot");
          }
        };
  }

  private static M05SemanticOutcome acceptedRemainder(
      M05SemanticOutcome reference, M05SemanticMarketState before, M05Command.Place place) {
    M05RuleSetIdentity rule = before.activeIdentity();
    BigInteger sequence = before.nextAcceptanceSequence();
    M05SemanticEvent.Accepted accepted =
        new M05SemanticEvent.Accepted(
            sequence,
            place.orderId(),
            place.side(),
            place.priceTicks(),
            place.quantityLots(),
            place.executionPolicy(),
            rule,
            rule);
    M05SemanticEvent.Rested rested =
        new M05SemanticEvent.Rested(
            sequence,
            place.orderId(),
            place.side(),
            place.priceTicks(),
            place.quantityLots(),
            rule,
            rule);
    M05SemanticMarketState changed =
        new M05SemanticMarketState(
            reference.stateAfter().nextApplicationSequence(),
            sequence.add(BigInteger.ONE),
            reference.stateAfter().controlRevision(),
            reference.stateAfter().activeRuleSet(),
            reference.stateAfter().preparedRuleSet(),
            reference.stateAfter().lastActivationFence(),
            reference.bookAfter());
    return outcome(reference, List.of(accepted, rested), changed);
  }

  private static M05SemanticBook filterToBand(M05SemanticBook book, M05MarketRuleSetArtifact rule) {
    return new M05SemanticBook(filterLevels(book.bids(), rule), filterLevels(book.asks(), rule));
  }

  private static List<M05SemanticBook.PriceLevel> filterLevels(
      List<M05SemanticBook.PriceLevel> levels, M05MarketRuleSetArtifact rule) {
    List<M05SemanticBook.PriceLevel> result = new ArrayList<>();
    for (M05SemanticBook.PriceLevel level : levels) {
      if (level.priceTicks().compareTo(rule.lowerInclusive()) >= 0
          && level.priceTicks().compareTo(rule.upperInclusive()) <= 0) {
        result.add(level);
      }
    }
    return List.copyOf(result);
  }

  private static M05SemanticMarketState state(
      M05SemanticMarketState reference, M05MarketRuleSetArtifact prepared) {
    return new M05SemanticMarketState(
        reference.nextApplicationSequence(),
        reference.nextAcceptanceSequence(),
        reference.controlRevision(),
        reference.activeRuleSet(),
        Optional.of(prepared),
        reference.lastActivationFence(),
        reference.book());
  }

  private static M05SemanticOutcome outcome(
      M05SemanticOutcome reference, List<M05SemanticEvent> events, M05SemanticMarketState state) {
    return new M05SemanticOutcome(reference.applicationSequence(), events, state);
  }

  private static boolean rejectedPrepare(M05SemanticOutcome outcome, String code) {
    return outcome.events().getFirst() instanceof M05SemanticEvent.PrepareRuleSetRejected rejected
        && code.equals(rejected.code());
  }

  private static boolean rejectedActivate(M05SemanticOutcome outcome, String code) {
    return outcome.events().getFirst() instanceof M05SemanticEvent.ActivateRuleSetRejected rejected
        && code.equals(rejected.code());
  }

  private static boolean rejectedPlace(M05SemanticOutcome outcome, String code) {
    return outcome.events().getFirst() instanceof M05SemanticEvent.PlaceRejected rejected
        && code.equals(rejected.code());
  }

  private static M05RuleSetIdentity identity(M05Command.Identity value) {
    return new M05RuleSetIdentity(value.version(), value.contentHash());
  }

  private static M05RuleSetIdentity identity(M05Command.Artifact value) {
    return new M05RuleSetIdentity(value.version(), value.contentHash());
  }

  private static M05MarketRuleSetArtifact artifact(M05Command.Artifact value) {
    return new M05MarketRuleSetArtifact(
        value.schemaVersion(),
        value.instrumentId(),
        value.version(),
        value.lowerInclusive(),
        value.upperInclusive(),
        value.contentHash());
  }

  private abstract static class ModelCandidate implements M05Candidate {
    protected final M05LinearReferenceModel model = new M05LinearReferenceModel();

    protected final M05SemanticOutcome applyReference(M05Command command) {
      return model.apply(M05ReferenceCandidate.referenceCommand(command));
    }

    @Override
    public final M05SemanticMarketState snapshot() {
      return model.snapshot();
    }
  }
}
