package io.github.lchareln.cex.matching.cluster;

import io.github.lchareln.cex.matching.local.CanonicalResult;
import io.github.lchareln.cex.matching.local.M08Envelope;
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

  Decision preflight(M08Envelope envelope) {
    Objects.requireNonNull(envelope, "envelope");
    M11IdentityBinding commandBinding = byCommand.get(envelope.commandId());
    if (commandBinding != null) {
      return sameBinding(commandBinding, envelope)
          ? new Duplicate(commandBinding)
          : new Rejected("COMMAND_ID_CONFLICT");
    }
    M11IdentityBinding slotBinding = bySlot.get(envelope.slot());
    if (slotBinding != null) {
      return sameBinding(slotBinding, envelope)
          ? new Duplicate(slotBinding)
          : new Rejected("SLOT_CONFLICT");
    }
    String producerRejection = producerRejection(envelope.slot());
    return producerRejection == null ? New.INSTANCE : new Rejected(producerRejection);
  }

  void commit(M08Envelope envelope, CanonicalResult result) {
    if (preflight(envelope) != New.INSTANCE) {
      throw new IllegalStateException("identity must be new at commit");
    }
    long expectedApplication = byApplication.size() + 1L;
    if (result.applicationSequence() != expectedApplication) {
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
  }

  List<M11IdentityBinding> bindings() {
    return List.copyOf(byApplication.values());
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
    }
    return restored;
  }

  private String producerRejection(Slot slot) {
    ProducerCursor cursor = producers.get(ProducerKey.from(slot));
    if (cursor == null) {
      return slot.producerSequence() == 1 ? null : "PRODUCER_SEQUENCE_GAP";
    }
    if (slot.producerEpoch() < cursor.currentEpoch()) {
      return "PRODUCER_EPOCH_FENCED";
    }
    if (slot.producerEpoch() == cursor.currentEpoch()) {
      return slot.producerSequence() == cursor.nextSequence() ? null : "PRODUCER_SEQUENCE_GAP";
    }
    return slot.producerSequence() == 1 ? null : "PRODUCER_SEQUENCE_GAP";
  }

  private static boolean sameBinding(M11IdentityBinding binding, M08Envelope envelope) {
    return binding.commandId().equals(envelope.commandId())
        && binding.slot().equals(envelope.slot())
        && binding.payloadHash().equals(envelope.payloadHash());
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
