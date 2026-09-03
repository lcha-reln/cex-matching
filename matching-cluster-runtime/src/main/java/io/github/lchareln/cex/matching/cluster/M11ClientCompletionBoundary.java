package io.github.lchareln.cex.matching.cluster;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Production boundary that distinguishes an accepted ingress offer from correlated completion. */
public final class M11ClientCompletionBoundary {
  public enum Source {
    INGRESS_OFFER,
    CORRELATED_EGRESS
  }

  private final M11FaultPolicy faultPolicy;

  public M11ClientCompletionBoundary() {
    this(M11FaultPolicy.none());
  }

  public M11ClientCompletionBoundary(M11FaultPolicy faultPolicy) {
    this.faultPolicy = Objects.requireNonNull(faultPolicy, "faultPolicy");
  }

  public Decision onIngressOfferAccepted(M11CommandRequest request, long offeredPosition) {
    Objects.requireNonNull(request, "request");
    if (offeredPosition < 0) {
      throw new IllegalArgumentException("offeredPosition must be accepted");
    }
    boolean complete = faultPolicy.completesOnIngressOffer();
    return new Decision(
        request.correlationId(),
        Source.INGRESS_OFFER,
        complete,
        complete ? Optional.of(M11ResponseStatus.NEW_APPLIED) : Optional.empty());
  }

  public Decision onCorrelatedEgress(M11CommandResponse response) {
    Objects.requireNonNull(response, "response");
    return new Decision(
        response.correlationId(), Source.CORRELATED_EGRESS, true, Optional.of(response.status()));
  }

  public record Decision(
      UUID correlationId,
      Source source,
      boolean businessComplete,
      Optional<M11ResponseStatus> responseStatus) {
    public Decision {
      Objects.requireNonNull(correlationId, "correlationId");
      Objects.requireNonNull(source, "source");
      responseStatus = Objects.requireNonNull(responseStatus, "responseStatus");
      if (businessComplete != responseStatus.isPresent()) {
        throw new IllegalArgumentException("completion and response status presence disagree");
      }
    }
  }
}
