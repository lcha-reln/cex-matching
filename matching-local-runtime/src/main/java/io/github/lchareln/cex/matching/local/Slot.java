package io.github.lchareln.cex.matching.local;

import java.util.Objects;

/** Durable producer position bound bidirectionally to one command identity and payload hash. */
public record Slot(String producerId, long producerEpoch, long shardId, long producerSequence) {
  public Slot {
    Objects.requireNonNull(producerId, "producerId");
    if (producerId.isBlank() || producerId.length() > 128) {
      throw new IllegalArgumentException("producerId must contain 1 to 128 non-blank characters");
    }
    if (producerEpoch <= 0 || shardId <= 0 || producerSequence <= 0) {
      throw new IllegalArgumentException("epoch, shard, and producer sequence must be positive");
    }
  }
}
