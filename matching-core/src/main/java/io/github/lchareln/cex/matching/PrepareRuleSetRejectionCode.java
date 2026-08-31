package io.github.lchareln.cex.matching;

/** Deterministic M05 Prepare rejection reasons. */
public enum PrepareRuleSetRejectionCode {
  EXPECTED_ACTIVE_RULE_SET_MISMATCH,
  MALFORMED_CONTENT_HASH,
  CONTENT_HASH_MISMATCH,
  SAME_VERSION_DIFFERENT_CONTENT,
  VERSION_NOT_INCREASING
}
