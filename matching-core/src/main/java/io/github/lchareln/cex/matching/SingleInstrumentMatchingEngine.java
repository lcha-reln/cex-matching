package io.github.lchareln.cex.matching;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** Single-writer, in-memory price-time matcher with M07 taker-side self-trade prevention. */
public final class SingleInstrumentMatchingEngine {
  private final PlaceLimitOrderValidator placeValidator = new PlaceLimitOrderValidator();
  private final ExecutionPolicyValidator executionPolicyValidator = new ExecutionPolicyValidator();
  private final SelfTradePreventionValidator selfTradePreventionValidator =
      new SelfTradePreventionValidator();
  private final CancelOrderValidator cancelValidator = new CancelOrderValidator();
  private final NavigableMap<Long, PriceLevelState> bids =
      new TreeMap<>(Collections.reverseOrder());
  private final NavigableMap<Long, PriceLevelState> asks = new TreeMap<>();
  private final Map<OrderId, OrderState> ordersById = new HashMap<>();

  private long acceptedOrderCount;
  private long nextAcceptanceSequence;
  private long nextApplicationSequence;
  private MarketRuleSetArtifact activeRuleSet = MarketRuleSetArtifact.bootstrap();
  private MarketRuleSetArtifact preparedRuleSet;
  private long controlRevision;
  private ActivationFence lastActivationFence;
  private MarketMode marketMode = MarketMode.OPEN;
  private long modeRevision;
  private ModeTransitionFence lastModeTransitionFence;
  private MassCancelFence lastMassCancelFence;

  public SingleInstrumentMatchingEngine() {
    this(1, 1);
  }

  SingleInstrumentMatchingEngine(long nextAcceptanceSequence) {
    this(nextAcceptanceSequence, 1);
  }

  SingleInstrumentMatchingEngine(long nextAcceptanceSequence, long nextApplicationSequence) {
    if (nextAcceptanceSequence <= 0) {
      throw new IllegalArgumentException("next acceptance sequence must be positive");
    }
    if (nextApplicationSequence <= 0) {
      throw new IllegalArgumentException("next application sequence must be positive");
    }
    this.nextAcceptanceSequence = nextAcceptanceSequence;
    this.nextApplicationSequence = nextApplicationSequence;
  }

  /** Applies one legacy GTC limit command. The caller must serialize calls to this method. */
  public ExecutionBatch place(PlaceLimitOrderInput input) {
    Objects.requireNonNull(input, "input");
    return applyPlace(
        new PlaceLimitOrderRequest(input),
        null,
        SelfTradePreventionInstruction.legacy().participantGroupId(),
        SelfTradePreventionInstruction.legacy().policy().name());
  }

  /** Applies one M04 limit request under its explicit execution policy. */
  public ExecutionBatch placeRequest(PlaceLimitOrderRequest request) {
    Objects.requireNonNull(request, "request");
    return applyPlace(
        request,
        null,
        SelfTradePreventionInstruction.legacy().participantGroupId(),
        SelfTradePreventionInstruction.legacy().policy().name());
  }

  /** Applies one M05 place request guarded by the caller's expected active rule-set identity. */
  public ExecutionBatch placeGoverned(GovernedPlaceLimitOrderRequest request) {
    Objects.requireNonNull(request, "request");
    return applyPlace(
        request.orderRequest(),
        request.expectedActive(),
        SelfTradePreventionInstruction.legacy().participantGroupId(),
        SelfTradePreventionInstruction.legacy().policy().name());
  }

  /** Applies one raw M07 STP request; the incoming taker instruction owns the disposition. */
  public ExecutionBatch placeStp(StpPlaceLimitOrderRequest request) {
    Objects.requireNonNull(request, "request");
    return applyPlace(
        request.orderRequest(), null, request.participantGroupId(), request.stpPolicy());
  }

  /** Applies one M07 STP request guarded by the exact active rule-set identity. */
  public ExecutionBatch placeGovernedStp(GovernedStpPlaceLimitOrderRequest request) {
    Objects.requireNonNull(request, "request");
    return applyPlace(
        request.request().orderRequest(),
        request.expectedActive(),
        request.request().participantGroupId(),
        request.request().stpPolicy());
  }

