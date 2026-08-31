package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.reference.M05MarketRuleSetArtifact;
import io.github.lchareln.cex.matching.reference.M05RuleSetIdentity;
import io.github.lchareln.cex.matching.reference.M05SemanticBook;
import io.github.lchareln.cex.matching.reference.M05SemanticEvent;
import io.github.lchareln.cex.matching.reference.M05SemanticMarketState;
import io.github.lchareln.cex.matching.reference.M05SemanticOutcome;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Semantic coverage witnesses measured from reference outcomes and command-boundary state. */
final class M05GeneratedCoverage {
  static final String M04_LEGACY_BOOTSTRAP = "M04_LEGACY_BOOTSTRAP";
  static final String CONTENT_HASH_MISMATCH = "CONTENT_HASH_MISMATCH";
  static final String PREPARE_IDEMPOTENCY = "PREPARE_IDEMPOTENCY";
  static final String VERSION_CONTENT_CONFLICT = "VERSION_CONTENT_CONFLICT";
  static final String PREPARED_SUPERSESSION = "PREPARED_SUPERSESSION";
  static final String ACTIVATE_WITHOUT_PREPARE = "ACTIVATE_WITHOUT_PREPARE";
  static final String STALE_ACTIVATION_FENCE = "STALE_ACTIVATION_FENCE";
  static final String FAILED_ACTIVATION_ATOMICITY = "FAILED_ACTIVATION_ATOMICITY";
  static final String STALE_PLACE_FENCE = "STALE_PLACE_FENCE";
  static final String LOWER_BOUND_TOUCH = "LOWER_BOUND_TOUCH";
  static final String UPPER_BOUND_TOUCH = "UPPER_BOUND_TOUCH";
  static final String BELOW_BAND = "BELOW_BAND";
  static final String ABOVE_BAND = "ABOVE_BAND";
  static final String BUY_SELL_SYMMETRY = "BUY_SELL_SYMMETRY";
  static final String DUPLICATE_BEFORE_FENCE_AND_BAND = "DUPLICATE_BEFORE_FENCE_AND_BAND";
  static final String BAND_BEFORE_FOK = "BAND_BEFORE_FOK";
  static final String BAND_BEFORE_POST_ONLY = "BAND_BEFORE_POST_ONLY";
  static final String GRANDFATHERED_MAKER = "GRANDFATHERED_MAKER";
  static final String CROSS_VERSION_TRADE = "CROSS_VERSION_TRADE";
  static final String REJECTION_SEQUENCE_CONTINUITY = "REJECTION_SEQUENCE_CONTINUITY";

