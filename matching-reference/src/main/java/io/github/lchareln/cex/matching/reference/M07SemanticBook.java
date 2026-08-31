package io.github.lchareln.cex.matching.reference;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

/** Detached M07 full-depth book with rule-set and opaque participant attribution. */
public record M07SemanticBook(List<PriceLevel> bids, List<PriceLevel> asks) {
  public M07SemanticBook {
    bids = List.copyOf(bids);
    asks = List.copyOf(asks);
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
      M06RuleSetIdentity admissionRuleSet,
      BigInteger participantGroupId,
      String stpPolicy) {
    public RestingOrder {
      Objects.requireNonNull(acceptanceSequence, "acceptanceSequence");
      Objects.requireNonNull(orderId, "orderId");
      Objects.requireNonNull(remainingQuantityLots, "remainingQuantityLots");
      Objects.requireNonNull(admissionRuleSet, "admissionRuleSet");
      Objects.requireNonNull(participantGroupId, "participantGroupId");
      Objects.requireNonNull(stpPolicy, "stpPolicy");
    }
  }
}
