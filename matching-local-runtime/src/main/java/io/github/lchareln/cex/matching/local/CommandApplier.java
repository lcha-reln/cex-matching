package io.github.lchareln.cex.matching.local;

/** Internal deterministic apply port used both live and during genesis recovery. */
interface CommandApplier {
  boolean supports(M08Command command);

  long nextApplicationSequence();

  CanonicalResult apply(M08Command command);

  String semanticStateDigest();
}