  private ExecutionBatch applyPlace(
      PlaceLimitOrderRequest request,
      RuleSetIdentity expectedActive,
      long participantGroupId,
      String rawStpPolicy) {
    PlaceLimitOrderInput input = request.orderInput();
    ValidationResult validation = placeValidator.validate(input);
    ValidationResult policyValidation =
        executionPolicyValidator.validate(request.executionPolicy());
    ValidationResult stpGroupValidation =
        selfTradePreventionValidator.validateGroup(participantGroupId);
    ValidationResult stpPolicyValidation =
        selfTradePreventionValidator.validatePolicy(rawStpPolicy);
    ValidationResult stpInstructionValidation =
        stpGroupValidation instanceof ValidationResult.Valid
                && stpPolicyValidation instanceof ValidationResult.Valid
            ? selfTradePreventionValidator.validateInstruction(participantGroupId, rawStpPolicy)
            : null;

    assertCommandBoundaryState();
    AppliedCommand applied = nextAppliedCommand();
    if (validation instanceof ValidationResult.Invalid invalid) {
      return businessResult(List.of(new MatchingEvent.Rejected(invalid.code())), applied);
    }
    if (policyValidation instanceof ValidationResult.Invalid invalid) {
      return businessResult(List.of(new MatchingEvent.Rejected(invalid.code())), applied);
    }
    if (stpGroupValidation instanceof ValidationResult.Invalid invalid) {
      return businessResult(List.of(new MatchingEvent.Rejected(invalid.code())), applied);
    }
    if (stpPolicyValidation instanceof ValidationResult.Invalid invalid) {
      return businessResult(List.of(new MatchingEvent.Rejected(invalid.code())), applied);
    }
    if (stpInstructionValidation instanceof ValidationResult.Invalid invalid) {
      return businessResult(List.of(new MatchingEvent.Rejected(invalid.code())), applied);
    }

    PlaceLimitOrder command = placeValidator.normalize(input);
    ExecutionPolicy policy = executionPolicyValidator.normalize(request.executionPolicy());
    SelfTradePreventionInstruction stpInstruction =
        selfTradePreventionValidator.normalize(participantGroupId, rawStpPolicy);
    if (ordersById.containsKey(command.orderId())) {
      return businessResult(
          List.of(
              new MatchingEvent.PlaceRejected(
                  command.orderId(), PlaceRejectionCode.DUPLICATE_ORDER_ID)),
          applied);
    }
    if (expectedActive != null && !expectedActive.equals(activeRuleSet.identity())) {
      return businessResult(
          List.of(
              new MatchingEvent.PlaceRejected(
                  command.orderId(), PlaceRejectionCode.RULE_SET_MISMATCH)),
          applied);
    }
    if (!activeRuleSet.admits(command.priceTicks())) {
      return businessResult(
          List.of(
              new MatchingEvent.PlaceRejected(
                  command.orderId(), PlaceRejectionCode.PRICE_OUTSIDE_ACTIVE_BAND)),
          applied);
    }
    if (marketMode != MarketMode.OPEN) {
      return businessResult(
          List.of(
              new MatchingEvent.PlaceRejected(
                  command.orderId(), PlaceRejectionCode.MARKET_NOT_OPEN)),
          applied);
    }
    if (policy == ExecutionPolicy.FOK && !canFillCompletely(command, stpInstruction)) {
      return businessResult(
          List.of(
              new MatchingEvent.PlaceRejected(
                  command.orderId(), PlaceRejectionCode.FOK_NOT_FILLABLE)),
          applied);
    }
    if (policy == ExecutionPolicy.POST_ONLY && wouldTake(command)) {
      return businessResult(
          List.of(
              new MatchingEvent.PlaceRejected(
                  command.orderId(), PlaceRejectionCode.POST_ONLY_WOULD_TAKE)),
          applied);
    }

    long sequenceValue = nextAcceptanceSequence;
    final long followingSequence;
    final long followingAcceptedOrderCount;
    try {
      followingSequence = Math.incrementExact(sequenceValue);
    } catch (ArithmeticException exception) {
      throw new IllegalStateException(
          "acceptance sequence exhausted before state mutation", exception);
    }
    try {
      followingAcceptedOrderCount = Math.incrementExact(acceptedOrderCount);
    } catch (ArithmeticException exception) {
      throw new IllegalStateException(
          "retained-order count exhausted before state mutation", exception);
    }

    AcceptanceSequence sequence = new AcceptanceSequence(sequenceValue);
    RuleSetIdentity admissionRuleSet = activeRuleSet.identity();
    OrderState taker = new OrderState(sequence, command, policy, admissionRuleSet, stpInstruction);
    MatchingEvent.Accepted accepted =
        new MatchingEvent.Accepted(
            sequence,
            command.orderId(),
            command.side(),
            command.priceTicks(),
            command.quantityLots(),
            policy,
            admissionRuleSet,
            stpInstruction.participantGroupId(),
            stpInstruction.policy());
    if (ordersById.putIfAbsent(command.orderId(), taker) != null) {
      throw new IllegalStateException(
          "duplicate order identity appeared during single-writer apply");
    }
    acceptedOrderCount = followingAcceptedOrderCount;

    List<MatchingEvent> events = new ArrayList<>();
    events.add(accepted);
    if (command.side() == Side.BUY) {
      match(taker, asks, events, true);
    } else {
      match(taker, bids, events, false);
    }

    if (taker.lifecycle == Lifecycle.CANCELED) {
      // SelfTradePrevented is the terminal event for CANCEL_TAKER and CANCEL_BOTH.
    } else if (taker.remainingQuantityLots == 0) {
      taker.markFilled();
    } else if (policy == ExecutionPolicy.IOC) {
      long canceledQuantityLots = taker.cancelAcceptedRemainder(applied.current());
      events.add(
          new MatchingEvent.RemainderCanceled(
              taker.sequence,
              taker.orderId,
              taker.side,
              taker.priceTicks,
              new QuantityLots(canceledQuantityLots),
              RemainderCancelReason.IOC_REMAINDER,
              taker.admissionRuleSet));
    } else if (policy == ExecutionPolicy.FOK) {
      throw new IllegalStateException("FOK preflight and execution disagreed");
    } else {
      rest(taker);
      events.add(
          new MatchingEvent.Rested(
              taker.sequence,
              taker.orderId,
              taker.side,
              taker.priceTicks,
              new QuantityLots(taker.remainingQuantityLots),
              taker.admissionRuleSet,
              taker.stpInstruction.participantGroupId(),
              taker.stpInstruction.policy()));
    }

    nextAcceptanceSequence = followingSequence;
    assertCommandBoundaryState();
    return businessResult(events, applied);
  }

  /** Cancels the positive active remainder addressed by instrument and order identity. */
  public ExecutionBatch cancel(CancelOrderInput input) {
    Objects.requireNonNull(input, "input");
    assertCommandBoundaryState();
    AppliedCommand applied = nextAppliedCommand();
    ValidationResult validation = cancelValidator.validate(input);
    if (validation instanceof ValidationResult.Invalid invalid) {
      return businessResult(List.of(new MatchingEvent.Rejected(invalid.code())), applied);
    }

    CancelOrder command = cancelValidator.normalize(input);
    if (marketMode == MarketMode.HALTED) {
      return businessResult(
          List.of(
              new MatchingEvent.CancelRejected(
                  command.orderId(), CancelRejectionCode.MARKET_NOT_CANCELABLE)),
          applied);
    }
    OrderState order = ordersById.get(command.orderId());
    if (order == null) {
      return businessResult(
          List.of(
              new MatchingEvent.CancelRejected(
                  command.orderId(), CancelRejectionCode.ORDER_NOT_FOUND)),
          applied);
    }
    if (order.lifecycle == Lifecycle.FILLED) {
      return businessResult(
          List.of(
              new MatchingEvent.CancelRejected(
                  command.orderId(), CancelRejectionCode.ORDER_ALREADY_FILLED)),
          applied);
    }
    if (order.lifecycle == Lifecycle.CANCELED) {
      return businessResult(
          List.of(
              new MatchingEvent.CancelRejected(
                  command.orderId(), CancelRejectionCode.ORDER_ALREADY_CANCELED)),
          applied);
    }
    if (order.lifecycle != Lifecycle.RESTING) {
      throw new IllegalStateException("cancel observed an order in a transient lifecycle state");
    }

    NavigableMap<Long, PriceLevelState> side = order.side == Side.BUY ? bids : asks;
    PriceLevelState level = side.get(order.priceTicks.value());
    if (level == null || level.order(order.orderId) != order) {
      throw new IllegalStateException("active order index and price level disagree");
    }

    MatchingEvent.Canceled canceled =
        new MatchingEvent.Canceled(
            order.sequence,
            order.orderId,
            order.side,
            order.priceTicks,
            new QuantityLots(order.remainingQuantityLots),
            order.admissionRuleSet,
            activeRuleSet.identity());
    if (!level.remove(order)) {
      throw new IllegalStateException("active order disappeared during single-writer cancel");
    }
    if (level.isEmpty() && !side.remove(order.priceTicks.value(), level)) {
      throw new IllegalStateException("empty price level disappeared during single-writer cancel");
    }
    order.markCanceled(CancellationOrigin.USER_REQUEST, applied.current());

    assertCommandBoundaryState();
    return businessResult(List.of(canceled), applied);
  }

