package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lchareln.cex.matching.reference.M07SemanticBook;
import io.github.lchareln.cex.matching.reference.M07SemanticEvent;
import io.github.lchareln.cex.matching.reference.M07SemanticOutcome;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class M07CompletionSemanticsTest {
  private static Path root() {
    return Path.of(System.getProperty("matching.repositoryRoot")).toAbsolutePath().normalize();
  }

  @Test
  void fixedGeneratedCoverageAndMutantsFormOneClosedJudgeBoundary() {
    M07FixedScenarioRunner.Result fixed =
        new M07FixedScenarioRunner().run(root(), M07ProductionCandidate::new);
    assertEquals(16, fixed.scenarios());
    assertEquals(72, fixed.commands());
    assertEquals(72, fixed.differentialComparisons());
    assertEquals(72, fixed.ledgerChecks());

    M07Corpus.Fixed corpus = M07Corpus.loadFixed(root());
    M07Corpus.Profile profile = M07Corpus.loadProfile(root());
    M07GeneratedSuite generator = new M07GeneratedSuite();
    List<M07GeneratedSuite.History> first = generator.generate(profile, corpus);
    List<M07GeneratedSuite.History> second = generator.generate(profile, corpus);
    M07Canonical.Canonical firstCanonical = M07Canonical.generated(first);
    M07Canonical.Canonical secondCanonical = M07Canonical.generated(second);
    assertArrayEquals(firstCanonical.bytes(), secondCanonical.bytes());
    assertEquals(firstCanonical.digest(), secondCanonical.digest());

    M07PropertyJudge judge = new M07PropertyJudge();
    int commands = 0;
    for (M07GeneratedSuite.History history : first) {
      M07PropertyJudge.Observation observation =
          judge.judge(history.commands(), M07ProductionCandidate::new);
      assertEquals(M07PropertyJudge.PASS, observation.classification(), observation.message());
      commands += observation.completedCommands();
    }
    assertEquals(10_240, commands);

    M07Coverage.Result coverage = new M07Coverage().analyze(corpus, first);
    coverage.assertComplete();
    assertEquals(24, coverage.satisfied());

    M07CounterexampleSuite.Result counterexamples = new M07CounterexampleSuite().run(root());
    assertEquals(8, counterexamples.counterexamples().size());
    assertEquals(8, counterexamples.replay().passed());
    assertEquals(M07PropertyJudge.SYSTEM_ERROR, counterexamples.systemErrorControl());

    Map<String, Integer> minimizedLengths = new LinkedHashMap<>();
    M07PropertyJudge mutantJudge = new M07PropertyJudge();
    for (M07CounterexampleSuite.Counterexample counterexample : counterexamples.counterexamples()) {
      minimizedLengths.put(counterexample.mutantId(), counterexample.minimized().size());
      assertTrue(
          counterexample.minimized().size() >= 2,
          counterexample.mutantId() + " must reach a maker/taker interaction");
      M07Mutants.Mutant mutant =
          M07Mutants.required().stream()
              .filter(value -> value.id().equals(counterexample.mutantId()))
              .findFirst()
              .orElseThrow();
      for (int prefixLength = 1; prefixLength < counterexample.minimized().size(); prefixLength++) {
        assertEquals(
            M07PropertyJudge.PASS,
            mutantJudge
                .judge(counterexample.minimized().subList(0, prefixLength), mutant.factory())
                .classification(),
            counterexample.mutantId() + " diverged before its final interaction command");
      }
      assertEquals(
          counterexample.minimized().size() - 1,
          counterexample.observation().commandIndex(),
          counterexample.mutantId() + " first difference must be the interaction command");
    }
    assertTrue(
        minimizedLengths.get("M07-FOK-COUNTS-RAW-SELF-LIQUIDITY") >= 3,
        "FOK mutant needs self, external, and taker commands");
    assertTrue(
        minimizedLengths.get("M07-CANCEL-MAKER-BEST-LEVEL-ONLY") >= 3,
        "cross-level mutant needs two self levels and a taker command");
  }

  @Test
  void everyNamedMutantIsABusinessFailureAndThrowingControlIsNotAKill() {
    M07PropertyJudge judge = new M07PropertyJudge();
    for (M07Mutants.Mutant mutant : M07Mutants.required()) {
      assertEquals(
          M07PropertyJudge.STUDENT_FAILURE,
          judge.judge(mutant.seedCommands(), mutant.factory()).classification(),
          mutant.id());
    }
    assertEquals(
        M07PropertyJudge.SYSTEM_ERROR,
        judge
            .judge(M07Mutants.required().getFirst().seedCommands(), M07Mutants.systemErrorControl())
            .classification());
  }

  @Test
  void firstTwoMutantsCrossTheirNamedBusinessBoundaryWithCoherentEventsBookAndSequences() {
    M07Mutants.Mutant sameGroupTradeAllowed = M07Mutants.required().get(0);
    M07Candidate tradeCandidate = sameGroupTradeAllowed.factory().create();
    tradeCandidate.apply(sameGroupTradeAllowed.seedCommands().get(0));
    M07SemanticOutcome traded = tradeCandidate.apply(sameGroupTradeAllowed.seedCommands().get(1));
    M07SemanticEvent.Trade trade =
        assertInstanceOf(
            M07SemanticEvent.Trade.class,
            traded.events().stream()
                .filter(M07SemanticEvent.Trade.class::isInstance)
                .findFirst()
                .orElseThrow());
    assertEquals(BigInteger.ONE, trade.makerSequence());
    assertEquals(BigInteger.TWO, trade.takerSequence());
    assertTrue(traded.stateAfter().book().bids().isEmpty());
    assertTrue(traded.stateAfter().book().asks().isEmpty());
    assertEquals(BigInteger.valueOf(3), traded.stateAfter().nextAcceptanceSequence());
    assertEquals(traded.stateAfter(), tradeCandidate.snapshot());

    M07Mutants.Mutant differentGroupCanceled = M07Mutants.required().get(1);
    M07Candidate cancelCandidate = differentGroupCanceled.factory().create();
    cancelCandidate.apply(differentGroupCanceled.seedCommands().get(0));
    M07SemanticOutcome canceled =
        cancelCandidate.apply(differentGroupCanceled.seedCommands().get(1));
    M07SemanticEvent.SelfTradePrevented prevention =
        assertInstanceOf(
            M07SemanticEvent.SelfTradePrevented.class,
            canceled.events().stream()
                .filter(M07SemanticEvent.SelfTradePrevented.class::isInstance)
                .findFirst()
                .orElseThrow());
    assertEquals(BigInteger.ONE, prevention.makerSequence());
    assertEquals(BigInteger.TWO, prevention.takerSequence());
    assertEquals("CANCEL_TAKER", prevention.stpPolicy());
    assertFalse(canceled.events().stream().anyMatch(M07SemanticEvent.Trade.class::isInstance));
    assertTrue(canceled.stateAfter().book().bids().isEmpty());
    M07SemanticBook.RestingOrder survivingMaker =
        canceled.stateAfter().book().asks().getFirst().orders().getFirst();
    assertEquals(BigInteger.valueOf(11), survivingMaker.orderId());
    assertEquals(BigInteger.TWO, survivingMaker.remainingQuantityLots());
    assertEquals(BigInteger.valueOf(7), survivingMaker.participantGroupId());
    assertEquals(BigInteger.valueOf(3), canceled.stateAfter().nextAcceptanceSequence());
    assertEquals(canceled.stateAfter(), cancelCandidate.snapshot());
  }

  @Test
  void allThreeLegacyProductionEntrypointsMapExactlyToZeroNone() {
    M07LegacyEntrypointRegression.Result result = new M07LegacyEntrypointRegression().run();
    assertEquals(3, result.entrypoints());
    assertEquals(true, result.byteEquivalentSemantics());
    assertEquals(0, result.participantGroupId());
    assertEquals("NONE", result.stpPolicy());
  }
}
