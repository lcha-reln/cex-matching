package io.github.lchareln.cex.matching.local;

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
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.CRC32C;

/** Deterministic, bounded, big-endian M09S1 snapshot codec. */
final class M09SnapshotCodec {
  static final int MAGIC = 0x4D303953; // M09S
  static final int VERSION = 1;
  static final int MAX_SNAPSHOT_BYTES = 256 * 1024 * 1024;

  private static final int SHA256_BYTES = 32;
  private static final int MAX_STRING_BYTES = 1024 * 1024;
  private static final int MAX_ENTRIES = 2_000_000;
  private static final int PREFIX_BYTES = Integer.BYTES * 3 + Long.BYTES * 4;
  private static final int TRAILER_BYTES = Integer.BYTES + SHA256_BYTES;

  byte[] encode(SnapshotAnchor anchor, LocalRuntimeStateImage state) {
    if (anchor.shardId() <= 0
        || anchor.lastWalSequence() != state.lastWalSequence()
        || anchor.lastApplicationSequence() != state.lastApplicationSequence()) {
      throw new IllegalArgumentException("snapshot anchor and state image disagree");
    }
    byte[] payload = encodeState(state);
    Writer writer = new Writer();
    writer.putInt(MAGIC);
    writer.putInt(VERSION);
    writer.putLong(anchor.generation());
    writer.putLong(anchor.shardId());
    writer.putLong(anchor.lastWalSequence());
    writer.putLong(anchor.lastApplicationSequence());
    writer.putInt(payload.length);
    writer.putBytes(payload);
    byte[] withoutTrailer = writer.toByteArray();
    writer.putInt(crc32c(withoutTrailer));
    byte[] withoutDigest = writer.toByteArray();
    writer.putBytes(sha256(withoutDigest));
    byte[] encoded = writer.toByteArray();
    if (encoded.length > MAX_SNAPSHOT_BYTES) {
      throw new IllegalArgumentException("M09S1 snapshot exceeds configured format limit");
    }
    return encoded;
  }

  DecodedSnapshot decodeCanonical(byte[] encoded) throws SnapshotCorruptionException {
    if (encoded.length < PREFIX_BYTES + TRAILER_BYTES || encoded.length > MAX_SNAPSHOT_BYTES) {
      throw corrupt("M09S1 snapshot length is outside the format bound");
    }
    int digestOffset = encoded.length - SHA256_BYTES;
    byte[] claimedDigest = Arrays.copyOfRange(encoded, digestOffset, encoded.length);
    byte[] actualDigest = sha256(Arrays.copyOf(encoded, digestOffset));
    if (!Arrays.equals(claimedDigest, actualDigest)) {
      throw corrupt("M09S1 serialization SHA-256 mismatch");
    }
    int crcOffset = digestOffset - Integer.BYTES;
    int claimedCrc = ByteBuffer.wrap(encoded, crcOffset, Integer.BYTES).getInt();
    if (claimedCrc != crc32c(Arrays.copyOf(encoded, crcOffset))) {
      throw corrupt("M09S1 CRC32C mismatch");
    }

    Reader reader = new Reader(Arrays.copyOf(encoded, crcOffset));
    if (reader.getInt() != MAGIC || reader.getInt() != VERSION) {
      throw corrupt("unsupported M09S1 magic or version");
    }
    SnapshotAnchor anchor =
        construct(
            "snapshot anchor",
            () ->
                new SnapshotAnchor(
                    reader.getLong(), reader.getLong(), reader.getLong(), reader.getLong()));
    byte[] payload = reader.getByteArray(MAX_SNAPSHOT_BYTES - PREFIX_BYTES - TRAILER_BYTES);
    reader.requireExhausted();
    LocalRuntimeStateImage state = decodeState(payload);
    if (anchor.lastWalSequence() != state.lastWalSequence()
        || anchor.lastApplicationSequence() != state.lastApplicationSequence()) {
      throw corrupt("M09S1 anchor and payload disagree");
    }
    DecodedSnapshot decoded = new DecodedSnapshot(anchor, state);
    if (!Arrays.equals(encoded, encode(anchor, state))) {
      throw corrupt("M09S1 snapshot is not in canonical byte form");
    }
    return decoded;
  }

