package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

final class M04ShrinkerTest {
  @Test
  void allRequiredCounterexamplesBecomeOneMinimalAndReplayable() {
    List<Mutant> mutants =
        List.of(
            mutant(
                M04Mutants.iocRemainderRests(), "EXECUTION_POLICY_GRAMMAR", "IOC_REMAINDER_RESTED"),
            mutant(
                M04Mutants.iocBehavesLikeFok(),
                "IOC_IMMEDIATE_EXECUTION",
                "IOC_WAS_PRECHECK_REJECTED"),
            mutant(
                M04Mutants.fokPartialStateLeak(),
                "POLICY_REJECTION_ATOMICITY",
                "FOK_REJECTION_CHANGED_BOOK"),
            mutant(
                M04Mutants.fokBestLevelOnly(), "FOK_FILLABILITY", "MULTI_LEVEL_LIQUIDITY_IGNORED"),
            mutant(
                M04Mutants.fokIgnoresLimitPrice(),
                "FOK_FILLABILITY",
                "OUTSIDE_LIMIT_LIQUIDITY_COUNTED"),
            mutant(M04Mutants.postOnlyTouchAccepted(), "POST_ONLY_ADMISSION", "TOUCH_WAS_ACCEPTED"),
            mutant(
                M04Mutants.policyRejectConsumesIdentity(),
                "POLICY_REJECTION_ATOMICITY",
                "REJECTED_ID_RESERVED"),
            mutant(
                M04Mutants.unknownPolicyDefaultsGtc(),
                "VALIDATION_PRIORITY_AND_NO_MUTATION",
                "UNKNOWN_POLICY_ACCEPTED"));
    List<M04ScenarioCorpus.Scenario> scenarios =
        M04ScenarioCorpus.load(
            M04TestPaths.root().resolve(M04StartCheckRunner.FIXED_CORPUS_PATH),
            M04TestPaths.root().resolve(M04StartCheckRunner.FIXED_CORPUS_SCHEMA_PATH));

    for (Mutant mutant : mutants) {
      M04ScenarioCorpus.Scenario source = find(scenarios, mutant);
      M04Shrinker.Result result =
          new M04Shrinker()
              .shrink(
                  source.id(), "fixed", source.commands(), mutant.factory(), mutant.fingerprint());
      assertTrue(result.oneMinimal(), mutant.fingerprint().value());
      assertTrue(result.commands().size() <= source.commands().size());
      assertEquals(mutant.fingerprint().value(), result.observation().failure().fingerprint());
    }
  }

  private static M04ScenarioCorpus.Scenario find(
      List<M04ScenarioCorpus.Scenario> scenarios, Mutant mutant) {
    for (M04ScenarioCorpus.Scenario scenario : scenarios) {
      M04PropertyJudge.Observation observation =
          new M04PropertyJudge()
              .judge(scenario.id(), "fixed", scenario.commands(), mutant.factory());
      if (mutant.fingerprint().matches(observation)) {
        return scenario;
      }
    }
    throw new AssertionError("no fixed witness for " + mutant.fingerprint().value());
  }

  private static Mutant mutant(M04Candidate.Factory factory, String property, String divergence) {
    return new Mutant(factory, new M04Shrinker.Fingerprint(property, divergence));
  }

  private record Mutant(M04Candidate.Factory factory, M04Shrinker.Fingerprint fingerprint) {}
}
