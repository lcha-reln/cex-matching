package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.reference.LinearReferenceModel;
import io.github.lchareln.cex.matching.reference.ReferenceCommand;
import io.github.lchareln.cex.matching.reference.SemanticBook;
import io.github.lchareln.cex.matching.reference.SemanticEvent;
import io.github.lchareln.cex.matching.reference.SemanticOutcome;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Named neutral-outcome mutants for the eight frozen M04 semantic faults. */
final class M04Mutants {
  static final String IOC_REMAINDER_RESTS = "M04-IOC-REMAINDER-RESTS";
  static final String IOC_BEHAVES_LIKE_FOK = "M04-IOC-BEHAVES-LIKE-FOK";
  static final String FOK_PARTIAL_STATE_LEAK = "M04-FOK-PARTIAL-STATE-LEAK";
  static final String FOK_BEST_LEVEL_ONLY = "M04-FOK-BEST-LEVEL-ONLY";
  static final String FOK_IGNORES_LIMIT_PRICE = "M04-FOK-IGNORES-LIMIT-PRICE";
  static final String POST_ONLY_TOUCH_ACCEPTED = "M04-POST-ONLY-TOUCH-ACCEPTED";
  static final String POLICY_REJECT_CONSUMES_IDENTITY = "M04-POLICY-REJECT-CONSUMES-IDENTITY";
  static final String UNKNOWN_POLICY_DEFAULTS_GTC = "M04-UNKNOWN-POLICY-DEFAULTS-GTC";

  private M04Mutants() {}

  static M04Candidate.Factory iocRemainderRests() {
    return () ->
        new ModelCandidate() {
          @Override
          public SemanticOutcome apply(ReferenceCommand command) {
            SemanticOutcome outcome = model.apply(command);
            if (command instanceof ReferenceCommand.Place place
                && "IOC".equals(place.executionPolicy())) {
              List<SemanticEvent> events = new ArrayList<>(outcome.events());
              for (int index = 0; index < events.size(); index++) {
                if (events.get(index) instanceof SemanticEvent.RemainderCanceled canceled) {
                  events.set(
                      index,
                      new SemanticEvent.Rested(
                          canceled.sequence(),
                          canceled.orderId(),
                          canceled.side(),
                          canceled.priceTicks(),
                          canceled.canceledQuantityLots()));
                  return new SemanticOutcome(events, outcome.bookAfter());
                }
              }
            }
            return outcome;
          }
        };
  }

  static M04Candidate.Factory iocBehavesLikeFok() {
    return () ->
        new ModelCandidate() {
          @Override
          public SemanticOutcome apply(ReferenceCommand command) {
            SemanticBook before = model.snapshot();
            SemanticOutcome outcome = model.apply(command);
            if (command instanceof ReferenceCommand.Place place
                && "IOC".equals(place.executionPolicy())
                && hasRemainder(outcome)) {
              return singleton(
                  new SemanticEvent.PlaceRejected(place.orderId(), "FOK_NOT_FILLABLE"), before);
            }
            return outcome;
          }
        };
  }

  static M04Candidate.Factory fokPartialStateLeak() {
    return () ->
        new ModelCandidate() {
          @Override
          public SemanticOutcome apply(ReferenceCommand command) {
            SemanticOutcome outcome = model.apply(command);
            if (command instanceof ReferenceCommand.Place place
                && "FOK".equals(place.executionPolicy())
                && rejected(outcome, "FOK_NOT_FILLABLE")) {
              SemanticOutcome leaked = model.apply(withPolicy(place, "IOC"));
              return new SemanticOutcome(outcome.events(), leaked.bookAfter());
            }
            return outcome;
          }
        };
  }

  static M04Candidate.Factory fokBestLevelOnly() {
    return () ->
        new ModelCandidate() {
          @Override
          public SemanticOutcome apply(ReferenceCommand command) {
            SemanticBook before = model.snapshot();
            SemanticOutcome outcome = model.apply(command);
            if (command instanceof ReferenceCommand.Place place
                && "FOK".equals(place.executionPolicy())
                && outcome.events().getFirst() instanceof SemanticEvent.Accepted
                && outcome.events().stream().filter(SemanticEvent.Trade.class::isInstance).count()
                    > 1) {
              return singleton(
                  new SemanticEvent.PlaceRejected(place.orderId(), "FOK_NOT_FILLABLE"), before);
            }
            return outcome;
          }
        };
  }

