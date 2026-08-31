package io.github.lchareln.cex.matching.reference;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

/** Detached M05 full-depth book with admission-rule attribution on every resting order. */
public record M05SemanticBook(List<PriceLevel> bids, List<PriceLevel> asks) {
  public M05SemanticBook {
    bids = List.copyOf(bids);
    asks = List.copyOf(asks);
  }

  public static M05SemanticBook empty() {
    return new M05SemanticBook(List.of(), List.of());
  }

  public record PriceLevel(String side, BigInteger priceTicks, List<RestingOrder> orders) {
    public PriceLevel {
      Objects.requireNonNull(side, "side");
      Objects.requireNonNull(priceTicks, "priceTicks");
      orders = List.copyOf(orders);
    }
  }

  public record RestingOrder(
      BigInteger acceptanceSequence,
      BigInteger orderId,
      BigInteger remainingQuantityLots,
      M05RuleSetIdentity admissionRuleSet) {
    public RestingOrder {
      Objects.requireNonNull(acceptanceSequence, "acceptanceSequence");
      Objects.requireNonNull(orderId, "orderId");
      Objects.requireNonNull(remainingQuantityLots, "remainingQuantityLots");
      Objects.requireNonNull(admissionRuleSet, "admissionRuleSet");
    }
  }
}
