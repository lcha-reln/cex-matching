package io.github.lchareln.cex.matching.reference;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Independent M07 model backed by one flat order list and complete linear price-time scans.
 *
 * <p>It is JDK-only, imports no production matcher type, and does not delegate to an older model.
 */
public final class M07LinearReferenceModel {
  private static final String INSTRUMENT = M06MarketRuleSetArtifact.INSTRUMENT;
  private static final String BUY = "BUY";
  private static final String SELL = "SELL";
  private static final String GTC = "GTC";
  private static final String IOC = "IOC";
  private static final String FOK = "FOK";
  private static final String POST_ONLY = "POST_ONLY";
  private static final String NONE = "NONE";
  private static final String CANCEL_TAKER = "CANCEL_TAKER";
  private static final String CANCEL_MAKER = "CANCEL_MAKER";
  private static final String CANCEL_BOTH = "CANCEL_BOTH";
  private static final String OPEN = "OPEN";
  private static final String CANCEL_ONLY = "CANCEL_ONLY";
  private static final String HALTED = "HALTED";
  private static final BigInteger MAXIMUM = BigInteger.valueOf(Long.MAX_VALUE);

  private final List<ReferenceOrder> orders = new ArrayList<>();
  private BigInteger nextApplicationSequence = BigInteger.ONE;
  private BigInteger nextAcceptanceSequence = BigInteger.ONE;
  private BigInteger controlRevision = BigInteger.ZERO;
  private M06MarketRuleSetArtifact activeRuleSet = M06MarketRuleSetArtifact.bootstrap();
  private M06MarketRuleSetArtifact preparedRuleSet;
  private M07SemanticMarketState.ActivationFence lastActivationFence;
  private String marketMode = OPEN;
  private BigInteger modeRevision = BigInteger.ZERO;
  private M07SemanticMarketState.ModeTransitionFence lastModeTransitionFence;
  private M07SemanticMarketState.MassCancelFence lastMassCancelFence;

  /** Applies one caller-serialized M07 command at the next application boundary. */
  public M07SemanticOutcome apply(M07ReferenceCommand command) {
    Objects.requireNonNull(command, "command");
    assertConsistentState();
    if (nextApplicationSequence.compareTo(MAXIMUM) >= 0) {
      throw new IllegalStateException("application sequence exhausted before state mutation");
    }
    BigInteger applicationSequence = nextApplicationSequence;
    List<M07SemanticEvent> events =
        switch (command) {
          case M07ReferenceCommand.Place place -> place(place, applicationSequence);
          case M07ReferenceCommand.Cancel cancel -> cancel(cancel, applicationSequence);
          case M07ReferenceCommand.PrepareRuleSet prepare -> prepare(prepare);
          case M07ReferenceCommand.ActivateRuleSet activate ->
              activate(activate, applicationSequence);
          case M07ReferenceCommand.ChangeMarketMode change ->
              changeMarketMode(change, applicationSequence);
          case M07ReferenceCommand.MassCancel massCancel ->
              massCancel(massCancel, applicationSequence);
        };
    nextApplicationSequence = nextApplicationSequence.add(BigInteger.ONE);
    assertConsistentState();
    return new M07SemanticOutcome(applicationSequence, events, snapshot());
  }

  /** Returns a detached state image without consuming an application boundary. */
  public M07SemanticMarketState snapshot() {
    assertConsistentState();
    return new M07SemanticMarketState(
        nextApplicationSequence,
        nextAcceptanceSequence,
        controlRevision,
        activeRuleSet,
        Optional.ofNullable(preparedRuleSet),
        Optional.ofNullable(lastActivationFence),
        marketMode,
        modeRevision,
        Optional.ofNullable(lastModeTransitionFence),
        Optional.ofNullable(lastMassCancelFence),
        deriveBook());
  }

