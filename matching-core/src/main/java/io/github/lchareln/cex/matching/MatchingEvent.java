package io.github.lchareln.cex.matching;

import java.util.Objects;

/** Ordered business events emitted for one M01 place command. */
public sealed interface MatchingEvent
    permits MatchingEvent.Rejected,
        MatchingEvent.Accepted,
        MatchingEvent.Trade,
        MatchingEvent.Rested {

  /** A schema-valid input rejected by the frozen M00 business validator. */
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

  /** A valid command assigned its in-memory time-priority sequence. */
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
}
