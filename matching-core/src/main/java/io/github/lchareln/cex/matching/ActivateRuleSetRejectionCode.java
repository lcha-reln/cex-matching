package io.github.lchareln.cex.matching;

/** Deterministic M05 Activate rejection reasons. */
public enum ActivateRuleSetRejectionCode {
  APPLICATION_SEQUENCE_MISMATCH,
  EXPECTED_ACTIVE_RULE_SET_MISMATCH,
  NO_PREPARED_RULE_SET,
  TARGET_RULE_SET_MISMATCH,
  PREPARED_CONTENT_HASH_MISMATCH
}
