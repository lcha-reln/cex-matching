package io.github.lchareln.cex.matching;

/** Positive integer price expressed in instrument ticks. */
public record PriceTicks(long value) {
  public PriceTicks {
    if (value <= 0) {
      throw new IllegalArgumentException("priceTicks must be positive");
    }
  }
}
