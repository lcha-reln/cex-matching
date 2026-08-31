package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.github.lchareln.cex.matching.reference.M06SemanticBook;
import io.github.lchareln.cex.matching.reference.M06SemanticEvent;
import io.github.lchareln.cex.matching.reference.M06SemanticOutcome;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

final class M06CompletionSemanticsTest {
  private static Path root() {
    return Path.of(System.getProperty("matching.repositoryRoot")).toAbsolutePath().normalize();
  }

  @Test
  void fixedGeneratedCoverageAndMutantsFormOneClosedJudgeBoundary() {
    M06FixedScenarioRunner.Result fixed =
        new M06FixedScenarioRunner().run(root(), M06ProductionCandidate::new);
    assertEquals(15, fixed.scenarios());
    assertEquals(64, fixed.commands());
    assertEquals(64, fixed.differentialComparisons());
    assertEquals(64, fixed.ledgerChecks());

    M06Corpus.Fixed corpus = M06Corpus.loadFixed(root());
    M06Corpus.Profile profile = M06Corpus.loadProfile(root());
    M06GeneratedSuite generator = new M06GeneratedSuite();
    List<M06GeneratedSuite.History> first = generator.generate(profile, corpus);
    List<M06GeneratedSuite.History> second = generator.generate(profile, corpus);
    M06Canonical.Canonical firstCanonical = M06Canonical.generated(first);
    M06Canonical.Canonical secondCanonical = M06Canonical.generated(second);
    assertArrayEquals(firstCanonical.bytes(), secondCanonical.bytes());
    assertEquals(firstCanonical.digest(), secondCanonical.digest());

    M06PropertyJudge judge = new M06PropertyJudge();
    int commands = 0;
    for (M06GeneratedSuite.History history : first) {
      M06PropertyJudge.Observation observation =
          judge.judge(history.commands(), M06ProductionCandidate::new);
      assertEquals(M06PropertyJudge.PASS, observation.classification(), observation.message());
      commands += observation.completedCommands();
    }
    assertEquals(10_240, commands);

    M06Coverage.Result coverage = new M06Coverage().analyze(corpus, first);
    coverage.assertComplete();
    assertEquals(26, coverage.satisfied());

    M06CounterexampleSuite.Result counterexamples = new M06CounterexampleSuite().run(root());
    assertEquals(10, counterexamples.counterexamples().size());
    assertEquals(10, counterexamples.replay().passed());
    assertEquals(M06PropertyJudge.SYSTEM_ERROR, counterexamples.systemErrorControl());
  }

  @Test
  void acceptedNamedPermissionMutantsActuallyCrossThePermissionBoundary() {
    M06Mutants.Mutant placeMutant = M06Mutants.required().get(0);
    M06SemanticOutcome placeOutcome = execute(placeMutant);
    assertInstanceOf(M06SemanticEvent.Accepted.class, placeOutcome.events().getFirst());
    assertInstanceOf(M06SemanticEvent.Rested.class, placeOutcome.events().getLast());
    assertEquals(2, placeOutcome.stateAfter().nextAcceptanceSequence().intValueExact());

    M06Mutants.Mutant cancelMutant = M06Mutants.required().get(1);
    M06SemanticOutcome cancelOutcome = execute(cancelMutant);
    assertInstanceOf(M06SemanticEvent.Canceled.class, cancelOutcome.events().getFirst());
    assertEquals(M06SemanticBook.empty(), cancelOutcome.stateAfter().book());
  }

  private static M06SemanticOutcome execute(M06Mutants.Mutant mutant) {
    M06Candidate candidate = mutant.factory().create();
    M06SemanticOutcome outcome = null;
    for (var command : mutant.seedCommands()) {
      outcome = candidate.apply(command);
    }
    return java.util.Objects.requireNonNull(outcome);
  }
}