  private List<M07SemanticEvent> place(
      M07ReferenceCommand.Place command, BigInteger applicationSequence) {
    M07SemanticEvent.Rejected invalid = validate(command);
    if (invalid != null) {
      return singleton(invalid);
    }
    M06RuleSetIdentity executionRuleSet = activeRuleSet.identity();
    if (find(command.orderId()) != null) {
      return singleton(
          new M07SemanticEvent.PlaceRejected(
              command.orderId(), "DUPLICATE_ORDER_ID", executionRuleSet));
    }
    if (isGoverned(command.entrypoint()) && !command.expectedRuleSet().equals(executionRuleSet)) {
      return singleton(
          new M07SemanticEvent.PlaceRejected(
              command.orderId(), "RULE_SET_MISMATCH", executionRuleSet));
    }
    if (command.priceTicks().compareTo(activeRuleSet.lowerInclusive()) < 0
        || command.priceTicks().compareTo(activeRuleSet.upperInclusive()) > 0) {
      return singleton(
          new M07SemanticEvent.PlaceRejected(
              command.orderId(), "PRICE_OUTSIDE_ACTIVE_BAND", executionRuleSet));
    }
    if (!OPEN.equals(marketMode)) {
      return singleton(
          new M07SemanticEvent.PlaceRejected(
              command.orderId(), "MARKET_NOT_OPEN", executionRuleSet));
    }
    if (FOK.equals(command.executionPolicy()) && !isFullyExecutable(command)) {
      return singleton(
          new M07SemanticEvent.PlaceRejected(
              command.orderId(), "FOK_NOT_FILLABLE", executionRuleSet));
    }
    if (POST_ONLY.equals(command.executionPolicy()) && hasCrossingMaker(command)) {
      return singleton(
          new M07SemanticEvent.PlaceRejected(
              command.orderId(), "POST_ONLY_WOULD_TAKE", executionRuleSet));
    }
    if (nextAcceptanceSequence.compareTo(MAXIMUM) >= 0) {
      throw new IllegalStateException("acceptance sequence exhausted before state mutation");
    }

    ReferenceOrder taker =
        new ReferenceOrder(
            nextAcceptanceSequence,
            command.orderId(),
            command.side(),
            command.priceTicks(),
            command.quantityLots(),
            command.executionPolicy(),
            executionRuleSet,
            command.participantGroupId(),
            command.stpPolicy());
    nextAcceptanceSequence = nextAcceptanceSequence.add(BigInteger.ONE);
    orders.add(taker);

    List<M07SemanticEvent> events = new ArrayList<>();
    events.add(
        new M07SemanticEvent.Accepted(
            taker.sequence,
            taker.orderId,
            taker.side,
            taker.price,
            taker.original,
            taker.executionPolicy,
            taker.admissionRuleSet,
            executionRuleSet,
            taker.participantGroupId,
            taker.stpPolicy));

    while (taker.remaining.signum() > 0) {
      ReferenceOrder maker = selectMaker(taker.side, taker.price);
      if (maker == null) {
        break;
      }
      BigInteger wouldTrade = taker.remaining.min(maker.remaining);
      if (conflicts(taker, maker)) {
        BigInteger makerCanceled = BigInteger.ZERO;
        BigInteger takerCanceled = BigInteger.ZERO;
        if (CANCEL_MAKER.equals(taker.stpPolicy) || CANCEL_BOTH.equals(taker.stpPolicy)) {
          makerCanceled = maker.remaining;
          maker.cancel(makerCanceled, "SELF_TRADE_PREVENTION", applicationSequence);
        }
        if (CANCEL_TAKER.equals(taker.stpPolicy) || CANCEL_BOTH.equals(taker.stpPolicy)) {
          takerCanceled = taker.remaining;
          taker.cancelAcceptedRemainder(
              takerCanceled, "SELF_TRADE_PREVENTION", applicationSequence);
        }
        events.add(
            new M07SemanticEvent.SelfTradePrevented(
                maker.sequence,
                maker.orderId,
                taker.sequence,
                taker.orderId,
                maker.price,
                wouldTrade,
                taker.participantGroupId,
                taker.stpPolicy,
                makerCanceled,
                takerCanceled,
                maker.admissionRuleSet,
                taker.admissionRuleSet,
                executionRuleSet));
        if (!CANCEL_MAKER.equals(taker.stpPolicy)) {
          break;
        }
        continue;
      }
      maker.fill(wouldTrade);
      taker.fill(wouldTrade);
      events.add(
          new M07SemanticEvent.Trade(
              maker.sequence,
              maker.orderId,
              taker.sequence,
              taker.orderId,
              maker.price,
              wouldTrade,
              maker.admissionRuleSet,
              taker.admissionRuleSet,
              executionRuleSet));
    }

    if (taker.lifecycle == Lifecycle.CANCELED) {
      // SelfTradePrevented is the terminal event.
    } else if (taker.remaining.signum() == 0) {
      taker.markFilled();
    } else if (IOC.equals(taker.executionPolicy)) {
      BigInteger canceled = taker.remaining;
      taker.cancelAcceptedRemainder(canceled, "IOC_REMAINDER", applicationSequence);
      events.add(
          new M07SemanticEvent.RemainderCanceled(
              taker.sequence,
              taker.orderId,
              taker.side,
              taker.price,
              canceled,
              "IOC_REMAINDER",
              taker.admissionRuleSet,
              executionRuleSet));
    } else if (FOK.equals(taker.executionPolicy)) {
      throw new IllegalStateException("M07 FOK preflight and apply disagreed");
    } else {
      taker.markResting();
      events.add(
          new M07SemanticEvent.Rested(
              taker.sequence,
              taker.orderId,
              taker.side,
              taker.price,
              taker.remaining,
              taker.admissionRuleSet,
              executionRuleSet,
              taker.participantGroupId,
              taker.stpPolicy));
    }
    return List.copyOf(events);
  }

