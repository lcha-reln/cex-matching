package io.github.lchareln.cex.matching.testkit;

import java.util.List;

/** The exact ordered M04 mutant calibration set and frozen failure fingerprints. */
final class M04RequiredMutants {
  private M04RequiredMutants() {}

  static List<RequiredMutant> all() {
    return List.of(
        mutant(
            M04Mutants.IOC_REMAINDER_RESTS,
            "ioc-remainder-rests",
            M04Mutants.iocRemainderRests(),
            "EXECUTION_POLICY_GRAMMAR",
            "IOC_REMAINDER_RESTED",
            "IOC_ZERO_FILL"),
        mutant(
            M04Mutants.IOC_BEHAVES_LIKE_FOK,
            "ioc-behaves-like-fok",
            M04Mutants.iocBehavesLikeFok(),
            "IOC_IMMEDIATE_EXECUTION",
            "IOC_WAS_PRECHECK_REJECTED",
            "IOC_PARTIAL_FILL"),
        mutant(
            M04Mutants.FOK_PARTIAL_STATE_LEAK,
            "fok-partial-state-leak",
            M04Mutants.fokPartialStateLeak(),
            "POLICY_REJECTION_ATOMICITY",
            "FOK_REJECTION_CHANGED_BOOK",
            "FOK_INSUFFICIENT"),
        mutant(
            M04Mutants.FOK_BEST_LEVEL_ONLY,
            "fok-best-level-only",
            M04Mutants.fokBestLevelOnly(),
            "FOK_FILLABILITY",
            "MULTI_LEVEL_LIQUIDITY_IGNORED",
            "FOK_MULTI_LEVEL"),
        mutant(
            M04Mutants.FOK_IGNORES_LIMIT_PRICE,
            "fok-ignores-limit-price",
            M04Mutants.fokIgnoresLimitPrice(),
            "FOK_FILLABILITY",
            "OUTSIDE_LIMIT_LIQUIDITY_COUNTED",
            "FOK_OUTSIDE_LIMIT_EXCLUDED"),
        mutant(
            M04Mutants.POST_ONLY_TOUCH_ACCEPTED,
            "post-only-touch-accepted",
            M04Mutants.postOnlyTouchAccepted(),
            "POST_ONLY_ADMISSION",
            "TOUCH_WAS_ACCEPTED",
            "POST_ONLY_TOUCH"),
        mutant(
            M04Mutants.POLICY_REJECT_CONSUMES_IDENTITY,
            "policy-reject-consumes-identity",
            M04Mutants.policyRejectConsumesIdentity(),
            "POLICY_REJECTION_ATOMICITY",
            "REJECTED_ID_RESERVED",
            "REJECTED_ID_LATER_REUSED"),
        mutant(
            M04Mutants.UNKNOWN_POLICY_DEFAULTS_GTC,
            "unknown-policy-defaults-gtc",
            M04Mutants.unknownPolicyDefaultsGtc(),
            "VALIDATION_PRIORITY_AND_NO_MUTATION",
            "UNKNOWN_POLICY_ACCEPTED",
            "BASE_VALID_UNUSED_ID_UNKNOWN"));
  }

  private static RequiredMutant mutant(
      String id,
      String scenarioId,
      M04Candidate.Factory factory,
      String property,
      String divergence,
      String generatedCoverageKey) {
    return new RequiredMutant(
        id,
        scenarioId,
        factory,
        new M04Shrinker.Fingerprint(property, divergence),
        generatedCoverageKey);
  }

  record RequiredMutant(
      String id,
      String scenarioId,
      M04Candidate.Factory factory,
      M04Shrinker.Fingerprint fingerprint,
      String generatedCoverageKey) {}
}
