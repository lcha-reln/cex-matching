package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.CancelOrderInput;
import io.github.lchareln.cex.matching.PlaceLimitOrderInput;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Deliberately plausible M02 semantic faults used to prove the judge is discriminating. */
final class M02Mutants {
  static final String WRONG_FIFO_ID = "M02-CANCEL-WRONG-FIFO-ORDER";
  static final String GHOST_RESTING_ID = "M02-GHOST-RESTING-ORDER";
  static final String TERMINAL_REUSE_ID = "M02-TERMINAL-ID-REUSE";
  static final String REPEATED_CANCEL_ID = "M02-REPEATED-CANCEL-SUCCEEDS";
  static final String SYSTEM_ERROR_ID = "M02-THROWING-CONTROL";

  private M02Mutants() {}

  static M02Candidate.Factory wrongFifoAfterMiddleCancel(M02Candidate.Factory delegateFactory) {
    return () ->
        new ForwardingCandidate(delegateFactory.create()) {
          @Override
          public Outcome cancel(CancelOrderInput input) {
            Outcome actual = delegate.cancel(input);
            if (input.orderId().longValueExact() != 301) {
              return actual;
            }
            List<M02ScenarioPack.Level> asks = new ArrayList<>(actual.bookAfter().asks());
            if (!asks.isEmpty() && asks.getFirst().orders().size() == 2) {
              List<M02ScenarioPack.RestingOrder> reversed =
                  new ArrayList<>(asks.getFirst().orders());
              java.util.Collections.reverse(reversed);
              asks.set(0, new M02ScenarioPack.Level(asks.getFirst().priceTicks(), reversed));
            }
            return new Outcome(
                actual.events(), new M02ScenarioPack.Book(actual.bookAfter().bids(), asks));
          }
        };
  }

  static M02Candidate.Factory ghostRestingOrder(M02Candidate.Factory delegateFactory) {
    return () ->
        new ForwardingCandidate(delegateFactory.create()) {
          private M02ScenarioPack.Book previous = M02ScenarioPack.Book.empty();

          @Override
          public Outcome place(PlaceLimitOrderInput input) {
            Outcome actual = delegate.place(input);
            previous = actual.bookAfter();
            return actual;
          }

          @Override
          public Outcome cancel(CancelOrderInput input) {
            M02ScenarioPack.Book before = previous;
            Outcome actual = delegate.cancel(input);
            previous = actual.bookAfter();
            return input.orderId().longValueExact() == 200
                ? new Outcome(actual.events(), before)
                : actual;
          }
        };
  }

  static M02Candidate.Factory terminalIdentityReuse(M02Candidate.Factory delegateFactory) {
    return () ->
        new ForwardingCandidate(delegateFactory.create()) {
          @Override
          public Outcome place(PlaceLimitOrderInput input) {
            Outcome actual = delegate.place(input);
            if (input.orderId().longValueExact() != 1000
                || actual.events().isEmpty()
                || !(actual.events().getFirst() instanceof M02ScenarioPack.PlaceRejected)) {
              return actual;
            }
            long sequence = 2;
            long price = input.priceTicks().longValueExact();
            long quantity = input.quantityLots().longValueExact();
            M02ScenarioPack.RestingOrder order =
                new M02ScenarioPack.RestingOrder(sequence, 1000, quantity);
            M02ScenarioPack.Level level = new M02ScenarioPack.Level(price, List.of(order));
            M02ScenarioPack.Book book =
                "BUY".equals(input.side())
                    ? new M02ScenarioPack.Book(List.of(level), List.of())
                    : new M02ScenarioPack.Book(List.of(), List.of(level));
            return new Outcome(
                List.of(
                    new M02ScenarioPack.Accepted(sequence, 1000, input.side(), price, quantity),
                    new M02ScenarioPack.Rested(sequence, 1000, input.side(), price, quantity)),
                book);
          }
        };
  }

  static M02Candidate.Factory repeatedCancelSucceeds(M02Candidate.Factory delegateFactory) {
    return () ->
        new ForwardingCandidate(delegateFactory.create()) {
          private final Map<Long, M02ScenarioPack.Canceled> successful = new HashMap<>();

          @Override
          public Outcome cancel(CancelOrderInput input) {
            Outcome actual = delegate.cancel(input);
            if (actual.events().getFirst() instanceof M02ScenarioPack.Canceled canceled) {
              successful.put(canceled.orderId(), canceled);
              return actual;
            }
            long orderId = input.orderId().longValueExact();
            if (orderId == 700
                && actual.events().getFirst() instanceof M02ScenarioPack.CancelRejected
                && successful.containsKey(orderId)) {
              return new Outcome(List.of(successful.get(orderId)), actual.bookAfter());
            }
            return actual;
          }
        };
  }

  static M02Candidate.Factory throwingControl() {
    return () ->
        new M02Candidate() {
          @Override
          public Outcome place(PlaceLimitOrderInput input) {
            throw new IllegalStateException("intentional M02 system error control");
          }

          @Override
          public Outcome cancel(CancelOrderInput input) {
            throw new IllegalStateException("intentional M02 system error control");
          }
        };
  }

  private abstract static class ForwardingCandidate implements M02Candidate {
    protected final M02Candidate delegate;

    private ForwardingCandidate(M02Candidate delegate) {
      this.delegate = delegate;
    }

    @Override
    public Outcome place(PlaceLimitOrderInput input) {
      return delegate.place(input);
    }

    @Override
    public Outcome cancel(CancelOrderInput input) {
      return delegate.cancel(input);
    }
  }
}