  private byte[] encodeState(LocalRuntimeStateImage state) {
    Writer writer = new Writer();
    CommandApplierState applier = state.applierState();
    writer.putString(applier.transcriptDigest());
    writer.putString(applier.semanticStateDigest());
    putControl(writer, applier.matchingState().control());
    writer.putInt(applier.matchingState().orders().size());
    applier.matchingState().orders().forEach(order -> putOrder(writer, order));
    writer.putInt(state.identityBindings().size());
    state.identityBindings().forEach(binding -> putBinding(writer, binding));
    writer.putLong(state.lastWalSequence());
    writer.putLong(state.lastApplicationSequence());
    return writer.toByteArray();
  }

  private LocalRuntimeStateImage decodeState(byte[] encoded) throws SnapshotCorruptionException {
    Reader reader = new Reader(encoded);
    String transcriptDigest = reader.getString(MAX_STRING_BYTES);
    String semanticDigest = reader.getString(MAX_STRING_BYTES);
    MarketControlSnapshot control = getControl(reader);
    int orderCount = reader.getCount();
    List<MatchingStateImage.OrderImage> orders = new ArrayList<>(orderCount);
    for (int index = 0; index < orderCount; index++) {
      orders.add(getOrder(reader));
    }
    MatchingStateImage matchingState =
        construct("matching state", () -> new MatchingStateImage(control, orders));
    int bindingCount = reader.getCount();
    List<IdentityBindingImage> bindings = new ArrayList<>(bindingCount);
    for (int index = 0; index < bindingCount; index++) {
      bindings.add(getBinding(reader));
    }
    long lastWal = reader.getLong();
    long lastApplication = reader.getLong();
    reader.requireExhausted();
    CommandApplierState applier =
        construct(
            "command applier state",
            () -> new CommandApplierState(matchingState, transcriptDigest, semanticDigest));
    return construct(
        "local runtime state",
        () -> new LocalRuntimeStateImage(applier, bindings, lastWal, lastApplication));
  }

  private static void putControl(Writer writer, MarketControlSnapshot control) {
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

  private static MarketControlSnapshot getControl(Reader reader)
      throws SnapshotCorruptionException {
    MarketRuleSetArtifact active = getRuleSet(reader);
    Optional<MarketRuleSetArtifact> prepared = getOptional(reader, () -> getRuleSet(reader));
    long controlRevision = reader.getLong();
    Optional<ActivationFence> activation = getOptional(reader, () -> getActivationFence(reader));
    ApplicationSequence nextApplication =
        construct("next application sequence", () -> new ApplicationSequence(reader.getLong()));
    AcceptanceSequence nextAcceptance =
        construct("next acceptance sequence", () -> new AcceptanceSequence(reader.getLong()));
    MarketMode mode = getEnum(MarketMode.class, reader.getString(MAX_STRING_BYTES));
    long modeRevision = reader.getLong();
    Optional<ModeTransitionFence> modeFence = getOptional(reader, () -> getModeFence(reader));
    Optional<MassCancelFence> massCancel = getOptional(reader, () -> getMassCancelFence(reader));
    return construct(
        "market control state",
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
                massCancel));
  }

