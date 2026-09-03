package io.github.lchareln.cex.matching.cluster;

import io.github.lchareln.cex.matching.local.CanonicalResult;
import io.github.lchareln.cex.matching.local.M08Envelope;
import io.github.lchareln.cex.matching.local.PreflightRejectionCode;
import io.github.lchareln.cex.matching.local.Slot;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

final class M11IdentityTable {
  private final Map<UUID, M11IdentityBinding> byCommand = new HashMap<>();
  private final Map<Slot, M11IdentityBinding> bySlot = new HashMap<>();
  private final Map<ProducerKey, ProducerCursor> producers = new HashMap<>();
  private final Map<Long, M11IdentityBinding> byApplication = new LinkedHashMap<>();
  private long nextApplicationSequence = 1;

  Decision preflight(M08Envelope envelope) {
    Objects.requireNonNull(envelope, "envelope");
    M11IdentityBinding commandBinding = byCommand.get(envelope.commandId());
    M11IdentityBinding slotBinding = bySlot.get(envelope.slot());
    if (commandBinding != null) {
      if (!commandBinding.slot().equals(envelope.slot())) {
        return rejected(PreflightRejectionCode.COMMAND_ID_SLOT_CONFLICT);
      }
      if (!commandBinding.payloadHash().equals(envelope.payloadHash())) {
        return rejected(PreflightRejectionCode.COMMAND_ID_PAYLOAD_CONFLICT);
      }
      if (slotBinding != commandBinding) {
        throw new IllegalStateException("commandId and slot indexes are not bidirectionally bound");
      }
      return new Duplicate(commandBinding);
    }
    if (slotBinding != null) {
      return rejected(PreflightRejectionCode.SLOT_IDENTITY_CONFLICT);
    }
    String producerRejection = producerRejection(envelope.slot());
    return producerRejection == null ? New.INSTANCE : new Rejected(producerRejection);
  }

  void commit(M08Envelope envelope, CanonicalResult result) {
    if (preflight(envelope) != New.INSTANCE) {
      throw new IllegalStateException("identity must be new at commit");
    }
    if (result.applicationSequence() != nextApplicationSequence) {
      throw new IllegalStateException("identity result is not at the next application sequence");
    }
    M11IdentityBinding binding =
        new M11IdentityBinding(
            envelope.commandId(), envelope.slot(), envelope.payloadHash(), result);
    byCommand.put(binding.commandId(), binding);
    bySlot.put(binding.slot(), binding);
    byApplication.put(result.applicationSequence(), binding);
    ProducerKey key = ProducerKey.from(binding.slot());
    producers.put(
        key,
        new ProducerCursor(
            binding.slot().producerEpoch(),
            Math.incrementExact(binding.slot().producerSequence())));
    nextApplicationSequence = Math.incrementExact(nextApplicationSequence);
  }

  List<M11IdentityBinding> bindings() {
    return List.copyOf(byApplication.values());
  }

  boolean containsCommand(UUID commandId) {
    return byCommand.containsKey(Objects.requireNonNull(commandId, "commandId"));
  }

  static M11IdentityTable emptyAt(long nextApplicationSequence) {
    if (nextApplicationSequence <= 1) {
      throw new IllegalArgumentException("dropped identity state requires an advanced sequence");
    }
    M11IdentityTable restored = new M11IdentityTable();
    restored.nextApplicationSequence = nextApplicationSequence;
    return restored;
  }

  static M11IdentityTable restore(List<M11IdentityBinding> bindings) {
    M11IdentityTable restored = new M11IdentityTable();
    for (M11IdentityBinding binding : new ArrayList<>(bindings)) {
      if (restored.byCommand.containsKey(binding.commandId())
          || restored.bySlot.containsKey(binding.slot())
          || restored.producerRejection(binding.slot()) != null
          || binding.result().applicationSequence() != restored.byApplication.size() + 1L) {
        throw new IllegalArgumentException(
            "snapshot identity table is duplicated or discontinuous");
      }
      restored.byCommand.put(binding.commandId(), binding);
      restored.bySlot.put(binding.slot(), binding);
      restored.byApplication.put(binding.result().applicationSequence(), binding);
      restored.producers.put(
          ProducerKey.from(binding.slot()),
          new ProducerCursor(
              binding.slot().producerEpoch(),
              Math.incrementExact(binding.slot().producerSequence())));
      restored.nextApplicationSequence = Math.incrementExact(restored.nextApplicationSequence);
    }
    return restored;
  }

  private String producerRejection(Slot slot) {
    ProducerCursor cursor = producers.get(ProducerKey.from(slot));
    if (cursor == null) {
      return slot.producerSequence() == 1
          ? null
          : PreflightRejectionCode.PRODUCER_EPOCH_MUST_START_AT_ONE.name();
    }
    if (slot.producerEpoch() < cursor.currentEpoch()) {
      return PreflightRejectionCode.PRODUCER_EPOCH_FENCED.name();
    }
    if (slot.producerEpoch() > cursor.currentEpoch()) {
      return slot.producerSequence() == 1
          ? null
          : PreflightRejectionCode.PRODUCER_EPOCH_MUST_START_AT_ONE.name();
    }
    if (cursor.nextSequence() == Long.MAX_VALUE) {
      return PreflightRejectionCode.PRODUCER_SEQUENCE_EXHAUSTED.name();
    }
    if (slot.producerSequence() == cursor.nextSequence()) {
      return null;
    }
    return slot.producerSequence() > cursor.nextSequence()
        ? PreflightRejectionCode.PRODUCER_SEQUENCE_GAP.name()
        : PreflightRejectionCode.PRODUCER_SEQUENCE_STALE.name();
  }

  private static Rejected rejected(PreflightRejectionCode code) {
    return new Rejected(code.name());
  }

  sealed interface Decision permits New, Duplicate, Rejected {}

  enum New implements Decision {
    INSTANCE
  }

  record Duplicate(M11IdentityBinding binding) implements Decision {}

  record Rejected(String code) implements Decision {}

  private record ProducerKey(String producerId, long shardId) {
    static ProducerKey from(Slot slot) {
      return new ProducerKey(slot.producerId(), slot.shardId());
    }
  }

  private record ProducerCursor(long currentEpoch, long nextSequence) {}
}
