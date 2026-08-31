package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.reference.M05MarketRuleSetArtifact;
import io.github.lchareln.cex.matching.reference.M05RuleSetIdentity;
import io.github.lchareln.cex.matching.reference.M05SemanticBook;
import io.github.lchareln.cex.matching.reference.M05SemanticEvent;
import io.github.lchareln.cex.matching.reference.M05SemanticMarketState;
import io.github.lchareln.cex.matching.reference.M05SemanticOutcome;
import java.math.BigInteger;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Independent sequence, control, attribution, and detached-book invariant ledger. */
final class M05EventLedger {
  private static final BigInteger ONE = BigInteger.ONE;
  private BigInteger expectedApplicationSequence = ONE;
  private BigInteger nextAcceptanceSequence = ONE;
  private BigInteger controlRevision = BigInteger.ZERO;
  private M05MarketRuleSetArtifact active = bootstrap();
  private M05MarketRuleSetArtifact prepared;
  private M05SemanticMarketState.ActivationFence lastFence;
  private M05SemanticBook previousBook = M05SemanticBook.empty();

  void verifyAndApply(M05Command command, M05SemanticOutcome outcome) {
    require(
        outcome.applicationSequence().equals(expectedApplicationSequence),
        "APPLICATION_SEQUENCE_CONTIGUITY",
        "APPLICATION_SEQUENCE_GAP",
        "command result did not consume the next application sequence");
    require(
        !outcome.events().isEmpty(),
        "EVENT_GRAMMAR",
        "EMPTY_EVENT_BATCH",
        "every applied command must emit one or more deterministic events");
    switch (command) {
      case M05Command.PrepareRuleSet prepareCommand -> verifyPrepare(prepareCommand, outcome);
      case M05Command.ActivateRuleSet activateCommand -> verifyActivate(activateCommand, outcome);
      case M05Command.Place place -> verifyPlace(place, outcome);
      case M05Command.Cancel cancel -> verifyCancel(cancel, outcome);
    }
    expectedApplicationSequence = expectedApplicationSequence.add(ONE);
    verifyState(outcome.stateAfter());
    previousBook = outcome.bookAfter();
  }

  private void verifyPrepare(M05Command.PrepareRuleSet command, M05SemanticOutcome outcome) {
    singleton(outcome.events(), "PrepareRuleSet");
    M05SemanticEvent event = outcome.events().getFirst();
    if (event instanceof M05SemanticEvent.RuleSetPrepared accepted) {
      require(
          accepted.identity().equals(identity(command.artifact())),
          "RULE_SET_PREPARE_ATOMICITY",
          "PREPARED_IDENTITY_CHANGED",
          "prepared identity differs from command artifact");
      switch (accepted.status()) {
        case PREPARED -> {
          require(
              prepared == null,
              "RULE_SET_PREPARE_ATOMICITY",
              "PREPARED_STATUS_INVALID",
              "PREPARED was emitted while another artifact was staged");
          require(
              accepted.supersededIdentity().isEmpty(),
              "RULE_SET_PREPARE_ATOMICITY",
              "UNEXPECTED_SUPERSEDED_IDENTITY",
              "PREPARED must not identify a superseded artifact");
        }
        case ALREADY_PREPARED -> {
          require(
              prepared != null && prepared.identity().equals(accepted.identity()),
              "RULE_SET_PREPARE_IDEMPOTENCY",
              "IDEMPOTENT_PREPARE_CHANGED_IDENTITY",
              "ALREADY_PREPARED does not refer to the staged artifact");
          require(
              accepted.supersededIdentity().isEmpty(),
              "RULE_SET_PREPARE_IDEMPOTENCY",
              "IDEMPOTENT_PREPARE_SUPERSEDED",
              "idempotent prepare cannot supersede");
        }
        case SUPERSEDED -> {
          require(
              prepared != null
                  && accepted.supersededIdentity().orElseThrow().equals(prepared.identity()),
              "RULE_SET_VERSION_MONOTONICITY",
              "SUPERSEDED_IDENTITY_CHANGED",
              "supersession did not name the previously staged artifact");
          require(
              command.artifact().version().compareTo(prepared.version()) > 0,
              "RULE_SET_VERSION_MONOTONICITY",
              "NON_INCREASING_SUPERSESSION",
              "superseding artifact version must increase");
        }
      }
      prepared = artifact(command.artifact());
    } else {
      require(
          event instanceof M05SemanticEvent.PrepareRuleSetRejected,
          "EVENT_GRAMMAR",
          "PREPARE_EVENT_UNION",
          "PrepareRuleSet emitted an illegal event");
    }
    require(
        outcome.bookAfter().equals(previousBook),
        "CONTROL_COMMAND_BOOK_ISOLATION",
        "PREPARE_MUTATED_BOOK",
        "PrepareRuleSet must not mutate resting orders");
  }

