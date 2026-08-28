package io.github.lchareln.cex.matching;

import java.util.Objects;

/** Ordered business events emitted for one place or cancel command. */
public sealed interface MatchingEvent
    permits MatchingEvent.Rejected,
        MatchingEvent.PlaceRejected,
        MatchingEvent.CancelRejected,
        MatchingEvent.Accepted,
        MatchingEvent.Trade,
        MatchingEvent.Rested,
        MatchingEvent.Canceled {

  /** A schema-valid place or cancel input rejected by the frozen field validator. */
  record Rejected(ValidationCode code, String field) implements MatchingEvent {
    public Rejected {
      Objects.requireNonNull(code, "code");
      Objects.requireNonNull(field, "field");
      if (!code.field().equals(field)) {
        throw new IllegalArgumentException("validation code and field do not match");
      }
    }

    public Rejected(ValidationCode code) {
      this(code, code.field());
    }
  }

  /** A valid place command rejected because its order identity was already accepted. */
  record PlaceRejected(OrderId orderId, PlaceRejectionCode code) implements MatchingEvent {
    public PlaceRejected {
      Objects.requireNonNull(orderId, "orderId");
      Objects.requireNonNull(code, "code");
    }
  }

  /** A valid cancel command that found no cancellable active remainder. */
  record CancelRejected(OrderId orderId, CancelRejectionCode code) implements MatchingEvent {
    public CancelRejected {
      Objects.requireNonNull(orderId, "orderId");
      Objects.requireNonNull(code, "code");
    }
  }

  /** A valid unique place command assigned its in-memory time-priority sequence. */
  record Accepted(
      AcceptanceSequence sequence,
      OrderId orderId,
      Side side,
      PriceTicks priceTicks,
      QuantityLots quantityLots)
      implements MatchingEvent {
    public Accepted {
      Objects.requireNonNull(sequence, "sequence");
      Objects.requireNonNull(orderId, "orderId");
      Objects.requireNonNull(side, "side");
      Objects.requireNonNull(priceTicks, "priceTicks");
      Objects.requireNonNull(quantityLots, "quantityLots");
    }
  }

  /** One maker-level execution; trades are never aggregated across makers. */
  record Trade(
      AcceptanceSequence makerSequence,
      OrderId makerOrderId,
      AcceptanceSequence takerSequence,
      OrderId takerOrderId,
      PriceTicks priceTicks,
      QuantityLots quantityLots)
      implements MatchingEvent {
    public Trade {
      Objects.requireNonNull(makerSequence, "makerSequence");
      Objects.requireNonNull(makerOrderId, "makerOrderId");
      Objects.requireNonNull(takerSequence, "takerSequence");
      Objects.requireNonNull(takerOrderId, "takerOrderId");
      Objects.requireNonNull(priceTicks, "priceTicks");
      Objects.requireNonNull(quantityLots, "quantityLots");
    }
  }

  /** The positive GTC remainder appended to its own price level. */
  record Rested(
      AcceptanceSequence sequence,
      OrderId orderId,
      Side side,
      PriceTicks priceTicks,
      QuantityLots remainingQuantityLots)
      implements MatchingEvent {
    public Rested {
      Objects.requireNonNull(sequence, "sequence");
      Objects.requireNonNull(orderId, "orderId");
      Objects.requireNonNull(side, "side");
      Objects.requireNonNull(priceTicks, "priceTicks");
      Objects.requireNonNull(remainingQuantityLots, "remainingQuantityLots");
    }
  }

  /** The positive active remainder removed by one successful cancellation. */
  record Canceled(
      AcceptanceSequence sequence,
      OrderId orderId,
      Side side,
      PriceTicks priceTicks,
      QuantityLots canceledQuantityLots)
      implements MatchingEvent {
    public Canceled {
      Objects.requireNonNull(sequence, "sequence");
      Objects.requireNonNull(orderId, "orderId");
      Objects.requireNonNull(side, "side");
      Objects.requireNonNull(priceTicks, "priceTicks");
      Objects.requireNonNull(canceledQuantityLots, "canceledQuantityLots");
    }
  }
}
