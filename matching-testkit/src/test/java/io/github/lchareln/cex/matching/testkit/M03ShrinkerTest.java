package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lchareln.cex.matching.reference.ReferenceCommand;
import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class M03ShrinkerTest {
  private static final String SEED = "0000000000001aa8";

  @Test
  void reductionIsDeterministicFreshStateAndExactFingerprintPreserving() {
    List<ReferenceCommand> original =
        List.of(
            place(900, "BUY", 1, 1),
            place(1, "SELL", 90, 1),
            place(2, "SELL", 100, 1),
            place(3, "BUY", 100, 2),
            cancel(900));
    M03Shrinker.Fingerprint fingerprint =
        new M03Shrinker.Fingerprint("PRICE_TIME_PRIORITY", "WRONG_MAKER_ORDER");
    AtomicInteger creations = new AtomicInteger();
    M03Candidate.Factory countedProduction =
        () -> {
          creations.incrementAndGet();
          return new M03ProductionCandidate();
        };
    M03Candidate.Factory mutant = M03Mutants.bestPriceLast(countedProduction);

    M03Shrinker.Result first =
        new M03Shrinker().shrink("best-price", SEED, original, mutant, fingerprint);
    assertEquals(first.trials(), creations.get());
    assertTrue(first.commands().size() < original.size());
    assertTrue(first.oneMinimal());
    assertTrue(first.trials() <= M03Shrinker.MAX_TRIALS);
    assertTrue(fingerprint.matches(first.observation()));

    M03Shrinker.Result second =
        new M03Shrinker()
            .shrink(
                "best-price",
                SEED,
                original,
                M03Mutants.bestPriceLast(M03ProductionCandidate::new),
                fingerprint);
    assertEquals(first.commands(), second.commands());
    assertEquals(first.trials(), second.trials());
  }

  @Test
  void everyRequiredMutantShrinksToAOneMinimalStudentFailure() {
    assertShrinks(
        List.of(place(1, "SELL", 90, 1), place(2, "SELL", 100, 1), place(3, "BUY", 100, 2)),
        M03Mutants.bestPriceLast(M03ProductionCandidate::new),
        "PRICE_TIME_PRIORITY",
        "WRONG_MAKER_ORDER");
    assertShrinks(
        List.of(place(1, "SELL", 100, 1), place(2, "SELL", 100, 1), place(3, "BUY", 100, 2)),
        M03Mutants.samePriceLifo(M03ProductionCandidate::new),
        "PRICE_TIME_PRIORITY",
        "WRONG_MAKER_ORDER");
    assertShrinks(
        List.of(place(1, "SELL", 90, 1), place(2, "BUY", 100, 1)),
        M03Mutants.takerPrice(M03ProductionCandidate::new),
        "MAKER_PRICE",
        "TRADE_PRICE");
    assertShrinks(
        List.of(place(1, "SELL", 90, 1), place(2, "BUY", 100, 1)),
        M03Mutants.tradeQuantityOverflow(M03ProductionCandidate::new),
        "QUANTITY_PARTITION",
        "TRADE_EXCEEDS_REMAINDER");
    assertShrinks(
        List.of(place(1, "BUY", 100, 2), cancel(1)),
        M03Mutants.cancelGhostBook(M03ProductionCandidate::new),
        "BOOK_LIFECYCLE_BIJECTION",
        "ACTIVE_ID_SET");
    assertShrinks(
        List.of(place(1, "BUY", 100, 2), cancel(1), place(1, "BUY", 100, 2)),
        M03Mutants.canceledIdentityReuse(M03ProductionCandidate::new),
        "LIFECYCLE_IRREVERSIBILITY",
        "TERMINAL_OR_ACTIVE_ID_REUSED");
  }

  @Test
  void wrongFingerprintAndSystemErrorsCannotBeShrunkAsStudentFailures() {
    List<ReferenceCommand> commands = List.of(place(1, "SELL", 90, 1), place(2, "BUY", 100, 1));
    M03Shrinker.Fingerprint wrong = new M03Shrinker.Fingerprint("MAKER_PRICE", "WRONG_KIND");

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new M03Shrinker()
                .shrink(commands, M03Mutants.takerPrice(M03ProductionCandidate::new), wrong));
    IllegalStateException systemError =
        assertThrows(
            IllegalStateException.class,
            () ->
                new M03Shrinker()
                    .shrink(
                        commands,
                        M03Mutants.throwingControl(),
                        new M03Shrinker.Fingerprint("ANY_PROPERTY", "ANY_KIND")));
    assertTrue(systemError.getMessage().contains("SYSTEM_ERROR"));
  }

  @Test
  void aSystemErrorDuringAnyReductionTrialFailsClosedImmediately() {
    List<ReferenceCommand> original =
        List.of(place(1, "SELL", 90, 1), place(2, "SELL", 100, 1), place(3, "BUY", 100, 2));
    M03Candidate.Factory factory =
        () -> {
          M03Candidate delegate = M03Mutants.bestPriceLast(M03ProductionCandidate::new).create();
          return new M03Candidate() {
            private boolean first = true;

            @Override
            public io.github.lchareln.cex.matching.reference.SemanticOutcome apply(
                ReferenceCommand command) {
              if (first
                  && command instanceof ReferenceCommand.Place place
                  && !BigInteger.ONE.equals(place.orderId())) {
                throw new IllegalStateException("intentional shrink-trial control");
              }
              first = false;
              return delegate.apply(command);
            }
          };
        };

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                new M03Shrinker()
                    .shrink(
                        original,
                        factory,
                        new M03Shrinker.Fingerprint("PRICE_TIME_PRIORITY", "WRONG_MAKER_ORDER")));

    assertTrue(failure.getMessage().contains("SYSTEM_ERROR"));
  }

  private static void assertShrinks(
      List<ReferenceCommand> commands,
      M03Candidate.Factory factory,
      String propertyId,
      String divergenceKind) {
    M03Shrinker.Fingerprint fingerprint = new M03Shrinker.Fingerprint(propertyId, divergenceKind);
    M03Shrinker.Result result = new M03Shrinker().shrink(commands, factory, fingerprint);
    assertTrue(fingerprint.matches(result.observation()));
    assertTrue(result.oneMinimal());
    assertTrue(result.trials() <= M03Shrinker.MAX_TRIALS);
  }

  private static ReferenceCommand place(long orderId, String side, long price, long quantity) {
    return new ReferenceCommand.Place(
        "BTC-USDT",
        BigInteger.valueOf(orderId),
        side,
        BigInteger.valueOf(price),
        BigInteger.valueOf(quantity));
  }

  private static ReferenceCommand cancel(long orderId) {
    return new ReferenceCommand.Cancel("BTC-USDT", BigInteger.valueOf(orderId));
  }
}
