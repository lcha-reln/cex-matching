package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.reference.ReferenceCommand;
import io.github.lchareln.cex.matching.reference.SemanticBook;
import io.github.lchareln.cex.matching.reference.SemanticEvent;
import io.github.lchareln.cex.matching.reference.SemanticOutcome;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Plausible semantic defects used to calibrate the generated M03 judge. */
final class M03Mutants {
  static final String BEST_PRICE_LAST_ID = "M03-BEST-PRICE-LAST";
  static final String SAME_PRICE_LIFO_ID = "M03-SAME-PRICE-LIFO";
  static final String TAKER_PRICE_ID = "M03-TAKER-PRICE-TRADE";
  static final String QUANTITY_OVERFLOW_ID = "M03-TRADE-QUANTITY-OVERFLOW";
  static final String CANCEL_GHOST_ID = "M03-CANCEL-GHOST-BOOK";
  static final String CANCELED_REUSE_ID = "M03-CANCELED-ID-REUSE";
  static final String SYSTEM_ERROR_ID = "M03-THROWING-CONTROL";

  private M03Mutants() {}

  static M03Candidate.Factory bestPriceLast(M03Candidate.Factory delegateFactory) {
    return () ->
        new Forwarding(delegateFactory.create()) {
          @Override
          public SemanticOutcome apply(ReferenceCommand command) {
            SemanticOutcome actual = delegate.apply(command);
            List<Integer> trades = tradeIndexes(actual.events());
            if (trades.size() < 2 || sameTradePrice(actual.events(), trades)) {
              return actual;
            }
            return new SemanticOutcome(reversedTrades(actual.events(), trades), actual.bookAfter());
          }
        };
  }

  static M03Candidate.Factory samePriceLifo(M03Candidate.Factory delegateFactory) {
    return () ->
        new Forwarding(delegateFactory.create()) {
          @Override
          public SemanticOutcome apply(ReferenceCommand command) {
            SemanticOutcome actual = delegate.apply(command);
            List<Integer> trades = tradeIndexes(actual.events());
            if (trades.size() < 2 || !sameTradePrice(actual.events(), trades)) {
              return actual;
            }
            return new SemanticOutcome(reversedTrades(actual.events(), trades), actual.bookAfter());
          }
        };
  }

  static M03Candidate.Factory takerPrice(M03Candidate.Factory delegateFactory) {
    return () ->
        new Forwarding(delegateFactory.create()) {
          @Override
          public SemanticOutcome apply(ReferenceCommand command) {
            SemanticOutcome actual = delegate.apply(command);
            if (!(command instanceof ReferenceCommand.Place place)) {
              return actual;
            }
            List<SemanticEvent> changed = new ArrayList<>(actual.events().size());
            boolean mutated = false;
            for (SemanticEvent event : actual.events()) {
              if (event instanceof SemanticEvent.Trade trade
                  && !trade.priceTicks().equals(place.priceTicks())) {
                changed.add(
                    new SemanticEvent.Trade(
                        trade.makerSequence(),
                        trade.makerOrderId(),
                        trade.takerSequence(),
                        trade.takerOrderId(),
                        place.priceTicks(),
                        trade.quantityLots()));
                mutated = true;
              } else {
                changed.add(event);
              }
            }
            return mutated ? new SemanticOutcome(changed, actual.bookAfter()) : actual;
          }
        };
  }

  static M03Candidate.Factory tradeQuantityOverflow(M03Candidate.Factory delegateFactory) {
    return () ->
        new Forwarding(delegateFactory.create()) {
          @Override
          public SemanticOutcome apply(ReferenceCommand command) {
            SemanticOutcome actual = delegate.apply(command);
            List<SemanticEvent> changed = new ArrayList<>(actual.events());
            for (int index = 0; index < changed.size(); index++) {
              if (changed.get(index) instanceof SemanticEvent.Trade trade) {
                changed.set(
                    index,
                    new SemanticEvent.Trade(
                        trade.makerSequence(),
                        trade.makerOrderId(),
                        trade.takerSequence(),
                        trade.takerOrderId(),
                        trade.priceTicks(),
                        trade.quantityLots().add(BigInteger.ONE)));
                return new SemanticOutcome(changed, actual.bookAfter());
              }
            }
            return actual;
          }
        };
  }

  static M03Candidate.Factory cancelGhostBook(M03Candidate.Factory delegateFactory) {
    return () ->
        new Forwarding(delegateFactory.create()) {
          private SemanticBook previous = SemanticBook.empty();

          @Override
          public SemanticOutcome apply(ReferenceCommand command) {
            SemanticBook before = previous;
            SemanticOutcome actual = delegate.apply(command);
            previous = actual.bookAfter();
            return command instanceof ReferenceCommand.Cancel
                    && actual.events().getFirst() instanceof SemanticEvent.Canceled
                ? new SemanticOutcome(actual.events(), before)
                : actual;
          }
        };
  }

