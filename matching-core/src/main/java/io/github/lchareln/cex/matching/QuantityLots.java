package io.github.lchareln.cex.matching;

/** Positive integer quantity expressed in instrument lots. */
public record QuantityLots(long value) {
  public QuantityLots {
    if (value <= 0) {
      throw new IllegalArgumentException("quantityLots must be positive");
    }
  }
}
