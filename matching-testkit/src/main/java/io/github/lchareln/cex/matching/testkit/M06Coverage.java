package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.reference.M06ReferenceCommand;
import io.github.lchareln.cex.matching.reference.M06SemanticEvent;
import io.github.lchareln.cex.matching.reference.M06SemanticMarketState;
import io.github.lchareln.cex.matching.reference.M06SemanticOutcome;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Derives concrete first witnesses for every frozen M06 obligation from executed semantics. */
final class M06Coverage {
  Result analyze(M06Corpus.Fixed fixed, List<M06GeneratedSuite.History> generated) {
    Accumulator accumulator = new Accumulator();
    for (M06Corpus.Scenario scenario : fixed.scenarios()) {
      scan(
          "FIXED",
          scenario.id(),
          scenario.cases().stream().map(M06Corpus.Case::command).toList(),
          accumulator);
    }
    for (M06GeneratedSuite.History history : generated) {
      scan("GENERATED", Integer.toString(history.index()), history.commands(), accumulator);
    }
    List<Witness> witnesses = new ArrayList<>();
    for (String id : M06StartCheckRunner.COVERAGE_IDS) {
      Witness witness = accumulator.witnesses.get(id);
      if (witness == null) {
        witness = new Witness(id, false, "NONE", "", -1, 0);
      } else {
        witness =
            new Witness(
                id,
                true,
                witness.source(),
                witness.history(),
                witness.commandIndex(),
                accumulator.counts.getOrDefault(id, 0));
      }
      witnesses.add(witness);
    }
    return new Result(List.copyOf(witnesses), Map.copyOf(accumulator.counts));
  }