  /** Prepares one valid newer artifact without changing current order admission. */
  public MarketControlBatch prepareRuleSet(PrepareRuleSet command) {
    Objects.requireNonNull(command, "command");
    assertCommandBoundaryState();
    AppliedCommand applied = nextAppliedCommand();
    MarketRuleSetArtifact candidate = command.artifact();

    if (!command.expectedActive().equals(activeRuleSet.identity())) {
      return controlRejection(
          candidate, PrepareRuleSetRejectionCode.EXPECTED_ACTIVE_RULE_SET_MISMATCH, applied);
    }
    if (!candidate.hasCanonicalContentHash()) {
      return controlRejection(
          candidate, PrepareRuleSetRejectionCode.MALFORMED_CONTENT_HASH, applied);
    }
    if (!candidate.contentHashMatches()) {
      return controlRejection(
          candidate, PrepareRuleSetRejectionCode.CONTENT_HASH_MISMATCH, applied);
    }

    int comparedWithActive = candidate.version().compareTo(activeRuleSet.version());
    if (comparedWithActive == 0 && !candidate.equals(activeRuleSet)) {
      return controlRejection(
          candidate, PrepareRuleSetRejectionCode.SAME_VERSION_DIFFERENT_CONTENT, applied);
    }
    if (comparedWithActive <= 0) {
      return controlRejection(
          candidate, PrepareRuleSetRejectionCode.VERSION_NOT_INCREASING, applied);
    }

    PrepareRuleSetStatus status = PrepareRuleSetStatus.PREPARED;
    if (preparedRuleSet != null) {
      int comparedWithPrepared = candidate.version().compareTo(preparedRuleSet.version());
      if (comparedWithPrepared == 0) {
        if (candidate.equals(preparedRuleSet)) {
          status = PrepareRuleSetStatus.ALREADY_PREPARED;
        } else {
          return controlRejection(
              candidate, PrepareRuleSetRejectionCode.SAME_VERSION_DIFFERENT_CONTENT, applied);
        }
      } else if (comparedWithPrepared < 0) {
        return controlRejection(
            candidate, PrepareRuleSetRejectionCode.VERSION_NOT_INCREASING, applied);
      } else {
        status = PrepareRuleSetStatus.SUPERSEDED;
      }
    }

    if (status != PrepareRuleSetStatus.ALREADY_PREPARED) {
      preparedRuleSet = candidate;
    }
    MarketControlEvent.RuleSetPrepared event =
        new MarketControlEvent.RuleSetPrepared(
            applied.current(), activeRuleSet.identity(), candidate.identity(), status);
    assertCommandBoundaryState();
    return controlResult(event, applied);
  }

  /** Activates exactly the prepared identity at the declared serialized application boundary. */
  public MarketControlBatch activateRuleSet(ActivateRuleSet command) {
    Objects.requireNonNull(command, "command");
    assertCommandBoundaryState();
    AppliedCommand applied = nextAppliedCommand();

    if (!command.expectedApplicationSequence().equals(applied.current())) {
      return activateRejection(
          command.target(), ActivateRuleSetRejectionCode.APPLICATION_SEQUENCE_MISMATCH, applied);
    }
    if (!command.expectedActive().equals(activeRuleSet.identity())) {
      return activateRejection(
          command.target(),
          ActivateRuleSetRejectionCode.EXPECTED_ACTIVE_RULE_SET_MISMATCH,
          applied);
    }
    if (preparedRuleSet == null) {
      return activateRejection(
          command.target(), ActivateRuleSetRejectionCode.NO_PREPARED_RULE_SET, applied);
    }
    if (!command.target().equals(preparedRuleSet.identity())) {
      return activateRejection(
          command.target(), ActivateRuleSetRejectionCode.TARGET_RULE_SET_MISMATCH, applied);
    }
    if (!preparedRuleSet.contentHashMatches()) {
      return activateRejection(
          command.target(), ActivateRuleSetRejectionCode.PREPARED_CONTENT_HASH_MISMATCH, applied);
    }

    final long nextControlRevision;
    try {
      nextControlRevision = Math.incrementExact(controlRevision);
    } catch (ArithmeticException failure) {
      throw new IllegalStateException("control revision exhausted before state mutation", failure);
    }
    RuleSetIdentity previousActive = activeRuleSet.identity();
    MarketRuleSetArtifact activated = preparedRuleSet;
    ActivationFence fence =
        new ActivationFence(
            applied.current(), nextControlRevision, new AcceptanceSequence(nextAcceptanceSequence));
    activeRuleSet = activated;
    preparedRuleSet = null;
    controlRevision = nextControlRevision;
    lastActivationFence = fence;

    MarketControlEvent.RuleSetActivated event =
        new MarketControlEvent.RuleSetActivated(
            applied.current(), previousActive, activated.identity(), fence);
    assertCommandBoundaryState();
    return controlResult(event, applied);
  }

  /** Changes the replicated-ready operating mode at the declared application boundary. */
  public MarketControlBatch changeMarketMode(ChangeMarketMode command) {
    Objects.requireNonNull(command, "command");
    assertCommandBoundaryState();
    AppliedCommand applied = nextAppliedCommand();

    if (!command.expectedApplicationSequence().equals(applied.current())) {
      return modeChangeRejection(
          command, ChangeMarketModeRejectionCode.APPLICATION_SEQUENCE_MISMATCH, applied);
    }
    if (command.expectedMode() != marketMode) {
      return modeChangeRejection(
          command, ChangeMarketModeRejectionCode.EXPECTED_MODE_MISMATCH, applied);
    }
    if (command.targetMode() == marketMode) {
      return modeChangeRejection(command, ChangeMarketModeRejectionCode.NO_MODE_CHANGE, applied);
    }
    if (!marketMode.canTransitionTo(command.targetMode())) {
      return modeChangeRejection(
          command, ChangeMarketModeRejectionCode.INVALID_TRANSITION, applied);
    }

    final long nextModeRevision;
    try {
      nextModeRevision = Math.incrementExact(modeRevision);
    } catch (ArithmeticException failure) {
      throw new IllegalStateException("mode revision exhausted before state mutation", failure);
    }
    MarketMode previousMode = marketMode;
    ModeTransitionFence fence =
        new ModeTransitionFence(
            applied.current(),
            nextModeRevision,
            previousMode,
            command.targetMode(),
            new AcceptanceSequence(nextAcceptanceSequence));
    marketMode = command.targetMode();
    modeRevision = nextModeRevision;
    lastModeTransitionFence = fence;

    MarketControlEvent.ModeChanged event =
        new MarketControlEvent.ModeChanged(
            applied.current(), command.operatorId(), previousMode, command.targetMode(), fence);
    assertCommandBoundaryState();
    return controlResult(event, applied);
  }

