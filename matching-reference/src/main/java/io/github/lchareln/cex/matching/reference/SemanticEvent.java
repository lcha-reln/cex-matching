package io.github.lchareln.cex.matching.reference;

import java.math.BigInteger;
import java.util.Objects;

/** Neutral immutable business events produced without importing production event types. */
public sealed interface SemanticEvent
    permits SemanticEvent.Rejected,
        SemanticEvent.PlaceRejected,
        SemanticEvent.CancelRejected,
        SemanticEvent.Accepted,
        SemanticEvent.Trade,
        SemanticEvent.Rested,
        SemanticEvent.Canceled {

  record Rejected(String code, String field) implements SemanticEvent {
    public Rejected {
      Objects.requireNonNull(code, "code");
      Objects.requireNonNull(field, "field");
    }
  }

  record PlaceRejected(BigInteger orderId, String code) implements SemanticEvent {
    public PlaceRejected {
      Objects.requireNonNull(orderId, "orderId");
      Objects.requireNonNull(code, "code");
    }
  }

  record CancelRejected(BigInteger orderId, String code) implements SemanticEvent {
    public CancelRejected {
      Objects.requireNonNull(orderId, "orderId");
      Objects.requireNonNull(code, "code");
    }
  }

  record Accepted(
      BigInteger sequence,
      BigInteger orderId,
      String side,
      BigInteger priceTicks,
      BigInteger quantityLots)
      implements SemanticEvent {
    public Accepted {
      Objects.requireNonNull(sequence, "sequence");
      Objects.requireNonNull(orderId, "orderId");
      Objects.requireNonNull(side, "side");
      Objects.requireNonNull(priceTicks, "priceTicks");
      Objects.requireNonNull(quantityLots, "quantityLots");
    }
  }

  record Trade(
      BigInteger makerSequence,
      BigInteger makerOrderId,
      BigInteger takerSequence,
      BigInteger takerOrderId,
      BigInteger priceTicks,
      BigInteger quantityLots)
      implements SemanticEvent {
    public Trade {
      Objects.requireNonNull(makerSequence, "makerSequence");
      Objects.requireNonNull(makerOrderId, "makerOrderId");
      Objects.requireNonNull(takerSequence, "takerSequence");
      Objects.requireNonNull(takerOrderId, "takerOrderId");
      Objects.requireNonNull(priceTicks, "priceTicks");
      Objects.requireNonNull(quantityLots, "quantityLots");
    }
  }

  record Rested(
      BigInteger sequence,
      BigInteger orderId,
      String side,
      BigInteger priceTicks,
      BigInteger remainingQuantityLots)
      implements SemanticEvent {
    public Rested {
      Objects.requireNonNull(sequence, "sequence");
      Objects.requireNonNull(orderId, "orderId");
      Objects.requireNonNull(side, "side");
      Objects.requireNonNull(priceTicks, "priceTicks");
      Objects.requireNonNull(remainingQuantityLots, "remainingQuantityLots");
    }
  }

  record Canceled(
      BigInteger sequence,
      BigInteger orderId,
      String side,
      BigInteger priceTicks,
      BigInteger canceledQuantityLots)
      implements SemanticEvent {
    public Canceled {
      Objects.requireNonNull(sequence, "sequence");
      Objects.requireNonNull(orderId, "orderId");
      Objects.requireNonNull(side, "side");
      Objects.requireNonNull(priceTicks, "priceTicks");
      Objects.requireNonNull(canceledQuantityLots, "canceledQuantityLots");
    }
  }
}