  Result analyze(M05GeneratorProfile profile, List<M05GeneratedHistory> histories) {
    List<String> required = requiredKeys();
    if (!profile.coverageRequirements().equals(required)) {
      throw new IllegalArgumentException("M05 frozen coverage obligations changed");
    }
    if (histories.size() != profile.histories() || profile.histories() != 160) {
      throw new IllegalArgumentException("M05 coverage histories differ from frozen profile");
    }

    LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
    required.forEach(key -> counts.put(key, 0));
    LinkedHashMap<String, Witness> first = new LinkedHashMap<>();
    Set<Occurrence> occurrences = new LinkedHashSet<>();

    for (M05GeneratedHistory history : List.copyOf(histories)) {
      if (history.commands().size() != profile.commandsPerHistory()
          || profile.commandsPerHistory() != 64) {
        throw new IllegalArgumentException("M05 coverage command count differs from profile");
      }
      M05ReferenceCandidate reference = new M05ReferenceCandidate();
      Set<BigInteger> acceptedOrderIds = new HashSet<>();
      Map<BigInteger, RejectedPlace> reusableRejectedIds = new HashMap<>();
      Map<SymmetryKey, Set<String>> symmetrySides = new HashMap<>();

      for (int commandIndex = 0; commandIndex < history.commands().size(); commandIndex++) {
        M05Command command = history.commands().get(commandIndex);
        M05SemanticMarketState before = reference.snapshot();
        M05SemanticOutcome outcome = reference.apply(command);
        M05SemanticMarketState after = outcome.stateAfter();
        Set<String> observed = new LinkedHashSet<>();

        switch (command) {
          case M05Command.Place place ->
              classifyPlace(place, before, outcome, acceptedOrderIds, symmetrySides, observed);
          case M05Command.Cancel ignored -> {
            // No frozen M05 coverage obligation is introduced by Cancel alone.
          }
          case M05Command.PrepareRuleSet prepare ->
              classifyPrepare(prepare, before, outcome, observed);
          case M05Command.ActivateRuleSet activate ->
              classifyActivate(activate, before, outcome, observed);
        }

        if (command instanceof M05Command.Place place) {
          M05SemanticEvent firstEvent = outcome.events().getFirst();
          if (firstEvent instanceof M05SemanticEvent.PlaceRejected rejected
              && !"DUPLICATE_ORDER_ID".equals(rejected.code())
              && after.nextAcceptanceSequence().equals(before.nextAcceptanceSequence())
              && after
                  .nextApplicationSequence()
                  .equals(before.nextApplicationSequence().add(BigInteger.ONE))) {
            reusableRejectedIds.put(
                place.orderId(),
                new RejectedPlace(outcome.applicationSequence(), before.nextAcceptanceSequence()));
          } else if (firstEvent instanceof M05SemanticEvent.Accepted accepted) {
            RejectedPlace rejected = reusableRejectedIds.remove(accepted.orderId());
            if (rejected != null
                && accepted.acceptanceSequence().equals(rejected.nextAcceptanceSequence())
                && outcome.applicationSequence().compareTo(rejected.applicationSequence()) > 0
                && after
                    .nextAcceptanceSequence()
                    .equals(accepted.acceptanceSequence().add(BigInteger.ONE))) {
              observed.add(REJECTION_SEQUENCE_CONTINUITY);
            }
          }
        }

        for (M05SemanticEvent event : outcome.events()) {
          if (event instanceof M05SemanticEvent.Accepted accepted) {
            acceptedOrderIds.add(accepted.orderId());
          }
          if (event instanceof M05SemanticEvent.Trade trade
              && !trade.makerAdmissionRuleSet().equals(trade.takerAdmissionRuleSet())
              && trade.takerAdmissionRuleSet().equals(after.activeIdentity())
              && trade.executionRuleSet().equals(after.activeIdentity())) {
            observed.add(CROSS_VERSION_TRADE);
          }
        }

        for (String key : observed) {
          Witness witness =
              new Witness(
                  history.historyIndex(), commandIndex, history.seedHex(), history.laneId());
          counts.put(key, counts.get(key) + 1);
          first.putIfAbsent(key, witness);
          occurrences.add(new Occurrence(key, history.historyIndex(), commandIndex));
        }
      }
    }

    Result result = new Result(counts, first, occurrences);
    result.assertRequired();
    return result;
  }

  private static void classifyPrepare(
      M05Command.PrepareRuleSet command,
      M05SemanticMarketState before,
      M05SemanticOutcome outcome,
      Set<String> observed) {
    M05SemanticEvent first = outcome.events().getFirst();
    M05SemanticMarketState after = outcome.stateAfter();
    if (first instanceof M05SemanticEvent.PrepareRuleSetRejected rejected) {
      if ("CONTENT_HASH_MISMATCH".equals(rejected.code())
          && !M05RuleSetCanonical.matches(command.artifact())
          && controlAndBookUnchangedExceptApplication(before, after)) {
        observed.add(CONTENT_HASH_MISMATCH);
      }
      if ("SAME_VERSION_DIFFERENT_CONTENT".equals(rejected.code())
          && before.preparedRuleSet().isPresent()
          && command.artifact().version().equals(before.preparedRuleSet().orElseThrow().version())
          && !command
              .artifact()
              .contentHash()
              .equals(before.preparedRuleSet().orElseThrow().contentHash())
          && controlAndBookUnchangedExceptApplication(before, after)) {
        observed.add(VERSION_CONTENT_CONFLICT);
      }
    } else if (first instanceof M05SemanticEvent.RuleSetPrepared prepared) {
      if (prepared.status() == M05SemanticEvent.PrepareStatus.ALREADY_PREPARED
          && before.preparedRuleSet().isPresent()
          && before.preparedRuleSet().equals(after.preparedRuleSet())
          && prepared.identity().equals(before.preparedRuleSet().orElseThrow().identity())) {
        observed.add(PREPARE_IDEMPOTENCY);
      }
      if (prepared.status() == M05SemanticEvent.PrepareStatus.SUPERSEDED
          && before.preparedRuleSet().isPresent()
          && prepared
              .supersededIdentity()
              .equals(java.util.Optional.of(before.preparedRuleSet().orElseThrow().identity()))
          && after.preparedRuleSet().isPresent()
          && prepared.identity().equals(after.preparedRuleSet().orElseThrow().identity())
          && after
                  .preparedRuleSet()
                  .orElseThrow()
                  .version()
                  .compareTo(before.preparedRuleSet().orElseThrow().version())
              > 0) {
        observed.add(PREPARED_SUPERSESSION);
      }
    }
  }

