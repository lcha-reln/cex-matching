package io.github.lchareln.cex.matching.cluster;

import io.github.lchareln.cex.matching.local.Slot;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/** Immutable terminal witness for one client invocation. */
public record M12InvocationOutcome(
    M12InvocationState state,
    UUID correlationId,
    UUID commandId,
    Slot slot,
    String payloadHash,
    long attemptOrdinal,
    long clientGeneration,
    OptionalLong acceptedPosition,
    Optional<M12TransportAuthority> acceptedAuthority,
    Optional<M12TransportAuthority> completionAuthority,
    Optional<M11CommandResponse> response,
    Optional<M12UnknownReason> unresolvedReason) {
  public M12InvocationOutcome {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(correlationId, "correlationId");
    Objects.requireNonNull(commandId, "commandId");
    Objects.requireNonNull(slot, "slot");
    Objects.requireNonNull(payloadHash, "payloadHash");
    acceptedPosition = Objects.requireNonNull(acceptedPosition, "acceptedPosition");
    acceptedAuthority = Objects.requireNonNull(acceptedAuthority, "acceptedAuthority");
    completionAuthority = Objects.requireNonNull(completionAuthority, "completionAuthority");
    response = Objects.requireNonNull(response, "response");
    unresolvedReason = Objects.requireNonNull(unresolvedReason, "unresolvedReason");
    if (!payloadHash.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("payloadHash must be lowercase SHA-256");
    }
    if (attemptOrdinal <= 0 || clientGeneration <= 0) {
      throw new IllegalArgumentException("attemptOrdinal and clientGeneration must be positive");
    }
    boolean accepted = acceptedPosition.isPresent();
    if (accepted != acceptedAuthority.isPresent()) {
      throw new IllegalArgumentException("accepted position and authority presence must agree");
    }
    if (accepted && acceptedPosition.orElseThrow() < 0) {
      throw new IllegalArgumentException("acceptedPosition must be non-negative");
    }
    if (accepted && acceptedAuthority.orElseThrow().clientGeneration() != clientGeneration) {
      throw new IllegalArgumentException("accepted authority belongs to another client generation");
    }
    switch (state) {
      case NOT_SUBMITTED -> {
        if (accepted
            || completionAuthority.isPresent()
            || response.isPresent()
            || unresolvedReason.isEmpty()) {
          throw new IllegalArgumentException("NOT_SUBMITTED outcome fields are inconsistent");
        }
      }
      case UNKNOWN -> {
        if (!accepted
            || completionAuthority.isPresent()
            || response.isPresent()
            || unresolvedReason.isEmpty()) {
          throw new IllegalArgumentException("UNKNOWN outcome fields are inconsistent");
        }
      }
      case ACKNOWLEDGED -> {
        if (!accepted
            || completionAuthority.isEmpty()
            || response.isEmpty()
            || unresolvedReason.isPresent()) {
          throw new IllegalArgumentException("ACKNOWLEDGED outcome fields are inconsistent");
        }
        M11CommandResponse acknowledgement = response.orElseThrow();
        if (!acknowledgement.correlationId().equals(correlationId)
            || acknowledgement.commandId().isEmpty()
            || !acknowledgement.commandId().orElseThrow().equals(commandId)) {
          throw new IllegalArgumentException("acknowledgement identity does not match invocation");
        }
        if (completionAuthority.orElseThrow().clientGeneration() != clientGeneration) {
          throw new IllegalArgumentException("acknowledgement came from another client generation");
        }
        if (!completionAuthority.equals(acceptedAuthority)) {
          throw new IllegalArgumentException("acknowledgement authority changed after acceptance");
        }
      }
    }
  }
}