  private List<M07SemanticEvent> cancel(
      M07ReferenceCommand.Cancel command, BigInteger applicationSequence) {
    M07SemanticEvent.Rejected invalid = validate(command);
    if (invalid != null) {
      return singleton(invalid);
    }
    M06RuleSetIdentity executionRuleSet = activeRuleSet.identity();
    if (HALTED.equals(marketMode)) {
      return singleton(
          new M07SemanticEvent.CancelRejected(
              command.orderId(), "MARKET_NOT_CANCELABLE", executionRuleSet));
    }
    ReferenceOrder order = find(command.orderId());
    if (order == null) {
      return singleton(
          new M07SemanticEvent.CancelRejected(
              command.orderId(), "ORDER_NOT_FOUND", executionRuleSet));
    }
    if (order.lifecycle == Lifecycle.FILLED) {
      return singleton(
          new M07SemanticEvent.CancelRejected(
              command.orderId(), "ORDER_ALREADY_FILLED", executionRuleSet));
    }
    if (order.lifecycle == Lifecycle.CANCELED) {
      return singleton(
          new M07SemanticEvent.CancelRejected(
              command.orderId(), "ORDER_ALREADY_CANCELED", executionRuleSet));
    }
    BigInteger canceled = order.remaining;
    M07SemanticEvent.Canceled event =
        new M07SemanticEvent.Canceled(
            order.sequence,
            order.orderId,
            order.side,
            order.price,
            canceled,
            order.admissionRuleSet,
            executionRuleSet);
    order.cancel(canceled, "USER_REQUEST", applicationSequence);
    return singleton(event);
  }

  private List<M07SemanticEvent> prepare(M07ReferenceCommand.PrepareRuleSet command) {
    if (!command.expectedActive().equals(activeRuleSet.identity())) {
      return singleton(
          new M07SemanticEvent.PrepareRuleSetRejected("EXPECTED_ACTIVE_RULE_SET_MISMATCH"));
    }
    String artifactFailure = artifactFailure(command.artifact());
    if (artifactFailure != null) {
      return singleton(new M07SemanticEvent.PrepareRuleSetRejected(artifactFailure));
    }
    M06MarketRuleSetArtifact candidate = command.artifact();
    int activeComparison = candidate.version().compareTo(activeRuleSet.version());
    if (activeComparison <= 0) {
      String code =
          activeComparison == 0 && !candidate.equals(activeRuleSet)
              ? "SAME_VERSION_DIFFERENT_CONTENT"
              : "VERSION_NOT_INCREASING";
      return singleton(new M07SemanticEvent.PrepareRuleSetRejected(code));
    }
    if (preparedRuleSet == null) {
      preparedRuleSet = candidate;
      return singleton(
          new M07SemanticEvent.RuleSetPrepared(
              candidate.identity(), M07SemanticEvent.PrepareStatus.PREPARED, Optional.empty()));
    }
    int preparedComparison = candidate.version().compareTo(preparedRuleSet.version());
    if (preparedComparison == 0) {
      if (candidate.equals(preparedRuleSet)) {
        return singleton(
            new M07SemanticEvent.RuleSetPrepared(
                candidate.identity(),
                M07SemanticEvent.PrepareStatus.ALREADY_PREPARED,
                Optional.empty()));
      }
      return singleton(
          new M07SemanticEvent.PrepareRuleSetRejected("SAME_VERSION_DIFFERENT_CONTENT"));
    }
    if (preparedComparison < 0) {
      return singleton(new M07SemanticEvent.PrepareRuleSetRejected("VERSION_NOT_INCREASING"));
    }
    M06RuleSetIdentity superseded = preparedRuleSet.identity();
    preparedRuleSet = candidate;
    return singleton(
        new M07SemanticEvent.RuleSetPrepared(
            candidate.identity(),
            M07SemanticEvent.PrepareStatus.SUPERSEDED,
            Optional.of(superseded)));
  }

