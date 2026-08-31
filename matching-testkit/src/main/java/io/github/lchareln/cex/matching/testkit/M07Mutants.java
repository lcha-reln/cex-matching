package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.reference.M07LinearReferenceModel;
import io.github.lchareln.cex.matching.reference.M07ReferenceCommand;
import io.github.lchareln.cex.matching.reference.M07SemanticBook;
import io.github.lchareln.cex.matching.reference.M07SemanticEvent;
import io.github.lchareln.cex.matching.reference.M07SemanticMarketState;
import io.github.lchareln.cex.matching.reference.M07SemanticOutcome;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Eight self-consistent faulty state machines required by the frozen M07 profile. */
final class M07Mutants {
  private M07Mutants() {}

  static List<Mutant> required() {
    List<Mutant> values =
        List.of(
            mutant(
                "M07-SAME-GROUP-TRADE-ALLOWED",
                Fault.SAME_GROUP_TRADE_ALLOWED,
                List.of(
                    stp(1, "SELL", 100, 2, 7, "CANCEL_TAKER", "GTC"),
                    stp(2, "BUY", 100, 2, 7, "CANCEL_TAKER", "GTC"))),
            mutant(
                "M07-DIFFERENT-GROUP-CANCELED",
                Fault.DIFFERENT_GROUP_CANCELED,
                List.of(
                    stp(11, "SELL", 100, 2, 7, "CANCEL_TAKER", "GTC"),
                    stp(12, "BUY", 100, 2, 8, "CANCEL_TAKER", "GTC"))),
            mutant(
                "M07-CANCEL-TAKER-SKIPS-SELF",
                Fault.CANCEL_TAKER_SKIPS_SELF,
                List.of(
                    stp(21, "SELL", 100, 2, 9, "CANCEL_MAKER", "GTC"),
                    stp(22, "BUY", 100, 3, 9, "CANCEL_TAKER", "GTC"))),
            mutant(
                "M07-CANCEL-MAKER-CANCELS-TAKER",
                Fault.CANCEL_MAKER_CANCELS_TAKER,
                List.of(
                    stp(31, "BUY", 100, 2, 10, "CANCEL_TAKER", "GTC"),
                    stp(32, "SELL", 100, 3, 10, "CANCEL_MAKER", "GTC"))),
            mutant(
                "M07-CANCEL-BOTH-LEAVES-MAKER",
                Fault.CANCEL_BOTH_LEAVES_MAKER,
                List.of(
                    stp(41, "SELL", 100, 2, 11, "CANCEL_TAKER", "GTC"),
                    stp(42, "BUY", 100, 3, 11, "CANCEL_BOTH", "GTC"))),
            mutant(
                "M07-FOK-COUNTS-RAW-SELF-LIQUIDITY",
                Fault.FOK_COUNTS_RAW_SELF,
                List.of(
                    stp(51, "SELL", 100, 2, 12, "CANCEL_TAKER", "GTC"),
                    stp(52, "SELL", 101, 3, 13, "CANCEL_TAKER", "GTC"),
                    stp(53, "BUY", 101, 4, 12, "CANCEL_TAKER", "FOK"))),
            mutant(
                "M07-POST-ONLY-RUNS-STP-FIRST",
                Fault.POST_ONLY_RUNS_STP_FIRST,
                List.of(
                    stp(61, "SELL", 100, 2, 14, "CANCEL_TAKER", "GTC"),
                    stp(62, "BUY", 100, 2, 14, "CANCEL_MAKER", "POST_ONLY"))),
            mutant(
                "M07-CANCEL-MAKER-BEST-LEVEL-ONLY",
                Fault.CANCEL_MAKER_BEST_LEVEL_ONLY,
                List.of(
                    stp(71, "SELL", 100, 1, 15, "CANCEL_TAKER", "GTC"),
                    stp(72, "SELL", 101, 1, 16, "CANCEL_TAKER", "GTC"),
                    stp(73, "SELL", 102, 1, 15, "CANCEL_TAKER", "GTC"),
                    stp(74, "SELL", 103, 1, 17, "CANCEL_TAKER", "GTC"),
                    stp(75, "BUY", 103, 2, 15, "CANCEL_MAKER", "GTC"))));
    if (!values.stream().map(Mutant::id).toList().equals(M07StartCheckRunner.REQUIRED_MUTANTS)) {
      throw new IllegalStateException("M07 required mutant implementation order changed");
    }
    return values;
  }

  static M07Candidate.Factory systemErrorControl() {
    return () ->
        new M07Candidate() {
          @Override
          public M07SemanticOutcome apply(M07ReferenceCommand command) {
            throw new IllegalStateException("intentional M07 SYSTEM_ERROR control");
          }

          @Override
          public M07SemanticMarketState snapshot() {
            return new M07LinearReferenceModel().snapshot();
          }
        };
  }

