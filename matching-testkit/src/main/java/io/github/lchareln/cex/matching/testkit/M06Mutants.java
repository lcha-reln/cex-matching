package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.reference.M06MarketRuleSetArtifact;
import io.github.lchareln.cex.matching.reference.M06ReferenceCommand;
import io.github.lchareln.cex.matching.reference.M06RuleSetIdentity;
import io.github.lchareln.cex.matching.reference.M06SemanticBook;
import io.github.lchareln.cex.matching.reference.M06SemanticEvent;
import io.github.lchareln.cex.matching.reference.M06SemanticMarketState;
import io.github.lchareln.cex.matching.reference.M06SemanticOutcome;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Ten named semantic fault models required by the immutable M06 generator profile. */
final class M06Mutants {
  private static final M06RuleSetIdentity BOOTSTRAP =
      M06MarketRuleSetArtifact.bootstrap().identity();
  private static final M06RuleSetIdentity LOST_ATTRIBUTION =
      new M06RuleSetIdentity(
          BigInteger.valueOf(999),
          "sha256:0000000000000000000000000000000000000000000000000000000000000000");

  private M06Mutants() {}

  static List<Mutant> required() {
    List<Mutant> mutants =
        List.of(
            mutant(
                "M06-CANCEL-ONLY-PLACE-ACCEPTED",
                Fault.CANCEL_ONLY_PLACE_ACCEPTED,
                List.of(change(1, "OPEN", "CANCEL_ONLY"), place(1))),
            mutant(
                "M06-HALTED-CUSTOMER-CANCEL-ACCEPTED",
                Fault.HALTED_CANCEL_ACCEPTED,
                List.of(place(91), change(2, "OPEN", "HALTED"), cancel(91))),
            mutant(
                "M06-HALTED-DIRECTLY-REOPENED",
                Fault.DIRECT_REOPEN,
                List.of(change(1, "OPEN", "HALTED"), change(2, "HALTED", "OPEN"))),
            mutant(
                "M06-STALE-MODE-FENCE-ACCEPTED",
                Fault.STALE_MODE_ACCEPTED,
                List.of(change(1, "CANCEL_ONLY", "HALTED"))),
            mutant(
                "M06-MODE-CHANGE-IMPLICITLY-CLEARS-BOOK",
                Fault.MODE_CLEARS_BOOK,
                List.of(place(2), change(2, "OPEN", "CANCEL_ONLY"))),
            mutant(
                "M06-FAILED-MODE-CHANGE-RESETS-OPEN",
                Fault.FAILED_MODE_RESETS_OPEN,
                List.of(change(1, "OPEN", "HALTED"), change(2, "HALTED", "OPEN"))),
            mutant(
                "M06-MASS-CANCEL-WITHOUT-HALT", Fault.MASS_WITHOUT_HALT, List.of(mass(1, "OPEN"))),
            mutant(
                "M06-MASS-CANCEL-NON-ACCEPTANCE-ORDER",
                Fault.MASS_REVERSED,
                List.of(place(3), sell(4), change(3, "OPEN", "HALTED"), mass(4, "HALTED"))),
            mutant(
                "M06-FAILED-MASS-CANCEL-PARTIALLY-CLEARS",
                Fault.FAILED_MASS_PARTIAL,
                List.of(place(5), mass(2, "OPEN"))),
            mutant(
                "M06-MASS-CANCEL-DROPS-TERMINAL-ATTRIBUTION",
                Fault.MASS_DROPS_ATTRIBUTION,
                List.of(place(6), change(2, "OPEN", "HALTED"), mass(3, "HALTED"))));
    if (!mutants.stream().map(Mutant::id).toList().equals(M06StartCheckRunner.REQUIRED_MUTANTS)) {
      throw new IllegalStateException("M06 required mutant implementation order changed");
    }
    return mutants;
  }

  static M06Candidate.Factory systemErrorControl() {
    return () ->
        new M06Candidate() {
          @Override
          public M06SemanticOutcome apply(M06ReferenceCommand command) {
            throw new IllegalStateException("intentional M06 SYSTEM_ERROR control");
          }

          @Override
          public M06SemanticMarketState snapshot() {
            return new M06ReferenceCandidate().snapshot();
          }
        };
  }

