package io.github.lchareln.cex.matching.reference;

import java.math.BigInteger;
import java.util.Objects;

/** Raw schema-valid commands consumed by the independent reference model. */
public sealed interface ReferenceCommand permits ReferenceCommand.Place, ReferenceCommand.Cancel {

  /** One raw GTC limit-order command before business validation. */
  record Place(
      String instrumentId,
      BigInteger orderId,
      String side,
      BigInteger priceTicks,
      BigInteger quantityLots)
      implements ReferenceCommand {
    public Place {
      Objects.requireNonNull(instrumentId, "instrumentId");
      Objects.requireNonNull(orderId, "orderId");
      Objects.requireNonNull(side, "side");
      Objects.requireNonNull(priceTicks, "priceTicks");
      Objects.requireNonNull(quantityLots, "quantityLots");
    }
  }

  /** One raw cancellation command before business validation. */
  record Cancel(String instrumentId, BigInteger orderId) implements ReferenceCommand {
    public Cancel {
      Objects.requireNonNull(instrumentId, "instrumentId");
      Objects.requireNonNull(orderId, "orderId");
    }
  }
}
