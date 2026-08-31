package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.reference.M07ReferenceCommand;
import io.github.lchareln.cex.matching.reference.M07SemanticBook;
import io.github.lchareln.cex.matching.reference.M07SemanticEvent;
import io.github.lchareln.cex.matching.reference.M07SemanticMarketState;
import io.github.lchareln.cex.matching.reference.M07SemanticOutcome;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Derives first concrete semantic witnesses for every frozen M07 obligation. */
final class M07Coverage {
  Result analyze(M07Corpus.Fixed fixed, List<M07GeneratedSuite.History> generated) {
    Accumulator accumulator = new Accumulator();
    for (M07Corpus.Scenario scenario : fixed.scenarios()) {
      scan(
          "FIXED",
          scenario.id(),
          scenario.cases().stream().map(M07Corpus.Case::command).toList(),
          accumulator);
    }
    for (M07GeneratedSuite.History history : generated) {
      scan("GENERATED", Integer.toString(history.index()), history.commands(), accumulator);
    }
    List<Witness> witnesses = new ArrayList<>();
    for (String id : M07StartCheckRunner.COVERAGE_IDS) {
      Witness first = accumulator.witnesses.get(id);
      witnesses.add(
          first == null
              ? new Witness(id, false, "NONE", "", -1, 0)
              : new Witness(
                  id,
                  true,
                  first.source(),
                  first.history(),
                  first.commandIndex(),
                  accumulator.counts.getOrDefault(id, 0)));
    }
    return new Result(List.copyOf(witnesses), Map.copyOf(accumulator.counts));
  }

