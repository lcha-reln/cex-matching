package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.CancelOrderInput;
import io.github.lchareln.cex.matching.PlaceLimitOrderInput;
import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

/** Stateful probes for validation-before-lifecycle rules outside the frozen semantic history. */
final class M02StatefulPriorityProbes {
  static final int EXPECTED_CHECKS = 4;

  Result verify(M02Candidate.Factory factory) {
    Objects.requireNonNull(factory, "factory");
    try {
      invalidDuplicatePlaceIsStillAValidationRejection(factory);
      invalidCancelCannotMutateAnActiveIdentity(factory);
      invalidCancelCannotInspectATerminalIdentity(factory);
      oversizedCancelIdentityIsRejectedBeforeNormalization(factory);
      return new Result(true, EXPECTED_CHECKS, "stateful validation priority matched");
    } catch (ProbeFailure failure) {
      return new Result(false, 0, failure.getMessage());
    }
  }

  private static void invalidDuplicatePlaceIsStillAValidationRejection(
      M02Candidate.Factory factory) {
    M02Candidate candidate = Objects.requireNonNull(factory.create(), "candidate");
    M02Candidate.Outcome seeded = candidate.place(place(9100, "BUY", 99, 2));
    M02ScenarioPack.Book before = seeded.bookAfter();

    M02Candidate.Outcome invalid =
        candidate.place(
            new PlaceLimitOrderInput(
                "BTC-USDT", BigInteger.valueOf(9100), "HOLD", BigInteger.ZERO, BigInteger.ZERO));
    check(
        invalid.events().equals(List.of(new M02ScenarioPack.Rejected("INVALID_SIDE", "side"))),
        "invalid duplicate Place did not honor validation priority");
    check(before.equals(invalid.bookAfter()), "invalid duplicate Place changed the book");

    M02Candidate.Outcome next = candidate.place(place(9101, "BUY", 98, 1));
    checkAcceptedSequence(next, 2, 9101, "invalid duplicate Place consumed acceptance sequence");
  }

  private static void invalidCancelCannotMutateAnActiveIdentity(M02Candidate.Factory factory) {
    M02Candidate candidate = Objects.requireNonNull(factory.create(), "candidate");
    M02Candidate.Outcome seeded = candidate.place(place(9200, "SELL", 101, 3));
    M02ScenarioPack.Book before = seeded.bookAfter();

    M02Candidate.Outcome invalid =
        candidate.cancel(new CancelOrderInput("ETH-USDT", BigInteger.valueOf(9200)));
    check(
        invalid
            .events()
            .equals(List.of(new M02ScenarioPack.Rejected("UNKNOWN_INSTRUMENT", "instrumentId"))),
        "invalid Cancel inspected or applied an active identity");
    check(before.equals(invalid.bookAfter()), "invalid Cancel changed an active order book");

    M02Candidate.Outcome valid =
        candidate.cancel(new CancelOrderInput("BTC-USDT", BigInteger.valueOf(9200)));
    check(
        valid.events().size() == 1
            && valid.events().getFirst() instanceof M02ScenarioPack.Canceled canceled
            && canceled.orderId() == 9200
            && canceled.canceledQuantityLots() == 3,
        "active identity was not cancelable after an invalid Cancel");
  }

  private static void invalidCancelCannotInspectATerminalIdentity(M02Candidate.Factory factory) {
    M02Candidate candidate = Objects.requireNonNull(factory.create(), "candidate");
    candidate.place(place(9300, "BUY", 97, 4));
    candidate.cancel(new CancelOrderInput("BTC-USDT", BigInteger.valueOf(9300)));

    M02Candidate.Outcome invalid =
        candidate.cancel(new CancelOrderInput("ETH-USDT", BigInteger.valueOf(9300)));
    check(
        invalid
            .events()
            .equals(List.of(new M02ScenarioPack.Rejected("UNKNOWN_INSTRUMENT", "instrumentId"))),
        "invalid Cancel inspected a canceled terminal identity");
    check(invalid.bookAfter().equals(M02ScenarioPack.Book.empty()), "terminal probe changed book");

    M02Candidate.Outcome repeated =
        candidate.cancel(new CancelOrderInput("BTC-USDT", BigInteger.valueOf(9300)));
    check(
        repeated
            .events()
            .equals(List.of(new M02ScenarioPack.CancelRejected(9300, "ORDER_ALREADY_CANCELED"))),
        "invalid terminal Cancel changed the stable repeated result");
  }

  private static void oversizedCancelIdentityIsRejectedBeforeNormalization(
      M02Candidate.Factory factory) {
    M02Candidate candidate = Objects.requireNonNull(factory.create(), "candidate");
    M02Candidate.Outcome seeded = candidate.place(place(9400, "BUY", 96, 1));
    M02ScenarioPack.Book before = seeded.bookAfter();
    BigInteger oversizedOrderId = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE);

    M02Candidate.Outcome invalid =
        candidate.cancel(new CancelOrderInput("BTC-USDT", oversizedOrderId));
    check(
        invalid
            .events()
            .equals(List.of(new M02ScenarioPack.Rejected("INVALID_ORDER_ID", "orderId"))),
        "oversized Cancel identity was normalized before validation");
    check(before.equals(invalid.bookAfter()), "oversized Cancel identity changed the book");

    M02Candidate.Outcome next = candidate.place(place(9401, "BUY", 95, 1));
    checkAcceptedSequence(next, 2, 9401, "oversized Cancel consumed acceptance sequence");
  }

  private static PlaceLimitOrderInput place(long orderId, String side, long price, long quantity) {
    return new PlaceLimitOrderInput(
        "BTC-USDT",
        BigInteger.valueOf(orderId),
        side,
        BigInteger.valueOf(price),
        BigInteger.valueOf(quantity));
  }

  private static void checkAcceptedSequence(
      M02Candidate.Outcome outcome, long sequence, long orderId, String message) {
    check(
        !outcome.events().isEmpty()
            && outcome.events().getFirst() instanceof M02ScenarioPack.Accepted accepted
            && accepted.sequence() == sequence
            && accepted.orderId() == orderId,
        message);
  }

  private static void check(boolean condition, String message) {
    if (!condition) {
      throw new ProbeFailure(message);
    }
  }

  record Result(boolean passed, int checks, String message) {}

  private static final class ProbeFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private ProbeFailure(String message) {
      super(message);
    }
  }
}
