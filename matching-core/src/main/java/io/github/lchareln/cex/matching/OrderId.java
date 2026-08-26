package io.github.lchareln.cex.matching;

/** Opaque order identity. Addressable lifecycle semantics are deliberately deferred to M02. */
public record OrderId(long value) {
  public OrderId {
    if (value <= 0) {
      throw new IllegalArgumentException("orderId must be positive");
    }
  }
}