  /** Atomically terminates every resting order in global acceptance-sequence order. */
  public MassCancelBatch massCancel(MassCancel command) {
    Objects.requireNonNull(command, "command");
    assertCommandBoundaryState();
    AppliedCommand applied = nextAppliedCommand();

    if (!command.expectedApplicationSequence().equals(applied.current())) {
      return massCancelRejection(
          command, MassCancelRejectionCode.APPLICATION_SEQUENCE_MISMATCH, applied);
    }
    if (command.expectedMode() != marketMode) {
      return massCancelRejection(command, MassCancelRejectionCode.EXPECTED_MODE_MISMATCH, applied);
    }
    if (marketMode != MarketMode.HALTED) {
      return massCancelRejection(command, MassCancelRejectionCode.MARKET_NOT_HALTED, applied);
    }

    List<OrderState> frozenOrders =
        ordersById.values().stream()
            .filter(order -> order.lifecycle == Lifecycle.RESTING)
            .sorted(Comparator.comparingLong(order -> order.sequence.value()))
            .toList();
    long canceledOrderCount = frozenOrders.size();
    final int eventCapacity;
    try {
      eventCapacity = Math.addExact(frozenOrders.size(), 2);
    } catch (ArithmeticException failure) {
      throw new IllegalStateException(
          "Mass Cancel event capacity exhausted before mutation", failure);
    }
    List<MassCancelEvent> events = new ArrayList<>(eventCapacity);
    events.add(
        new MassCancelEvent.Started(
            applied.current(), command.operatorId(), marketMode, modeRevision, canceledOrderCount));
    for (OrderState order : frozenOrders) {
      events.add(
          new MassCancelEvent.OrderCanceled(
              applied.current(),
              command.operatorId(),
              order.sequence,
              order.orderId,
              order.side,
              order.priceTicks,
              new QuantityLots(order.remainingQuantityLots),
              order.admissionRuleSet,
              activeRuleSet.identity()));
    }
    events.add(
        new MassCancelEvent.Completed(
            applied.current(), command.operatorId(), marketMode, modeRevision, canceledOrderCount));

    Optional<AcceptanceSequence> firstCanceled =
        frozenOrders.isEmpty() ? Optional.empty() : Optional.of(frozenOrders.getFirst().sequence);
    Optional<AcceptanceSequence> lastCanceled =
        frozenOrders.isEmpty() ? Optional.empty() : Optional.of(frozenOrders.getLast().sequence);
    MassCancelFence fence =
        new MassCancelFence(
            applied.current(),
            modeRevision,
            command.operatorId(),
            canceledOrderCount,
            firstCanceled,
            lastCanceled);

    for (OrderState order : frozenOrders) {
      removeRestingOrder(order);
      order.markCanceled(CancellationOrigin.OPERATOR_MASS_CANCEL, applied.current());
    }
    lastMassCancelFence = fence;
    assertCommandBoundaryState();
    return massCancelResult(events, applied);
  }

  /** Returns a detached immutable full-depth snapshot. */
  public OrderBookSnapshot snapshot() {
    assertCommandBoundaryState();
    return detachedSnapshot();
  }

  /** Returns detached active, prepared, revision, fence, and next-sequence state. */
  public MarketControlSnapshot marketControlSnapshot() {
    assertCommandBoundaryState();
    return detachedMarketControlSnapshot(nextApplicationSequence);
  }

  /** Returns the complete infrastructure-free state required by a durable checkpoint. */
  public MatchingStateImage stateImage() {
    assertConsistentState();
    List<MatchingStateImage.OrderImage> orders =
        ordersById.values().stream()
            .sorted(Comparator.comparingLong(order -> order.sequence.value()))
            .map(SingleInstrumentMatchingEngine::orderImage)
            .toList();
    return new MatchingStateImage(detachedMarketControlSnapshot(nextApplicationSequence), orders);
  }

  /** Restores a fresh matcher only after the supplied image passes all core invariants. */
  public static SingleInstrumentMatchingEngine restore(MatchingStateImage image) {
    Objects.requireNonNull(image, "image");
    SingleInstrumentMatchingEngine restored = new SingleInstrumentMatchingEngine();
    restored.restoreState(image);
    restored.assertConsistentState();
    return restored;
  }

  /**
   * Package-local cold-path correctness hook; it exposes no order lifecycle data.
   *
   * <p>This audit is intentionally proportional to all retained order identities. Command
   * processing uses {@link #assertCommandBoundaryState()} and mutation-local checks instead;
   * checkpoints, restore, and explicit correctness tests retain this complete audit.
   */
  void assertConsistentState() {
    assertCommandBoundaryState();

    Set<OrderId> restingIds = new HashSet<>();
    Set<Long> acceptanceSequences = new HashSet<>();
    verifySide(bids, Side.BUY, restingIds);
    verifySide(asks, Side.SELL, restingIds);

    for (Map.Entry<OrderId, OrderState> entry : ordersById.entrySet()) {
      OrderState order = entry.getValue();
      if (!entry.getKey().equals(order.orderId)) {
        throw new IllegalStateException("order registry key and value identity disagree");
      }
      if (!acceptanceSequences.add(order.sequence.value())) {
        throw new IllegalStateException("acceptance sequence is not unique");
      }
      if (order.sequence.value() >= nextAcceptanceSequence) {
        throw new IllegalStateException("accepted order is not behind the next sequence");
      }
      if (order.admissionRuleSet == null) {
        throw new IllegalStateException("accepted order lost its admission rule set");
      }
      order.assertQuantityPartition();
      boolean inBook = restingIds.contains(order.orderId);
      if ((order.lifecycle == Lifecycle.RESTING) != inBook) {
        throw new IllegalStateException("order lifecycle and book membership disagree");
      }
      if (order.lifecycle == Lifecycle.ACCEPTED) {
        throw new IllegalStateException("transient accepted state escaped a command boundary");
      }
    }
  }

