package io.github.lchareln.cex.matching;

/** Successful Prepare disposition for the single immutable prepared slot. */
public enum PrepareRuleSetStatus {
  PREPARED,
  ALREADY_PREPARED,
  SUPERSEDED
}