  private static Mutant mutant(String id, Fault fault, List<M06ReferenceCommand> seedCommands) {
    return new Mutant(id, () -> new MutatingCandidate(fault), List.copyOf(seedCommands));
  }

  private static M06ReferenceCommand.Place place(long orderId) {
    return M06ReferenceCommand.Place.legacy(
        "BTC-USDT",
        BigInteger.valueOf(orderId),
        "BUY",
        BigInteger.valueOf(99),
        BigInteger.valueOf(2),
        "GTC");
  }

  private static M06ReferenceCommand.Place sell(long orderId) {
    return M06ReferenceCommand.Place.legacy(
        "BTC-USDT",
        BigInteger.valueOf(orderId),
        "SELL",
        BigInteger.valueOf(102),
        BigInteger.valueOf(2),
        "GTC");
  }

  private static M06ReferenceCommand.Cancel cancel(long orderId) {
    return new M06ReferenceCommand.Cancel("BTC-USDT", BigInteger.valueOf(orderId));
  }

  private static M06ReferenceCommand.ChangeMarketMode change(
      long application, String expected, String target) {
    return new M06ReferenceCommand.ChangeMarketMode(
        BigInteger.valueOf(application), expected, target, "mutant-operator");
  }

  private static M06ReferenceCommand.MassCancel mass(long application, String expected) {
    return new M06ReferenceCommand.MassCancel(
        BigInteger.valueOf(application), expected, "mutant-operator");
  }

  record Mutant(String id, M06Candidate.Factory factory, List<M06ReferenceCommand> seedCommands) {}

  private enum Fault {
    CANCEL_ONLY_PLACE_ACCEPTED,
    HALTED_CANCEL_ACCEPTED,
    DIRECT_REOPEN,
    STALE_MODE_ACCEPTED,
    MODE_CLEARS_BOOK,
    FAILED_MODE_RESETS_OPEN,
    MASS_WITHOUT_HALT,
    MASS_REVERSED,
    FAILED_MASS_PARTIAL,
    MASS_DROPS_ATTRIBUTION
  }

  private static final class MutatingCandidate implements M06Candidate {
    private final M06ProductionCandidate delegate = new M06ProductionCandidate();
    private final Fault fault;
    private M06SemanticMarketState visible = delegate.snapshot();

    private MutatingCandidate(Fault fault) {
      this.fault = fault;
    }

    @Override
    public M06SemanticOutcome apply(M06ReferenceCommand command) {
      M06SemanticMarketState before = delegate.snapshot();
      M06SemanticOutcome normal = delegate.apply(command);
      M06SemanticOutcome mutated = mutate(fault, command, before, normal);
      visible = mutated.stateAfter();
      return mutated;
    }

    @Override
    public M06SemanticMarketState snapshot() {
      return visible;
    }
  }

  private static M06SemanticOutcome mutate(
      Fault fault,
      M06ReferenceCommand command,
      M06SemanticMarketState before,
      M06SemanticOutcome normal) {
    return switch (fault) {
      case CANCEL_ONLY_PLACE_ACCEPTED -> acceptPlaceInCancelOnly(command, before, normal);
      case HALTED_CANCEL_ACCEPTED -> cancelWhileHalted(command, before, normal);
      case DIRECT_REOPEN -> directReopen(command, before, normal, false);
      case STALE_MODE_ACCEPTED -> directReopen(command, before, normal, true);
      case MODE_CLEARS_BOOK -> clearsBook(normal, before);
      case FAILED_MODE_RESETS_OPEN -> resetsOpen(normal, before);
      case MASS_WITHOUT_HALT -> massWithoutHalt(command, before, normal);
      case MASS_REVERSED -> reverseMassOrder(normal);
      case FAILED_MASS_PARTIAL -> partialMassFailure(normal, before);
      case MASS_DROPS_ATTRIBUTION -> dropAttribution(normal);
    };
  }