  /** Constant-time command-boundary checks; touched orders are guarded by mutation-local checks. */
  private void assertCommandBoundaryState() {
    if (nextAcceptanceSequence <= 0) {
      throw new IllegalStateException("next acceptance sequence is not positive");
    }
    if (nextApplicationSequence <= 0) {
      throw new IllegalStateException("next application sequence is not positive");
    }
    if (!activeRuleSet.contentHashMatches()) {
      throw new IllegalStateException("active rule-set content hash changed");
    }
    if (preparedRuleSet != null
        && (!preparedRuleSet.contentHashMatches()
            || preparedRuleSet.version().compareTo(activeRuleSet.version()) <= 0)) {
      throw new IllegalStateException("prepared rule set is not valid and newer than active");
    }
    if (controlRevision < 0
        || (controlRevision == 0) != (lastActivationFence == null)
        || (lastActivationFence != null
            && lastActivationFence.controlRevision() != controlRevision)) {
      throw new IllegalStateException("control revision and last activation fence disagree");
    }
    if (marketMode == null
        || modeRevision < 0
        || (modeRevision == 0) != (lastModeTransitionFence == null)
        || (lastModeTransitionFence != null
            && (lastModeTransitionFence.modeRevision() != modeRevision
                || lastModeTransitionFence.activeMode() != marketMode
                || lastModeTransitionFence.appliedCommandSequence().value()
                    > nextApplicationSequence
                || lastModeTransitionFence.nextAcceptanceSequence().value()
                    > nextAcceptanceSequence))) {
      throw new IllegalStateException("mode revision and last transition fence disagree");
    }
    if (lastMassCancelFence != null
        && (lastMassCancelFence.modeRevision() > modeRevision
            || lastMassCancelFence.appliedCommandSequence().value() > nextApplicationSequence
            || lastMassCancelFence
                .lastCanceledSequence()
                .map(sequence -> sequence.value() >= nextAcceptanceSequence)
                .orElse(false))) {
      throw new IllegalStateException("last Mass Cancel fence is ahead of matcher state");
    }
    if (acceptedOrderCount < 0 || acceptedOrderCount != ordersById.size()) {
      throw new IllegalStateException("accepted order count and registry size disagree");
    }
    if (!bids.isEmpty() && !asks.isEmpty() && bids.firstKey() >= asks.firstKey()) {
      throw new IllegalStateException("active book is crossed");
    }
  }

  private AppliedCommand nextAppliedCommand() {
    long current = nextApplicationSequence;
    final long following;
    try {
      following = Math.incrementExact(current);
    } catch (ArithmeticException failure) {
      throw new IllegalStateException(
          "application sequence exhausted before state mutation", failure);
    }
    return new AppliedCommand(new ApplicationSequence(current), following);
  }

  private ExecutionBatch businessResult(List<MatchingEvent> events, AppliedCommand applied) {
    assertCommandBoundaryState();
    ExecutionBatch result =
        new ExecutionBatch(
            events,
            detachedSnapshot(),
            new MarketExecutionContext(
                activeRuleSet.identity(), controlRevision, applied.current(), marketMode));
    commitApplication(applied);
    assertCommandBoundaryState();
    return result;
  }

  private MarketControlBatch controlRejection(
      MarketRuleSetArtifact candidate, PrepareRuleSetRejectionCode code, AppliedCommand applied) {
    return controlResult(
        new MarketControlEvent.PrepareRejected(
            applied.current(), candidate.version(), candidate.contentHash(), code),
        applied);
  }

  private MarketControlBatch activateRejection(
      RuleSetIdentity target, ActivateRuleSetRejectionCode code, AppliedCommand applied) {
    return controlResult(
        new MarketControlEvent.ActivateRejected(
            applied.current(), activeRuleSet.identity(), target, code),
        applied);
  }

  private MarketControlBatch modeChangeRejection(
      ChangeMarketMode command, ChangeMarketModeRejectionCode code, AppliedCommand applied) {
    return controlResult(
        new MarketControlEvent.ModeChangeRejected(
            applied.current(), command.operatorId(), marketMode, command.targetMode(), code),
        applied);
  }

  private MassCancelBatch massCancelRejection(
      MassCancel command, MassCancelRejectionCode code, AppliedCommand applied) {
    return massCancelResult(
        List.of(
            new MassCancelEvent.Rejected(
                applied.current(), command.operatorId(), marketMode, code)),
        applied);
  }

  private MarketControlBatch controlResult(MarketControlEvent event, AppliedCommand applied) {
    assertCommandBoundaryState();
    MarketControlBatch result =
        new MarketControlBatch(
            List.of(event), detachedMarketControlSnapshot(applied.following()), detachedSnapshot());
    commitApplication(applied);
    assertCommandBoundaryState();
    return result;
  }

  private MassCancelBatch massCancelResult(List<MassCancelEvent> events, AppliedCommand applied) {
    assertCommandBoundaryState();
    MassCancelBatch result =
        new MassCancelBatch(
            events, detachedMarketControlSnapshot(applied.following()), detachedSnapshot());
    commitApplication(applied);
    assertCommandBoundaryState();
    return result;
  }

  private void commitApplication(AppliedCommand applied) {
    if (nextApplicationSequence != applied.current().value()) {
      throw new IllegalStateException("application sequence changed during one serialized command");
    }
    nextApplicationSequence = applied.following();
  }

  private void rest(OrderState order) {
    if (order.lifecycle != Lifecycle.ACCEPTED || order.remainingQuantityLots <= 0) {
      throw new IllegalStateException("only a positive accepted remainder can rest");
    }
    NavigableMap<Long, PriceLevelState> side = order.side == Side.BUY ? bids : asks;
    PriceLevelState level =
        side.computeIfAbsent(order.priceTicks.value(), ignored -> new PriceLevelState());
    level.add(order);
    order.markResting();
  }