  private static void classifyActivate(
      M05Command.ActivateRuleSet command,
      M05SemanticMarketState before,
      M05SemanticOutcome outcome,
      Set<String> observed) {
    M05SemanticEvent first = outcome.events().getFirst();
    M05SemanticMarketState after = outcome.stateAfter();
    if (first instanceof M05SemanticEvent.ActivateRuleSetRejected rejected) {
      if ("NO_PREPARED_RULE_SET".equals(rejected.code()) && before.preparedRuleSet().isEmpty()) {
        observed.add(ACTIVATE_WITHOUT_PREPARE);
      }
      if ("APPLICATION_SEQUENCE_MISMATCH".equals(rejected.code())
          && !command.expectedApplicationSequence().equals(before.nextApplicationSequence())) {
        observed.add(STALE_ACTIVATION_FENCE);
      }
      if (controlAndBookUnchangedExceptApplication(before, after)
          && outcome.applicationSequence().equals(before.nextApplicationSequence())) {
        observed.add(FAILED_ACTIVATION_ATOMICITY);
      }
      return;
    }
    if (first instanceof M05SemanticEvent.RuleSetActivated activated
        && activated.fence().applicationSequence().equals(outcome.applicationSequence())
        && grandfatheredOrderSurvived(before, after)) {
      observed.add(GRANDFATHERED_MAKER);
    }
  }