  private void verifyActivate(M05Command.ActivateRuleSet command, M05SemanticOutcome outcome) {
    singleton(outcome.events(), "ActivateRuleSet");
    M05SemanticEvent event = outcome.events().getFirst();
    if (event instanceof M05SemanticEvent.RuleSetActivated activated) {
      require(
          command.expectedApplicationSequence().equals(expectedApplicationSequence),
          "ACTIVATION_SEQUENCE_FENCE",
          "STALE_ACTIVATION_ACCEPTED",
          "successful activation did not match the command boundary");
      require(
          identity(command.expectedActive()).equals(active.identity()),
          "ACTIVATION_ACTIVE_IDENTITY_FENCE",
          "STALE_ACTIVE_IDENTITY_ACCEPTED",
          "successful activation used a stale active identity");
      require(
          prepared != null && identity(command.target()).equals(prepared.identity()),
          "ACTIVATE_REQUIRES_PREPARED",
          "UNPREPARED_ACTIVATION_ACCEPTED",
          "successful activation did not target the prepared artifact");
      require(
          activated.previousActive().equals(active.identity())
              && activated.active().equals(prepared.identity()),
          "RULE_SET_ACTIVATION_ATOMICITY",
          "ACTIVATION_EVENT_IDENTITY",
          "activation event did not expose the exact transition");
      BigInteger nextRevision = controlRevision.add(ONE);
      M05SemanticMarketState.ActivationFence fence = activated.fence();
      require(
          fence.applicationSequence().equals(expectedApplicationSequence)
              && fence.controlRevision().equals(nextRevision)
              && fence.firstAcceptanceSequence().equals(nextAcceptanceSequence),
          "ACTIVATION_SEQUENCE_FENCE",
          "ACTIVATION_FENCE_VALUES",
          "activation fence values differ from the ordered boundary");
      active = prepared;
      prepared = null;
      controlRevision = nextRevision;
      lastFence = fence;
    } else {
      require(
          event instanceof M05SemanticEvent.ActivateRuleSetRejected,
          "EVENT_GRAMMAR",
          "ACTIVATE_EVENT_UNION",
          "ActivateRuleSet emitted an illegal event");
    }
    require(
        outcome.bookAfter().equals(previousBook),
        "GRANDFATHER_RESTING_ORDERS",
        "ACTIVATION_REVALIDATED_BOOK",
        "activation must grandfather the existing book byte-for-byte");
  }

  private void verifyPlace(M05Command.Place command, M05SemanticOutcome outcome) {
    M05SemanticEvent first = outcome.events().getFirst();
    if (first instanceof M05SemanticEvent.Accepted accepted) {
      require(
          accepted.acceptanceSequence().equals(nextAcceptanceSequence),
          "ACCEPTANCE_SEQUENCE_CONTIGUITY",
          "ACCEPTANCE_SEQUENCE_GAP",
          "Accepted did not use the next acceptance sequence");
      require(
          accepted.orderId().equals(command.orderId())
              && accepted.priceTicks().equals(command.priceTicks())
              && accepted.quantityLots().equals(command.quantityLots())
              && accepted.side().equals(command.side())
              && accepted.executionPolicy().equals(command.executionPolicy()),
          "EVENT_GRAMMAR",
          "ACCEPTED_FIELDS",
          "Accepted fields differ from Place");
      require(
          accepted.admissionRuleSet().equals(active.identity())
              && accepted.executionRuleSet().equals(active.identity()),
          "RULE_SET_ATTRIBUTION",
          "ACCEPTED_RULE_ATTRIBUTION",
          "Accepted must name the active rule as admission and execution rule");
      require(
          inBand(command.priceTicks(), active),
          "INCLUSIVE_ORDER_ENTRY_PRICE_BAND",
          "OUT_OF_BAND_PLACE_ACCEPTED",
          "accepted Place is outside the active inclusive band");
      if ("GOVERNED".equals(command.entrypoint())) {
        require(
            identity(command.expectedRuleSet()).equals(active.identity()),
            "GOVERNED_PLACE_FENCE",
            "STALE_PLACE_RULE_ACCEPTED",
            "governed Place accepted under a stale rule identity");
      }
      nextAcceptanceSequence = nextAcceptanceSequence.add(ONE);
      verifyExecutionEvents(outcome.events(), accepted);
    } else if (first instanceof M05SemanticEvent.PlaceRejected rejected) {
      singleton(outcome.events(), "rejected Place");
      require(
          rejected.orderId().equals(command.orderId()),
          "EVENT_GRAMMAR",
          "PLACE_REJECTION_ORDER",
          "Place rejection order differs from command");
      require(
          rejected.executionRuleSet().equals(active.identity()),
          "RULE_SET_ATTRIBUTION",
          "PLACE_REJECTION_RULE",
          "Place rejection did not name the active execution rule");
    } else {
      require(
          first instanceof M05SemanticEvent.Rejected,
          "EVENT_GRAMMAR",
          "PLACE_EVENT_UNION",
          "Place emitted an illegal first event");
      singleton(outcome.events(), "field-rejected Place");
    }
  }