  private void removeRestingOrder(OrderState order) {
    if (order.lifecycle != Lifecycle.RESTING || order.remainingQuantityLots <= 0) {
      throw new IllegalStateException("only a positive resting order can leave the book");
    }
    NavigableMap<Long, PriceLevelState> side = order.side == Side.BUY ? bids : asks;
    PriceLevelState level = side.get(order.priceTicks.value());
    if (level == null || level.order(order.orderId) != order || !level.remove(order)) {
      throw new IllegalStateException("active order disappeared during single-writer removal");
    }
    if (level.isEmpty() && !side.remove(order.priceTicks.value(), level)) {
      throw new IllegalStateException("empty price level disappeared during single-writer removal");
    }
  }

  private void match(
      OrderState taker,
      NavigableMap<Long, PriceLevelState> oppositeSide,
      List<MatchingEvent> events,
      boolean buying) {
    while (taker.remainingQuantityLots > 0 && !oppositeSide.isEmpty()) {
      long makerPrice = oppositeSide.firstKey();
      boolean crosses =
          buying ? taker.priceTicks.value() >= makerPrice : taker.priceTicks.value() <= makerPrice;
      if (!crosses) {
        break;
      }

      PriceLevelState level = oppositeSide.firstEntry().getValue();
      OrderState maker = level.first();
      if (maker.lifecycle != Lifecycle.RESTING
          || maker.priceTicks.value() != makerPrice
          || ordersById.get(maker.orderId) != maker) {
        throw new IllegalStateException("maker index and price level disagree before fill");
      }
      long traded = Math.min(taker.remainingQuantityLots, maker.remainingQuantityLots);
      if (taker.stpInstruction.conflictsWith(maker.stpInstruction)) {
        long makerCanceledQuantityLots = 0;
        long takerCanceledQuantityLots = 0;
        SelfTradePreventionPolicy policy = taker.stpInstruction.policy();
        if (policy == SelfTradePreventionPolicy.CANCEL_MAKER
            || policy == SelfTradePreventionPolicy.CANCEL_BOTH) {
          makerCanceledQuantityLots = maker.remainingQuantityLots;
          if (!level.remove(maker)) {
            throw new IllegalStateException("STP maker disappeared during single-writer apply");
          }
          if (level.isEmpty() && !oppositeSide.remove(makerPrice, level)) {
            throw new IllegalStateException(
                "empty STP maker level disappeared during single-writer apply");
          }
          maker.markCanceled(
              CancellationOrigin.SELF_TRADE_PREVENTION, currentApplicationSequence());
        }
        if (policy == SelfTradePreventionPolicy.CANCEL_TAKER
            || policy == SelfTradePreventionPolicy.CANCEL_BOTH) {
          takerCanceledQuantityLots =
              taker.cancelAcceptedRemainder(
                  CancellationOrigin.SELF_TRADE_PREVENTION, currentApplicationSequence());
        }
        events.add(
            new MatchingEvent.SelfTradePrevented(
                maker.sequence,
                maker.orderId,
                taker.sequence,
                taker.orderId,
                maker.priceTicks,
                new QuantityLots(traded),
                taker.stpInstruction.participantGroupId(),
                policy,
                makerCanceledQuantityLots,
                takerCanceledQuantityLots,
                maker.admissionRuleSet,
                taker.admissionRuleSet,
                activeRuleSet.identity()));
        if (policy != SelfTradePreventionPolicy.CANCEL_MAKER) {
          return;
        }
        continue;
      }
      maker.fill(traded);
      taker.fill(traded);
      events.add(
          new MatchingEvent.Trade(
              maker.sequence,
              maker.orderId,
              taker.sequence,
              taker.orderId,
              maker.priceTicks,
              new QuantityLots(traded),
              maker.admissionRuleSet,
              taker.admissionRuleSet,
              activeRuleSet.identity()));

      if (maker.remainingQuantityLots == 0) {
        if (!level.remove(maker)) {
          throw new IllegalStateException("filled maker disappeared during single-writer apply");
        }
        maker.markFilled();
        if (level.isEmpty() && !oppositeSide.remove(makerPrice, level)) {
          throw new IllegalStateException(
              "empty maker level disappeared during single-writer apply");
        }
      }
    }
  }

  private boolean wouldTake(PlaceLimitOrder command) {
    NavigableMap<Long, PriceLevelState> opposite = command.side() == Side.BUY ? asks : bids;
    if (opposite.isEmpty()) {
      return false;
    }
    long bestOppositePrice = opposite.firstKey();
    return crosses(command.side(), command.priceTicks().value(), bestOppositePrice);
  }

  private boolean canFillCompletely(
      PlaceLimitOrder command, SelfTradePreventionInstruction takerInstruction) {
    NavigableMap<Long, PriceLevelState> opposite = command.side() == Side.BUY ? asks : bids;
    long required = command.quantityLots().value();
    for (Map.Entry<Long, PriceLevelState> levelEntry : opposite.entrySet()) {
      if (!crosses(command.side(), command.priceTicks().value(), levelEntry.getKey())) {
        break;
      }
      for (OrderState maker : levelEntry.getValue().values()) {
        if (takerInstruction.conflictsWith(maker.stpInstruction)) {
          if (takerInstruction.policy() == SelfTradePreventionPolicy.CANCEL_MAKER) {
            continue;
          }
          return false;
        }
        if (maker.remainingQuantityLots >= required) {
          return true;
        }
        required -= maker.remainingQuantityLots;
      }
    }
    return false;
  }

  private ApplicationSequence currentApplicationSequence() {
    return new ApplicationSequence(nextApplicationSequence);
  }

  private static boolean crosses(Side takerSide, long takerLimitPrice, long makerPrice) {
    return takerSide == Side.BUY ? takerLimitPrice >= makerPrice : takerLimitPrice <= makerPrice;
  }

  private void verifySide(
      NavigableMap<Long, PriceLevelState> side, Side expectedSide, Set<OrderId> restingIds) {
    for (Map.Entry<Long, PriceLevelState> levelEntry : side.entrySet()) {
      if (levelEntry.getValue().isEmpty()) {
        throw new IllegalStateException("active book contains an empty price level");
      }
      long previousSequence = 0;
      for (Map.Entry<OrderId, OrderState> orderEntry : levelEntry.getValue().entries()) {
        OrderState order = orderEntry.getValue();
        if (!orderEntry.getKey().equals(order.orderId)
            || ordersById.get(order.orderId) != order
            || order.side != expectedSide
            || order.priceTicks.value() != levelEntry.getKey()
            || order.lifecycle != Lifecycle.RESTING
            || order.admissionRuleSet == null
            || order.remainingQuantityLots <= 0) {
          throw new IllegalStateException("active order index and price level disagree");
        }
        if (order.sequence.value() <= previousSequence) {
          throw new IllegalStateException("price level is not FIFO by acceptance sequence");
        }
        if (!restingIds.add(order.orderId)) {
          throw new IllegalStateException("active order appears more than once in the book");
        }
        previousSequence = order.sequence.value();
      }
    }
  }