  private static void classifyPlace(
      M05Command.Place place,
      M05SemanticMarketState before,
      M05SemanticOutcome outcome,
      Set<BigInteger> acceptedOrderIds,
      Map<SymmetryKey, Set<String>> symmetrySides,
      Set<String> observed) {
    M05SemanticEvent first = outcome.events().getFirst();
    M05MarketRuleSetArtifact active = before.activeRuleSet();
    int lower = place.priceTicks().compareTo(active.lowerInclusive());
    int upper = place.priceTicks().compareTo(active.upperInclusive());

    if ("LEGACY".equals(place.entrypoint())
        && active.equals(bootstrap())
        && first instanceof M05SemanticEvent.Accepted accepted
        && accepted.admissionRuleSet().equals(active.identity())
        && accepted.executionRuleSet().equals(active.identity())) {
      observed.add(M04_LEGACY_BOOTSTRAP);
    }

    boolean accepted = first instanceof M05SemanticEvent.Accepted;
    boolean outsideRejected = rejected(first, "PRICE_OUTSIDE_ACTIVE_BAND");
    if (lower == 0 && accepted) {
      observed.add(LOWER_BOUND_TOUCH);
    }
    if (upper == 0 && accepted) {
      observed.add(UPPER_BOUND_TOUCH);
    }
    if (lower < 0 && outsideRejected) {
      observed.add(BELOW_BAND);
    }
    if (upper > 0 && outsideRejected) {
      observed.add(ABOVE_BAND);
    }

    if ((accepted || outsideRejected)
        && ("BUY".equals(place.side()) || "SELL".equals(place.side()))) {
      String relation = bandRelation(lower, upper);
      SymmetryKey key =
          new SymmetryKey(
              active.identity(),
              active.lowerInclusive(),
              active.upperInclusive(),
              place.priceTicks(),
              relation,
              accepted ? "ACCEPTED" : "PRICE_OUTSIDE_ACTIVE_BAND");
      Set<String> sides = symmetrySides.computeIfAbsent(key, ignored -> new HashSet<>());
      sides.add(place.side());
      if (sides.contains("BUY") && sides.contains("SELL")) {
        observed.add(BUY_SELL_SYMMETRY);
      }
    }

    if ("GOVERNED".equals(place.entrypoint())
        && acceptedOrderIds.contains(place.orderId())
        && !identity(place.expectedRuleSet()).equals(active.identity())
        && (lower < 0 || upper > 0)
        && rejected(first, "DUPLICATE_ORDER_ID")) {
      observed.add(DUPLICATE_BEFORE_FENCE_AND_BAND);
    }

    if ("GOVERNED".equals(place.entrypoint())
        && !acceptedOrderIds.contains(place.orderId())
        && !identity(place.expectedRuleSet()).equals(active.identity())
        && rejected(first, "RULE_SET_MISMATCH")) {
      observed.add(STALE_PLACE_FENCE);
    }

    if (outsideRejected
        && !acceptedOrderIds.contains(place.orderId())
        && governedIdentityIsCurrent(place, active.identity())) {
      if ("FOK".equals(place.executionPolicy())) {
        observed.add(BAND_BEFORE_FOK);
      } else if ("POST_ONLY".equals(place.executionPolicy())) {
        observed.add(BAND_BEFORE_POST_ONLY);
      }
    }
  }

  private static boolean grandfatheredOrderSurvived(
      M05SemanticMarketState before, M05SemanticMarketState after) {
    List<BookOrder> beforeOrders = flatten(before.book());
    Set<BookOrder> afterOrders = Set.copyOf(flatten(after.book()));
    for (BookOrder order : beforeOrders) {
      if ((order.priceTicks().compareTo(after.activeRuleSet().lowerInclusive()) < 0
              || order.priceTicks().compareTo(after.activeRuleSet().upperInclusive()) > 0)
          && afterOrders.contains(order)) {
        return true;
      }
    }
    return false;
  }

  private static List<BookOrder> flatten(M05SemanticBook book) {
    List<BookOrder> result = new ArrayList<>();
    for (M05SemanticBook.PriceLevel level : book.bids()) {
      level.orders().forEach(order -> result.add(new BookOrder("BUY", level.priceTicks(), order)));
    }
    for (M05SemanticBook.PriceLevel level : book.asks()) {
      level.orders().forEach(order -> result.add(new BookOrder("SELL", level.priceTicks(), order)));
    }
    return List.copyOf(result);
  }

  private static boolean controlAndBookUnchangedExceptApplication(
      M05SemanticMarketState before, M05SemanticMarketState after) {
    return after
            .nextApplicationSequence()
            .equals(before.nextApplicationSequence().add(BigInteger.ONE))
        && after.nextAcceptanceSequence().equals(before.nextAcceptanceSequence())
        && after.controlRevision().equals(before.controlRevision())
        && after.activeRuleSet().equals(before.activeRuleSet())
        && after.preparedRuleSet().equals(before.preparedRuleSet())
        && after.lastActivationFence().equals(before.lastActivationFence())
        && after.book().equals(before.book());
  }

  private static boolean governedIdentityIsCurrent(
      M05Command.Place place, M05RuleSetIdentity activeIdentity) {
    return "LEGACY".equals(place.entrypoint())
        || identity(place.expectedRuleSet()).equals(activeIdentity);
  }

  private static M05RuleSetIdentity identity(M05Command.Identity identity) {
    return identity == null
        ? null
        : new M05RuleSetIdentity(identity.version(), identity.contentHash());
  }