  private static void putOrder(Writer writer, MatchingStateImage.OrderImage order) {
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

  private static MatchingStateImage.OrderImage getOrder(Reader reader)
      throws SnapshotCorruptionException {
    AcceptanceSequence sequence =
        construct("order sequence", () -> new AcceptanceSequence(reader.getLong()));
    OrderId orderId = construct("order id", () -> new OrderId(reader.getLong()));
    Side side = getEnum(Side.class, reader.getString(MAX_STRING_BYTES));
    PriceTicks price = construct("order price", () -> new PriceTicks(reader.getLong()));
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
                    "order cancellation",
                    () ->
                        new MatchingStateImage.Cancellation(
                            getEnum(
                                MatchingStateImage.CancellationOrigin.class,
                                reader.getString(MAX_STRING_BYTES)),
                            new ApplicationSequence(reader.getLong()))));
    return construct(
        "order image",
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
                cancellation));
  }

  private static void putBinding(Writer writer, IdentityBindingImage binding) {
    writer.putLong(binding.commandId().getMostSignificantBits());
    writer.putLong(binding.commandId().getLeastSignificantBits());
    Slot slot = binding.slot();
    writer.putString(slot.producerId());
    writer.putLong(slot.producerEpoch());
    writer.putLong(slot.shardId());
    writer.putLong(slot.producerSequence());
    writer.putString(binding.payloadHash());
    WalPosition position = binding.position();
    writer.putLong(position.segmentId());
    writer.putLong(position.walSequence());
    writer.putLong(position.applicationSequence());
    writer.putLong(position.offset());
    writer.putInt(position.recordLength());
    CanonicalResult result = binding.result();
    writer.putString(result.resultType());
    writer.putLong(result.applicationSequence());
    writer.putInt(result.events().size());
    result.events().forEach(writer::putString);
    writer.putString(result.context());
    writer.putString(result.semanticStateDigest());
    writer.putString(result.resultDigest());
  }

  private static IdentityBindingImage getBinding(Reader reader) throws SnapshotCorruptionException {
    UUID commandId = new UUID(reader.getLong(), reader.getLong());
    Slot slot =
        construct(
            "producer slot",
            () ->
                new Slot(
                    reader.getString(MAX_STRING_BYTES),
                    reader.getLong(),
                    reader.getLong(),
                    reader.getLong()));
    String payloadHash = reader.getString(MAX_STRING_BYTES);
    WalPosition position =
        construct(
            "WAL position",
            () ->
                new WalPosition(
                    reader.getLong(),
                    reader.getLong(),
                    reader.getLong(),
                    reader.getLong(),
                    reader.getInt()));
    String resultType = reader.getString(MAX_STRING_BYTES);
    long resultApplication = reader.getLong();
    int eventCount = reader.getCount();
    List<String> events = new ArrayList<>(eventCount);
    for (int index = 0; index < eventCount; index++) {
      events.add(reader.getString(MAX_STRING_BYTES));
    }
    String context = reader.getString(MAX_STRING_BYTES);
    String semanticDigest = reader.getString(MAX_STRING_BYTES);
    String resultDigest = reader.getString(MAX_STRING_BYTES);
    CanonicalResult result =
        construct(
            "canonical result",
            () ->
                new CanonicalResult(
                    resultType, resultApplication, events, context, semanticDigest, resultDigest));
    return construct(
        "identity binding",
        () -> new IdentityBindingImage(commandId, slot, payloadHash, position, result));
  }

  private static void putRuleSet(Writer writer, MarketRuleSetArtifact artifact) {
    writer.putString(artifact.schemaVersion());
    writer.putString(artifact.instrumentId());
    writer.putLong(artifact.version().value());
    writer.putLong(artifact.lowerInclusive().value());
    writer.putLong(artifact.upperInclusive().value());
    writer.putString(artifact.contentHash());
  }

  private static MarketRuleSetArtifact getRuleSet(Reader reader)
      throws SnapshotCorruptionException {
    return construct(
        "market rule set",
        () ->
            new MarketRuleSetArtifact(
                reader.getString(MAX_STRING_BYTES),
                reader.getString(MAX_STRING_BYTES),
                new RuleSetVersion(reader.getLong()),
                new PriceTicks(reader.getLong()),
                new PriceTicks(reader.getLong()),
                reader.getString(MAX_STRING_BYTES)));
  }

  private static void putRuleIdentity(Writer writer, RuleSetIdentity identity) {
    writer.putLong(identity.version().value());
    writer.putString(identity.contentHash());
  }

  private static RuleSetIdentity getRuleIdentity(Reader reader) throws SnapshotCorruptionException {
    return construct(
        "rule-set identity",
        () -> new RuleSetIdentity(reader.getLong(), reader.getString(MAX_STRING_BYTES)));
  }

  private static void putActivationFence(Writer writer, ActivationFence fence) {
    writer.putLong(fence.appliedCommandSequence().value());
    writer.putLong(fence.controlRevision());
    writer.putLong(fence.firstAcceptanceSequence().value());
  }

  private static ActivationFence getActivationFence(Reader reader)
      throws SnapshotCorruptionException {
    return construct(
        "activation fence",
        () ->
            new ActivationFence(
                new ApplicationSequence(reader.getLong()),
                reader.getLong(),
                new AcceptanceSequence(reader.getLong())));
  }

  private static void putModeFence(Writer writer, ModeTransitionFence fence) {
    writer.putLong(fence.appliedCommandSequence().value());
    writer.putLong(fence.modeRevision());
    writer.putString(fence.previousMode().name());
    writer.putString(fence.activeMode().name());
    writer.putLong(fence.nextAcceptanceSequence().value());
  }

  private static ModeTransitionFence getModeFence(Reader reader)
      throws SnapshotCorruptionException {
    return construct(
        "mode transition fence",
        () ->
            new ModeTransitionFence(
                new ApplicationSequence(reader.getLong()),
                reader.getLong(),
                getEnum(MarketMode.class, reader.getString(MAX_STRING_BYTES)),
                getEnum(MarketMode.class, reader.getString(MAX_STRING_BYTES)),
                new AcceptanceSequence(reader.getLong())));
  }

  private static void putMassCancelFence(Writer writer, MassCancelFence fence) {
    writer.putLong(fence.appliedCommandSequence().value());
    writer.putLong(fence.modeRevision());
    writer.putString(fence.operatorId().value());
    writer.putLong(fence.canceledOrderCount());
    putOptional(
        writer, fence.firstCanceledSequence(), sequence -> writer.putLong(sequence.value()));
    putOptional(writer, fence.lastCanceledSequence(), sequence -> writer.putLong(sequence.value()));
  }

  private static MassCancelFence getMassCancelFence(Reader reader)
      throws SnapshotCorruptionException {
    ApplicationSequence application =
        construct("Mass Cancel application", () -> new ApplicationSequence(reader.getLong()));
    long modeRevision = reader.getLong();
    OperatorId operator =
        construct("Mass Cancel operator", () -> new OperatorId(reader.getString(MAX_STRING_BYTES)));
    long count = reader.getLong();
    Optional<AcceptanceSequence> first =
        getOptional(
            reader,
            () ->
                construct(
                    "first canceled sequence", () -> new AcceptanceSequence(reader.getLong())));
    Optional<AcceptanceSequence> last =
        getOptional(
            reader,
            () ->
                construct(
                    "last canceled sequence", () -> new AcceptanceSequence(reader.getLong())));
    return construct(
        "Mass Cancel fence",
        () -> new MassCancelFence(application, modeRevision, operator, count, first, last));
  }

  private static <T> void putOptional(Writer writer, Optional<T> value, Sink<T> sink) {
    writer.putByte(value.isPresent() ? 1 : 0);
    value.ifPresent(sink::accept);
  }

  private static <T> Optional<T> getOptional(Reader reader, Source<T> source)
      throws SnapshotCorruptionException {
    int present = reader.getUnsignedByte();
    if (present == 0) {
      return Optional.empty();
    }
    if (present != 1) {
      throw corrupt("M09S1 optional flag is not canonical");
    }
    return Optional.of(source.get());
  }

  private static <E extends Enum<E>> E getEnum(Class<E> type, String name)
      throws SnapshotCorruptionException {
    return construct("enum " + type.getSimpleName(), () -> Enum.valueOf(type, name));
  }

  private static <T> T construct(String field, Constructor<T> constructor)
      throws SnapshotCorruptionException {
    try {
      return constructor.create();
    } catch (IllegalArgumentException | ArithmeticException failure) {
      throw new SnapshotCorruptionException("invalid M09S1 " + field, failure);
    }
  }

  private static byte[] sha256(byte[] value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value);
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("the Java runtime does not provide SHA-256", failure);
    }
  }

  static String serializationDigest(byte[] encoded) {
    return HexFormat.of().formatHex(sha256(encoded));
  }

  private static int crc32c(byte[] value) {
    CRC32C crc = new CRC32C();
    crc.update(value, 0, value.length);
    return (int) crc.getValue();
  }

  private static SnapshotCorruptionException corrupt(String message) {
    return new SnapshotCorruptionException(message);
  }

  record DecodedSnapshot(SnapshotAnchor anchor, LocalRuntimeStateImage state) {}

  @FunctionalInterface
  private interface Sink<T> {
    void accept(T value);
  }

  @FunctionalInterface
  private interface Source<T> {
    T get() throws SnapshotCorruptionException;
  }

  @FunctionalInterface
  private interface Constructor<T> {
    T create() throws SnapshotCorruptionException;
  }

  private static final class Writer {
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final DataOutputStream output = new DataOutputStream(bytes);

    private void putByte(int value) {
      try {
        output.writeByte(value);
      } catch (IOException impossible) {
        throw new IllegalStateException(impossible);
      }
    }

    private void putInt(int value) {
      try {
        output.writeInt(value);
      } catch (IOException impossible) {
        throw new IllegalStateException(impossible);
      }
    }

    private void putLong(long value) {
      try {
        output.writeLong(value);
      } catch (IOException impossible) {
        throw new IllegalStateException(impossible);
      }
    }

    private void putBytes(byte[] value) {
      try {
        output.write(value);
      } catch (IOException impossible) {
        throw new IllegalStateException(impossible);
      }
    }

    private void putString(String value) {
      byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
      if (encoded.length > MAX_STRING_BYTES) {
        throw new IllegalArgumentException("M09S1 string exceeds field limit");
      }
      putInt(encoded.length);
      putBytes(encoded);
    }

    private byte[] toByteArray() {
      return bytes.toByteArray();
    }
  }

  private static final class Reader {
    private final ByteBuffer bytes;

    private Reader(byte[] encoded) {
      bytes = ByteBuffer.wrap(encoded).asReadOnlyBuffer();
    }

    private int getUnsignedByte() throws SnapshotCorruptionException {
      require(Byte.BYTES);
      return Byte.toUnsignedInt(bytes.get());
    }

    private int getInt() throws SnapshotCorruptionException {
      require(Integer.BYTES);
      return bytes.getInt();
    }

    private long getLong() throws SnapshotCorruptionException {
      require(Long.BYTES);
      return bytes.getLong();
    }

    private int getCount() throws SnapshotCorruptionException {
      int count = getInt();
      if (count < 0 || count > MAX_ENTRIES) {
        throw corrupt("M09S1 collection count is outside the format bound");
      }
      return count;
    }

    private byte[] getByteArray(int limit) throws SnapshotCorruptionException {
      int length = getInt();
      if (length < 0 || length > limit) {
        throw corrupt("M09S1 byte length is outside the format bound");
      }
      require(length);
      byte[] value = new byte[length];
      bytes.get(value);
      return value;
    }

    private String getString(int limit) throws SnapshotCorruptionException {
      byte[] encoded = getByteArray(limit);
      try {
        return StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(encoded))
            .toString();
      } catch (CharacterCodingException failure) {
        throw new SnapshotCorruptionException("M09S1 string is not canonical UTF-8", failure);
      }
    }

    private void requireExhausted() throws SnapshotCorruptionException {
      if (bytes.hasRemaining()) {
        throw corrupt("M09S1 contains trailing bytes");
      }
    }

    private void require(int count) throws SnapshotCorruptionException {
      if (count < 0 || bytes.remaining() < count) {
        throw corrupt("M09S1 is truncated");
      }
    }
  }
}