  private List<M07SemanticEvent> activate(
      M07ReferenceCommand.ActivateRuleSet command, BigInteger applicationSequence) {
    if (!command.expectedApplicationSequence().equals(applicationSequence)) {
      return singleton(
          new M07SemanticEvent.ActivateRuleSetRejected("APPLICATION_SEQUENCE_MISMATCH"));
    }
    M06RuleSetIdentity previousActive = activeRuleSet.identity();
    if (!command.expectedActive().equals(previousActive)) {
      return singleton(
          new M07SemanticEvent.ActivateRuleSetRejected("EXPECTED_ACTIVE_RULE_SET_MISMATCH"));
    }
    if (preparedRuleSet == null) {
      return singleton(new M07SemanticEvent.ActivateRuleSetRejected("NO_PREPARED_RULE_SET"));
    }
    if (!command.target().equals(preparedRuleSet.identity())) {
      return singleton(new M07SemanticEvent.ActivateRuleSetRejected("TARGET_RULE_SET_MISMATCH"));
    }
    if (!preparedRuleSet.contentHash().equals(preparedRuleSet.computedContentHash())) {
      return singleton(
          new M07SemanticEvent.ActivateRuleSetRejected("PREPARED_CONTENT_HASH_MISMATCH"));
    }
    if (controlRevision.compareTo(MAXIMUM) >= 0) {
      throw new IllegalStateException("control revision exhausted before state mutation");
    }
    BigInteger nextRevision = controlRevision.add(BigInteger.ONE);
    M07SemanticMarketState.ActivationFence fence =
        new M07SemanticMarketState.ActivationFence(
            applicationSequence, nextRevision, nextAcceptanceSequence);
    M06MarketRuleSetArtifact activated = preparedRuleSet;
    activeRuleSet = activated;
    preparedRuleSet = null;
    controlRevision = nextRevision;
    lastActivationFence = fence;
    return singleton(
        new M07SemanticEvent.RuleSetActivated(previousActive, activated.identity(), fence));
  }

  private List<M07SemanticEvent> changeMarketMode(
      M07ReferenceCommand.ChangeMarketMode command, BigInteger applicationSequence) {
    if (!command.expectedApplicationSequence().equals(applicationSequence)) {
      return singleton(
          new M07SemanticEvent.ModeChangeRejected(
              command.operatorId(),
              marketMode,
              command.targetMode(),
              "APPLICATION_SEQUENCE_MISMATCH"));
    }
    if (!command.expectedMode().equals(marketMode)) {
      return singleton(
          new M07SemanticEvent.ModeChangeRejected(
              command.operatorId(), marketMode, command.targetMode(), "EXPECTED_MODE_MISMATCH"));
    }
    if (command.targetMode().equals(marketMode)) {
      return singleton(
          new M07SemanticEvent.ModeChangeRejected(
              command.operatorId(), marketMode, command.targetMode(), "NO_MODE_CHANGE"));
    }
    if (!isPermittedTransition(marketMode, command.targetMode())) {
      return singleton(
          new M07SemanticEvent.ModeChangeRejected(
              command.operatorId(), marketMode, command.targetMode(), "INVALID_TRANSITION"));
    }
    if (modeRevision.compareTo(MAXIMUM) >= 0) {
      throw new IllegalStateException("mode revision exhausted before state mutation");
    }
    String previousMode = marketMode;
    BigInteger nextRevision = modeRevision.add(BigInteger.ONE);
    M07SemanticMarketState.ModeTransitionFence fence =
        new M07SemanticMarketState.ModeTransitionFence(
            applicationSequence,
            nextRevision,
            previousMode,
            command.targetMode(),
            nextAcceptanceSequence);
    marketMode = command.targetMode();
    modeRevision = nextRevision;
    lastModeTransitionFence = fence;
    return singleton(
        new M07SemanticEvent.ModeChanged(command.operatorId(), previousMode, marketMode, fence));
  }