  private OrderBookSnapshot detachedSnapshot() {
    return new OrderBookSnapshot(snapshotSide(bids, Side.BUY), snapshotSide(asks, Side.SELL));
  }

  private static MatchingStateImage.OrderImage orderImage(OrderState order) {
    Optional<MatchingStateImage.Cancellation> cancellation =
        order.cancellationOrigin == null
            ? Optional.empty()
            : Optional.of(
                new MatchingStateImage.Cancellation(
                    MatchingStateImage.CancellationOrigin.valueOf(order.cancellationOrigin.name()),
                    order.cancellationApplicationSequence));
    return new MatchingStateImage.OrderImage(
        order.sequence,
        order.orderId,
        order.side,
        order.priceTicks,
        order.executionPolicy,
        order.admissionRuleSet,
        order.stpInstruction.participantGroupId(),
        order.stpInstruction.policy(),
        order.originalQuantityLots,
        order.remainingQuantityLots,
        order.filledQuantityLots,
        order.canceledQuantityLots,
        MatchingStateImage.Lifecycle.valueOf(order.lifecycle.name()),
        cancellation);
  }

  private void restoreState(MatchingStateImage image) {
    MarketControlSnapshot control = image.control();
    nextApplicationSequence = control.nextApplicationSequence().value();
    nextAcceptanceSequence = control.nextAcceptanceSequence().value();
    activeRuleSet = control.activeRuleSet();
    preparedRuleSet = control.preparedRuleSet().orElse(null);
    controlRevision = control.controlRevision();
    lastActivationFence = control.lastActivationFence().orElse(null);
    marketMode = control.marketMode();
    modeRevision = control.modeRevision();
    lastModeTransitionFence = control.lastModeTransitionFence().orElse(null);
    lastMassCancelFence = control.lastMassCancelFence().orElse(null);

    for (MatchingStateImage.OrderImage orderImage : image.orders()) {
      PlaceLimitOrder command =
          new PlaceLimitOrder(
              PlaceLimitOrderValidator.INSTRUMENT_ID,
              orderImage.orderId(),
              orderImage.side(),
              orderImage.priceTicks(),
              new QuantityLots(orderImage.originalQuantityLots()));
      OrderState order =
          new OrderState(
              orderImage.sequence(),
              command,
              orderImage.executionPolicy(),
              orderImage.admissionRuleSet(),
              new SelfTradePreventionInstruction(
                  orderImage.participantGroupId(), orderImage.selfTradePreventionPolicy()));
      order.remainingQuantityLots = orderImage.remainingQuantityLots();
      order.filledQuantityLots = orderImage.filledQuantityLots();
      order.canceledQuantityLots = orderImage.canceledQuantityLots();
      order.lifecycle = Lifecycle.valueOf(orderImage.lifecycle().name());
      orderImage
          .cancellation()
          .ifPresent(
              cancellation -> {
                order.cancellationOrigin = CancellationOrigin.valueOf(cancellation.origin().name());
                order.cancellationApplicationSequence = cancellation.applicationSequence();
              });
      if (ordersById.putIfAbsent(order.orderId, order) != null) {
        throw new IllegalArgumentException("state image contains duplicate order identity");
      }
      if (order.lifecycle == Lifecycle.RESTING) {
        NavigableMap<Long, PriceLevelState> side = order.side == Side.BUY ? bids : asks;
        side.computeIfAbsent(order.priceTicks.value(), ignored -> new PriceLevelState()).add(order);
      }
    }
    acceptedOrderCount = ordersById.size();
  }

  private MarketControlSnapshot detachedMarketControlSnapshot(long nextApplication) {
    return new MarketControlSnapshot(
        activeRuleSet,
        Optional.ofNullable(preparedRuleSet),
        controlRevision,
        Optional.ofNullable(lastActivationFence),
        new ApplicationSequence(nextApplication),
        new AcceptanceSequence(nextAcceptanceSequence),
        marketMode,
        modeRevision,
        Optional.ofNullable(lastModeTransitionFence),
        Optional.ofNullable(lastMassCancelFence));
  }

  private static List<OrderBookSnapshot.PriceLevel> snapshotSide(
      NavigableMap<Long, PriceLevelState> side, Side sideName) {
    List<OrderBookSnapshot.PriceLevel> levels = new ArrayList<>(side.size());
    side.forEach(
        (price, level) -> {
          List<OrderBookSnapshot.RestingOrderView> views = new ArrayList<>(level.size());
          for (OrderState order : level.values()) {
            views.add(
                new OrderBookSnapshot.RestingOrderView(
                    order.sequence,
                    order.orderId,
                    new QuantityLots(order.remainingQuantityLots),
                    order.admissionRuleSet,
                    order.stpInstruction.participantGroupId(),
                    order.stpInstruction.policy()));
          }
          levels.add(
              new OrderBookSnapshot.PriceLevel(
                  sideName, new PriceTicks(price), List.copyOf(views)));
        });
    return List.copyOf(levels);
  }

  private enum Lifecycle {
    ACCEPTED,
    RESTING,
    FILLED,
    CANCELED
  }

  private enum CancellationOrigin {
    USER_REQUEST,
    OPERATOR_MASS_CANCEL,
    IOC_REMAINDER,
    SELF_TRADE_PREVENTION
  }

  private record AppliedCommand(ApplicationSequence current, long following) {
    private AppliedCommand {
      Objects.requireNonNull(current, "current");
      if (following <= current.value()) {
        throw new IllegalArgumentException("following application sequence must increase");
      }
    }
  }

  private static final class PriceLevelState {
    private final LinkedHashMap<OrderId, OrderState> orders = new LinkedHashMap<>();

    private void add(OrderState order) {
      if (orders.putIfAbsent(order.orderId, order) != null) {
        throw new IllegalStateException("price level already contains order identity");
      }
    }

    private OrderState first() {
      if (orders.isEmpty()) {
        throw new IllegalStateException("cannot read an empty price level");
      }
      return orders.values().iterator().next();
    }

    private OrderState order(OrderId orderId) {
      return orders.get(orderId);
    }

    private boolean remove(OrderState order) {
      return orders.remove(order.orderId, order);
    }

    private boolean isEmpty() {
      return orders.isEmpty();
    }