  private static void scan(
      String source, String history, List<M06ReferenceCommand> commands, Accumulator accumulator) {
    M06ReferenceCandidate model = new M06ReferenceCandidate();
    Set<BigInteger> massCanceledIds = new LinkedHashSet<>();
    for (int index = 0; index < commands.size(); index++) {
      M06ReferenceCommand command = commands.get(index);
      M06SemanticMarketState before = model.snapshot();
      M06SemanticOutcome outcome = model.apply(command);
      M06SemanticMarketState after = outcome.stateAfter();
      List<M06SemanticEvent> events = outcome.events();

      hit(
          accumulator,
          "APPLICATION_SEQUENCE_CONTINUITY",
          source,
          history,
          index,
          outcome.applicationSequence().equals(before.nextApplicationSequence())
              && after
                  .nextApplicationSequence()
                  .equals(before.nextApplicationSequence().add(BigInteger.ONE)));
      hit(
          accumulator,
          "BOOTSTRAP_OPEN",
          source,
          history,
          index,
          index == 0 && "OPEN".equals(before.marketMode()) && before.modeRevision().signum() == 0);

      M06SemanticEvent.ModeChanged changed = first(events, M06SemanticEvent.ModeChanged.class);
      if (changed != null) {
        hit(accumulator, "VALID_MODE_TRANSITION", source, history, index, true);
        hit(
            accumulator,
            "TRANSITION_BOOK_UNCHANGED",
            source,
            history,
            index,
            !before.book().equals(io.github.lchareln.cex.matching.reference.M06SemanticBook.empty())
                && before.book().equals(after.book()));
      }
      M06SemanticEvent.ModeChangeRejected modeRejected =
          first(events, M06SemanticEvent.ModeChangeRejected.class);
      if (modeRejected != null) {
        hit(
            accumulator,
            "DIRECT_REOPEN_REJECTED",
            source,
            history,
            index,
            "INVALID_TRANSITION".equals(modeRejected.code())
                && "HALTED".equals(modeRejected.observedMode())
                && "OPEN".equals(modeRejected.targetMode()));
        hit(
            accumulator,
            "SAME_MODE_REJECTED",
            source,
            history,
            index,
            "NO_MODE_CHANGE".equals(modeRejected.code()));
        hit(
            accumulator,
            "STALE_APPLICATION_FENCE",
            source,
            history,
            index,
            "APPLICATION_SEQUENCE_MISMATCH".equals(modeRejected.code()));
        hit(
            accumulator,
            "STALE_EXPECTED_MODE",
            source,
            history,
            index,
            "EXPECTED_MODE_MISMATCH".equals(modeRejected.code()));
        hit(
            accumulator,
            "MODE_FAILURE_ATOMIC",
            source,
            history,
            index,
            sameExceptApplication(before, after));
      }

      M06SemanticEvent.Accepted accepted = first(events, M06SemanticEvent.Accepted.class);
      M06SemanticEvent.PlaceRejected placeRejected =
          first(events, M06SemanticEvent.PlaceRejected.class);
      hit(
          accumulator,
          "PLACE_OPEN_ALLOWED",
          source,
          history,
          index,
          command instanceof M06ReferenceCommand.Place
              && "OPEN".equals(before.marketMode())
              && accepted != null);
      hit(
          accumulator,
          "PLACE_CANCEL_ONLY_REJECTED",
          source,
          history,
          index,
          command instanceof M06ReferenceCommand.Place
              && "CANCEL_ONLY".equals(before.marketMode())
              && placeRejected != null
              && "MARKET_NOT_OPEN".equals(placeRejected.code()));
      hit(
          accumulator,
          "PLACE_HALTED_REJECTED",
          source,
          history,
          index,
          command instanceof M06ReferenceCommand.Place
              && "HALTED".equals(before.marketMode())
              && placeRejected != null
              && "MARKET_NOT_OPEN".equals(placeRejected.code()));

      M06SemanticEvent.Canceled canceled = first(events, M06SemanticEvent.Canceled.class);
      M06SemanticEvent.CancelRejected cancelRejected =
          first(events, M06SemanticEvent.CancelRejected.class);
      hit(
          accumulator,
          "CANCEL_OPEN_ALLOWED",
          source,
          history,
          index,
          command instanceof M06ReferenceCommand.Cancel
              && "OPEN".equals(before.marketMode())
              && canceled != null);
      hit(
          accumulator,
          "CANCEL_CANCEL_ONLY_ALLOWED",
          source,
          history,
          index,
          command instanceof M06ReferenceCommand.Cancel
              && "CANCEL_ONLY".equals(before.marketMode())
              && canceled != null);
      hit(
          accumulator,
          "CANCEL_HALTED_REJECTED",
          source,
          history,
          index,
          command instanceof M06ReferenceCommand.Cancel
              && "HALTED".equals(before.marketMode())
              && cancelRejected != null
              && "MARKET_NOT_CANCELABLE".equals(cancelRejected.code()));

      boolean ruleSucceeded =
          first(events, M06SemanticEvent.RuleSetPrepared.class) != null
              || first(events, M06SemanticEvent.RuleSetActivated.class) != null;
      if (ruleSucceeded) {
        accumulator.successfulRuleModes.add(before.marketMode());
      }
      hit(
          accumulator,
          "RULE_CONTROL_ALL_MODES",
          source,
          history,
          index,
          ruleSucceeded
              && accumulator.successfulRuleModes.containsAll(
                  List.of("OPEN", "CANCEL_ONLY", "HALTED")));

      M06SemanticEvent.MassCancelRejected massRejected =
          first(events, M06SemanticEvent.MassCancelRejected.class);
      hit(
          accumulator,
          "MASS_CANCEL_REQUIRES_HALTED",
          source,
          history,
          index,
          massRejected != null && "MARKET_NOT_HALTED".equals(massRejected.code()));
      M06SemanticEvent.MassCancelStarted started =
          first(events, M06SemanticEvent.MassCancelStarted.class);
      M06SemanticEvent.MassCancelCompleted completed =
          first(events, M06SemanticEvent.MassCancelCompleted.class);
      List<M06SemanticEvent.MassOrderCanceled> massOrders =
          all(events, M06SemanticEvent.MassOrderCanceled.class);
      massOrders.forEach(event -> massCanceledIds.add(event.orderId()));
      boolean massSuccess = started != null && completed != null;
      hit(
          accumulator,
          "MASS_CANCEL_EMPTY_SUCCESS",
          source,
          history,
          index,
          massSuccess && completed.canceledOrderCount().signum() == 0);
      hit(
          accumulator,
          "MASS_CANCEL_GLOBAL_ACCEPTANCE_ORDER",
          source,
          history,
          index,
          massOrders.size() >= 2 && ascending(massOrders));
      hit(
          accumulator,
          "MASS_CANCEL_BID_SIDE",
          source,
          history,
          index,
          massOrders.stream().anyMatch(event -> "BUY".equals(event.side())));
      hit(
          accumulator,
          "MASS_CANCEL_ASK_SIDE",
          source,
          history,
          index,
          massOrders.stream().anyMatch(event -> "SELL".equals(event.side())));
      hit(
          accumulator,
          "MASS_CANCEL_CROSS_PRICE",
          source,
          history,
          index,
          massOrders.stream().map(M06SemanticEvent.MassOrderCanceled::priceTicks).distinct().count()
              >= 2);
      hit(
          accumulator,
          "MASS_CANCEL_RULE_ATTRIBUTION",
          source,
          history,
          index,
          massOrders.stream()
              .anyMatch(
                  event ->
                      event.executionRuleSet().equals(before.activeIdentity())
                          && event.admissionRuleSet().equals(event.executionRuleSet())
                          && event.executionRuleSet().version().signum() > 0));
      hit(
          accumulator,
          "MASS_CANCEL_ACTIVE_RULE_UNCHANGED",
          source,
          history,
          index,
          massSuccess && before.activeRuleSet().equals(after.activeRuleSet()));
      hit(
          accumulator,
          "MASS_CANCEL_TERMINAL_IDENTITY",
          source,
          history,
          index,
          command instanceof M06ReferenceCommand.Cancel cancel
              && massCanceledIds.contains(cancel.orderId())
              && cancelRejected != null
              && "ORDER_ALREADY_CANCELED".equals(cancelRejected.code()));

      hit(
          accumulator,
          "LEGACY_M00_M05_COMPATIBILITY",
          source,
          history,
          index,
          command instanceof M06ReferenceCommand.Place place
              && place.entrypoint() == M06ReferenceCommand.PlaceEntrypoint.LEGACY
              && "OPEN".equals(before.marketMode())
              && accepted != null);
    }
  }

