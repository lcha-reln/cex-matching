package io.github.lchareln.cex.matching.reference;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Independent M06 model backed by one flat order list and complete linear maker scans.
 *
 * <p>It deliberately does not delegate to the M04 model or import any production matcher type.
 */
public final class M06LinearReferenceModel {
  private static final String INSTRUMENT = M06MarketRuleSetArtifact.INSTRUMENT;
  private static final String BUY = "BUY";
  private static final String SELL = "SELL";
  private static final String GTC = "GTC";
  private static final String IOC = "IOC";
  private static final String FOK = "FOK";
  private static final String POST_ONLY = "POST_ONLY";
  private static final String IOC_REMAINDER = "IOC_REMAINDER";
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
  private M06SemanticMarketState.ActivationFence lastActivationFence;
  private String marketMode = OPEN;
  private BigInteger modeRevision = BigInteger.ZERO;
  private M06SemanticMarketState.ModeTransitionFence lastModeTransitionFence;
  private M06SemanticMarketState.MassCancelFence lastMassCancelFence;

  /** Applies one caller-serialized M06 command at the next in-memory application boundary. */
  public M06SemanticOutcome apply(M06ReferenceCommand command) {
    Objects.requireNonNull(command, "command");
    assertConsistentState();
    if (nextApplicationSequence.compareTo(MAXIMUM) >= 0) {
      throw new IllegalStateException("application sequence exhausted before state mutation");
    }

    BigInteger applicationSequence = nextApplicationSequence;
    BigInteger followingApplicationSequence = applicationSequence.add(BigInteger.ONE);
    List<M06SemanticEvent> events =
        switch (command) {
          case M06ReferenceCommand.Place place -> place(place, applicationSequence);
          case M06ReferenceCommand.Cancel cancel -> cancel(cancel, applicationSequence);
          case M06ReferenceCommand.PrepareRuleSet prepare -> prepare(prepare);
          case M06ReferenceCommand.ActivateRuleSet activate ->
              activate(activate, applicationSequence);
          case M06ReferenceCommand.ChangeMarketMode changeMode ->
              changeMarketMode(changeMode, applicationSequence);
          case M06ReferenceCommand.MassCancel massCancel ->
              massCancel(massCancel, applicationSequence);
        };
    nextApplicationSequence = followingApplicationSequence;

    assertConsistentState();
    M06SemanticMarketState state = snapshot();
    return new M06SemanticOutcome(applicationSequence, events, state);
  }

