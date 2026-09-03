package io.github.lchareln.cex.matching.local;

import io.github.lchareln.cex.matching.ActivationFence;
import io.github.lchareln.cex.matching.MarketControlSnapshot;
import io.github.lchareln.cex.matching.MarketRuleSetArtifact;
import io.github.lchareln.cex.matching.MassCancelFence;
import io.github.lchareln.cex.matching.MatchingStateImage;
import io.github.lchareln.cex.matching.ModeTransitionFence;
import io.github.lchareln.cex.matching.RuleSetIdentity;
import java.math.BigInteger;
import java.util.Optional;
import java.util.UUID;

/** Testkit-only producer for a real post-Place core state and complete identity/result image. */
public final class M11GoldenStateProducer {
  private M11GoldenStateProducer() {}

  public static GoldenState produce(M08Envelope envelope) {
    MatchingCoreCommandApplier applier = new MatchingCoreCommandApplier();
    CanonicalResult responseResult = applier.apply(envelope.command());
    String responseSemanticStateDigest = applier.stateImage().semanticStateDigest();
    M08Envelope second = secondEnvelope(envelope);
    CanonicalResult secondResult = applier.apply(second.command());
    CommandApplierState state = applier.stateImage();

    BinaryEncoding.Writer core = new BinaryEncoding.Writer();
    core.putString(state.transcriptDigest());
    core.putString(state.semanticStateDigest());
    putControl(core, state.matchingState().control());
    core.putInt(state.matchingState().orders().size());
    state.matchingState().orders().forEach(order -> putOrder(core, order));

    BinaryEncoding.Writer identities = new BinaryEncoding.Writer();
    identities.putInt(2);
    putIdentity(identities, envelope, responseResult);
    putIdentity(identities, second, secondResult);

    byte[] identityBytes = identities.toByteArray();
    core.putBytes(identityBytes);
    return new GoldenState(
        core.toByteArray(),
        identityBytes,
        envelope.payloadHash(),
        responseResult.resultDigest(),
        responseSemanticStateDigest,
        state.semanticStateDigest());
  }

  private static M08Envelope secondEnvelope(M08Envelope first) {
    M08EnvelopeCodec codec = new M08EnvelopeCodec();
    byte[] encoded =
        codec.encode(
            first.slot().producerId(),
            first.slot().producerEpoch(),
            first.slot().shardId(),
            Math.incrementExact(first.slot().producerSequence()),
            UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa"),
            new M08Command.Place(
                "BTC-USDT",
                BigInteger.valueOf(43),
                "SELL",
                BigInteger.valueOf(6_600_000),
                BigInteger.valueOf(2),
                "GTC",
                0,
                "NONE",
                Optional.empty()));
    try {
      return codec.decodeCanonical(encoded, first.slot().shardId());
    } catch (StructuralRejectionException failure) {
      throw new IllegalStateException("second generated M08C1 envelope is not canonical", failure);
    }
  }

  private static void putIdentity(
      BinaryEncoding.Writer identities, M08Envelope envelope, CanonicalResult result) {
    identities.putLong(envelope.commandId().getMostSignificantBits());
    identities.putLong(envelope.commandId().getLeastSignificantBits());
    identities.putString(envelope.slot().producerId());
    identities.putLong(envelope.slot().producerEpoch());
    identities.putLong(envelope.slot().shardId());
    identities.putLong(envelope.slot().producerSequence());
    identities.putString(envelope.payloadHash());
    putResult(identities, result);
  }

  private static void putControl(BinaryEncoding.Writer writer, MarketControlSnapshot control) {
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

  private static void putOrder(BinaryEncoding.Writer writer, MatchingStateImage.OrderImage order) {
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

  private static void putResult(BinaryEncoding.Writer writer, CanonicalResult result) {
    writer.putString(result.resultType());
    writer.putLong(result.applicationSequence());
    writer.putInt(result.events().size());
    result.events().forEach(writer::putString);
    writer.putString(result.context());
    writer.putString(result.semanticStateDigest());
    writer.putString(result.resultDigest());
  }

  private static void putRuleSet(BinaryEncoding.Writer writer, MarketRuleSetArtifact artifact) {
    writer.putString(artifact.schemaVersion());
    writer.putString(artifact.instrumentId());
    writer.putLong(artifact.version().value());
    writer.putLong(artifact.lowerInclusive().value());
    writer.putLong(artifact.upperInclusive().value());
    writer.putString(artifact.contentHash());
  }

  private static void putRuleIdentity(BinaryEncoding.Writer writer, RuleSetIdentity identity) {
    writer.putLong(identity.version().value());
    writer.putString(identity.contentHash());
  }

  private static void putActivationFence(BinaryEncoding.Writer writer, ActivationFence fence) {
    writer.putLong(fence.appliedCommandSequence().value());
    writer.putLong(fence.controlRevision());
    writer.putLong(fence.firstAcceptanceSequence().value());
  }

  private static void putModeFence(BinaryEncoding.Writer writer, ModeTransitionFence fence) {
    writer.putLong(fence.appliedCommandSequence().value());
    writer.putLong(fence.modeRevision());
    writer.putString(fence.previousMode().name());
    writer.putString(fence.activeMode().name());
    writer.putLong(fence.nextAcceptanceSequence().value());
  }

  private static void putMassCancelFence(BinaryEncoding.Writer writer, MassCancelFence fence) {
    writer.putLong(fence.appliedCommandSequence().value());
    writer.putLong(fence.modeRevision());
    writer.putString(fence.operatorId().value());
    writer.putLong(fence.canceledOrderCount());
    putOptional(writer, fence.firstCanceledSequence(), value -> writer.putLong(value.value()));
    putOptional(writer, fence.lastCanceledSequence(), value -> writer.putLong(value.value()));
  }

  private static <T> void putOptional(
      BinaryEncoding.Writer writer, Optional<T> value, Sink<T> sink) {
    writer.putByte(value.isPresent() ? 1 : 0);
    value.ifPresent(sink::accept);
  }

  @FunctionalInterface
  private interface Sink<T> {
    void accept(T value);
  }

  public record GoldenState(
      byte[] canonicalStateBytes,
      byte[] identityTableBytes,
      String payloadHash,
      String resultDigest,
      String responseSemanticStateDigest,
      String semanticStateDigest) {
    public GoldenState {
      canonicalStateBytes = canonicalStateBytes.clone();
      identityTableBytes = identityTableBytes.clone();
    }

    @Override
    public byte[] canonicalStateBytes() {
      return canonicalStateBytes.clone();
    }

    @Override
    public byte[] identityTableBytes() {
      return identityTableBytes.clone();
    }
  }
}