  private void verifyExecutionEvents(
      List<M05SemanticEvent> events, M05SemanticEvent.Accepted accepted) {
    for (int index = 1; index < events.size(); index++) {
      M05SemanticEvent event = events.get(index);
      if (event instanceof M05SemanticEvent.Trade trade) {
        require(
            trade.takerSequence().equals(accepted.acceptanceSequence())
                && trade.takerOrderId().equals(accepted.orderId())
                && trade.takerAdmissionRuleSet().equals(accepted.admissionRuleSet())
                && trade.executionRuleSet().equals(active.identity()),
            "RULE_SET_ATTRIBUTION",
            "TRADE_RULE_ATTRIBUTION",
            "Trade taker or rule attribution differs from Accepted");
        require(
            trade.quantityLots().signum() > 0,
            "QUANTITY_PARTITION",
            "NON_POSITIVE_TRADE",
            "Trade quantity must be positive");
      } else if (event instanceof M05SemanticEvent.Rested rested) {
        require(
            index == events.size() - 1
                && rested.orderId().equals(accepted.orderId())
                && rested.acceptanceSequence().equals(accepted.acceptanceSequence())
                && rested.admissionRuleSet().equals(accepted.admissionRuleSet())
                && rested.executionRuleSet().equals(active.identity()),
            "RULE_SET_ATTRIBUTION",
            "RESTED_RULE_ATTRIBUTION",
            "Rested terminal event attribution changed");
      } else if (event instanceof M05SemanticEvent.RemainderCanceled canceled) {
        require(
            index == events.size() - 1
                && canceled.orderId().equals(accepted.orderId())
                && canceled.admissionRuleSet().equals(accepted.admissionRuleSet())
                && canceled.executionRuleSet().equals(active.identity()),
            "RULE_SET_ATTRIBUTION",
            "REMAINDER_RULE_ATTRIBUTION",
            "RemainderCanceled terminal event attribution changed");
      } else {
        fail(
            "EVENT_GRAMMAR",
            "ACCEPTED_PLACE_EVENT_UNION",
            "accepted Place emitted an illegal follow-up event");
      }
    }
  }

  private void verifyCancel(M05Command.Cancel command, M05SemanticOutcome outcome) {
    singleton(outcome.events(), "Cancel");
    M05SemanticEvent event = outcome.events().getFirst();
    if (event instanceof M05SemanticEvent.Canceled canceled) {
      require(
          canceled.orderId().equals(command.orderId()),
          "EVENT_GRAMMAR",
          "CANCEL_ORDER",
          "Canceled order differs from command");
      require(
          canceled.executionRuleSet().equals(active.identity()),
          "RULE_SET_ATTRIBUTION",
          "CANCEL_EXECUTION_RULE",
          "Cancel did not name the current execution rule");
    } else if (event instanceof M05SemanticEvent.CancelRejected rejected) {
      require(
          rejected.orderId().equals(command.orderId())
              && rejected.executionRuleSet().equals(active.identity()),
          "RULE_SET_ATTRIBUTION",
          "CANCEL_REJECTION_RULE",
          "CancelRejected did not retain command identity and current rule");
    } else {
      require(
          event instanceof M05SemanticEvent.Rejected,
          "EVENT_GRAMMAR",
          "CANCEL_EVENT_UNION",
          "Cancel emitted an illegal event");
    }
  }

  private void verifyState(M05SemanticMarketState state) {
    require(
        state.nextApplicationSequence().equals(expectedApplicationSequence),
        "APPLICATION_SEQUENCE_CONTIGUITY",
        "NEXT_APPLICATION_SEQUENCE",
        "state nextApplicationSequence changed unexpectedly");
    require(
        state.nextAcceptanceSequence().equals(nextAcceptanceSequence),
        "ACCEPTANCE_SEQUENCE_CONTIGUITY",
        "NEXT_ACCEPTANCE_SEQUENCE",
        "state nextAcceptanceSequence changed unexpectedly");
    require(
        state.controlRevision().equals(controlRevision),
        "RULE_SET_ACTIVATION_ATOMICITY",
        "CONTROL_REVISION",
        "control revision changes only on successful Activate");
    require(
        state.activeRuleSet().equals(active),
        "RULE_SET_ACTIVATION_ATOMICITY",
        "ACTIVE_RULE_STATE",
        "active rule artifact differs from event-derived state");
    require(
        state.preparedRuleSet().equals(java.util.Optional.ofNullable(prepared)),
        "RULE_SET_PREPARE_ATOMICITY",
        "PREPARED_RULE_STATE",
        "prepared rule artifact differs from event-derived state");
    require(
        state.lastActivationFence().equals(java.util.Optional.ofNullable(lastFence)),
        "ACTIVATION_SEQUENCE_FENCE",
        "LAST_ACTIVATION_FENCE",
        "last activation fence differs from the successful activation event");
    verifyBook(state.book());
  }