  private static Mutant mutant(String id, Fault fault, List<M07ReferenceCommand> commands) {
    return new Mutant(id, () -> new FaultyModel(fault), List.copyOf(commands));
  }

  private static M07ReferenceCommand.Place stp(
      long orderId,
      String side,
      long price,
      long quantity,
      long group,
      String policy,
      String executionPolicy) {
    return M07ReferenceCommand.Place.stp(
        "BTC-USDT",
        BigInteger.valueOf(orderId),
        side,
        BigInteger.valueOf(price),
        BigInteger.valueOf(quantity),
        executionPolicy,
        BigInteger.valueOf(group),
        policy);
  }

  record Mutant(String id, M07Candidate.Factory factory, List<M07ReferenceCommand> seedCommands) {}

  private enum Fault {
    SAME_GROUP_TRADE_ALLOWED,
    DIFFERENT_GROUP_CANCELED,
    CANCEL_TAKER_SKIPS_SELF,
    CANCEL_MAKER_CANCELS_TAKER,
    CANCEL_BOTH_LEAVES_MAKER,
    FOK_COUNTS_RAW_SELF,
    POST_ONLY_RUNS_STP_FIRST,
    CANCEL_MAKER_BEST_LEVEL_ONLY
  }

  private static final class FaultyModel implements M07Candidate {
    private final M07LinearReferenceModel model = new M07LinearReferenceModel();
    private final Map<BigInteger, OriginalMetadata> originalMetadata = new LinkedHashMap<>();
    private final Fault fault;
    private boolean crossLevelAnchorAccepted;

    private FaultyModel(Fault fault) {
      this.fault = fault;
    }

    @Override
    public M07SemanticOutcome apply(M07ReferenceCommand command) {
      M07ReferenceCommand rewritten = rewrite(command);
      M07SemanticOutcome internal = model.apply(rewritten);
      if (command instanceof M07ReferenceCommand.Place original
          && accepted(internal, original.orderId())) {
        originalMetadata.put(original.orderId(), OriginalMetadata.from(original));
        if (fault == Fault.CANCEL_MAKER_BEST_LEVEL_ONLY
            && original.orderId().equals(BigInteger.valueOf(71))) {
          crossLevelAnchorAccepted = true;
        }
      }
      return project(internal, command);
    }

    @Override
    public M07SemanticMarketState snapshot() {
      return project(model.snapshot());
    }

    private M07ReferenceCommand rewrite(M07ReferenceCommand command) {
      if (!(command instanceof M07ReferenceCommand.Place place)
          || place.entrypoint() == M07ReferenceCommand.PlaceEntrypoint.LEGACY
          || place.entrypoint() == M07ReferenceCommand.PlaceEntrypoint.GOVERNED) {
        return command;
      }
      BigInteger group = place.participantGroupId();
      String stpPolicy = place.stpPolicy();
      String executionPolicy = place.executionPolicy();
      switch (fault) {
        case SAME_GROUP_TRADE_ALLOWED -> group = group.add(place.orderId());
        case DIFFERENT_GROUP_CANCELED -> group = BigInteger.ONE;
        case CANCEL_TAKER_SKIPS_SELF -> {
          if ("CANCEL_TAKER".equals(stpPolicy)) {
            group = group.add(place.orderId());
          }
        }
        case CANCEL_MAKER_CANCELS_TAKER -> {
          if ("CANCEL_MAKER".equals(stpPolicy)) {
            stpPolicy = "CANCEL_TAKER";
          }
        }
        case CANCEL_BOTH_LEAVES_MAKER -> {
          if ("CANCEL_BOTH".equals(stpPolicy)) {
            stpPolicy = "CANCEL_TAKER";
          }
        }
        case FOK_COUNTS_RAW_SELF -> {
          if ("FOK".equals(executionPolicy)) {
            group = group.add(BigInteger.valueOf(1_000_000L));
          }
        }
        case POST_ONLY_RUNS_STP_FIRST -> {
          if ("POST_ONLY".equals(executionPolicy)) {
            executionPolicy = "GTC";
          }
        }
        case CANCEL_MAKER_BEST_LEVEL_ONLY -> {
          if (crossLevelAnchorAccepted && place.orderId().equals(BigInteger.valueOf(73))) {
            group = group.add(BigInteger.ONE);
          }
        }
      }
      return new M07ReferenceCommand.Place(
          place.entrypoint(),
          place.expectedRuleSet(),
          place.instrumentId(),
          place.orderId(),
          place.side(),
          place.priceTicks(),
          place.quantityLots(),
          executionPolicy,
          group,
          stpPolicy);
    }