  private List<M07SemanticEvent> massCancel(
      M07ReferenceCommand.MassCancel command, BigInteger applicationSequence) {
    if (!command.expectedApplicationSequence().equals(applicationSequence)) {
      return singleton(
          new M07SemanticEvent.MassCancelRejected(
              command.operatorId(), marketMode, "APPLICATION_SEQUENCE_MISMATCH"));
    }
    if (!command.expectedMode().equals(marketMode)) {
      return singleton(
          new M07SemanticEvent.MassCancelRejected(
              command.operatorId(), marketMode, "EXPECTED_MODE_MISMATCH"));
    }
    if (!HALTED.equals(marketMode)) {
      return singleton(
          new M07SemanticEvent.MassCancelRejected(
              command.operatorId(), marketMode, "MARKET_NOT_HALTED"));
    }
    List<ReferenceOrder> resting =
        orders.stream()
            .filter(order -> order.lifecycle == Lifecycle.RESTING)
            .sorted(Comparator.comparing(order -> order.sequence))
            .toList();
    BigInteger count = BigInteger.valueOf(resting.size());
    List<M07SemanticEvent> events = new ArrayList<>();
    events.add(
        new M07SemanticEvent.MassCancelStarted(
            command.operatorId(), marketMode, modeRevision, count));
    M06RuleSetIdentity executionRuleSet = activeRuleSet.identity();
    for (ReferenceOrder order : resting) {
      events.add(
          new M07SemanticEvent.MassOrderCanceled(
              command.operatorId(),
              order.sequence,
              order.orderId,
              order.side,
              order.price,
              order.remaining,
              order.admissionRuleSet,
              executionRuleSet));
    }
    events.add(
        new M07SemanticEvent.MassCancelCompleted(
            command.operatorId(), marketMode, modeRevision, count));
    lastMassCancelFence =
        new M07SemanticMarketState.MassCancelFence(
            applicationSequence,
            modeRevision,
            command.operatorId(),
            count,
            resting.isEmpty() ? Optional.empty() : Optional.of(resting.getFirst().sequence),
            resting.isEmpty() ? Optional.empty() : Optional.of(resting.getLast().sequence));
    for (ReferenceOrder order : resting) {
      order.cancel(order.remaining, "OPERATOR_MASS_CANCEL", applicationSequence);
    }
    return List.copyOf(events);
  }

  private static M07SemanticEvent.Rejected validate(M07ReferenceCommand.Place command) {
    if (!INSTRUMENT.equals(command.instrumentId())) {
      return new M07SemanticEvent.Rejected("UNKNOWN_INSTRUMENT", "instrumentId");
    }
    if (!isPositiveLong(command.orderId())) {
      return new M07SemanticEvent.Rejected("INVALID_ORDER_ID", "orderId");
    }
    if (!BUY.equals(command.side()) && !SELL.equals(command.side())) {
      return new M07SemanticEvent.Rejected("INVALID_SIDE", "side");
    }
    if (!isPositiveLong(command.priceTicks())) {
      return new M07SemanticEvent.Rejected("INVALID_PRICE", "priceTicks");
    }
    if (!isPositiveLong(command.quantityLots())) {
      return new M07SemanticEvent.Rejected("INVALID_QUANTITY", "quantityLots");
    }
    if (!isExecutionPolicy(command.executionPolicy())) {
      return new M07SemanticEvent.Rejected("INVALID_EXECUTION_POLICY", "executionPolicy");
    }
    if (command.participantGroupId().signum() < 0
        || command.participantGroupId().compareTo(MAXIMUM) > 0) {
      return new M07SemanticEvent.Rejected("INVALID_STP_GROUP_ID", "participantGroupId");
    }
    if (!isStpPolicy(command.stpPolicy())) {
      return new M07SemanticEvent.Rejected("INVALID_STP_POLICY", "stpPolicy");
    }
    if (!isValidStpInstruction(command.participantGroupId(), command.stpPolicy())) {
      return new M07SemanticEvent.Rejected("INVALID_STP_INSTRUCTION", "stpInstruction");
    }
    return null;
  }

  private static M07SemanticEvent.Rejected validate(M07ReferenceCommand.Cancel command) {
    if (!INSTRUMENT.equals(command.instrumentId())) {
      return new M07SemanticEvent.Rejected("UNKNOWN_INSTRUMENT", "instrumentId");
    }
    if (!isPositiveLong(command.orderId())) {
      return new M07SemanticEvent.Rejected("INVALID_ORDER_ID", "orderId");
    }
    return null;
  }

