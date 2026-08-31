package io.github.lchareln.cex.matching.local;

/** The local runtime never resumes submission after an ambiguous append/apply outcome. */
public enum RuntimeState {
  OPEN,
  FAILED_CLOSED,
  CLOSED
}