    private M07SemanticOutcome project(
        M07SemanticOutcome internal, M07ReferenceCommand originalCommand) {
      OriginalMetadata current =
          originalCommand instanceof M07ReferenceCommand.Place place
              ? OriginalMetadata.from(place)
              : null;
      List<M07SemanticEvent> events = new ArrayList<>(internal.events().size());
      for (M07SemanticEvent event : internal.events()) {
        events.add(project(event, current));
      }
      return new M07SemanticOutcome(
          internal.applicationSequence(), events, project(internal.stateAfter()));
    }

    private M07SemanticEvent project(M07SemanticEvent event, OriginalMetadata current) {
      if (event instanceof M07SemanticEvent.Accepted accepted) {
        OriginalMetadata metadata = metadata(accepted.orderId(), current);
        return new M07SemanticEvent.Accepted(
            accepted.acceptanceSequence(),
            accepted.orderId(),
            accepted.side(),
            accepted.priceTicks(),
            accepted.quantityLots(),
            metadata.executionPolicy(),
            accepted.admissionRuleSet(),
            accepted.executionRuleSet(),
            metadata.participantGroupId(),
            metadata.stpPolicy());
      }
      if (event instanceof M07SemanticEvent.Rested rested) {
        OriginalMetadata metadata = metadata(rested.orderId(), current);
        return new M07SemanticEvent.Rested(
            rested.acceptanceSequence(),
            rested.orderId(),
            rested.side(),
            rested.priceTicks(),
            rested.remainingQuantityLots(),
            rested.admissionRuleSet(),
            rested.executionRuleSet(),
            metadata.participantGroupId(),
            metadata.stpPolicy());
      }
      if (event instanceof M07SemanticEvent.SelfTradePrevented prevented && current != null) {
        return new M07SemanticEvent.SelfTradePrevented(
            prevented.makerSequence(),
            prevented.makerOrderId(),
            prevented.takerSequence(),
            prevented.takerOrderId(),
            prevented.makerPriceTicks(),
            prevented.wouldTradeQuantityLots(),
            current.participantGroupId(),
            prevented.stpPolicy(),
            prevented.makerCanceledQuantityLots(),
            prevented.takerCanceledQuantityLots(),
            prevented.makerAdmissionRuleSet(),
            prevented.takerAdmissionRuleSet(),
            prevented.executionRuleSet());
      }
      return event;
    }

    private M07SemanticMarketState project(M07SemanticMarketState internal) {
      return new M07SemanticMarketState(
          internal.nextApplicationSequence(),
          internal.nextAcceptanceSequence(),
          internal.controlRevision(),
          internal.activeRuleSet(),
          internal.preparedRuleSet(),
          internal.lastActivationFence(),
          internal.marketMode(),
          internal.modeRevision(),
          internal.lastModeTransitionFence(),
          internal.lastMassCancelFence(),
          project(internal.book()));
    }

    private M07SemanticBook project(M07SemanticBook internal) {
      return new M07SemanticBook(
          internal.bids().stream().map(this::project).toList(),
          internal.asks().stream().map(this::project).toList());
    }

    private M07SemanticBook.PriceLevel project(M07SemanticBook.PriceLevel level) {
      return new M07SemanticBook.PriceLevel(
          level.side(),
          level.priceTicks(),
          level.orders().stream()
              .map(
                  order -> {
                    OriginalMetadata metadata = metadata(order.orderId(), null);
                    return new M07SemanticBook.RestingOrder(
                        order.acceptanceSequence(),
                        order.orderId(),
                        order.remainingQuantityLots(),
                        order.admissionRuleSet(),
                        metadata.participantGroupId(),
                        metadata.stpPolicy());
                  })
              .toList());
    }

    private OriginalMetadata metadata(BigInteger orderId, OriginalMetadata current) {
      if (current != null && current.orderId().equals(orderId)) {
        return current;
      }
      OriginalMetadata metadata = originalMetadata.get(orderId);
      if (metadata == null) {
        throw new IllegalStateException(
            "missing original M07 mutant metadata for order " + orderId);
      }
      return metadata;
    }

    private static boolean accepted(M07SemanticOutcome outcome, BigInteger orderId) {
      return outcome.events().stream()
          .filter(M07SemanticEvent.Accepted.class::isInstance)
          .map(M07SemanticEvent.Accepted.class::cast)
          .anyMatch(event -> event.orderId().equals(orderId));
    }
  }

  private record OriginalMetadata(
      BigInteger orderId, BigInteger participantGroupId, String stpPolicy, String executionPolicy) {
    private static OriginalMetadata from(M07ReferenceCommand.Place place) {
      return new OriginalMetadata(
          place.orderId(), place.participantGroupId(), place.stpPolicy(), place.executionPolicy());
    }
  }
}