  private static void scan(
      String source, String history, List<M07ReferenceCommand> commands, Accumulator accumulator) {
    M07ReferenceCandidate model = new M07ReferenceCandidate();
    boolean sawIocStp = false;
    boolean sawIocRemainder = false;
    for (int index = 0; index < commands.size(); index++) {
      M07ReferenceCommand command = commands.get(index);
      M07SemanticMarketState before = model.snapshot();
      M07SemanticOutcome outcome = model.apply(command);
      M07SemanticMarketState after = outcome.stateAfter();
      List<M07SemanticEvent> events = outcome.events();
      M07SemanticEvent.Rejected rejected = first(events, M07SemanticEvent.Rejected.class);
      M07SemanticEvent.PlaceRejected placeRejected =
          first(events, M07SemanticEvent.PlaceRejected.class);
      M07SemanticEvent.Accepted accepted = first(events, M07SemanticEvent.Accepted.class);
      List<M07SemanticEvent.Trade> trades = all(events, M07SemanticEvent.Trade.class);
      List<M07SemanticEvent.SelfTradePrevented> stps =
          all(events, M07SemanticEvent.SelfTradePrevented.class);
      M07SemanticEvent.Rested rested = first(events, M07SemanticEvent.Rested.class);
      M07SemanticEvent.RemainderCanceled remainder =
          first(events, M07SemanticEvent.RemainderCanceled.class);

      hit(
          accumulator,
          "INVALID_STP_GROUP_ID",
          source,
          history,
          index,
          code(rejected, "INVALID_STP_GROUP_ID"));
      hit(
          accumulator,
          "INVALID_STP_POLICY",
          source,
          history,
          index,
          code(rejected, "INVALID_STP_POLICY"));
      hit(
          accumulator,
          "INVALID_STP_INSTRUCTION",
          source,
          history,
          index,
          code(rejected, "INVALID_STP_INSTRUCTION"));
      hit(
          accumulator,
          "VALIDATION_FAILURE_ATOMIC",
          source,
          history,
          index,
          rejected != null && sameExceptApplication(before, after));

      if (command instanceof M07ReferenceCommand.Place place) {
        hit(
            accumulator,
            "LEGACY_ZERO_NONE_COMPATIBILITY",
            source,
            history,
            index,
            place.entrypoint() == M07ReferenceCommand.PlaceEntrypoint.LEGACY
                && accepted != null
                && accepted.participantGroupId().signum() == 0
                && "NONE".equals(accepted.stpPolicy()));
        hit(
            accumulator,
            "GROUP_ZERO_NEVER_SELF",
            source,
            history,
            index,
            place.participantGroupId().signum() == 0 && !trades.isEmpty() && stps.isEmpty());
        BigInteger makerGroup =
            trades.isEmpty() ? null : group(before.book(), trades.getFirst().makerOrderId());
        hit(
            accumulator,
            "DIFFERENT_GROUP_TRADE",
            source,
            history,
            index,
            makerGroup != null
                && place.participantGroupId().signum() > 0
                && !makerGroup.equals(place.participantGroupId())
                && !trades.isEmpty());

        boolean sell = "SELL".equals(place.side());
        M07SemanticEvent.SelfTradePrevented cancelTaker = policy(stps, "CANCEL_TAKER");
        M07SemanticEvent.SelfTradePrevented cancelMaker = policy(stps, "CANCEL_MAKER");
        M07SemanticEvent.SelfTradePrevented cancelBoth = policy(stps, "CANCEL_BOTH");
        hit(
            accumulator,
            "SAME_GROUP_NO_TRADE",
            source,
            history,
            index,
            !stps.isEmpty() && noSelfTrade(stps, trades));
        hit(
            accumulator,
            "CANCEL_TAKER_CANCELS_FULL_REMAINDER",
            source,
            history,
            index,
            sell
                && cancelTaker != null
                && cancelTaker.takerCanceledQuantityLots().signum() > 0
                && !contains(after.book(), place.orderId()));
        hit(
            accumulator,
            "CANCEL_TAKER_PRESERVES_MAKER",
            source,
            history,
            index,
            sell && cancelTaker != null && contains(after.book(), cancelTaker.makerOrderId()));
        hit(
            accumulator,
            "CANCEL_MAKER_CANCELS_MAKER",
            source,
            history,
            index,
            sell && cancelMaker != null && !contains(after.book(), cancelMaker.makerOrderId()));
        hit(
            accumulator,
            "CANCEL_BOTH_CANCELS_BOTH",
            source,
            history,
            index,
            sell
                && cancelBoth != null
                && !contains(after.book(), cancelBoth.makerOrderId())
                && !contains(after.book(), cancelBoth.takerOrderId()));
        hit(
            accumulator,
            "CANCEL_MAKER_CONTINUES_SAME_LEVEL",
            source,
            history,
            index,
            cancelMaker != null && tradeStpTradeSamePrice(events));
        hit(
            accumulator,
            "CANCEL_MAKER_CONTINUES_CROSS_LEVEL",
            source,
            history,
            index,
            sell && distinctStpPrices(stps) >= 2 && !trades.isEmpty());
        hit(
            accumulator,
            "PRICE_TIME_EVENT_INTERLEAVING",
            source,
            history,
            index,
            tradeStpTrade(events));
        hit(
            accumulator,
            "PARTIAL_TRADE_BEFORE_STP",
            source,
            history,
            index,
            cancelTaker != null
                && firstIndex(events, M07SemanticEvent.Trade.class)
                    < firstIndex(events, M07SemanticEvent.SelfTradePrevented.class));
        hit(
            accumulator,
            "GTC_REMAINDER_RESTS",
            source,
            history,
            index,
            "GTC".equals(place.executionPolicy())
                && cancelMaker != null
                && rested != null
                && rested.orderId().equals(place.orderId()));

        if ("IOC".equals(place.executionPolicy()) && cancelTaker != null && remainder == null) {
          sawIocStp = true;
        }
        if ("IOC".equals(place.executionPolicy())
            && remainder != null
            && "IOC_REMAINDER".equals(remainder.reason())) {
          sawIocRemainder = true;
        }
        hit(
            accumulator,
            "IOC_STP_AND_REMAINDER_REASONS",
            source,
            history,
            index,
            sawIocStp && sawIocRemainder);
        hit(
            accumulator,
            "FOK_TAKER_OR_BOTH_PRECHECK",
            source,
            history,
            index,
            "FOK".equals(place.executionPolicy())
                && "CANCEL_BOTH".equals(place.stpPolicy())
                && code(placeRejected, "FOK_NOT_FILLABLE"));
        hit(
            accumulator,
            "FOK_CANCEL_MAKER_PRECHECK",
            source,
            history,
            index,
            "FOK".equals(place.executionPolicy())
                && "CANCEL_MAKER".equals(place.stpPolicy())
                && accepted != null
                && cancelMaker != null
                && !trades.isEmpty());
        hit(
            accumulator,
            "FOK_FAILURE_ATOMIC",
            source,
            history,
            index,
            "FOK".equals(place.executionPolicy())
                && code(placeRejected, "FOK_NOT_FILLABLE")
                && before.book().equals(after.book())
                && before.nextAcceptanceSequence().equals(after.nextAcceptanceSequence()));
        hit(
            accumulator,
            "POST_ONLY_RAW_BOOK_FIRST",
            source,
            history,
            index,
            "POST_ONLY".equals(place.executionPolicy())
                && code(placeRejected, "POST_ONLY_WOULD_TAKE")
                && accepted == null
                && stps.isEmpty()
                && before.book().equals(after.book()));
        hit(
            accumulator,
            "RULE_SET_ATTRIBUTION",
            source,
            history,
            index,
            !stps.isEmpty()
                && before.activeIdentity().version().signum() > 0
                && stps.stream()
                    .allMatch(
                        event ->
                            event.executionRuleSet().equals(before.activeIdentity())
                                && event.takerAdmissionRuleSet().equals(before.activeIdentity())));
        hit(
            accumulator,
            "MARKET_MODE_BEFORE_STP",
            source,
            history,
            index,
            !"OPEN".equals(before.marketMode())
                && code(placeRejected, "MARKET_NOT_OPEN")
                && stps.isEmpty()
                && before.book().equals(after.book()));
      }
    }
  }