  private boolean isFullyExecutable(M07ReferenceCommand.Place taker) {
    BigInteger required = taker.quantityLots();
    for (ReferenceOrder maker : crossingMakers(taker.side(), taker.priceTicks())) {
      if (conflicts(taker.participantGroupId(), maker.participantGroupId)) {
        if (CANCEL_MAKER.equals(taker.stpPolicy())) {
          continue;
        }
        return false;
      }
      if (maker.remaining.compareTo(required) >= 0) {
        return true;
      }
      required = required.subtract(maker.remaining);
    }
    return false;
  }

  private boolean hasCrossingMaker(M07ReferenceCommand.Place taker) {
    return !crossingMakers(taker.side(), taker.priceTicks()).isEmpty();
  }

  private ReferenceOrder selectMaker(String takerSide, BigInteger takerPrice) {
    List<ReferenceOrder> makers = crossingMakers(takerSide, takerPrice);
    return makers.isEmpty() ? null : makers.getFirst();
  }

  private List<ReferenceOrder> crossingMakers(String takerSide, BigInteger takerPrice) {
    List<ReferenceOrder> makers = new ArrayList<>();
    for (ReferenceOrder order : orders) {
      if (order.lifecycle == Lifecycle.RESTING
          && !order.side.equals(takerSide)
          && crosses(takerSide, takerPrice, order.price)) {
        makers.add(order);
      }
    }
    Comparator<ReferenceOrder> comparator = Comparator.comparing(order -> order.price);
    if (SELL.equals(takerSide)) {
      comparator = comparator.reversed();
    }
    comparator = comparator.thenComparing(order -> order.sequence);
    makers.sort(comparator);
    return makers;
  }

  private static boolean conflicts(ReferenceOrder taker, ReferenceOrder maker) {
    return conflicts(taker.participantGroupId, maker.participantGroupId);
  }

  private static boolean conflicts(BigInteger takerGroup, BigInteger makerGroup) {
    return takerGroup.signum() > 0 && takerGroup.equals(makerGroup);
  }

  private ReferenceOrder find(BigInteger orderId) {
    for (ReferenceOrder order : orders) {
      if (order.orderId.equals(orderId)) {
        return order;
      }
    }
    return null;
  }

  private M07SemanticBook deriveBook() {
    return new M07SemanticBook(deriveSide(BUY), deriveSide(SELL));
  }

  private List<M07SemanticBook.PriceLevel> deriveSide(String side) {
    List<ReferenceOrder> active = new ArrayList<>();
    for (ReferenceOrder order : orders) {
      if (order.lifecycle == Lifecycle.RESTING && side.equals(order.side)) {
        active.add(order);
      }
    }
    Comparator<ReferenceOrder> comparator = Comparator.comparing(order -> order.price);
    if (BUY.equals(side)) {
      comparator = comparator.reversed();
    }
    comparator = comparator.thenComparing(order -> order.sequence);
    active.sort(comparator);

    List<M07SemanticBook.PriceLevel> levels = new ArrayList<>();
    BigInteger currentPrice = null;
    List<M07SemanticBook.RestingOrder> currentOrders = new ArrayList<>();
    for (ReferenceOrder order : active) {
      if (currentPrice != null && !currentPrice.equals(order.price)) {
        levels.add(new M07SemanticBook.PriceLevel(side, currentPrice, currentOrders));
        currentOrders = new ArrayList<>();
      }
      currentPrice = order.price;
      currentOrders.add(
          new M07SemanticBook.RestingOrder(
              order.sequence,
              order.orderId,
              order.remaining,
              order.admissionRuleSet,
              order.participantGroupId,
              order.stpPolicy));
    }
    if (currentPrice != null) {
      levels.add(new M07SemanticBook.PriceLevel(side, currentPrice, currentOrders));
    }
    return List.copyOf(levels);
  }

