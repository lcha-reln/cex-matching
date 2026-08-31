package io.github.lchareln.cex.matching.testkit;

import java.util.List;

/** Exact ordered M05 mutant calibration set with stable semantic fingerprints. */
final class M05RequiredMutants {
  private M05RequiredMutants() {}

  static List<RequiredMutant> all() {
    return List.of(
        mutant(
            M05Mutants.HASH_MISMATCH_PREPARED,
            "hash-mismatch-prepared",
            M05Mutants.hashMismatchPrepared(),
            "EXACT_COMMAND_DIFFERENTIAL",
            "EVENT_BATCH",
            "CONTENT_HASH_MISMATCH"),
        mutant(
            M05Mutants.SAME_VERSION_DIFFERENT_HASH_ACCEPTED,
            "same-version-different-hash-accepted",
            M05Mutants.sameVersionDifferentHashAccepted(),
            "RULE_SET_PREPARE_IDEMPOTENCY",
            "IDEMPOTENT_PREPARE_CHANGED_IDENTITY",
            "VERSION_CONTENT_CONFLICT"),
        mutant(
            M05Mutants.ACTIVATE_WITHOUT_PREPARE,
            "activate-without-prepare",
            M05Mutants.activateWithoutPrepare(),
            "ACTIVATE_REQUIRES_PREPARED",
            "UNPREPARED_ACTIVATION_ACCEPTED",
            "ACTIVATE_WITHOUT_PREPARE"),
        mutant(
            M05Mutants.STALE_ACTIVATION_FENCE_ACCEPTED,
            "stale-activation-fence-accepted",
            M05Mutants.staleActivationFenceAccepted(),
            "ACTIVATION_SEQUENCE_FENCE",
            "STALE_ACTIVATION_ACCEPTED",
            "STALE_ACTIVATION_FENCE"),
        mutant(
            M05Mutants.FAILED_ACTIVATION_CHANGES_ACTIVE,
            "failed-activation-changes-active",
            M05Mutants.failedActivationChangesActive(),
            "RULE_SET_ACTIVATION_ATOMICITY",
            "ACTIVE_RULE_STATE",
            "FAILED_ACTIVATION_ATOMICITY"),
        mutant(
            M05Mutants.OUT_OF_BAND_PLACE_ACCEPTED,
            "out-of-band-place-accepted",
            M05Mutants.outOfBandPlaceAccepted(),
            "INCLUSIVE_ORDER_ENTRY_PRICE_BAND",
            "OUT_OF_BAND_PLACE_ACCEPTED",
            "BELOW_BAND"),
        mutant(
            M05Mutants.STALE_PLACE_RULE_ACCEPTED,
            "stale-place-rule-accepted",
            M05Mutants.stalePlaceRuleAccepted(),
            "GOVERNED_PLACE_FENCE",
            "STALE_PLACE_RULE_ACCEPTED",
            "STALE_PLACE_FENCE"),
        mutant(
            M05Mutants.ACTIVATION_REVALIDATES_RESTING,
            "activation-revalidates-resting",
            M05Mutants.activationRevalidatesResting(),
            "GRANDFATHER_RESTING_ORDERS",
            "ACTIVATION_REVALIDATED_BOOK",
            "GRANDFATHERED_MAKER"));
  }

  private static RequiredMutant mutant(
      String id,
      String scenarioId,
      M05Candidate.Factory factory,
      String property,
      String divergence,
      String generatedCoverageKey) {
    return new RequiredMutant(
        id,
        scenarioId,
        factory,
        new M05Shrinker.Fingerprint(property, divergence),
        generatedCoverageKey);
  }

  record RequiredMutant(
      String id,
      String scenarioId,
      M05Candidate.Factory factory,
      M05Shrinker.Fingerprint fingerprint,
      String generatedCoverageKey) {}
}
