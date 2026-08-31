package io.github.lchareln.cex.matching.local;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** In-memory index rebuilt from durable records; bindings are committed only after apply. */
final class IdentityIndex {
  private final Map<UUID, Binding> byCommandId = new HashMap<>();
  private final Map<Slot, Binding> bySlot = new HashMap<>();
  private final Map<ProducerKey, ProducerCursor> producers = new HashMap<>();

  Decision preflight(M08Envelope envelope) {
    Binding idBinding = byCommandId.get(envelope.commandId());
    Binding slotBinding = bySlot.get(envelope.slot());

    if (idBinding != null) {
      if (!idBinding.slot().equals(envelope.slot())) {
        return new Rejected(PreflightRejectionCode.COMMAND_ID_SLOT_CONFLICT);
      }
      if (!idBinding.payloadHash().equals(envelope.payloadHash())) {
        return new Rejected(PreflightRejectionCode.COMMAND_ID_PAYLOAD_CONFLICT);
      }
      if (slotBinding != idBinding) {
        throw new IllegalStateException("commandId and slot indexes are not bidirectionally bound");
      }
      return new Duplicate(idBinding);
    }
    if (slotBinding != null) {
      return new Rejected(PreflightRejectionCode.SLOT_IDENTITY_CONFLICT);
    }

    ProducerKey key = ProducerKey.from(envelope.slot());
    ProducerCursor cursor = producers.get(key);
    if (cursor == null) {
      return envelope.slot().producerSequence() == 1
          ? New.INSTANCE
          : new Rejected(PreflightRejectionCode.PRODUCER_EPOCH_MUST_START_AT_ONE);
    }
    if (envelope.slot().producerEpoch() < cursor.currentEpoch()) {
      return new Rejected(PreflightRejectionCode.PRODUCER_EPOCH_FENCED);
    }
    if (envelope.slot().producerEpoch() > cursor.currentEpoch()) {
      return envelope.slot().producerSequence() == 1
          ? New.INSTANCE
          : new Rejected(PreflightRejectionCode.PRODUCER_EPOCH_MUST_START_AT_ONE);
    }
    if (cursor.nextSequence() == Long.MAX_VALUE) {
      return new Rejected(PreflightRejectionCode.PRODUCER_SEQUENCE_EXHAUSTED);
    }
    if (envelope.slot().producerSequence() == cursor.nextSequence()) {
      return New.INSTANCE;
    }
    return envelope.slot().producerSequence() > cursor.nextSequence()
        ? new Rejected(PreflightRejectionCode.PRODUCER_SEQUENCE_GAP)
        : new Rejected(PreflightRejectionCode.PRODUCER_SEQUENCE_STALE);
  }

  Binding commit(M08Envelope envelope, WalPosition position, CanonicalResult result) {
    if (!(preflight(envelope) instanceof New)) {
      throw new IllegalStateException("only a new identity may be committed");
    }
    Binding binding =
        new Binding(
            envelope.commandId(), envelope.slot(), envelope.payloadHash(), position, result);
    byCommandId.put(binding.commandId(), binding);
    bySlot.put(binding.slot(), binding);
    ProducerKey key = ProducerKey.from(binding.slot());
    long nextSequence = Math.incrementExact(binding.slot().producerSequence());
    producers.put(key, new ProducerCursor(binding.slot().producerEpoch(), nextSequence));
    return binding;
  }

  sealed interface Decision permits New, Duplicate, Rejected {}

  enum New implements Decision {
    INSTANCE
  }

  record Duplicate(Binding binding) implements Decision {
    Duplicate {
      Objects.requireNonNull(binding, "binding");
    }
  }

  record Rejected(PreflightRejectionCode code) implements Decision {
    Rejected {
      Objects.requireNonNull(code, "code");
    }
  }

  record Binding(
      UUID commandId, Slot slot, String payloadHash, WalPosition position, CanonicalResult result) {
    Binding {
      Objects.requireNonNull(commandId, "commandId");
      Objects.requireNonNull(slot, "slot");
      Objects.requireNonNull(payloadHash, "payloadHash");
      Objects.requireNonNull(position, "position");
      Objects.requireNonNull(result, "result");
    }
  }

  private record ProducerKey(String producerId, long shardId) {
    private static ProducerKey from(Slot slot) {
      return new ProducerKey(slot.producerId(), slot.shardId());
    }
  }

  private record ProducerCursor(long currentEpoch, long nextSequence) {}
}
