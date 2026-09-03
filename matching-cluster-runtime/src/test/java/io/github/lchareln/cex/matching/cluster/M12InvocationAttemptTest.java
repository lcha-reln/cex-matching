package io.github.lchareln.cex.matching.cluster;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lchareln.cex.matching.local.M08Command;
import java.math.BigInteger;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class M12InvocationAttemptTest {
  private static final long SHARD = 97;
  private static final String EMPTY_DIGEST = "0".repeat(64);
  private static final M12TransportAuthority AUTHORITY = new M12TransportAuthority(1, 101, 7, 0);

  @Test
  void noAcceptedOfferIsNotSubmittedButAcceptedWithoutResponseIsUnknown() throws Exception {
    M12InvocationAttempt beforeOffer = attempt(new UUID(1, 1), new UUID(97, 1), 1);
    M12InvocationOutcome notSubmitted =
        beforeOffer.finishUnacknowledged(M12UnknownReason.OFFER_TIMEOUT);

    assertEquals(M12InvocationState.NOT_SUBMITTED, notSubmitted.state());
    assertEquals(M12UnknownReason.OFFER_TIMEOUT, notSubmitted.unresolvedReason().orElseThrow());
    assertTrue(notSubmitted.acceptedPosition().isEmpty());

    M12InvocationAttempt afterOffer = attempt(new UUID(1, 2), new UUID(97, 2), 2);
    afterOffer.onOfferAccepted(42, AUTHORITY);
    M12InvocationOutcome unknown =
        afterOffer.finishUnacknowledged(M12UnknownReason.RESPONSE_TIMEOUT);

    assertEquals(M12InvocationState.UNKNOWN, unknown.state());
    assertEquals(42, unknown.acceptedPosition().orElseThrow());
    assertEquals(AUTHORITY, unknown.acceptedAuthority().orElseThrow());
    assertTrue(unknown.response().isEmpty());
  }

  @Test
  void bufferedBusinessRejectionIsAcknowledgedOnlyAtExplicitDeliveryBoundary() throws Exception {
    M12InvocationAttempt attempt = attempt(new UUID(2, 1), new UUID(97, 3), 1);
    attempt.onOfferAccepted(81, AUTHORITY);
    M11CommandResponse rejected =
        M11CommandResponse.rejected(attempt.request(), "ORDER_NOT_FOUND", EMPTY_DIGEST);

    attempt.onResponse(rejected, AUTHORITY);

    assertEquals(M12InvocationPhase.RESPONSE_BUFFERED, attempt.phase());
    assertTrue(attempt.responseBuffered());
    assertTrue(attempt.outcome().isEmpty());

    M12InvocationOutcome outcome = attempt.acknowledgeBuffered();
    assertEquals(M12InvocationState.ACKNOWLEDGED, outcome.state());
    assertEquals(M11ResponseStatus.REJECTED, outcome.response().orElseThrow().status());
    assertEquals(AUTHORITY, outcome.completionAuthority().orElseThrow());
    assertTrue(outcome.unresolvedReason().isEmpty());
  }

  @Test
  void abandoningAfterDecodeDoesNotTurnUnobservedResponseIntoAcknowledgement() throws Exception {
    M12InvocationAttempt attempt = attempt(new UUID(3, 1), new UUID(97, 4), 1);
    attempt.onOfferAccepted(91, AUTHORITY);
    attempt.onResponse(
        M11CommandResponse.rejected(attempt.request(), "ORDER_NOT_FOUND", EMPTY_DIGEST), AUTHORITY);

    M12InvocationOutcome outcome = attempt.abandon();

    assertEquals(M12InvocationState.UNKNOWN, outcome.state());
    assertEquals(M12UnknownReason.ABANDONED, outcome.unresolvedReason().orElseThrow());
    assertTrue(outcome.response().isEmpty());
    assertThrows(IllegalStateException.class, attempt::acknowledgeBuffered);
  }

  @Test
  void sameIdentityRetryChangesOnlyInvocationMetadata() throws Exception {
    M12InvocationAttempt original = attempt(new UUID(4, 1), new UUID(97, 5), 4);
    original.onOfferAccepted(101, AUTHORITY);
    original.finishUnacknowledged(M12UnknownReason.LEADER_CHANGED);

    UUID retryCorrelation = new UUID(4, 2);
    M12InvocationAttempt retry = original.retry(retryCorrelation, 5, 2);

    assertEquals(retryCorrelation, retry.correlationId());
    assertNotEquals(original.correlationId(), retry.correlationId());
    assertEquals(5, retry.attemptOrdinal());
    assertEquals(2, retry.clientGeneration());
    assertEquals(M12InvocationPhase.OFFERING, retry.phase());
    assertTrue(original.sameDurableIdentity(retry));
    assertArrayEquals(original.durableEnvelopeBytes(), retry.durableEnvelopeBytes());
    assertEquals(original.canonicalEnvelopeSha256(), retry.canonicalEnvelopeSha256());
    assertEquals(original.commandId(), retry.commandId());
    assertEquals(original.slot(), retry.slot());
    assertEquals(original.payloadHash(), retry.payloadHash());
  }

  @Test
  void retryRequiresUnknownFreshCorrelationAndIncreasingOrdinal() throws Exception {
    M12InvocationAttempt notSubmitted = attempt(new UUID(5, 1), new UUID(97, 6), 1);
    notSubmitted.finishUnacknowledged(M12UnknownReason.OFFER_TIMEOUT);
    assertThrows(IllegalStateException.class, () -> notSubmitted.retry(new UUID(5, 2), 2, 1));

    M12InvocationAttempt unknown = attempt(new UUID(5, 3), new UUID(97, 7), 3);
    unknown.onOfferAccepted(111, AUTHORITY);
    unknown.finishUnacknowledged(M12UnknownReason.PROCESS_EXITED);
    assertThrows(
        IllegalArgumentException.class, () -> unknown.retry(unknown.correlationId(), 4, 2));
    assertThrows(IllegalArgumentException.class, () -> unknown.retry(new UUID(5, 4), 3, 2));
  }

  @Test
  void wrongCommandOrStaleAuthorityCanNeverAcknowledge() throws Exception {
    M12InvocationAttempt wrongCommand = attempt(new UUID(6, 1), new UUID(97, 8), 1);
    wrongCommand.onOfferAccepted(121, AUTHORITY);
    M11CommandRequest anotherIdentity = request(wrongCommand.correlationId(), new UUID(97, 9), 9);
    wrongCommand.onResponse(
        M11CommandResponse.rejected(anotherIdentity, "ORDER_NOT_FOUND", EMPTY_DIGEST), AUTHORITY);
    assertEquals(M12InvocationState.UNKNOWN, wrongCommand.outcome().orElseThrow().state());
    assertEquals(
        M12UnknownReason.INVALID_EGRESS,
        wrongCommand.outcome().orElseThrow().unresolvedReason().orElseThrow());

    M12InvocationAttempt staleGeneration = attempt(new UUID(6, 2), new UUID(97, 10), 2);
    staleGeneration.onOfferAccepted(131, AUTHORITY);
    M12TransportAuthority anotherGeneration = new M12TransportAuthority(2, 101, 7, 0);
    staleGeneration.onResponse(
        M11CommandResponse.rejected(staleGeneration.request(), "ORDER_NOT_FOUND", EMPTY_DIGEST),
        anotherGeneration);
    assertFalse(staleGeneration.responseBuffered());
    assertEquals(M12InvocationState.UNKNOWN, staleGeneration.outcome().orElseThrow().state());
  }

  @Test
  void transportAuthorityComparisonIsScopedToOneClientGeneration() {
    M12TransportAuthority later = new M12TransportAuthority(1, 102, 8, 2);
    assertTrue(later.isLaterTermThan(AUTHORITY));
    assertThrows(
        IllegalArgumentException.class,
        () -> later.isLaterTermThan(new M12TransportAuthority(2, 102, 6, 1)));
  }

  private static M12InvocationAttempt attempt(UUID correlation, UUID commandId, long sequence)
      throws M11ProtocolException {
    return M12InvocationAttempt.first(request(correlation, commandId, sequence), sequence, 1);
  }

  private static M11CommandRequest request(UUID correlation, UUID commandId, long sequence)
      throws M11ProtocolException {
    return new M11RequestCodec()
        .create(
            2,
            2,
            correlation,
            "m12-invocation",
            1,
            SHARD,
            sequence,
            commandId,
            new M08Command.Cancel("BTC-USDT", BigInteger.valueOf(sequence)));
  }
}
