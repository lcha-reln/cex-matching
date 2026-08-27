package io.github.lchareln.cex.matching;

/** In-memory acceptance order used only for price-time priority. */
public record AcceptanceSequence(long value) {
  public AcceptanceSequence {
    if (value <= 0) {
      throw new IllegalArgumentException("acceptance sequence must be positive");
    }
  }
}
