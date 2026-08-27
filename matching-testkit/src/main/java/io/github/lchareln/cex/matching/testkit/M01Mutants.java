package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.PlaceLimitOrderInput;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Required semantic mutants remain testkit-only and never enter the production engine. */
final class M01Mutants {
  static final String TAKER_PRICE_ID = "M01-TAKER-PRICE";
  static final String LIFO_ID = "M01-SAME-PRICE-LIFO";
  static final String SKIP_MAKER_ID = "M01-SKIP-FIRST-MAKER";
  static final String SYSTEM_ERROR_ID = "M01-SYSTEM-ERROR-CONTROL";

  private M01Mutants() {}

  static M01Candidate.Factory makerUsesTakerPrice(M01Candidate.Factory delegate) {
    return () -> {
      M01Candidate candidate = delegate.create();
      return input -> mutateTradePrices(candidate.place(input), input);
    };
  }

  static M01Candidate.Factory samePriceLifo(M01Candidate.Factory delegate) {
    return () -> {
      M01Candidate candidate = delegate.create();
      return input -> reverseSamePriceTrades(candidate.place(input));
    };
  }

  static M01Candidate.Factory skipsFirstMaker(M01Candidate.Factory delegate) {
    return () -> {
      M01Candidate candidate = delegate.create();
      return input -> removeFirstOfMultipleTrades(candidate.place(input));
    };
  }

  static M01Candidate.Factory throwingControl() {
    return () ->
        input -> {
          throw new IllegalStateException("intentional M01 system-error control");
        };
  }

  private static M01Candidate.Outcome mutateTradePrices(
      M01Candidate.Outcome outcome, PlaceLimitOrderInput input) {
    long takerPrice;
    try {
      takerPrice = input.priceTicks().longValueExact();
    } catch (ArithmeticException exception) {
      return outcome;
    }
    List<M01ScenarioPack.Event> events = new ArrayList<>(outcome.events().size());
    for (M01ScenarioPack.Event event : outcome.events()) {
      if (event instanceof M01ScenarioPack.Trade trade) {
        events.add(
            new M01ScenarioPack.Trade(
                trade.makerSequence(),
                trade.makerOrderId(),
                trade.takerSequence(),
                trade.takerOrderId(),
                takerPrice,
                trade.quantityLots()));
      } else {
        events.add(event);
      }
    }
    return new M01Candidate.Outcome(events, outcome.bookAfter());
  }

  private static M01Candidate.Outcome reverseSamePriceTrades(M01Candidate.Outcome outcome) {
    List<Integer> tradeIndexes = new ArrayList<>();
    Long price = null;
    for (int index = 0; index < outcome.events().size(); index++) {
      if (outcome.events().get(index) instanceof M01ScenarioPack.Trade trade) {
        if (price == null) {
          price = trade.priceTicks();
        } else if (price.longValue() != trade.priceTicks()) {
          return outcome;
        }
        tradeIndexes.add(index);
      }
    }
    if (tradeIndexes.size() < 2) {
      return outcome;
    }
    List<M01ScenarioPack.Event> events = new ArrayList<>(outcome.events());
    List<M01ScenarioPack.Event> trades =
        tradeIndexes.stream()
            .map(outcome.events()::get)
            .collect(java.util.stream.Collectors.toList());
    Collections.reverse(trades);
    for (int index = 0; index < tradeIndexes.size(); index++) {
      events.set(tradeIndexes.get(index), trades.get(index));
    }
    return new M01Candidate.Outcome(events, outcome.bookAfter());
  }

  private static M01Candidate.Outcome removeFirstOfMultipleTrades(M01Candidate.Outcome outcome) {
    long trades = outcome.events().stream().filter(M01ScenarioPack.Trade.class::isInstance).count();
    if (trades < 2) {
      return outcome;
    }
    List<M01ScenarioPack.Event> events = new ArrayList<>(outcome.events());
    for (int index = 0; index < events.size(); index++) {
      if (events.get(index) instanceof M01ScenarioPack.Trade) {
        events.remove(index);
        break;
      }
    }
    return new M01Candidate.Outcome(events, outcome.bookAfter());
  }
}