  private static M06SemanticOutcome acceptPlaceInCancelOnly(
      M06ReferenceCommand command, M06SemanticMarketState before, M06SemanticOutcome normal) {
    if (!(command instanceof M06ReferenceCommand.Place place)
        || !"CANCEL_ONLY".equals(before.marketMode())
        || place.entrypoint() != M06ReferenceCommand.PlaceEntrypoint.LEGACY
        || !"GTC".equals(place.executionPolicy())
        || !(normal.events().getFirst() instanceof M06SemanticEvent.PlaceRejected rejected)
        || !"MARKET_NOT_OPEN".equals(rejected.code())) {
      return normal;
    }
    BigInteger sequence = before.nextAcceptanceSequence();
    M06RuleSetIdentity rule = before.activeIdentity();
    M06SemanticEvent.Accepted accepted =
        new M06SemanticEvent.Accepted(
            sequence,
            place.orderId(),
            place.side(),
            place.priceTicks(),
            place.quantityLots(),
            place.executionPolicy(),
            rule,
            rule);
    M06SemanticEvent.Rested rested =
        new M06SemanticEvent.Rested(
            sequence,
            place.orderId(),
            place.side(),
            place.priceTicks(),
            place.quantityLots(),
            rule,
            rule);
    M06SemanticBook book = addResting(before.book(), place, sequence, rule);
    M06SemanticMarketState state =
        stateWithAcceptance(
            normal.stateAfter(), before.nextAcceptanceSequence().add(BigInteger.ONE), book);
    return new M06SemanticOutcome(normal.applicationSequence(), List.of(accepted, rested), state);
  }

  private static M06SemanticOutcome cancelWhileHalted(
      M06ReferenceCommand command, M06SemanticMarketState before, M06SemanticOutcome normal) {
    if (!(command instanceof M06ReferenceCommand.Cancel cancel)
        || !"HALTED".equals(before.marketMode())
        || !(normal.events().getFirst() instanceof M06SemanticEvent.CancelRejected rejected)
        || !"MARKET_NOT_CANCELABLE".equals(rejected.code())) {
      return normal;
    }
    M06SemanticBook.RestingOrder order = findOrder(before.book(), cancel.orderId());
    if (order == null) {
      return normal;
    }
    M06SemanticEvent.Canceled canceled =
        new M06SemanticEvent.Canceled(
            order.acceptanceSequence(),
            order.orderId(),
            side(before.book(), order.orderId()),
            price(before.book(), order.orderId()),
            order.remainingQuantityLots(),
            order.admissionRuleSet(),
            before.activeIdentity());
    M06SemanticMarketState state =
        stateWithAcceptance(
            normal.stateAfter(),
            normal.stateAfter().nextAcceptanceSequence(),
            removeOrder(before.book(), cancel.orderId()));
    return new M06SemanticOutcome(normal.applicationSequence(), List.of(canceled), state);
  }

  private static M06SemanticOutcome directReopen(
      M06ReferenceCommand command,
      M06SemanticMarketState before,
      M06SemanticOutcome normal,
      boolean staleOnly) {
    if (!(command instanceof M06ReferenceCommand.ChangeMarketMode change)
        || !(normal.events().getFirst() instanceof M06SemanticEvent.ModeChangeRejected rejected)) {
      return normal;
    }
    boolean trigger =
        staleOnly
            ? "EXPECTED_MODE_MISMATCH".equals(rejected.code())
            : "INVALID_TRANSITION".equals(rejected.code())
                && "HALTED".equals(before.marketMode())
                && "OPEN".equals(change.targetMode());
    if (!trigger) {
      return normal;
    }
    BigInteger revision = before.modeRevision().add(BigInteger.ONE);
    M06SemanticMarketState.ModeTransitionFence fence =
        new M06SemanticMarketState.ModeTransitionFence(
            normal.applicationSequence(),
            revision,
            before.marketMode(),
            change.targetMode(),
            before.nextAcceptanceSequence());
    M06SemanticMarketState state =
        state(
            normal.stateAfter(),
            normal.stateAfter().book(),
            change.targetMode(),
            revision,
            Optional.of(fence),
            normal.stateAfter().lastMassCancelFence());
    M06SemanticEvent.ModeChanged event =
        new M06SemanticEvent.ModeChanged(
            change.operatorId(), before.marketMode(), change.targetMode(), fence);
    return new M06SemanticOutcome(normal.applicationSequence(), List.of(event), state);
  }