  private static boolean sameExceptApplication(
      M07SemanticMarketState before, M07SemanticMarketState after) {
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

  private static boolean noSelfTrade(
      List<M07SemanticEvent.SelfTradePrevented> stps, List<M07SemanticEvent.Trade> trades) {
    return stps.stream()
        .noneMatch(
            stp ->
                trades.stream()
                    .anyMatch(
                        trade ->
                            trade.makerOrderId().equals(stp.makerOrderId())
                                && trade.takerOrderId().equals(stp.takerOrderId())));
  }

  private static boolean tradeStpTrade(List<M07SemanticEvent> events) {
    int firstTrade = firstIndex(events, M07SemanticEvent.Trade.class);
    int stp = firstIndex(events, M07SemanticEvent.SelfTradePrevented.class);
    int lastTrade = lastIndex(events, M07SemanticEvent.Trade.class);
    return firstTrade >= 0 && stp > firstTrade && lastTrade > stp;
  }

  private static boolean tradeStpTradeSamePrice(List<M07SemanticEvent> events) {
    if (!tradeStpTrade(events)) {
      return false;
    }
    List<M07SemanticEvent.Trade> trades = all(events, M07SemanticEvent.Trade.class);
    M07SemanticEvent.SelfTradePrevented stp =
        first(events, M07SemanticEvent.SelfTradePrevented.class);
    return trades.stream().allMatch(trade -> trade.priceTicks().equals(stp.makerPriceTicks()));
  }

  private static int distinctStpPrices(List<M07SemanticEvent.SelfTradePrevented> events) {
    return (int)
        events.stream()
            .map(M07SemanticEvent.SelfTradePrevented::makerPriceTicks)
            .distinct()
            .count();
  }

  private static BigInteger group(M07SemanticBook book, BigInteger orderId) {
    return java.util.stream.Stream.concat(book.bids().stream(), book.asks().stream())
        .flatMap(level -> level.orders().stream())
        .filter(order -> order.orderId().equals(orderId))
        .map(M07SemanticBook.RestingOrder::participantGroupId)
        .findFirst()
        .orElse(null);
  }

  private static boolean contains(M07SemanticBook book, BigInteger orderId) {
    return group(book, orderId) != null;
  }

  private static boolean code(M07SemanticEvent.Rejected event, String code) {
    return event != null && code.equals(event.code());
  }

  private static boolean code(M07SemanticEvent.PlaceRejected event, String code) {
    return event != null && code.equals(event.code());
  }

  private static M07SemanticEvent.SelfTradePrevented policy(
      List<M07SemanticEvent.SelfTradePrevented> events, String policy) {
    return events.stream()
        .filter(event -> policy.equals(event.stpPolicy()))
        .findFirst()
        .orElse(null);
  }

  private static <T> T first(List<M07SemanticEvent> events, Class<T> type) {
    return events.stream().filter(type::isInstance).map(type::cast).findFirst().orElse(null);
  }

  private static <T> List<T> all(List<M07SemanticEvent> events, Class<T> type) {
    return events.stream().filter(type::isInstance).map(type::cast).toList();
  }

  private static int firstIndex(List<M07SemanticEvent> events, Class<?> type) {
    for (int index = 0; index < events.size(); index++) {
      if (type.isInstance(events.get(index))) {
        return index;
      }
    }
    return -1;
  }

  private static int lastIndex(List<M07SemanticEvent> events, Class<?> type) {
    for (int index = events.size() - 1; index >= 0; index--) {
      if (type.isInstance(events.get(index))) {
        return index;
      }
    }
    return -1;
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
            "M07 finite corpus missed coverage obligations: " + missing);
      }
    }
  }

  private static final class Accumulator {
    private final Map<String, Witness> witnesses = new LinkedHashMap<>();
    private final Map<String, Integer> counts = new LinkedHashMap<>();
  }
}