    private int size() {
      return orders.size();
    }

    private Iterable<OrderState> values() {
      return orders.values();
    }

    private Set<Map.Entry<OrderId, OrderState>> entries() {
      return orders.entrySet();
    }
  }

  private static final class OrderState {
    private final AcceptanceSequence sequence;
    private final OrderId orderId;
    private final Side side;
    private final PriceTicks priceTicks;
    private final ExecutionPolicy executionPolicy;
    private final RuleSetIdentity admissionRuleSet;
    private final SelfTradePreventionInstruction stpInstruction;
    private final long originalQuantityLots;

    private long remainingQuantityLots;
    private long filledQuantityLots;
    private long canceledQuantityLots;
    private Lifecycle lifecycle = Lifecycle.ACCEPTED;
    private CancellationOrigin cancellationOrigin;
    private ApplicationSequence cancellationApplicationSequence;

    private OrderState(
        AcceptanceSequence sequence,
        PlaceLimitOrder command,
        ExecutionPolicy executionPolicy,
        RuleSetIdentity admissionRuleSet,
        SelfTradePreventionInstruction stpInstruction) {
      this.sequence = sequence;
      this.orderId = command.orderId();
      this.side = command.side();
      this.priceTicks = command.priceTicks();
      this.executionPolicy = executionPolicy;
      this.admissionRuleSet = Objects.requireNonNull(admissionRuleSet, "admissionRuleSet");
      this.stpInstruction = Objects.requireNonNull(stpInstruction, "stpInstruction");
      this.originalQuantityLots = command.quantityLots().value();
      this.remainingQuantityLots = originalQuantityLots;
    }

    private void fill(long quantityLots) {
      if ((lifecycle != Lifecycle.ACCEPTED && lifecycle != Lifecycle.RESTING)
          || quantityLots <= 0
          || quantityLots > remainingQuantityLots) {
        throw new IllegalStateException("invalid fill transition");
      }
      remainingQuantityLots -= quantityLots;
      filledQuantityLots = Math.addExact(filledQuantityLots, quantityLots);
    }

    private void markResting() {
      if (lifecycle != Lifecycle.ACCEPTED || remainingQuantityLots <= 0) {
        throw new IllegalStateException("invalid resting transition");
      }
      lifecycle = Lifecycle.RESTING;
    }

    private void markFilled() {
      if ((lifecycle != Lifecycle.ACCEPTED && lifecycle != Lifecycle.RESTING)
          || remainingQuantityLots != 0
          || canceledQuantityLots != 0) {
        throw new IllegalStateException("invalid filled transition");
      }
      lifecycle = Lifecycle.FILLED;
    }

    private void markCanceled(CancellationOrigin origin, ApplicationSequence applicationSequence) {
      if (lifecycle != Lifecycle.RESTING || remainingQuantityLots <= 0) {
        throw new IllegalStateException("invalid canceled transition");
      }
      cancellationOrigin = Objects.requireNonNull(origin, "origin");
      cancellationApplicationSequence =
          Objects.requireNonNull(applicationSequence, "applicationSequence");
      canceledQuantityLots = remainingQuantityLots;
      remainingQuantityLots = 0;
      lifecycle = Lifecycle.CANCELED;
    }

    private long cancelAcceptedRemainder(ApplicationSequence applicationSequence) {
      return cancelAcceptedRemainder(CancellationOrigin.IOC_REMAINDER, applicationSequence);
    }

    private long cancelAcceptedRemainder(
        CancellationOrigin origin, ApplicationSequence applicationSequence) {
      if (lifecycle != Lifecycle.ACCEPTED || remainingQuantityLots <= 0) {
        throw new IllegalStateException("invalid accepted remainder cancellation");
      }
      if (origin == CancellationOrigin.IOC_REMAINDER && executionPolicy != ExecutionPolicy.IOC) {
        throw new IllegalStateException("only an IOC order has an IOC remainder");
      }
      if (origin != CancellationOrigin.IOC_REMAINDER
          && origin != CancellationOrigin.SELF_TRADE_PREVENTION) {
        throw new IllegalStateException("unsupported accepted cancellation origin");
      }
      long canceled = remainingQuantityLots;
      cancellationOrigin = Objects.requireNonNull(origin, "origin");
      cancellationApplicationSequence =
          Objects.requireNonNull(applicationSequence, "applicationSequence");
      canceledQuantityLots = canceled;
      remainingQuantityLots = 0;
      lifecycle = Lifecycle.CANCELED;
      return canceled;
    }

    private void assertQuantityPartition() {
      final long total;
      try {
        total =
            Math.addExact(
                Math.addExact(filledQuantityLots, remainingQuantityLots), canceledQuantityLots);
      } catch (ArithmeticException exception) {
        throw new IllegalStateException("order quantity partition overflowed", exception);
      }
      if (originalQuantityLots <= 0
          || filledQuantityLots < 0
          || remainingQuantityLots < 0
          || canceledQuantityLots < 0
          || total != originalQuantityLots) {
        throw new IllegalStateException("order quantity partition is inconsistent");
      }
      if (lifecycle == Lifecycle.RESTING
          && (remainingQuantityLots <= 0 || canceledQuantityLots != 0)) {
        throw new IllegalStateException("resting order has an invalid quantity partition");
      }
      if (lifecycle == Lifecycle.FILLED
          && (remainingQuantityLots != 0
              || canceledQuantityLots != 0
              || filledQuantityLots != originalQuantityLots)) {
        throw new IllegalStateException("filled order has an invalid quantity partition");
      }
      if (lifecycle == Lifecycle.CANCELED
          && (remainingQuantityLots != 0 || canceledQuantityLots <= 0)) {
        throw new IllegalStateException("canceled order has an invalid quantity partition");
      }
      if ((lifecycle == Lifecycle.CANCELED)
          != (cancellationOrigin != null && cancellationApplicationSequence != null)) {
        throw new IllegalStateException("canceled order lost its terminal attribution");
      }
      if (executionPolicy == ExecutionPolicy.IOC && lifecycle == Lifecycle.RESTING) {
        throw new IllegalStateException("IOC order cannot remain resting");
      }
      if (executionPolicy == ExecutionPolicy.FOK
          && (lifecycle == Lifecycle.RESTING || lifecycle == Lifecycle.CANCELED)) {
        throw new IllegalStateException("FOK order must be fully filled when accepted");
      }
      if (stpInstruction == null) {
        throw new IllegalStateException("accepted order lost its STP instruction");
      }
    }
  }
}
