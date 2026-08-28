package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import org.junit.jupiter.api.Test;

final class M04MutantsTest {
  @Test
  void fixedCorpusKillsEveryRequiredMutantWithItsFrozenFingerprint() {
    List<Mutant> mutants =
        List.of(
            new Mutant(
                M04Mutants.IOC_REMAINDER_RESTS,
                M04Mutants.iocRemainderRests(),
                "EXECUTION_POLICY_GRAMMAR/IOC_REMAINDER_RESTED"),
            new Mutant(
                M04Mutants.IOC_BEHAVES_LIKE_FOK,
                M04Mutants.iocBehavesLikeFok(),
                "IOC_IMMEDIATE_EXECUTION/IOC_WAS_PRECHECK_REJECTED"),
            new Mutant(
                M04Mutants.FOK_PARTIAL_STATE_LEAK,
                M04Mutants.fokPartialStateLeak(),
                "POLICY_REJECTION_ATOMICITY/FOK_REJECTION_CHANGED_BOOK"),
            new Mutant(
                M04Mutants.FOK_BEST_LEVEL_ONLY,
                M04Mutants.fokBestLevelOnly(),
                "FOK_FILLABILITY/MULTI_LEVEL_LIQUIDITY_IGNORED"),
            new Mutant(
                M04Mutants.FOK_IGNORES_LIMIT_PRICE,
                M04Mutants.fokIgnoresLimitPrice(),
                "FOK_FILLABILITY/OUTSIDE_LIMIT_LIQUIDITY_COUNTED"),
            new Mutant(
                M04Mutants.POST_ONLY_TOUCH_ACCEPTED,
                M04Mutants.postOnlyTouchAccepted(),
                "POST_ONLY_ADMISSION/TOUCH_WAS_ACCEPTED"),
            new Mutant(
                M04Mutants.POLICY_REJECT_CONSUMES_IDENTITY,
                M04Mutants.policyRejectConsumesIdentity(),
                "POLICY_REJECTION_ATOMICITY/REJECTED_ID_RESERVED"),
            new Mutant(
                M04Mutants.UNKNOWN_POLICY_DEFAULTS_GTC,
                M04Mutants.unknownPolicyDefaultsGtc(),
                "VALIDATION_PRIORITY_AND_NO_MUTATION/UNKNOWN_POLICY_ACCEPTED"));
    List<M04ScenarioCorpus.Scenario> scenarios =
        M04ScenarioCorpus.load(
            M04TestPaths.root().resolve(M04StartCheckRunner.FIXED_CORPUS_PATH),
            M04TestPaths.root().resolve(M04StartCheckRunner.FIXED_CORPUS_SCHEMA_PATH));

    for (Mutant mutant : mutants) {
      M04PropertyJudge.Observation found = null;
      for (M04ScenarioCorpus.Scenario scenario : scenarios) {
        M04PropertyJudge.Observation observation =
            new M04PropertyJudge()
                .judge(scenario.id(), "fixed", scenario.commands(), mutant.factory());
        if (M04PropertyJudge.STUDENT_FAILURE.equals(observation.classification())
            && observation.failure() != null
            && mutant.fingerprint().equals(observation.failure().fingerprint())) {
          found = observation;
          break;
        }
      }
      assertNotNull(found, mutant.id());
      assertEquals(M04PropertyJudge.STUDENT_FAILURE, found.classification(), mutant.id());
    }
  }

  @Test
  void throwingControlIsSystemError() {
    M04PropertyJudge.Observation observation =
        new M04PropertyJudge()
            .judge(
                "throwing",
                "fixed",
                List.of(
                    new io.github.lchareln.cex.matching.reference.ReferenceCommand.Place(
                        "BTC-USDT",
                        java.math.BigInteger.ONE,
                        "BUY",
                        java.math.BigInteger.ONE,
                        java.math.BigInteger.ONE,
                        "GTC")),
                M04Mutants.throwingControl());

    assertEquals(M04PropertyJudge.SYSTEM_ERROR, observation.classification());
  }

  private record Mutant(String id, M04Candidate.Factory factory, String fingerprint) {}
}