  private static void verifyBook(M05SemanticBook book) {
    Set<BigInteger> orderIds = new HashSet<>();
    Set<BigInteger> sequences = new HashSet<>();
    verifyLevels(book.bids(), "BUY", true, orderIds, sequences);
    verifyLevels(book.asks(), "SELL", false, orderIds, sequences);
  }

  private static void verifyLevels(
      List<M05SemanticBook.PriceLevel> levels,
      String side,
      boolean descending,
      Set<BigInteger> orderIds,
      Set<BigInteger> sequences) {
    BigInteger previousPrice = null;
    for (M05SemanticBook.PriceLevel level : levels) {
      require(side.equals(level.side()), "BOOK_SHAPE", "LEVEL_SIDE", "book level side changed");
      require(!level.orders().isEmpty(), "BOOK_SHAPE", "EMPTY_LEVEL", "empty level was retained");
      if (previousPrice != null) {
        int comparison = level.priceTicks().compareTo(previousPrice);
        require(
            descending ? comparison < 0 : comparison > 0,
            "PRICE_TIME_PRIORITY",
            "PRICE_LEVEL_ORDER",
            "book price levels are not strictly ordered");
      }
      previousPrice = level.priceTicks();
      BigInteger previousSequence = null;
      for (M05SemanticBook.RestingOrder order : level.orders()) {
        require(
            order.remainingQuantityLots().signum() > 0,
            "QUANTITY_PARTITION",
            "NON_POSITIVE_RESTING",
            "resting quantity must be positive");
        require(
            orderIds.add(order.orderId()) && sequences.add(order.acceptanceSequence()),
            "BOOK_IDENTITY",
            "DUPLICATE_RESTING_IDENTITY",
            "resting identity is duplicated");
        require(
            previousSequence == null || order.acceptanceSequence().compareTo(previousSequence) > 0,
            "PRICE_TIME_PRIORITY",
            "FIFO_SEQUENCE_ORDER",
            "same-price resting orders are not ordered by acceptance sequence");
        previousSequence = order.acceptanceSequence();
        require(
            order.admissionRuleSet() != null,
            "RULE_SET_ATTRIBUTION",
            "MISSING_RESTING_ADMISSION_RULE",
            "resting order lost its admission rule");
      }
    }
  }

  private static boolean inBand(BigInteger price, M05MarketRuleSetArtifact rule) {
    return price.compareTo(rule.lowerInclusive()) >= 0
        && price.compareTo(rule.upperInclusive()) <= 0;
  }

  private static M05RuleSetIdentity identity(M05Command.Artifact artifact) {
    return new M05RuleSetIdentity(artifact.version(), artifact.contentHash());
  }

  private static M05RuleSetIdentity identity(M05Command.Identity identity) {
    return new M05RuleSetIdentity(identity.version(), identity.contentHash());
  }

  private static M05MarketRuleSetArtifact artifact(M05Command.Artifact artifact) {
    return new M05MarketRuleSetArtifact(
        artifact.schemaVersion(),
        artifact.instrumentId(),
        artifact.version(),
        artifact.lowerInclusive(),
        artifact.upperInclusive(),
        artifact.contentHash());
  }

  private static M05MarketRuleSetArtifact bootstrap() {
    M05Command.Artifact value = M05RuleSetCanonical.BOOTSTRAP;
    return artifact(value);
  }

  private static void singleton(List<M05SemanticEvent> events, String command) {
    require(
        events.size() == 1,
        "EVENT_GRAMMAR",
        "NON_SINGLETON_" + command.toUpperCase(),
        command + " must emit exactly one event");
  }

  private static void require(
      boolean condition, String propertyId, String divergenceKind, String message) {
    if (!condition) {
      fail(propertyId, divergenceKind, message);
    }
  }

  private static void fail(String propertyId, String divergenceKind, String message) {
    throw new LedgerFailure(propertyId, divergenceKind, message);
  }

  static final class LedgerFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String propertyId;
    private final String divergenceKind;

    LedgerFailure(String propertyId, String divergenceKind, String message) {
      super(message);
      this.propertyId = propertyId;
      this.divergenceKind = divergenceKind;
    }

    String propertyId() {
      return propertyId;
    }

    String divergenceKind() {
      return divergenceKind;
    }
  }
}