  private static M05MarketRuleSetArtifact bootstrap() {
    M05Command.Artifact value = M05RuleSetCanonical.BOOTSTRAP;
    return new M05MarketRuleSetArtifact(
        value.schemaVersion(),
        value.instrumentId(),
        value.version(),
        value.lowerInclusive(),
        value.upperInclusive(),
        value.contentHash());
  }

  private static String bandRelation(int lowerComparison, int upperComparison) {
    if (lowerComparison < 0) {
      return "BELOW";
    }
    if (lowerComparison == 0) {
      return "LOWER";
    }
    if (upperComparison == 0) {
      return "UPPER";
    }
    if (upperComparison > 0) {
      return "ABOVE";
    }
    return "INSIDE";
  }

  private static boolean rejected(M05SemanticEvent event, String code) {
    return event instanceof M05SemanticEvent.PlaceRejected rejected && code.equals(rejected.code());
  }

  static List<String> requiredKeys() {
    return List.of(
        M04_LEGACY_BOOTSTRAP,
        CONTENT_HASH_MISMATCH,
        PREPARE_IDEMPOTENCY,
        VERSION_CONTENT_CONFLICT,
        PREPARED_SUPERSESSION,
        ACTIVATE_WITHOUT_PREPARE,
        STALE_ACTIVATION_FENCE,
        FAILED_ACTIVATION_ATOMICITY,
        STALE_PLACE_FENCE,
        LOWER_BOUND_TOUCH,
        UPPER_BOUND_TOUCH,
        BELOW_BAND,
        ABOVE_BAND,
        BUY_SELL_SYMMETRY,
        DUPLICATE_BEFORE_FENCE_AND_BAND,
        BAND_BEFORE_FOK,
        BAND_BEFORE_POST_ONLY,
        GRANDFATHERED_MAKER,
        CROSS_VERSION_TRADE,
        REJECTION_SEQUENCE_CONTINUITY);
  }

  record Witness(int historyIndex, int commandIndex, String seedHex, String laneId) {}

  record Obligation(String id, boolean satisfied, int historyIndex, int commandIndex) {}

  record Result(
      Map<String, Integer> counts,
      Map<String, Witness> firstWitnesses,
      Set<Occurrence> occurrences) {
    Result {
      counts = Collections.unmodifiableMap(new LinkedHashMap<>(counts));
      firstWitnesses = Collections.unmodifiableMap(new LinkedHashMap<>(firstWitnesses));
      occurrences = Set.copyOf(occurrences);
    }

    void assertRequired() {
      List<String> missing = new ArrayList<>();
      for (String key : requiredKeys()) {
        if (counts.getOrDefault(key, 0) <= 0 || !firstWitnesses.containsKey(key)) {
          missing.add(key);
        }
      }
      if (!missing.isEmpty()) {
        throw new IllegalStateException("M05 generated semantic coverage missing: " + missing);
      }
    }

    int satisfiedObligations() {
      return (int) requiredKeys().stream().filter(key -> counts.getOrDefault(key, 0) > 0).count();
    }

    List<Obligation> obligations() {
      return requiredKeys().stream()
          .map(
              key -> {
                Witness witness = firstWitnesses.get(key);
                return new Obligation(
                    key,
                    witness != null,
                    witness == null ? -1 : witness.historyIndex(),
                    witness == null ? -1 : witness.commandIndex());
              })
          .toList();
    }

    List<Obligation> orderedWitnesses() {
      return obligations();
    }

    boolean observedAt(String key, int historyIndex, int commandIndex) {
      return occurrences.contains(new Occurrence(key, historyIndex, commandIndex));
    }
  }

  private record SymmetryKey(
      M05RuleSetIdentity identity,
      BigInteger lowerInclusive,
      BigInteger upperInclusive,
      BigInteger priceTicks,
      String relation,
      String outcome) {}

  private record BookOrder(
      String side, BigInteger priceTicks, M05SemanticBook.RestingOrder order) {}

  private record RejectedPlace(BigInteger applicationSequence, BigInteger nextAcceptanceSequence) {}

  private record Occurrence(String key, int historyIndex, int commandIndex) {}
}