  private static M06SemanticOutcome clearsBook(
      M06SemanticOutcome normal, M06SemanticMarketState before) {
    if (normal.events().getFirst() instanceof M06SemanticEvent.ModeChanged
        && !before.book().equals(M06SemanticBook.empty())) {
      return new M06SemanticOutcome(
          normal.applicationSequence(),
          normal.events(),
          state(
              normal.stateAfter(),
              M06SemanticBook.empty(),
              normal.stateAfter().marketMode(),
              normal.stateAfter().modeRevision(),
              normal.stateAfter().lastModeTransitionFence(),
              normal.stateAfter().lastMassCancelFence()));
    }
    return normal;
  }

  private static M06SemanticOutcome resetsOpen(
      M06SemanticOutcome normal, M06SemanticMarketState before) {
    if (normal.events().getFirst() instanceof M06SemanticEvent.ModeChangeRejected
        && !"OPEN".equals(before.marketMode())) {
      return new M06SemanticOutcome(
          normal.applicationSequence(),
          normal.events(),
          state(
              normal.stateAfter(),
              normal.stateAfter().book(),
              "OPEN",
              normal.stateAfter().modeRevision(),
              normal.stateAfter().lastModeTransitionFence(),
              normal.stateAfter().lastMassCancelFence()));
    }
    return normal;
  }

  private static M06SemanticOutcome massWithoutHalt(
      M06ReferenceCommand command, M06SemanticMarketState before, M06SemanticOutcome normal) {
    if (!(command instanceof M06ReferenceCommand.MassCancel mass)
        || !(normal.events().getFirst() instanceof M06SemanticEvent.MassCancelRejected rejected)
        || !"MARKET_NOT_HALTED".equals(rejected.code())) {
      return normal;
    }
    List<M06SemanticEvent> events = new ArrayList<>();
    List<M06SemanticBook.RestingOrder> orders = allOrders(before.book());
    events.add(
        new M06SemanticEvent.MassCancelStarted(
            mass.operatorId(),
            before.marketMode(),
            before.modeRevision(),
            BigInteger.valueOf(orders.size())));
    for (M06SemanticBook.RestingOrder order : orders) {
      String side = side(before.book(), order.orderId());
      BigInteger price = price(before.book(), order.orderId());
      events.add(
          new M06SemanticEvent.MassOrderCanceled(
              mass.operatorId(),
              order.acceptanceSequence(),
              order.orderId(),
              side,
              price,
              order.remainingQuantityLots(),
              order.admissionRuleSet(),
              before.activeIdentity()));
    }
    events.add(
        new M06SemanticEvent.MassCancelCompleted(
            mass.operatorId(),
            before.marketMode(),
            before.modeRevision(),
            BigInteger.valueOf(orders.size())));
    Optional<BigInteger> first =
        orders.isEmpty() ? Optional.empty() : Optional.of(orders.getFirst().acceptanceSequence());
    Optional<BigInteger> last =
        orders.isEmpty() ? Optional.empty() : Optional.of(orders.getLast().acceptanceSequence());
    M06SemanticMarketState.MassCancelFence fence =
        new M06SemanticMarketState.MassCancelFence(
            normal.applicationSequence(),
            before.modeRevision(),
            mass.operatorId(),
            BigInteger.valueOf(orders.size()),
            first,
            last);
    M06SemanticMarketState state =
        state(
            normal.stateAfter(),
            M06SemanticBook.empty(),
            normal.stateAfter().marketMode(),
            normal.stateAfter().modeRevision(),
            normal.stateAfter().lastModeTransitionFence(),
            Optional.of(fence));
    return new M06SemanticOutcome(normal.applicationSequence(), events, state);
  }