  private static boolean sameExceptApplication(
      M06SemanticMarketState before, M06SemanticMarketState after) {
    return after
            .nextApplicationSequence()
            .equals(before.nextApplicationSequence().add(BigInteger.ONE))
        && after.nextAcceptanceSequence().equals(before.nextAcceptanceSequence())
        && after.controlRevision().equals(before.controlRevision())
        && after.activeRuleSet().equals(before.activeRuleSet())
        && after.preparedRuleSet().equals(before.preparedRuleSet())
        && after.lastActivationFence().equals(before.lastActivationFence())
        && after.marketMode().equals(before.marketMode())
        && after.modeRevision().equals(before.modeRevision())
        && after.lastModeTransitionFence().equals(before.lastModeTransitionFence())
        && after.lastMassCancelFence().equals(before.lastMassCancelFence())
        && after.book().equals(before.book());
  }

  private static boolean ascending(List<M06SemanticEvent.MassOrderCanceled> values) {
    for (int index = 1; index < values.size(); index++) {
      if (values
              .get(index - 1)
              .acceptanceSequence()
              .compareTo(values.get(index).acceptanceSequence())
          >= 0) {
        return false;
      }
    }
    return true;
  }

  private static <T extends M06SemanticEvent> T first(
      List<M06SemanticEvent> events, Class<T> type) {
    return events.stream().filter(type::isInstance).map(type::cast).findFirst().orElse(null);
  }

  private static <T extends M06SemanticEvent> List<T> all(
      List<M06SemanticEvent> events, Class<T> type) {
    return events.stream().filter(type::isInstance).map(type::cast).toList();
  }

  private static void hit(
      Accumulator accumulator,
      String id,
      String source,
      String history,
      int commandIndex,
      boolean condition) {
    if (!condition) {
      return;
    }
    accumulator.counts.merge(id, 1, Integer::sum);
    accumulator.witnesses.putIfAbsent(id, new Witness(id, true, source, history, commandIndex, 1));
  }

  record Witness(
      String id, boolean satisfied, String source, String history, int commandIndex, int count) {}

  record Result(List<Witness> witnesses, Map<String, Integer> counts) {
    int satisfied() {
      return (int) witnesses.stream().filter(Witness::satisfied).count();
    }

    void assertComplete() {
      List<String> missing =
          witnesses.stream().filter(witness -> !witness.satisfied()).map(Witness::id).toList();
      if (!missing.isEmpty()) {
        throw new IllegalStateException(
            "M06 finite corpus missed coverage obligations: " + missing);
      }
    }
  }

  private static final class Accumulator {
    private final Map<String, Witness> witnesses = new LinkedHashMap<>();
    private final Map<String, Integer> counts = new LinkedHashMap<>();
    private final Set<String> successfulRuleModes = new LinkedHashSet<>();
  }
}