  private void assertConsistentState() {
    if (!isPositiveLong(nextApplicationSequence)
        || !isPositiveLong(nextAcceptanceSequence)
        || controlRevision.signum() < 0
        || controlRevision.compareTo(MAXIMUM) > 0
        || modeRevision.signum() < 0
        || modeRevision.compareTo(MAXIMUM) > 0) {
      throw new IllegalStateException("M07 reference sequence or revision is outside its domain");
    }
    if ((!OPEN.equals(marketMode) && !CANCEL_ONLY.equals(marketMode) && !HALTED.equals(marketMode))
        || artifactFailure(activeRuleSet) != null) {
      throw new IllegalStateException("M07 reference control state is invalid");
    }
    if (preparedRuleSet != null
        && (artifactFailure(preparedRuleSet) != null
            || preparedRuleSet.version().compareTo(activeRuleSet.version()) <= 0)) {
      throw new IllegalStateException("M07 prepared rule set is invalid");
    }
    if ((lastActivationFence == null) != (controlRevision.signum() == 0)) {
      throw new IllegalStateException("M07 activation fence and revision disagree");
    }
    if ((lastModeTransitionFence == null) != (modeRevision.signum() == 0)) {
      throw new IllegalStateException("M07 mode fence and revision disagree");
    }
    for (int left = 0; left < orders.size(); left++) {
      ReferenceOrder order = orders.get(left);
      order.assertQuantityPartition();
      if (order.sequence.signum() <= 0
          || order.sequence.compareTo(nextAcceptanceSequence) >= 0
          || !order.admissionRuleSet.hasCanonicalShape()
          || !isValidStpInstruction(order.participantGroupId, order.stpPolicy)) {
        throw new IllegalStateException("M07 reference order attribution is invalid");
      }
      for (int right = left + 1; right < orders.size(); right++) {
        ReferenceOrder other = orders.get(right);
        if (order.orderId.equals(other.orderId) || order.sequence.equals(other.sequence)) {
          throw new IllegalStateException("M07 reference order identity is not unique");
        }
      }
    }
    ReferenceOrder bid = bestResting(BUY);
    ReferenceOrder ask = bestResting(SELL);
    if (bid != null && ask != null && bid.price.compareTo(ask.price) >= 0) {
      throw new IllegalStateException("M07 reference book is crossed");
    }
  }

  private ReferenceOrder bestResting(String side) {
    ReferenceOrder best = null;
    for (ReferenceOrder order : orders) {
      if (order.lifecycle != Lifecycle.RESTING || !side.equals(order.side)) {
        continue;
      }
      if (best == null
          || (BUY.equals(side)
              ? order.price.compareTo(best.price) > 0
              : order.price.compareTo(best.price) < 0)) {
        best = order;
      }
    }
    return best;
  }

  private static String artifactFailure(M06MarketRuleSetArtifact artifact) {
    if (!artifact.contentHashHasCanonicalShape()) {
      return "MALFORMED_CONTENT_HASH";
    }
    return artifact.contentHash().equals(artifact.computedContentHash())
        ? null
        : "CONTENT_HASH_MISMATCH";
  }

  private static boolean isGoverned(M07ReferenceCommand.PlaceEntrypoint entrypoint) {
    return entrypoint == M07ReferenceCommand.PlaceEntrypoint.GOVERNED
        || entrypoint == M07ReferenceCommand.PlaceEntrypoint.GOVERNED_STP;
  }

  private static boolean isExecutionPolicy(String value) {
    return GTC.equals(value) || IOC.equals(value) || FOK.equals(value) || POST_ONLY.equals(value);
  }

  private static boolean isStpPolicy(String value) {
    return NONE.equals(value)
        || CANCEL_TAKER.equals(value)
        || CANCEL_MAKER.equals(value)
        || CANCEL_BOTH.equals(value);
  }

  private static boolean isValidStpInstruction(BigInteger group, String policy) {
    return (group.signum() == 0 && NONE.equals(policy))
        || (group.signum() > 0 && !NONE.equals(policy) && isStpPolicy(policy));
  }

  private static boolean isPositiveLong(BigInteger value) {
    return value.signum() > 0 && value.compareTo(MAXIMUM) <= 0;
  }

  private static boolean crosses(String takerSide, BigInteger takerPrice, BigInteger makerPrice) {
    return BUY.equals(takerSide)
        ? takerPrice.compareTo(makerPrice) >= 0
        : takerPrice.compareTo(makerPrice) <= 0;
  }

  private static boolean isPermittedTransition(String current, String target) {
    return switch (current) {
      case OPEN -> CANCEL_ONLY.equals(target) || HALTED.equals(target);
      case CANCEL_ONLY -> OPEN.equals(target) || HALTED.equals(target);
      case HALTED -> CANCEL_ONLY.equals(target);
      default -> false;
    };
  }

  private static List<M07SemanticEvent> singleton(M07SemanticEvent event) {
    return List.of(event);
  }

  private enum Lifecycle {
    ACCEPTED,
    RESTING,
    FILLED,
    CANCELED
  }

