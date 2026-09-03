package io.github.lchareln.cex.matching.cluster;

import io.github.lchareln.cex.matching.local.CanonicalResult;
import io.github.lchareln.cex.matching.local.Slot;
import java.util.Objects;
import java.util.UUID;

/** Complete durable command-identity binding, including the replayable original result. */
public record M11IdentityBinding(
    UUID commandId, Slot slot, String payloadHash, CanonicalResult result) {
  public M11IdentityBinding {
    Objects.requireNonNull(commandId, "commandId");
    Objects.requireNonNull(slot, "slot");
    Objects.requireNonNull(payloadHash, "payloadHash");
    if (!payloadHash.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("payloadHash must be lowercase SHA-256");
    }
    Objects.requireNonNull(result, "result");
  }
}
