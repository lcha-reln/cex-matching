package io.github.lchareln.cex.matching.cluster;

import io.github.lchareln.cex.matching.local.Slot;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Single-owner state machine for one transport attempt of a durable M08 command identity.
 *
 * <p>A decoded response is first buffered. It becomes an acknowledgement only when the owner
 * crosses {@link #acknowledgeBuffered()}; abandoning before that boundary remains UNKNOWN.
 */
public final class M12InvocationAttempt {
  private final M11CommandRequest request;
  private final byte[] durableEnvelopeBytes;
  private final String canonicalEnvelopeSha256;
  private final long attemptOrdinal;
  private final long clientGeneration;

  private M12InvocationPhase phase = M12InvocationPhase.OFFERING;
  private OptionalLong acceptedPosition = OptionalLong.empty();
  private M12TransportAuthority acceptedAuthority;
  private M11CommandResponse bufferedResponse;
  private M12TransportAuthority bufferedAuthority;
  private M12InvocationOutcome terminalOutcome;

  private M12InvocationAttempt(
      M11CommandRequest request, long attemptOrdinal, long clientGeneration) {
    this.request = Objects.requireNonNull(request, "request");
    if (request.protocolVersion() != M11RequestCodec.CURRENT_VERSION
        || request.requestedResponseVersion() != M11ResponseCodec.CURRENT_VERSION) {
      throw new IllegalArgumentException("M12 requires request and response protocol v2");
    }
    if (attemptOrdinal <= 0 || clientGeneration <= 0) {
      throw new IllegalArgumentException("attemptOrdinal and clientGeneration must be positive");
    }
    this.attemptOrdinal = attemptOrdinal;
    this.clientGeneration = clientGeneration;
    durableEnvelopeBytes = request.envelopeBytes();
    canonicalEnvelopeSha256 = M11Digests.sha256Hex(durableEnvelopeBytes);
  }

  public static M12InvocationAttempt first(
      M11CommandRequest request, long attemptOrdinal, long clientGeneration) {
    return new M12InvocationAttempt(request, attemptOrdinal, clientGeneration);
  }

  /**
   * Creates a new invocation correlation while preserving every durable envelope byte.
   *
   * <p>Only an UNKNOWN attempt needs this recovery operation. The client generation may stay the
   * same or advance, but the invocation ordinal and correlation must advance.
   */
  public M12InvocationAttempt retry(
      UUID freshCorrelationId, long nextAttemptOrdinal, long nextClientGeneration) {
    Objects.requireNonNull(freshCorrelationId, "freshCorrelationId");
    M12InvocationOutcome completed = requireTerminal();
    if (completed.state() != M12InvocationState.UNKNOWN) {
      throw new IllegalStateException("only UNKNOWN can be resolved by same-identity retry");
    }
    if (request.correlationId().equals(freshCorrelationId)) {
      throw new IllegalArgumentException("retry correlationId must be fresh");
    }
    if (nextAttemptOrdinal <= attemptOrdinal) {
      throw new IllegalArgumentException("retry attemptOrdinal must increase");
    }
    M12InvocationAttempt retry =
        new M12InvocationAttempt(
            request.withCorrelationId(freshCorrelationId),
            nextAttemptOrdinal,
            nextClientGeneration);
    if (!Arrays.equals(durableEnvelopeBytes, retry.durableEnvelopeBytes)
        || !request.commandId().equals(retry.commandId())
        || !request.slot().equals(retry.slot())
        || !request.payloadHash().equals(retry.payloadHash())) {
      throw new IllegalStateException("retry changed durable command identity");
    }
    return retry;
  }

  public M11CommandRequest request() {
    return request;
  }

  public UUID correlationId() {
    return request.correlationId();
  }

  public UUID commandId() {
    return request.commandId();
  }

  public Slot slot() {
    return request.slot();
  }

  public String payloadHash() {
    return request.payloadHash();
  }

  public byte[] durableEnvelopeBytes() {
    return durableEnvelopeBytes.clone();
  }

  public String canonicalEnvelopeSha256() {
    return canonicalEnvelopeSha256;
  }

  public long attemptOrdinal() {
    return attemptOrdinal;
  }

  public long clientGeneration() {
    return clientGeneration;
  }

  public M12InvocationPhase phase() {
    return phase;
  }

  public boolean offerAccepted() {
    return acceptedPosition.isPresent();
  }

  public OptionalLong acceptedPosition() {
    return acceptedPosition;
  }

  public Optional<M12TransportAuthority> acceptedAuthority() {
    return Optional.ofNullable(acceptedAuthority);
  }

  public boolean responseBuffered() {
    return phase == M12InvocationPhase.RESPONSE_BUFFERED;
  }

  public Optional<M11CommandResponse> bufferedResponse() {
    return Optional.ofNullable(bufferedResponse);
  }

  public Optional<M12TransportAuthority> bufferedAuthority() {
    return Optional.ofNullable(bufferedAuthority);
  }

  public Optional<M12InvocationOutcome> outcome() {
    return Optional.ofNullable(terminalOutcome);
  }

  public boolean sameDurableIdentity(M12InvocationAttempt other) {
    Objects.requireNonNull(other, "other");
    return commandId().equals(other.commandId())
        && slot().equals(other.slot())
        && payloadHash().equals(other.payloadHash())
        && Arrays.equals(durableEnvelopeBytes, other.durableEnvelopeBytes);
  }

  /** Crosses the only boundary that can yield ACKNOWLEDGED, including a business rejection. */
  public M12InvocationOutcome acknowledgeBuffered() {
    if (phase != M12InvocationPhase.RESPONSE_BUFFERED) {
      throw new IllegalStateException("no current-authority response is buffered");
    }
    terminalOutcome =
        outcome(
            M12InvocationState.ACKNOWLEDGED,
            Optional.of(bufferedAuthority),
            Optional.of(bufferedResponse),
            Optional.empty());
    phase = M12InvocationPhase.TERMINAL;
    return terminalOutcome;
  }

  /** Ends an unobserved invocation at the acceptance-dependent conservative boundary. */
  public M12InvocationOutcome abandon() {
    return finishUnacknowledged(M12UnknownReason.ABANDONED);
  }

  void onOfferAccepted(long position, M12TransportAuthority authority) {
    requirePhase(M12InvocationPhase.OFFERING);
    if (position < 0) {
      throw new IllegalArgumentException("accepted Aeron offer position must be non-negative");
    }
    requireGeneration(authority);
    acceptedPosition = OptionalLong.of(position);
    acceptedAuthority = authority;
    phase = M12InvocationPhase.OFFER_ACCEPTED;
  }

  void onResponse(M11CommandResponse response, M12TransportAuthority authority) {
    Objects.requireNonNull(response, "response");
    Objects.requireNonNull(authority, "authority");
    if (phase == M12InvocationPhase.TERMINAL) {
      return;
    }
    if (phase != M12InvocationPhase.OFFER_ACCEPTED
        || authority.clientGeneration() != clientGeneration
        || !authority.equals(acceptedAuthority)
        || !response.correlationId().equals(correlationId())
        || response.protocolVersion() != M11ResponseCodec.CURRENT_VERSION
        || response.commandId().isEmpty()
        || !response.commandId().orElseThrow().equals(commandId())) {
      finishUnacknowledged(M12UnknownReason.INVALID_EGRESS);
      return;
    }
    bufferedResponse = response;
    bufferedAuthority = authority;
    phase = M12InvocationPhase.RESPONSE_BUFFERED;
  }

  M12InvocationOutcome finishUnacknowledged(M12UnknownReason reason) {
    Objects.requireNonNull(reason, "reason");
    if (phase == M12InvocationPhase.TERMINAL) {
      return terminalOutcome;
    }
    M12InvocationState state =
        acceptedPosition.isPresent()
            ? M12InvocationState.UNKNOWN
            : M12InvocationState.NOT_SUBMITTED;
    bufferedResponse = null;
    bufferedAuthority = null;
    terminalOutcome = outcome(state, Optional.empty(), Optional.empty(), Optional.of(reason));
    phase = M12InvocationPhase.TERMINAL;
    return terminalOutcome;
  }

  private M12InvocationOutcome requireTerminal() {
    if (terminalOutcome == null) {
      throw new IllegalStateException("invocation attempt is not terminal");
    }
    return terminalOutcome;
  }

  private M12InvocationOutcome outcome(
      M12InvocationState state,
      Optional<M12TransportAuthority> completionAuthority,
      Optional<M11CommandResponse> response,
      Optional<M12UnknownReason> reason) {
    return new M12InvocationOutcome(
        state,
        correlationId(),
        commandId(),
        slot(),
        payloadHash(),
        attemptOrdinal,
        clientGeneration,
        acceptedPosition,
        Optional.ofNullable(acceptedAuthority),
        completionAuthority,
        response,
        reason);
  }

  private void requireGeneration(M12TransportAuthority authority) {
    Objects.requireNonNull(authority, "authority");
    if (authority.clientGeneration() != clientGeneration) {
      throw new IllegalArgumentException("authority belongs to another client generation");
    }
  }

  private void requirePhase(M12InvocationPhase expected) {
    if (phase != expected) {
      throw new IllegalStateException("expected " + expected + " but was " + phase);
    }
  }
}