  private static final class ReferenceOrder {
    private final BigInteger sequence;
    private final BigInteger orderId;
    private final String side;
    private final BigInteger price;
    private final BigInteger original;
    private final String executionPolicy;
    private final M06RuleSetIdentity admissionRuleSet;
    private final BigInteger participantGroupId;
    private final String stpPolicy;
    private BigInteger remaining;
    private BigInteger filled = BigInteger.ZERO;
    private BigInteger canceled = BigInteger.ZERO;
    private Lifecycle lifecycle = Lifecycle.ACCEPTED;
    private String cancellationOrigin;
    private BigInteger cancellationApplicationSequence;

    private ReferenceOrder(
        BigInteger sequence,
        BigInteger orderId,
        String side,
        BigInteger price,
        BigInteger original,
        String executionPolicy,
        M06RuleSetIdentity admissionRuleSet,
        BigInteger participantGroupId,
        String stpPolicy) {
      this.sequence = sequence;
      this.orderId = orderId;
      this.side = side;
      this.price = price;
      this.original = original;
      this.executionPolicy = executionPolicy;
      this.admissionRuleSet = admissionRuleSet;
      this.participantGroupId = participantGroupId;
      this.stpPolicy = stpPolicy;
      remaining = original;
    }

    private void fill(BigInteger quantity) {
      if ((lifecycle != Lifecycle.ACCEPTED && lifecycle != Lifecycle.RESTING)
          || quantity.signum() <= 0
          || quantity.compareTo(remaining) > 0) {
        throw new IllegalStateException("invalid M07 reference fill");
      }
      remaining = remaining.subtract(quantity);
      filled = filled.add(quantity);
      if (remaining.signum() == 0) {
        lifecycle = Lifecycle.FILLED;
      }
    }

    private void markFilled() {
      if (lifecycle != Lifecycle.FILLED || remaining.signum() != 0) {
        throw new IllegalStateException("invalid M07 filled transition");
      }
    }

    private void markResting() {
      if (lifecycle != Lifecycle.ACCEPTED || remaining.signum() <= 0) {
        throw new IllegalStateException("invalid M07 resting transition");
      }
      lifecycle = Lifecycle.RESTING;
    }

    private void cancel(BigInteger quantity, String origin, BigInteger applicationSequence) {
      if (lifecycle != Lifecycle.RESTING || !quantity.equals(remaining) || quantity.signum() <= 0) {
        throw new IllegalStateException("invalid M07 resting cancellation");
      }
      cancelRemainder(quantity, origin, applicationSequence);
    }

    private void cancelAcceptedRemainder(
        BigInteger quantity, String origin, BigInteger applicationSequence) {
      if (lifecycle != Lifecycle.ACCEPTED
          || !quantity.equals(remaining)
          || quantity.signum() <= 0) {
        throw new IllegalStateException("invalid M07 accepted cancellation");
      }
      if ("IOC_REMAINDER".equals(origin) && !IOC.equals(executionPolicy)) {
        throw new IllegalStateException("only IOC has an IOC remainder");
      }
      cancelRemainder(quantity, origin, applicationSequence);
    }

    private void cancelRemainder(
        BigInteger quantity, String origin, BigInteger applicationSequence) {
      cancellationOrigin = Objects.requireNonNull(origin, "origin");
      cancellationApplicationSequence =
          Objects.requireNonNull(applicationSequence, "applicationSequence");
      remaining = BigInteger.ZERO;
      canceled = canceled.add(quantity);
      lifecycle = Lifecycle.CANCELED;
    }

    private void assertQuantityPartition() {
      if (!original.equals(filled.add(remaining).add(canceled))) {
        throw new IllegalStateException("M07 quantity partition is invalid");
      }
      boolean lifecycleValid =
          switch (lifecycle) {
            case ACCEPTED -> false;
            case RESTING ->
                remaining.signum() > 0
                    && canceled.signum() == 0
                    && !IOC.equals(executionPolicy)
                    && !FOK.equals(executionPolicy);
            case FILLED -> remaining.signum() == 0 && canceled.signum() == 0;
            case CANCELED -> remaining.signum() == 0 && canceled.signum() > 0;
          };
      if (!lifecycleValid
          || ((lifecycle == Lifecycle.CANCELED)
              != (cancellationOrigin != null && cancellationApplicationSequence != null))) {
        throw new IllegalStateException("M07 lifecycle attribution is invalid");
      }
    }
  }
}