  /** Returns a detached semantic image; reading state never consumes an application sequence. */
  public M06SemanticMarketState snapshot() {
    assertConsistentState();
    return new M06SemanticMarketState(
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

  private List<M06SemanticEvent> prepare(M06ReferenceCommand.PrepareRuleSet command) {
    M06RuleSetIdentity activeIdentity = activeRuleSet.identity();
    if (!command.expectedActive().equals(activeIdentity)) {
      return singleton(
          new M06SemanticEvent.PrepareRuleSetRejected("EXPECTED_ACTIVE_RULE_SET_MISMATCH"));
    }

    String artifactFailure = artifactFailure(command.artifact());
    if (artifactFailure != null) {
      return singleton(new M06SemanticEvent.PrepareRuleSetRejected(artifactFailure));
    }

    M06MarketRuleSetArtifact candidate = command.artifact();
    int activeComparison = candidate.version().compareTo(activeRuleSet.version());
    if (activeComparison <= 0) {
      String code =
          activeComparison == 0 && !candidate.equals(activeRuleSet)
              ? "SAME_VERSION_DIFFERENT_CONTENT"
              : "VERSION_NOT_INCREASING";
      return singleton(new M06SemanticEvent.PrepareRuleSetRejected(code));
    }

    if (preparedRuleSet == null) {
      preparedRuleSet = candidate;
      return singleton(
          new M06SemanticEvent.RuleSetPrepared(
              candidate.identity(), M06SemanticEvent.PrepareStatus.PREPARED, Optional.empty()));
    }

    int preparedComparison = candidate.version().compareTo(preparedRuleSet.version());
    if (preparedComparison == 0) {
      if (candidate.equals(preparedRuleSet)) {
        return singleton(
            new M06SemanticEvent.RuleSetPrepared(
                candidate.identity(),
                M06SemanticEvent.PrepareStatus.ALREADY_PREPARED,
                Optional.empty()));
      }
      return singleton(
          new M06SemanticEvent.PrepareRuleSetRejected("SAME_VERSION_DIFFERENT_CONTENT"));
    }
    if (preparedComparison < 0) {
      return singleton(new M06SemanticEvent.PrepareRuleSetRejected("VERSION_NOT_INCREASING"));
    }

    M06RuleSetIdentity superseded = preparedRuleSet.identity();
    preparedRuleSet = candidate;
    return singleton(
        new M06SemanticEvent.RuleSetPrepared(
            candidate.identity(),
            M06SemanticEvent.PrepareStatus.SUPERSEDED,
            Optional.of(superseded)));
  }

  private List<M06SemanticEvent> activate(
      M06ReferenceCommand.ActivateRuleSet command, BigInteger applicationSequence) {
    if (!command.expectedApplicationSequence().equals(applicationSequence)) {
      return singleton(
          new M06SemanticEvent.ActivateRuleSetRejected("APPLICATION_SEQUENCE_MISMATCH"));
    }
    M06RuleSetIdentity previousActive = activeRuleSet.identity();
    if (!command.expectedActive().equals(previousActive)) {
      return singleton(
          new M06SemanticEvent.ActivateRuleSetRejected("EXPECTED_ACTIVE_RULE_SET_MISMATCH"));
    }
    if (preparedRuleSet == null) {
      return singleton(new M06SemanticEvent.ActivateRuleSetRejected("NO_PREPARED_RULE_SET"));
    }
    if (!command.target().equals(preparedRuleSet.identity())) {
      return singleton(new M06SemanticEvent.ActivateRuleSetRejected("TARGET_RULE_SET_MISMATCH"));
    }
    if (!preparedRuleSet.contentHash().equals(preparedRuleSet.computedContentHash())) {
      return singleton(
          new M06SemanticEvent.ActivateRuleSetRejected("PREPARED_CONTENT_HASH_MISMATCH"));
    }
    if (controlRevision.compareTo(MAXIMUM) >= 0) {
      throw new IllegalStateException("control revision exhausted before state mutation");
    }

    BigInteger nextRevision = controlRevision.add(BigInteger.ONE);
    M06SemanticMarketState.ActivationFence fence =
        new M06SemanticMarketState.ActivationFence(
            applicationSequence, nextRevision, nextAcceptanceSequence);
    M06MarketRuleSetArtifact activated = preparedRuleSet;
    activeRuleSet = activated;
    preparedRuleSet = null;
    controlRevision = nextRevision;
    lastActivationFence = fence;
    return singleton(
        new M06SemanticEvent.RuleSetActivated(previousActive, activated.identity(), fence));
  }

  private List<M06SemanticEvent> changeMarketMode(
      M06ReferenceCommand.ChangeMarketMode command, BigInteger applicationSequence) {
    if (!command.expectedApplicationSequence().equals(applicationSequence)) {
      return singleton(
          new M06SemanticEvent.ModeChangeRejected(
              command.operatorId(),
              marketMode,
              command.targetMode(),
              "APPLICATION_SEQUENCE_MISMATCH"));
    }
    if (!command.expectedMode().equals(marketMode)) {
      return singleton(
          new M06SemanticEvent.ModeChangeRejected(
              command.operatorId(), marketMode, command.targetMode(), "EXPECTED_MODE_MISMATCH"));
    }
    if (command.targetMode().equals(marketMode)) {
      return singleton(
          new M06SemanticEvent.ModeChangeRejected(
              command.operatorId(), marketMode, command.targetMode(), "NO_MODE_CHANGE"));
    }
    if (!isPermittedTransition(marketMode, command.targetMode())) {
      return singleton(
          new M06SemanticEvent.ModeChangeRejected(
              command.operatorId(), marketMode, command.targetMode(), "INVALID_TRANSITION"));
    }
    if (modeRevision.compareTo(MAXIMUM) >= 0) {
      throw new IllegalStateException("mode revision exhausted before state mutation");
    }

    String previousMode = marketMode;
    BigInteger nextModeRevision = modeRevision.add(BigInteger.ONE);
    M06SemanticMarketState.ModeTransitionFence fence =
        new M06SemanticMarketState.ModeTransitionFence(
            applicationSequence,
            nextModeRevision,
            previousMode,
            command.targetMode(),
            nextAcceptanceSequence);
    marketMode = command.targetMode();
    modeRevision = nextModeRevision;
    lastModeTransitionFence = fence;
    return singleton(
        new M06SemanticEvent.ModeChanged(
            command.operatorId(), previousMode, command.targetMode(), fence));
  }

  private List<M06SemanticEvent> massCancel(
      M06ReferenceCommand.MassCancel command, BigInteger applicationSequence) {
    if (!command.expectedApplicationSequence().equals(applicationSequence)) {
      return singleton(
          new M06SemanticEvent.MassCancelRejected(
              command.operatorId(), marketMode, "APPLICATION_SEQUENCE_MISMATCH"));
    }
    if (!command.expectedMode().equals(marketMode)) {
      return singleton(
          new M06SemanticEvent.MassCancelRejected(
              command.operatorId(), marketMode, "EXPECTED_MODE_MISMATCH"));
    }
    if (!HALTED.equals(marketMode)) {
      return singleton(
          new M06SemanticEvent.MassCancelRejected(
              command.operatorId(), marketMode, "MARKET_NOT_HALTED"));
    }

    List<ReferenceOrder> frozenOrders =
        orders.stream()
            .filter(order -> order.lifecycle == Lifecycle.RESTING)
            .sorted(Comparator.comparing(order -> order.sequence))
            .toList();
    BigInteger count = BigInteger.valueOf(frozenOrders.size());
    List<M06SemanticEvent> events = new ArrayList<>();
    events.add(
        new M06SemanticEvent.MassCancelStarted(
            command.operatorId(), marketMode, modeRevision, count));
    M06RuleSetIdentity executionRuleSet = activeRuleSet.identity();
    for (ReferenceOrder order : frozenOrders) {
      events.add(
          new M06SemanticEvent.MassOrderCanceled(
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
        new M06SemanticEvent.MassCancelCompleted(
            command.operatorId(), marketMode, modeRevision, count));

    Optional<BigInteger> firstCanceled =
        frozenOrders.isEmpty() ? Optional.empty() : Optional.of(frozenOrders.getFirst().sequence);
    Optional<BigInteger> lastCanceled =
        frozenOrders.isEmpty() ? Optional.empty() : Optional.of(frozenOrders.getLast().sequence);
    M06SemanticMarketState.MassCancelFence fence =
        new M06SemanticMarketState.MassCancelFence(
            applicationSequence,
            modeRevision,
            command.operatorId(),
            count,
            firstCanceled,
            lastCanceled);
    for (ReferenceOrder order : frozenOrders) {
      order.cancel(order.remaining, "OPERATOR_MASS_CANCEL", applicationSequence);
    }
    lastMassCancelFence = fence;
    return List.copyOf(events);
  }

  private List<M06SemanticEvent> place(
      M06ReferenceCommand.Place command, BigInteger applicationSequence) {
    M06SemanticEvent.Rejected invalid = validate(command);
    if (invalid != null) {
      return singleton(invalid);
    }

    M06RuleSetIdentity executionRuleSet = activeRuleSet.identity();
    ReferenceOrder existing = find(command.orderId());
    if (existing != null) {
      return singleton(
          new M06SemanticEvent.PlaceRejected(
              command.orderId(), "DUPLICATE_ORDER_ID", executionRuleSet));
    }
    if (command.entrypoint() == M06ReferenceCommand.PlaceEntrypoint.GOVERNED
        && !command.expectedRuleSet().equals(executionRuleSet)) {
      return singleton(
          new M06SemanticEvent.PlaceRejected(
              command.orderId(), "RULE_SET_MISMATCH", executionRuleSet));
    }
    if (command.priceTicks().compareTo(activeRuleSet.lowerInclusive()) < 0
        || command.priceTicks().compareTo(activeRuleSet.upperInclusive()) > 0) {
      return singleton(
          new M06SemanticEvent.PlaceRejected(
              command.orderId(), "PRICE_OUTSIDE_ACTIVE_BAND", executionRuleSet));
    }
    if (!OPEN.equals(marketMode)) {
      return singleton(
          new M06SemanticEvent.PlaceRejected(
              command.orderId(), "MARKET_NOT_OPEN", executionRuleSet));
    }
    if (FOK.equals(command.executionPolicy()) && !isFullyExecutable(command)) {
      return singleton(
          new M06SemanticEvent.PlaceRejected(
              command.orderId(), "FOK_NOT_FILLABLE", executionRuleSet));
    }
    if (POST_ONLY.equals(command.executionPolicy()) && hasCrossingMaker(command)) {
      return singleton(
          new M06SemanticEvent.PlaceRejected(
              command.orderId(), "POST_ONLY_WOULD_TAKE", executionRuleSet));
    }
    if (nextAcceptanceSequence.compareTo(MAXIMUM) >= 0) {
      throw new IllegalStateException("acceptance sequence exhausted before state mutation");
    }

    BigInteger acceptanceSequence = nextAcceptanceSequence;
    nextAcceptanceSequence = nextAcceptanceSequence.add(BigInteger.ONE);
    ReferenceOrder taker =
        new ReferenceOrder(
            acceptanceSequence,
            command.orderId(),
            command.side(),
            command.priceTicks(),
            command.quantityLots(),
            command.executionPolicy(),
            executionRuleSet);
    orders.add(taker);

    List<M06SemanticEvent> events = new ArrayList<>();
    events.add(
        new M06SemanticEvent.Accepted(
            acceptanceSequence,
            command.orderId(),
            command.side(),
            command.priceTicks(),
            command.quantityLots(),
            command.executionPolicy(),
            executionRuleSet,
            executionRuleSet));

    while (taker.remaining.signum() > 0) {
      ReferenceOrder maker = selectMaker(taker);
      if (maker == null) {
        break;
      }
      BigInteger traded = taker.remaining.min(maker.remaining);
      maker.fill(traded);
      taker.fill(traded);
      events.add(
          new M06SemanticEvent.Trade(
              maker.sequence,
              maker.orderId,
              taker.sequence,
              taker.orderId,
              maker.price,
              traded,
              maker.admissionRuleSet,
              taker.admissionRuleSet,
              executionRuleSet));
    }

    if (taker.remaining.signum() == 0) {
      taker.markFilled();
    } else if (IOC.equals(command.executionPolicy())) {
      BigInteger canceled = taker.remaining;
      taker.cancelAcceptedRemainder(canceled, IOC_REMAINDER, applicationSequence);
      events.add(
          new M06SemanticEvent.RemainderCanceled(
              taker.sequence,
              taker.orderId,
              taker.side,
              taker.price,
              canceled,
              IOC_REMAINDER,
              taker.admissionRuleSet,
              executionRuleSet));
    } else if (FOK.equals(command.executionPolicy())) {
      throw new IllegalStateException("fillable FOK retained an unexpected remainder");
    } else {
      taker.markResting();
      events.add(
          new M06SemanticEvent.Rested(
              taker.sequence,
              taker.orderId,
              taker.side,
              taker.price,
              taker.remaining,
              taker.admissionRuleSet,
              executionRuleSet));
    }
    return List.copyOf(events);
  }

  private List<M06SemanticEvent> cancel(
      M06ReferenceCommand.Cancel command, BigInteger applicationSequence) {
    M06SemanticEvent.Rejected invalid = validate(command);
    if (invalid != null) {
      return singleton(invalid);
    }

    M06RuleSetIdentity executionRuleSet = activeRuleSet.identity();
    if (HALTED.equals(marketMode)) {
      return singleton(
          new M06SemanticEvent.CancelRejected(
              command.orderId(), "MARKET_NOT_CANCELABLE", executionRuleSet));
    }
    ReferenceOrder order = find(command.orderId());
    if (order == null) {
      return singleton(
          new M06SemanticEvent.CancelRejected(
              command.orderId(), "ORDER_NOT_FOUND", executionRuleSet));
    }
    if (order.lifecycle == Lifecycle.FILLED) {
      return singleton(
          new M06SemanticEvent.CancelRejected(
              command.orderId(), "ORDER_ALREADY_FILLED", executionRuleSet));
    }
    if (order.lifecycle == Lifecycle.CANCELED) {
      return singleton(
          new M06SemanticEvent.CancelRejected(
              command.orderId(), "ORDER_ALREADY_CANCELED", executionRuleSet));
    }
    if (order.lifecycle != Lifecycle.RESTING) {
      throw new IllegalStateException("cancel observed a transient M06 reference lifecycle");
    }

    BigInteger canceled = order.remaining;
    M06SemanticEvent.Canceled event =
        new M06SemanticEvent.Canceled(
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

  private static String artifactFailure(M06MarketRuleSetArtifact artifact) {
    if (!artifact.contentHashHasCanonicalShape()) {
      return "MALFORMED_CONTENT_HASH";
    }
    if (!artifact.contentHash().equals(artifact.computedContentHash())) {
      return "CONTENT_HASH_MISMATCH";
    }
    return null;
  }

  private static M06SemanticEvent.Rejected validate(M06ReferenceCommand.Place command) {
    if (!INSTRUMENT.equals(command.instrumentId())) {
      return new M06SemanticEvent.Rejected("UNKNOWN_INSTRUMENT", "instrumentId");
    }
    if (!isPositiveLong(command.orderId())) {
      return new M06SemanticEvent.Rejected("INVALID_ORDER_ID", "orderId");
    }
    if (!BUY.equals(command.side()) && !SELL.equals(command.side())) {
      return new M06SemanticEvent.Rejected("INVALID_SIDE", "side");
    }
    if (!isPositiveLong(command.priceTicks())) {
      return new M06SemanticEvent.Rejected("INVALID_PRICE", "priceTicks");
    }
    if (!isPositiveLong(command.quantityLots())) {
      return new M06SemanticEvent.Rejected("INVALID_QUANTITY", "quantityLots");
    }
    if (!isExecutionPolicy(command.executionPolicy())) {
      return new M06SemanticEvent.Rejected("INVALID_EXECUTION_POLICY", "executionPolicy");
    }
    return null;
  }

  private static M06SemanticEvent.Rejected validate(M06ReferenceCommand.Cancel command) {
    if (!INSTRUMENT.equals(command.instrumentId())) {
      return new M06SemanticEvent.Rejected("UNKNOWN_INSTRUMENT", "instrumentId");
    }
    if (!isPositiveLong(command.orderId())) {
      return new M06SemanticEvent.Rejected("INVALID_ORDER_ID", "orderId");
    }
    return null;
  }

  private static boolean isPositiveLong(BigInteger value) {
    return value.signum() > 0 && value.compareTo(MAXIMUM) <= 0;
  }

  private static boolean isExecutionPolicy(String value) {
    return GTC.equals(value) || IOC.equals(value) || FOK.equals(value) || POST_ONLY.equals(value);
  }

  private static boolean isPermittedTransition(String current, String target) {
    return switch (current) {
      case OPEN -> CANCEL_ONLY.equals(target) || HALTED.equals(target);
      case CANCEL_ONLY -> OPEN.equals(target) || HALTED.equals(target);
      case HALTED -> CANCEL_ONLY.equals(target);
      default -> false;
    };
  }

  private ReferenceOrder selectMaker(ReferenceOrder taker) {
    ReferenceOrder best = null;
    for (ReferenceOrder candidate : orders) {
      if (candidate.lifecycle != Lifecycle.RESTING
          || candidate.side.equals(taker.side)
          || !crosses(taker.side, taker.price, candidate)) {
        continue;
      }
      if (best == null || isHigherPriority(candidate, best, taker.side)) {
        best = candidate;
      }
    }
    return best;
  }

  private boolean hasCrossingMaker(M06ReferenceCommand.Place taker) {
    for (ReferenceOrder candidate : orders) {
      if (candidate.lifecycle == Lifecycle.RESTING
          && !candidate.side.equals(taker.side())
          && crosses(taker.side(), taker.priceTicks(), candidate)) {
        return true;
      }
    }
    return false;
  }

  private boolean isFullyExecutable(M06ReferenceCommand.Place taker) {
    BigInteger required = taker.quantityLots();
    for (ReferenceOrder candidate : orders) {
      if (candidate.lifecycle != Lifecycle.RESTING
          || candidate.side.equals(taker.side())
          || !crosses(taker.side(), taker.priceTicks(), candidate)) {
        continue;
      }
      if (candidate.remaining.compareTo(required) >= 0) {
        return true;
      }
      required = required.subtract(candidate.remaining);
    }
    return false;
  }

  private static boolean crosses(String takerSide, BigInteger takerPrice, ReferenceOrder maker) {
    return BUY.equals(takerSide)
        ? takerPrice.compareTo(maker.price) >= 0
        : takerPrice.compareTo(maker.price) <= 0;
  }

  private static boolean isHigherPriority(
      ReferenceOrder candidate, ReferenceOrder incumbent, String takerSide) {
    int priceComparison = candidate.price.compareTo(incumbent.price);
    if (priceComparison != 0) {
      return BUY.equals(takerSide) ? priceComparison < 0 : priceComparison > 0;
    }
    return candidate.sequence.compareTo(incumbent.sequence) < 0;
  }

  private ReferenceOrder find(BigInteger orderId) {
    for (ReferenceOrder order : orders) {
      if (order.orderId.equals(orderId)) {
        return order;
      }
    }
    return null;
  }

  private M06SemanticBook deriveBook() {
    return new M06SemanticBook(deriveSide(BUY), deriveSide(SELL));
  }

  private List<M06SemanticBook.PriceLevel> deriveSide(String side) {
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

    List<M06SemanticBook.PriceLevel> levels = new ArrayList<>();
    BigInteger currentPrice = null;
    List<M06SemanticBook.RestingOrder> currentOrders = new ArrayList<>();
    for (ReferenceOrder order : active) {
      if (currentPrice != null && !currentPrice.equals(order.price)) {
        levels.add(new M06SemanticBook.PriceLevel(side, currentPrice, currentOrders));
        currentOrders = new ArrayList<>();
      }
      currentPrice = order.price;
      currentOrders.add(
          new M06SemanticBook.RestingOrder(
              order.sequence, order.orderId, order.remaining, order.admissionRuleSet));
    }
    if (currentPrice != null) {
      levels.add(new M06SemanticBook.PriceLevel(side, currentPrice, currentOrders));
    }
    return List.copyOf(levels);
  }

  private void assertConsistentState() {
    if (nextApplicationSequence.signum() <= 0 || nextApplicationSequence.compareTo(MAXIMUM) > 0) {
      throw new IllegalStateException("next reference application sequence is outside its domain");
    }
    if (nextAcceptanceSequence.signum() <= 0 || nextAcceptanceSequence.compareTo(MAXIMUM) > 0) {
      throw new IllegalStateException("next reference acceptance sequence is outside its domain");
    }
    if (controlRevision.signum() < 0 || controlRevision.compareTo(MAXIMUM) > 0) {
      throw new IllegalStateException("control revision is outside its domain");
    }
    if ((!OPEN.equals(marketMode) && !CANCEL_ONLY.equals(marketMode) && !HALTED.equals(marketMode))
        || modeRevision.signum() < 0
        || modeRevision.compareTo(MAXIMUM) > 0) {
      throw new IllegalStateException("market mode or revision is outside its domain");
    }
    if (artifactFailure(activeRuleSet) != null) {
      throw new IllegalStateException("active reference rule set is invalid");
    }
    if (preparedRuleSet != null) {
      if (artifactFailure(preparedRuleSet) != null
          || preparedRuleSet.version().compareTo(activeRuleSet.version()) <= 0) {
        throw new IllegalStateException("prepared reference rule set is invalid");
      }
    }
    if (lastActivationFence == null) {
      if (controlRevision.signum() != 0) {
        throw new IllegalStateException("control revision exists without an activation fence");
      }
    } else if (!lastActivationFence.controlRevision().equals(controlRevision)
        || lastActivationFence.applicationSequence().compareTo(nextApplicationSequence) >= 0
        || lastActivationFence.firstAcceptanceSequence().compareTo(nextAcceptanceSequence) > 0) {
      throw new IllegalStateException("last activation fence is inconsistent");
    }
    if (lastModeTransitionFence == null) {
      if (modeRevision.signum() != 0 || !OPEN.equals(marketMode)) {
        throw new IllegalStateException("market mode exists without a transition fence");
      }
    } else if (!lastModeTransitionFence.modeRevision().equals(modeRevision)
        || !lastModeTransitionFence.activeMode().equals(marketMode)
        || lastModeTransitionFence.applicationSequence().compareTo(nextApplicationSequence) >= 0
        || lastModeTransitionFence.nextAcceptanceSequence().compareTo(nextAcceptanceSequence) > 0) {
      throw new IllegalStateException("last mode transition fence is inconsistent");
    }
    if (lastMassCancelFence != null) {
      if (lastMassCancelFence.modeRevision().signum() <= 0
          || lastMassCancelFence.modeRevision().compareTo(modeRevision) > 0
          || lastMassCancelFence.applicationSequence().compareTo(nextApplicationSequence) >= 0
          || lastMassCancelFence.canceledOrderCount().signum() < 0
          || (lastMassCancelFence.canceledOrderCount().signum() == 0)
              != lastMassCancelFence.firstCanceledSequence().isEmpty()
          || lastMassCancelFence.firstCanceledSequence().isEmpty()
              != lastMassCancelFence.lastCanceledSequence().isEmpty()) {
        throw new IllegalStateException("last Mass Cancel fence is inconsistent");
      }
      lastMassCancelFence
          .lastCanceledSequence()
          .ifPresent(
              sequence -> {
                if (sequence.compareTo(nextAcceptanceSequence) >= 0) {
                  throw new IllegalStateException("Mass Cancel sequence is outside history");
                }
              });
    }

    for (int left = 0; left < orders.size(); left++) {
      ReferenceOrder order = orders.get(left);
      order.assertQuantityPartition();
      if (order.sequence.signum() <= 0
          || order.sequence.compareTo(nextAcceptanceSequence) >= 0
          || !order.admissionRuleSet.hasCanonicalShape()) {
        throw new IllegalStateException(
            "reference order identity or attribution is outside history");
      }
      if (order.cancellationApplicationSequence != null
          && order.cancellationApplicationSequence.compareTo(nextApplicationSequence) >= 0) {
        throw new IllegalStateException("reference terminal application is outside history");
      }
      for (int right = left + 1; right < orders.size(); right++) {
        ReferenceOrder other = orders.get(right);
        if (order.orderId.equals(other.orderId)) {
          throw new IllegalStateException("reference order identity is not unique");
        }
        if (order.sequence.equals(other.sequence)) {
          throw new IllegalStateException("reference acceptance sequence is not unique");
        }
      }
    }

    ReferenceOrder bestBid = bestResting(BUY);
    ReferenceOrder bestAsk = bestResting(SELL);
    if (bestBid != null && bestAsk != null && bestBid.price.compareTo(bestAsk.price) >= 0) {
      throw new IllegalStateException("M06 reference book is crossed");
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

  private static List<M06SemanticEvent> singleton(M06SemanticEvent event) {
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
        M06RuleSetIdentity admissionRuleSet) {
      this.sequence = sequence;
      this.orderId = orderId;
      this.side = side;
      this.price = price;
      this.original = original;
      this.executionPolicy = executionPolicy;
      this.admissionRuleSet = admissionRuleSet;
      remaining = original;
    }

    private void fill(BigInteger quantity) {
      if ((lifecycle != Lifecycle.ACCEPTED && lifecycle != Lifecycle.RESTING)
          || quantity.signum() <= 0
          || quantity.compareTo(remaining) > 0) {
        throw new IllegalStateException("invalid M06 reference fill");
      }
      remaining = remaining.subtract(quantity);
      filled = filled.add(quantity);
      if (remaining.signum() == 0) {
        lifecycle = Lifecycle.FILLED;
      }
    }

    private void markResting() {
      if (lifecycle != Lifecycle.ACCEPTED || remaining.signum() <= 0) {
        throw new IllegalStateException("invalid M06 reference rest transition");
      }
      lifecycle = Lifecycle.RESTING;
    }

    private void markFilled() {
      if (lifecycle != Lifecycle.FILLED || remaining.signum() != 0) {
        throw new IllegalStateException("invalid M06 reference filled transition");
      }
    }

    private void cancel(BigInteger quantity, String origin, BigInteger applicationSequence) {
      if (lifecycle != Lifecycle.RESTING || quantity.signum() <= 0 || !quantity.equals(remaining)) {
        throw new IllegalStateException("invalid M06 reference cancel transition");
      }
      cancellationOrigin = Objects.requireNonNull(origin, "origin");
      cancellationApplicationSequence =
          Objects.requireNonNull(applicationSequence, "applicationSequence");
      remaining = BigInteger.ZERO;
      canceled = canceled.add(quantity);
      lifecycle = Lifecycle.CANCELED;
    }

    private void cancelAcceptedRemainder(
        BigInteger quantity, String origin, BigInteger applicationSequence) {
      if (lifecycle != Lifecycle.ACCEPTED
          || !IOC.equals(executionPolicy)
          || quantity.signum() <= 0
          || !quantity.equals(remaining)) {
        throw new IllegalStateException("invalid M06 accepted-remainder cancellation");
      }
      cancellationOrigin = Objects.requireNonNull(origin, "origin");
      cancellationApplicationSequence =
          Objects.requireNonNull(applicationSequence, "applicationSequence");
      remaining = BigInteger.ZERO;
      canceled = canceled.add(quantity);
      lifecycle = Lifecycle.CANCELED;
    }

    private void assertQuantityPartition() {
      if (!original.equals(filled.add(remaining).add(canceled))) {
        throw new IllegalStateException("M06 reference order quantity partition is invalid");
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
      if (!lifecycleValid) {
        throw new IllegalStateException("M06 reference order lifecycle is inconsistent");
      }
      if ((lifecycle == Lifecycle.CANCELED)
          != (cancellationOrigin != null && cancellationApplicationSequence != null)) {
        throw new IllegalStateException("M06 reference order lost terminal attribution");
      }
    }
  }
}
