package io.github.lchareln.cex.matching.cluster;

import io.github.lchareln.cex.matching.AcceptanceSequence;
import io.github.lchareln.cex.matching.ActivationFence;
import io.github.lchareln.cex.matching.ApplicationSequence;
import io.github.lchareln.cex.matching.ExecutionPolicy;
import io.github.lchareln.cex.matching.MarketControlSnapshot;
import io.github.lchareln.cex.matching.MarketMode;
import io.github.lchareln.cex.matching.MarketRuleSetArtifact;
import io.github.lchareln.cex.matching.MassCancelFence;
import io.github.lchareln.cex.matching.MatchingStateImage;
import io.github.lchareln.cex.matching.ModeTransitionFence;
import io.github.lchareln.cex.matching.OperatorId;
import io.github.lchareln.cex.matching.OrderId;
import io.github.lchareln.cex.matching.PriceTicks;
import io.github.lchareln.cex.matching.RuleSetIdentity;
import io.github.lchareln.cex.matching.RuleSetVersion;
import io.github.lchareln.cex.matching.SelfTradePreventionPolicy;
import io.github.lchareln.cex.matching.Side;
import io.github.lchareln.cex.matching.local.CommandApplierState;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Canonical and reversible representation of deterministic business state only. */
public final class M11CommandStateCodec {
  public static final int MAX_STATE_BYTES = 256 * 1024 * 1024;

  private static final int MAX_STRING_BYTES = 1024 * 1024;
  static final int MAX_ORDERS = 2_000_000;

  public byte[] encode(CommandApplierState state) {
    Objects.requireNonNull(state, "state");
    M11Binary.Writer writer = new M11Binary.Writer();
    putState(writer, state);
    byte[] encoded = writer.toByteArray();
    if (encoded.length > MAX_STATE_BYTES) {
      throw new IllegalArgumentException("deterministic state exceeds the M11 snapshot bound");
    }
    return encoded;
  }

  static void putState(M11Binary.Writer writer, CommandApplierState state) {
    writer.putString(state.transcriptDigest());
    writer.putString(state.semanticStateDigest());
    putControl(writer, state.matchingState().control());
    requireEncodableOrderCount(state.matchingState().orders().size());
    writer.putInt(state.matchingState().orders().size());
    state.matchingState().orders().forEach(order -> putOrder(writer, order));
  }

  static void requireEncodableOrderCount(int count) {
    M11EncodingBounds.requireAtMost(count, MAX_ORDERS, "order count");
  }

  public CommandApplierState decodeCanonical(byte[] encoded) throws M11ProtocolException {
    Objects.requireNonNull(encoded, "encoded");
    if (encoded.length == 0 || encoded.length > MAX_STATE_BYTES) {
      throw new M11ProtocolException(
          M11ProtocolException.Code.LENGTH_LIMIT,
          "deterministic state length is outside the snapshot bound");
    }
    M11Binary.Reader reader = new M11Binary.Reader(encoded);
    CommandApplierState state = readState(reader);
    reader.requireExhausted();
    if (!Arrays.equals(encoded, encode(state))) {
      throw new M11ProtocolException(
          M11ProtocolException.Code.NON_CANONICAL, "deterministic state is not canonical");
    }
    return state;
  }

  static CommandApplierState readState(M11Binary.Reader reader) throws M11ProtocolException {
    String transcriptDigest = reader.getString(MAX_STRING_BYTES);
    String semanticDigest = reader.getString(MAX_STRING_BYTES);
    MarketControlSnapshot control = getControl(reader);
    int orderCount = reader.getCount(MAX_ORDERS);
    List<MatchingStateImage.OrderImage> orders = new ArrayList<>(orderCount);
    for (int index = 0; index < orderCount; index++) {
      orders.add(getOrder(reader));
    }
    return construct(
        () ->
            new CommandApplierState(
                new MatchingStateImage(control, orders), transcriptDigest, semanticDigest),
        "command state");
  }

  private static void putControl(M11Binary.Writer writer, MarketControlSnapshot control) {
    putRuleSet(writer, control.activeRuleSet());
    putOptional(writer, control.preparedRuleSet(), value -> putRuleSet(writer, value));
    writer.putLong(control.controlRevision());
    putOptional(writer, control.lastActivationFence(), value -> putActivationFence(writer, value));
    writer.putLong(control.nextApplicationSequence().value());
    writer.putLong(control.nextAcceptanceSequence().value());
    writer.putString(control.marketMode().name());
    writer.putLong(control.modeRevision());
    putOptional(writer, control.lastModeTransitionFence(), value -> putModeFence(writer, value));
    putOptional(writer, control.lastMassCancelFence(), value -> putMassCancelFence(writer, value));
  }

