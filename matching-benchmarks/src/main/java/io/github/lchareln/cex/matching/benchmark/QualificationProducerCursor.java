package io.github.lchareln.cex.matching.benchmark;

/** Phase-local producer cursor: only bounded admission consumes the next durable slot. */
final class QualificationProducerCursor {
  private long nextSequence = 1;

  long nextSequence() {
    return nextSequence;
  }

  void admitted() {
    nextSequence = Math.incrementExact(nextSequence);
  }
}
