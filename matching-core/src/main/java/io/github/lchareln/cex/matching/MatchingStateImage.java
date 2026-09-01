package io.github.lchareln.cex.matching;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Infrastructure-free complete matcher state at one serialized command boundary.
 *
 * <p>This is deliberately richer than {@link OrderBookSnapshot}: terminal order tombstones remain
 * authoritative for duplicate-order and cancellation semantics after durable-log retention.
 */
public record MatchingStateImage(MarketControlSnapshot control, List<OrderImage> orders) {
  public MatchingStateImage {
    Objects.requireNonNull(control, "control");
    orders = List.copyOf(orders);
    Set<OrderId> ids = new HashSet<>();
    Set<AcceptanceSequence> sequences = new HashSet<>();
    long previousSequence = 0;
    for (OrderImage order : orders) {
      Objects.requireNonNull(order, "order");
      if (order.sequence().value() <= previousSequence) {
        throw new IllegalArgumentException("orders must be sorted by acceptance sequence");
      }
      if (!ids.add(order.orderId()) || !sequences.add(order.sequence())) {
        throw new IllegalArgumentException("order identity and acceptance sequence must be unique");
      }
      if (order.sequence().value() >= control.nextAcceptanceSequence().value()) {
        throw new IllegalArgumentException("accepted order is not behind the next sequence");
      }
      order
          .cancellation()
          .ifPresent(
              cancellation -> {
                if (cancellation.applicationSequence().value()
                    >= control.nextApplicationSequence().value()) {
                  throw new IllegalArgumentException(
                      "order cancellation is not behind the next application sequence");
                }
              });
      previousSequence = order.sequence().value();
    }
  }

  /** Returns a canonically ordered defensive copy. */
  public static MatchingStateImage create(
      MarketControlSnapshot control, List<OrderImage> unorderedOrders) {
    List<OrderImage> ordered =
        unorderedOrders.stream()
            .sorted(Comparator.comparingLong(order -> order.sequence().value()))
            .toList();
    return new MatchingStateImage(control, ordered);
  }

  public enum Lifecycle {
    RESTING,
    FILLED,
    CANCELED
  }

  public enum CancellationOrigin {
    USER_REQUEST,
    OPERATOR_MASS_CANCEL,
    IOC_REMAINDER,
    SELF_TRADE_PREVENTION
  }

  /** Full accepted-order lifecycle state, including non-resting tombstones. */
  public record OrderImage(
      AcceptanceSequence sequence,
      OrderId orderId,
      Side side,
      PriceTicks priceTicks,
      ExecutionPolicy executionPolicy,
      RuleSetIdentity admissionRuleSet,
      long participantGroupId,
      SelfTradePreventionPolicy selfTradePreventionPolicy,
      long originalQuantityLots,
      long remainingQuantityLots,
      long filledQuantityLots,
      long canceledQuantityLots,
      Lifecycle lifecycle,
      Optional<Cancellation> cancellation) {
    public OrderImage {
      Objects.requireNonNull(sequence, "sequence");
      Objects.requireNonNull(orderId, "orderId");
      Objects.requireNonNull(side, "side");
      Objects.requireNonNull(priceTicks, "priceTicks");
      Objects.requireNonNull(executionPolicy, "executionPolicy");
      Objects.requireNonNull(admissionRuleSet, "admissionRuleSet");
      new SelfTradePreventionInstruction(participantGroupId, selfTradePreventionPolicy);
      Objects.requireNonNull(lifecycle, "lifecycle");
      cancellation = Objects.requireNonNull(cancellation, "cancellation");
      long partition =
          quantityPartition(filledQuantityLots, remainingQuantityLots, canceledQuantityLots);
      if (originalQuantityLots <= 0
          || remainingQuantityLots < 0
          || filledQuantityLots < 0
          || canceledQuantityLots < 0
          || partition != originalQuantityLots) {
        throw new IllegalArgumentException("order quantity partition is inconsistent");
      }
      switch (lifecycle) {
        case RESTING -> {
          if (remainingQuantityLots <= 0 || canceledQuantityLots != 0 || cancellation.isPresent()) {
            throw new IllegalArgumentException("resting order image is inconsistent");
          }
        }
        case FILLED -> {
          if (remainingQuantityLots != 0
              || canceledQuantityLots != 0
              || filledQuantityLots != originalQuantityLots
              || cancellation.isPresent()) {
            throw new IllegalArgumentException("filled order image is inconsistent");
          }
        }
        case CANCELED -> {
          if (remainingQuantityLots != 0 || canceledQuantityLots <= 0 || cancellation.isEmpty()) {
            throw new IllegalArgumentException("canceled order image is inconsistent");
          }
        }
      }
      if (executionPolicy == ExecutionPolicy.IOC && lifecycle == Lifecycle.RESTING) {
        throw new IllegalArgumentException("IOC order cannot remain resting");
      }
      if (executionPolicy == ExecutionPolicy.FOK
          && (lifecycle == Lifecycle.RESTING || lifecycle == Lifecycle.CANCELED)) {
        throw new IllegalArgumentException("FOK order must be fully filled when accepted");
      }
      cancellation.ifPresent(
          value -> {
            if (value.origin() == CancellationOrigin.IOC_REMAINDER
                && executionPolicy != ExecutionPolicy.IOC) {
              throw new IllegalArgumentException("only an IOC order has an IOC remainder");
            }
          });
    }

    private static long quantityPartition(long filled, long remaining, long canceled) {
      try {
        return Math.addExact(Math.addExact(filled, remaining), canceled);
      } catch (ArithmeticException failure) {
        throw new IllegalArgumentException("order quantity partition overflowed", failure);
      }
    }
  }

  public record Cancellation(CancellationOrigin origin, ApplicationSequence applicationSequence) {
    public Cancellation {
      Objects.requireNonNull(origin, "origin");
      Objects.requireNonNull(applicationSequence, "applicationSequence");
    }
  }
}