  private static M06SemanticOutcome reverseMassOrder(M06SemanticOutcome normal) {
    List<Integer> positions = new ArrayList<>();
    for (int index = 0; index < normal.events().size(); index++) {
      if (normal.events().get(index) instanceof M06SemanticEvent.MassOrderCanceled) {
        positions.add(index);
      }
    }
    if (positions.size() < 2) {
      return normal;
    }
    List<M06SemanticEvent> events = new ArrayList<>(normal.events());
    List<M06SemanticEvent> values = positions.stream().map(events::get).toList().reversed();
    for (int index = 0; index < positions.size(); index++) {
      events.set(positions.get(index), values.get(index));
    }
    return new M06SemanticOutcome(normal.applicationSequence(), events, normal.stateAfter());
  }

  private static M06SemanticOutcome partialMassFailure(
      M06SemanticOutcome normal, M06SemanticMarketState before) {
    if (normal.events().getFirst() instanceof M06SemanticEvent.MassCancelRejected
        && !before.book().equals(M06SemanticBook.empty())) {
      M06SemanticBook partial = removeFirst(before.book());
      return new M06SemanticOutcome(
          normal.applicationSequence(),
          normal.events(),
          state(
              normal.stateAfter(),
              partial,
              normal.stateAfter().marketMode(),
              normal.stateAfter().modeRevision(),
              normal.stateAfter().lastModeTransitionFence(),
              normal.stateAfter().lastMassCancelFence()));
    }
    return normal;
  }

  private static M06SemanticOutcome dropAttribution(M06SemanticOutcome normal) {
    List<M06SemanticEvent> events = new ArrayList<>(normal.events());
    for (int index = 0; index < events.size(); index++) {
      if (events.get(index) instanceof M06SemanticEvent.MassOrderCanceled canceled) {
        events.set(
            index,
            new M06SemanticEvent.MassOrderCanceled(
                canceled.operatorId(),
                canceled.acceptanceSequence(),
                canceled.orderId(),
                canceled.side(),
                canceled.priceTicks(),
                canceled.canceledQuantityLots(),
                LOST_ATTRIBUTION,
                canceled.executionRuleSet()));
        return new M06SemanticOutcome(normal.applicationSequence(), events, normal.stateAfter());
      }
    }
    return normal;
  }

  private static M06SemanticMarketState state(
      M06SemanticMarketState base,
      M06SemanticBook book,
      String mode,
      BigInteger modeRevision,
      Optional<M06SemanticMarketState.ModeTransitionFence> modeFence,
      Optional<M06SemanticMarketState.MassCancelFence> massFence) {
    return new M06SemanticMarketState(
        base.nextApplicationSequence(),
        base.nextAcceptanceSequence(),
        base.controlRevision(),
        base.activeRuleSet(),
        base.preparedRuleSet(),
        base.lastActivationFence(),
        mode,
        modeRevision,
        modeFence,
        massFence,
        book);
  }

  private static M06SemanticMarketState stateWithAcceptance(
      M06SemanticMarketState base, BigInteger nextAcceptance, M06SemanticBook book) {
    return new M06SemanticMarketState(
        base.nextApplicationSequence(),
        nextAcceptance,
        base.controlRevision(),
        base.activeRuleSet(),
        base.preparedRuleSet(),
        base.lastActivationFence(),
        base.marketMode(),
        base.modeRevision(),
        base.lastModeTransitionFence(),
        base.lastMassCancelFence(),
        book);
  }

