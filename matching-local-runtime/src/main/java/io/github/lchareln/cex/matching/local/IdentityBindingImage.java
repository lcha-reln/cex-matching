package io.github.lchareln.cex.matching.local;

import java.util.Objects;
import java.util.UUID;

/** Canonically ordered durable identity binding retained independently of WAL retention. */
record IdentityBindingImage(
    UUID commandId, Slot slot, String payloadHash, WalPosition position, CanonicalResult result) {
  IdentityBindingImage {
    Objects.requireNonNull(commandId, "commandId");
    Objects.requireNonNull(slot, "slot");
    Objects.requireNonNull(payloadHash, "payloadHash");
    Objects.requireNonNull(position, "position");
    Objects.requireNonNull(result, "result");
    if (!payloadHash.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("payloadHash must be lowercase SHA-256");
    }
    if (position.applicationSequence() != result.applicationSequence()) {
      throw new IllegalArgumentException(
          "binding position and result application sequence disagree");
    }
  }
}