  static M03Candidate.Factory canceledIdentityReuse(M03Candidate.Factory delegateFactory) {
    return () ->
        new Forwarding(delegateFactory.create()) {
          private final Set<BigInteger> canceledIds = new HashSet<>();
          private BigInteger nextSequence = BigInteger.ONE;

          @Override
          public SemanticOutcome apply(ReferenceCommand command) {
            SemanticOutcome actual = delegate.apply(command);
            for (SemanticEvent event : actual.events()) {
              if (event instanceof SemanticEvent.Accepted accepted) {
                nextSequence = accepted.sequence().add(BigInteger.ONE);
              } else if (event instanceof SemanticEvent.Canceled canceled) {
                canceledIds.add(canceled.orderId());
              }
            }
            if (!(command instanceof ReferenceCommand.Place place)
                || !(actual.events().getFirst() instanceof SemanticEvent.PlaceRejected rejected)
                || !canceledIds.contains(rejected.orderId())) {
              return actual;
            }
            SemanticBook resurrected =
                addResting(
                    actual.bookAfter(),
                    place.side(),
                    place.priceTicks(),
                    new SemanticBook.RestingOrder(
                        nextSequence, place.orderId(), place.quantityLots()));
            return new SemanticOutcome(
                List.of(
                    new SemanticEvent.Accepted(
                        nextSequence,
                        place.orderId(),
                        place.side(),
                        place.priceTicks(),
                        place.quantityLots()),
                    new SemanticEvent.Rested(
                        nextSequence,
                        place.orderId(),
                        place.side(),
                        place.priceTicks(),
                        place.quantityLots())),
                resurrected);
          }
        };
  }

  static M03Candidate.Factory throwingControl() {
    return () ->
        command -> {
          throw new IllegalStateException("intentional M03 system error control");
        };
  }

  private static List<Integer> tradeIndexes(List<SemanticEvent> events) {
    List<Integer> indexes = new ArrayList<>();
    for (int index = 0; index < events.size(); index++) {
      if (events.get(index) instanceof SemanticEvent.Trade) {
        indexes.add(index);
      }
    }
    return indexes;
  }

  private static boolean sameTradePrice(List<SemanticEvent> events, List<Integer> indexes) {
    BigInteger first = ((SemanticEvent.Trade) events.get(indexes.getFirst())).priceTicks();
    return indexes.stream()
        .map(index -> ((SemanticEvent.Trade) events.get(index)).priceTicks())
        .allMatch(first::equals);
  }

  private static List<SemanticEvent> reversedTrades(
      List<SemanticEvent> events, List<Integer> indexes) {
    List<SemanticEvent> reversed = new ArrayList<>(events);
    List<SemanticEvent> trades = new ArrayList<>();
    indexes.forEach(index -> trades.add(events.get(index)));
    Collections.reverse(trades);
    for (int index = 0; index < indexes.size(); index++) {
      reversed.set(indexes.get(index), trades.get(index));
    }
    return List.copyOf(reversed);
  }

  private static SemanticBook addResting(
      SemanticBook book, String side, BigInteger price, SemanticBook.RestingOrder order) {
    List<SemanticBook.PriceLevel> levels =
        new ArrayList<>("BUY".equals(side) ? book.bids() : book.asks());
    boolean appended = false;
    for (int index = 0; index < levels.size(); index++) {
      SemanticBook.PriceLevel level = levels.get(index);
      if (level.priceTicks().equals(price)) {
        List<SemanticBook.RestingOrder> orders = new ArrayList<>(level.orders());
        orders.add(order);
        orders.sort(Comparator.comparing(SemanticBook.RestingOrder::sequence));
        levels.set(index, new SemanticBook.PriceLevel(side, price, orders));
        appended = true;
        break;
      }
    }
    if (!appended) {
      levels.add(new SemanticBook.PriceLevel(side, price, List.of(order)));
    }
    Comparator<SemanticBook.PriceLevel> byPrice =
        Comparator.comparing(SemanticBook.PriceLevel::priceTicks);
    levels.sort("BUY".equals(side) ? byPrice.reversed() : byPrice);
    return "BUY".equals(side)
        ? new SemanticBook(levels, book.asks())
        : new SemanticBook(book.bids(), levels);
  }

  private abstract static class Forwarding implements M03Candidate {
    final M03Candidate delegate;

    private Forwarding(M03Candidate delegate) {
      this.delegate = delegate;
    }
  }
}