  private static MarketControlSnapshot getControl(M11Binary.Reader reader)
      throws M11ProtocolException {
    MarketRuleSetArtifact active = getRuleSet(reader);
    Optional<MarketRuleSetArtifact> prepared = getOptional(reader, () -> getRuleSet(reader));
    long controlRevision = reader.getLong();
    Optional<ActivationFence> activation = getOptional(reader, () -> getActivationFence(reader));
    ApplicationSequence nextApplication =
        construct(() -> new ApplicationSequence(reader.getLong()), "next application sequence");
    AcceptanceSequence nextAcceptance =
        construct(() -> new AcceptanceSequence(reader.getLong()), "next acceptance sequence");
    MarketMode mode = getEnum(MarketMode.class, reader.getString(MAX_STRING_BYTES));
    long modeRevision = reader.getLong();
    Optional<ModeTransitionFence> modeFence = getOptional(reader, () -> getModeFence(reader));
    Optional<MassCancelFence> massCancel = getOptional(reader, () -> getMassCancelFence(reader));
    return construct(
        () ->
            new MarketControlSnapshot(
                active,
                prepared,
                controlRevision,
                activation,
                nextApplication,
                nextAcceptance,
                mode,
                modeRevision,
                modeFence,
                massCancel),
        "market control state");
  }

  private static void putOrder(M11Binary.Writer writer, MatchingStateImage.OrderImage order) {
    writer.putLong(order.sequence().value());
    writer.putLong(order.orderId().value());
    writer.putString(order.side().name());
    writer.putLong(order.priceTicks().value());
    writer.putString(order.executionPolicy().name());
    putRuleIdentity(writer, order.admissionRuleSet());
    writer.putLong(order.participantGroupId());
    writer.putString(order.selfTradePreventionPolicy().name());
    writer.putLong(order.originalQuantityLots());
    writer.putLong(order.remainingQuantityLots());
    writer.putLong(order.filledQuantityLots());
    writer.putLong(order.canceledQuantityLots());
    writer.putString(order.lifecycle().name());
    putOptional(
        writer,
        order.cancellation(),
        cancellation -> {
          writer.putString(cancellation.origin().name());
          writer.putLong(cancellation.applicationSequence().value());
        });
  }

  private static MatchingStateImage.OrderImage getOrder(M11Binary.Reader reader)
      throws M11ProtocolException {
    AcceptanceSequence sequence =
        construct(() -> new AcceptanceSequence(reader.getLong()), "order sequence");
    OrderId orderId = construct(() -> new OrderId(reader.getLong()), "order id");
    Side side = getEnum(Side.class, reader.getString(MAX_STRING_BYTES));
    PriceTicks price = construct(() -> new PriceTicks(reader.getLong()), "order price");
    ExecutionPolicy policy = getEnum(ExecutionPolicy.class, reader.getString(MAX_STRING_BYTES));
    RuleSetIdentity admission = getRuleIdentity(reader);
    long group = reader.getLong();
    SelfTradePreventionPolicy stp =
        getEnum(SelfTradePreventionPolicy.class, reader.getString(MAX_STRING_BYTES));
    long original = reader.getLong();
    long remaining = reader.getLong();
    long filled = reader.getLong();
    long canceled = reader.getLong();
    MatchingStateImage.Lifecycle lifecycle =
        getEnum(MatchingStateImage.Lifecycle.class, reader.getString(MAX_STRING_BYTES));
    Optional<MatchingStateImage.Cancellation> cancellation =
        getOptional(
            reader,
            () ->
                construct(
                    () ->
                        new MatchingStateImage.Cancellation(
                            getEnum(
                                MatchingStateImage.CancellationOrigin.class,
                                reader.getString(MAX_STRING_BYTES)),
                            new ApplicationSequence(reader.getLong())),
                    "order cancellation"));
    return construct(
        () ->
            new MatchingStateImage.OrderImage(
                sequence,
                orderId,
                side,
                price,
                policy,
                admission,
                group,
                stp,
                original,
                remaining,
                filled,
                canceled,
                lifecycle,
                cancellation),
        "order image");
  }

  private static void putRuleSet(M11Binary.Writer writer, MarketRuleSetArtifact artifact) {
    writer.putString(artifact.schemaVersion());
    writer.putString(artifact.instrumentId());
    writer.putLong(artifact.version().value());
    writer.putLong(artifact.lowerInclusive().value());
    writer.putLong(artifact.upperInclusive().value());
    writer.putString(artifact.contentHash());
  }

  private static MarketRuleSetArtifact getRuleSet(M11Binary.Reader reader)
      throws M11ProtocolException {
    return construct(
        () ->
            new MarketRuleSetArtifact(
                reader.getString(MAX_STRING_BYTES),
                reader.getString(MAX_STRING_BYTES),
                new RuleSetVersion(reader.getLong()),
                new PriceTicks(reader.getLong()),
                new PriceTicks(reader.getLong()),
                reader.getString(MAX_STRING_BYTES)),
        "rule set");
  }

