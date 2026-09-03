package io.github.lchareln.cex.matching.cluster;

import io.github.lchareln.cex.matching.local.CommandApplierState;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Complete application state stored in an Aeron Cluster snapshot. */
public record M11RuntimeState(
    CommandApplierState commandState, List<M11IdentityBinding> identityBindings) {
  public M11RuntimeState {
    Objects.requireNonNull(commandState, "commandState");
    identityBindings = List.copyOf(identityBindings);
    long expectedApplication = 1;
    Set<UUID> commands = new HashSet<>();
    Set<io.github.lchareln.cex.matching.local.Slot> slots = new HashSet<>();
    Map<ProducerKey, ProducerCursor> producers = new HashMap<>();
    for (M11IdentityBinding binding : identityBindings) {
      Objects.requireNonNull(binding, "identity binding");
      if (binding.result().applicationSequence() != expectedApplication) {
        throw new IllegalArgumentException("identity table is not in application-sequence order");
      }
      if (!commands.add(binding.commandId()) || !slots.add(binding.slot())) {
        throw new IllegalArgumentException("identity table contains a duplicate identity");
      }
      ProducerKey producerKey =
          new ProducerKey(binding.slot().producerId(), binding.slot().shardId());
      ProducerCursor cursor = producers.get(producerKey);
      if (cursor == null) {
        if (binding.slot().producerSequence() != 1) {
          throw new IllegalArgumentException("producer history must begin at sequence one");
        }
      } else if (binding.slot().producerEpoch() < cursor.epoch()
          || (binding.slot().producerEpoch() == cursor.epoch()
              && binding.slot().producerSequence() != cursor.nextSequence())
          || (binding.slot().producerEpoch() > cursor.epoch()
              && binding.slot().producerSequence() != 1)) {
        throw new IllegalArgumentException("producer history is fenced or discontinuous");
      }
      producers.put(
          producerKey,
          new ProducerCursor(
              binding.slot().producerEpoch(),
              Math.incrementExact(binding.slot().producerSequence())));
      expectedApplication = Math.incrementExact(expectedApplication);
    }
    if (commandState.matchingState().control().nextApplicationSequence().value()
        != expectedApplication) {
      throw new IllegalArgumentException("command state and identity table position disagree");
    }
  }

  public long nextApplicationSequence() {
    return commandState.matchingState().control().nextApplicationSequence().value();
  }

  private record ProducerKey(String producerId, long shardId) {}

  private record ProducerCursor(long epoch, long nextSequence) {}
}