  static M04Candidate.Factory fokIgnoresLimitPrice() {
    return () ->
        new ModelCandidate() {
          @Override
          public SemanticOutcome apply(ReferenceCommand command) {
            SemanticOutcome outcome = model.apply(command);
            if (command instanceof ReferenceCommand.Place place
                && "FOK".equals(place.executionPolicy())
                && rejected(outcome, "FOK_NOT_FILLABLE")) {
              BigInteger extreme =
                  "BUY".equals(place.side()) ? BigInteger.valueOf(Long.MAX_VALUE) : BigInteger.ONE;
              ReferenceCommand.Place aggressive =
                  new ReferenceCommand.Place(
                      place.instrumentId(),
                      place.orderId(),
                      place.side(),
                      extreme,
                      place.quantityLots(),
                      "IOC");
              return rewriteAccepted(model.apply(aggressive), place.priceTicks(), "FOK");
            }
            return outcome;
          }
        };
  }

  static M04Candidate.Factory postOnlyTouchAccepted() {
    return () ->
        new ModelCandidate() {
          @Override
          public SemanticOutcome apply(ReferenceCommand command) {
            if (command instanceof ReferenceCommand.Place place
                && "POST_ONLY".equals(place.executionPolicy())) {
              SemanticOutcome outcome = model.apply(withPolicy(place, "GTC"));
              return rewriteAccepted(outcome, place.priceTicks(), "POST_ONLY");
            }
            return model.apply(command);
          }
        };
  }

  static M04Candidate.Factory policyRejectConsumesIdentity() {
    return () ->
        new ModelCandidate() {
          private final Set<BigInteger> reserved = new HashSet<>();

          @Override
          public SemanticOutcome apply(ReferenceCommand command) {
            if (command instanceof ReferenceCommand.Place place
                && reserved.contains(place.orderId())) {
              return singleton(
                  new SemanticEvent.PlaceRejected(place.orderId(), "DUPLICATE_ORDER_ID"),
                  model.snapshot());
            }
            SemanticOutcome outcome = model.apply(command);
            if (outcome.events().getFirst() instanceof SemanticEvent.PlaceRejected rejected
                && ("FOK_NOT_FILLABLE".equals(rejected.code())
                    || "POST_ONLY_WOULD_TAKE".equals(rejected.code()))) {
              reserved.add(rejected.orderId());
            }
            return outcome;
          }
        };
  }

  static M04Candidate.Factory unknownPolicyDefaultsGtc() {
    return () ->
        new ModelCandidate() {
          @Override
          public SemanticOutcome apply(ReferenceCommand command) {
            if (command instanceof ReferenceCommand.Place place
                && !List.of("GTC", "IOC", "FOK", "POST_ONLY").contains(place.executionPolicy())) {
              SemanticOutcome outcome = model.apply(withPolicy(place, "GTC"));
              return rewriteAccepted(outcome, place.priceTicks(), place.executionPolicy());
            }
            return model.apply(command);
          }
        };
  }

  static M04Candidate.Factory throwingControl() {
    return () ->
        command -> {
          throw new IllegalStateException("intentional M04 throwing control");
        };
  }

  private static SemanticOutcome rewriteAccepted(
      SemanticOutcome outcome, BigInteger priceTicks, String policy) {
    List<SemanticEvent> events = new ArrayList<>(outcome.events().size());
    for (SemanticEvent event : outcome.events()) {
      if (event instanceof SemanticEvent.Accepted accepted) {
        events.add(
            new SemanticEvent.Accepted(
                accepted.sequence(),
                accepted.orderId(),
                accepted.side(),
                priceTicks,
                accepted.quantityLots(),
                policy));
      } else if (event instanceof SemanticEvent.RemainderCanceled canceled) {
        events.add(
            new SemanticEvent.RemainderCanceled(
                canceled.sequence(),
                canceled.orderId(),
                canceled.side(),
                priceTicks,
                canceled.canceledQuantityLots(),
                canceled.reason()));
      } else {
        events.add(event);
      }
    }
    return new SemanticOutcome(events, outcome.bookAfter());
  }

  private static ReferenceCommand.Place withPolicy(ReferenceCommand.Place place, String policy) {
    return new ReferenceCommand.Place(
        place.instrumentId(),
        place.orderId(),
        place.side(),
        place.priceTicks(),
        place.quantityLots(),
        policy);
  }

  private static boolean hasRemainder(SemanticOutcome outcome) {
    return outcome.events().stream().anyMatch(SemanticEvent.RemainderCanceled.class::isInstance);
  }

  private static boolean rejected(SemanticOutcome outcome, String code) {
    return outcome.events().getFirst() instanceof SemanticEvent.PlaceRejected rejected
        && code.equals(rejected.code());
  }

  private static SemanticOutcome singleton(SemanticEvent event, SemanticBook book) {
    return new SemanticOutcome(List.of(event), book);
  }

  private abstract static class ModelCandidate implements M04Candidate {
    protected final LinearReferenceModel model = new LinearReferenceModel();
  }
}