  private static void putRuleIdentity(M11Binary.Writer writer, RuleSetIdentity identity) {
    writer.putLong(identity.version().value());
    writer.putString(identity.contentHash());
  }

  private static RuleSetIdentity getRuleIdentity(M11Binary.Reader reader)
      throws M11ProtocolException {
    return construct(
        () -> new RuleSetIdentity(reader.getLong(), reader.getString(MAX_STRING_BYTES)),
        "rule-set identity");
  }

  private static void putActivationFence(M11Binary.Writer writer, ActivationFence fence) {
    writer.putLong(fence.appliedCommandSequence().value());
    writer.putLong(fence.controlRevision());
    writer.putLong(fence.firstAcceptanceSequence().value());
  }

  private static ActivationFence getActivationFence(M11Binary.Reader reader)
      throws M11ProtocolException {
    return construct(
        () ->
            new ActivationFence(
                new ApplicationSequence(reader.getLong()),
                reader.getLong(),
                new AcceptanceSequence(reader.getLong())),
        "activation fence");
  }

  private static void putModeFence(M11Binary.Writer writer, ModeTransitionFence fence) {
    writer.putLong(fence.appliedCommandSequence().value());
    writer.putLong(fence.modeRevision());
    writer.putString(fence.previousMode().name());
    writer.putString(fence.activeMode().name());
    writer.putLong(fence.nextAcceptanceSequence().value());
  }

  private static ModeTransitionFence getModeFence(M11Binary.Reader reader)
      throws M11ProtocolException {
    return construct(
        () ->
            new ModeTransitionFence(
                new ApplicationSequence(reader.getLong()),
                reader.getLong(),
                getEnum(MarketMode.class, reader.getString(MAX_STRING_BYTES)),
                getEnum(MarketMode.class, reader.getString(MAX_STRING_BYTES)),
                new AcceptanceSequence(reader.getLong())),
        "mode transition fence");
  }

  private static void putMassCancelFence(M11Binary.Writer writer, MassCancelFence fence) {
    writer.putLong(fence.appliedCommandSequence().value());
    writer.putLong(fence.modeRevision());
    writer.putString(fence.operatorId().value());
    writer.putLong(fence.canceledOrderCount());
    putOptional(writer, fence.firstCanceledSequence(), value -> writer.putLong(value.value()));
    putOptional(writer, fence.lastCanceledSequence(), value -> writer.putLong(value.value()));
  }

  private static MassCancelFence getMassCancelFence(M11Binary.Reader reader)
      throws M11ProtocolException {
    ApplicationSequence application =
        construct(() -> new ApplicationSequence(reader.getLong()), "Mass Cancel application");
    long modeRevision = reader.getLong();
    OperatorId operator =
        construct(() -> new OperatorId(reader.getString(MAX_STRING_BYTES)), "Mass Cancel operator");
    long count = reader.getLong();
    Optional<AcceptanceSequence> first =
        getOptional(
            reader,
            () -> construct(() -> new AcceptanceSequence(reader.getLong()), "first canceled"));
    Optional<AcceptanceSequence> last =
        getOptional(
            reader,
            () -> construct(() -> new AcceptanceSequence(reader.getLong()), "last canceled"));
    return construct(
        () -> new MassCancelFence(application, modeRevision, operator, count, first, last),
        "Mass Cancel fence");
  }

  private static <T> void putOptional(M11Binary.Writer writer, Optional<T> value, Sink<T> sink) {
    writer.putByte(value.isPresent() ? 1 : 0);
    value.ifPresent(sink::accept);
  }

  private static <T> Optional<T> getOptional(M11Binary.Reader reader, Source<T> source)
      throws M11ProtocolException {
    int present = reader.getUnsignedByte();
    if (present == 0) {
      return Optional.empty();
    }
    if (present != 1) {
      throw new M11ProtocolException(
          M11ProtocolException.Code.NON_CANONICAL, "optional flag is not canonical");
    }
    return Optional.of(source.get());
  }

  private static <E extends Enum<E>> E getEnum(Class<E> type, String name)
      throws M11ProtocolException {
    return construct(() -> Enum.valueOf(type, name), "enum " + type.getSimpleName());
  }

  private static <T> T construct(Constructor<T> constructor, String field)
      throws M11ProtocolException {
    try {
      return constructor.create();
    } catch (IllegalArgumentException | ArithmeticException failure) {
      throw new M11ProtocolException(
          M11ProtocolException.Code.INVALID_VALUE, "invalid " + field, failure);
    }
  }

  @FunctionalInterface
  private interface Sink<T> {
    void accept(T value);
  }

  @FunctionalInterface
  private interface Source<T> {
    T get() throws M11ProtocolException;
  }

  @FunctionalInterface
  private interface Constructor<T> {
    T create() throws M11ProtocolException;
  }
}