  private static M06SemanticBook addResting(
      M06SemanticBook book,
      M06ReferenceCommand.Place place,
      BigInteger sequence,
      M06RuleSetIdentity rule) {
    List<M06SemanticBook.PriceLevel> bids = new ArrayList<>(book.bids());
    List<M06SemanticBook.PriceLevel> asks = new ArrayList<>(book.asks());
    List<M06SemanticBook.PriceLevel> levels = "BUY".equals(place.side()) ? bids : asks;
    M06SemanticBook.RestingOrder resting =
        new M06SemanticBook.RestingOrder(sequence, place.orderId(), place.quantityLots(), rule);
    boolean added = false;
    for (int index = 0; index < levels.size(); index++) {
      M06SemanticBook.PriceLevel level = levels.get(index);
      if (level.priceTicks().equals(place.priceTicks())) {
        List<M06SemanticBook.RestingOrder> orders = new ArrayList<>(level.orders());
        orders.add(resting);
        levels.set(index, new M06SemanticBook.PriceLevel(level.side(), level.priceTicks(), orders));
        added = true;
        break;
      }
    }
    if (!added) {
      levels.add(
          new M06SemanticBook.PriceLevel(place.side(), place.priceTicks(), List.of(resting)));
      levels.sort(
          "BUY".equals(place.side())
              ? java.util.Comparator.comparing(M06SemanticBook.PriceLevel::priceTicks).reversed()
              : java.util.Comparator.comparing(M06SemanticBook.PriceLevel::priceTicks));
    }
    return new M06SemanticBook(List.copyOf(bids), List.copyOf(asks));
  }

  private static M06SemanticBook.RestingOrder findOrder(M06SemanticBook book, BigInteger orderId) {
    return java.util.stream.Stream.concat(book.bids().stream(), book.asks().stream())
        .flatMap(level -> level.orders().stream())
        .filter(order -> order.orderId().equals(orderId))
        .findFirst()
        .orElse(null);
  }

  private static M06SemanticBook removeOrder(M06SemanticBook book, BigInteger orderId) {
    return new M06SemanticBook(
        removeOrder(book.bids(), orderId), removeOrder(book.asks(), orderId));
  }

  private static List<M06SemanticBook.PriceLevel> removeOrder(
      List<M06SemanticBook.PriceLevel> levels, BigInteger orderId) {
    List<M06SemanticBook.PriceLevel> result = new ArrayList<>();
    for (M06SemanticBook.PriceLevel level : levels) {
      List<M06SemanticBook.RestingOrder> orders =
          level.orders().stream().filter(order -> !order.orderId().equals(orderId)).toList();
      if (!orders.isEmpty()) {
        result.add(new M06SemanticBook.PriceLevel(level.side(), level.priceTicks(), orders));
      }
    }
    return List.copyOf(result);
  }

  private static List<M06SemanticBook.RestingOrder> allOrders(M06SemanticBook book) {
    List<M06SemanticBook.RestingOrder> values = new ArrayList<>();
    book.bids().forEach(level -> values.addAll(level.orders()));
    book.asks().forEach(level -> values.addAll(level.orders()));
    values.sort(java.util.Comparator.comparing(M06SemanticBook.RestingOrder::acceptanceSequence));
    return List.copyOf(values);
  }

  private static String side(M06SemanticBook book, BigInteger orderId) {
    return book.bids().stream()
            .anyMatch(
                level -> level.orders().stream().anyMatch(order -> order.orderId().equals(orderId)))
        ? "BUY"
        : "SELL";
  }

  private static BigInteger price(M06SemanticBook book, BigInteger orderId) {
    return java.util.stream.Stream.concat(book.bids().stream(), book.asks().stream())
        .filter(level -> level.orders().stream().anyMatch(order -> order.orderId().equals(orderId)))
        .map(M06SemanticBook.PriceLevel::priceTicks)
        .findFirst()
        .orElseThrow();
  }

  private static M06SemanticBook removeFirst(M06SemanticBook book) {
    if (!book.bids().isEmpty()) {
      return new M06SemanticBook(removeFirstLevel(book.bids()), book.asks());
    }
    return new M06SemanticBook(book.bids(), removeFirstLevel(book.asks()));
  }

  private static List<M06SemanticBook.PriceLevel> removeFirstLevel(
      List<M06SemanticBook.PriceLevel> levels) {
    List<M06SemanticBook.PriceLevel> result = new ArrayList<>(levels);
    M06SemanticBook.PriceLevel first = result.getFirst();
    if (first.orders().size() == 1) {
      result.removeFirst();
    } else {
      result.set(
          0,
          new M06SemanticBook.PriceLevel(
              first.side(), first.priceTicks(), first.orders().subList(1, first.orders().size())));
    }
    return List.copyOf(result);
  }
}
