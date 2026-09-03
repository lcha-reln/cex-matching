package io.github.lchareln.cex.matching.cluster;

import io.github.lchareln.cex.matching.local.CanonicalResult;
import io.github.lchareln.cex.matching.local.DeterministicMatchingAdapter;
import java.util.Objects;
import java.util.Optional;

/**
 * Direct deterministic adapter used as the semantic oracle for the Cluster path.
 *
 * <p>It owns no thread, clock, network, directory, or standalone WAL.
 */
public final class DirectM11MatchingRuntime {
  private final DeterministicMatchingAdapter matcher;
  private final M11IdentityTable identities;
  private final M11FaultPolicy faultPolicy;
  private boolean unboundApplication;

  public DirectM11MatchingRuntime() {
    this(M11FaultPolicy.none());
  }

  public DirectM11MatchingRuntime(M11FaultPolicy faultPolicy) {
    this(
        new DeterministicMatchingAdapter(),
        new M11IdentityTable(),
        Objects.requireNonNull(faultPolicy, "faultPolicy"));
  }

  private DirectM11MatchingRuntime(
      DeterministicMatchingAdapter matcher,
      M11IdentityTable identities,
      M11FaultPolicy faultPolicy) {
    this.matcher = Objects.requireNonNull(matcher, "matcher");
    this.identities = Objects.requireNonNull(identities, "identities");
    this.faultPolicy = Objects.requireNonNull(faultPolicy, "faultPolicy");
  }

  public M11ApplicationResult submit(M11CommandRequest request) {
    return submit(request, "");
  }

  public M11ApplicationResult submit(M11CommandRequest request, String transportSession) {
    Objects.requireNonNull(request, "request");
    M11CommandRequest effective = faultPolicy.transportIdentity(request, transportSession);
    M11IdentityTable.Decision decision = identities.preflight(effective.envelope());
    return switch (decision) {
      case M11IdentityTable.Duplicate duplicate -> {
        CanonicalResult original = duplicate.binding().result();
        yield new M11ApplicationResult(
            M11CommandResponse.duplicate(request, original), Optional.of(original));
      }
      case M11IdentityTable.Rejected rejected ->
          new M11ApplicationResult(
              M11CommandResponse.rejected(request, rejected.code(), matcher.semanticStateDigest()),
              Optional.empty());
      case M11IdentityTable.New ignored -> applyNew(request, effective);
    };
  }

  public long nextApplicationSequence() {
    return matcher.nextApplicationSequence();
  }

  public String semanticStateDigest() {
    return matcher.semanticStateDigest();
  }

  public String exposedSemanticStateDigest(String session, java.util.UUID correlationId) {
    return faultPolicy.exposedSemanticDigest(matcher.semanticStateDigest(), session, correlationId);
  }

  public int identityBindingCount() {
    return identities.bindings().size();
  }

  public boolean hasIdentityBinding(java.util.UUID commandId) {
    return identities.containsCommand(commandId);
  }

  public boolean hasUnboundApplication() {
    return unboundApplication;
  }

  /**
   * Rebuilds a fresh runtime after process loss.
   *
   * <p>Normally this restores the last durable state. The respond-before-bind qualification fault
   * preserves the actually applied command state while exposing that its identity result was never
   * bound; it never substitutes an unrelated genesis rollback.
   */
  public DirectM11MatchingRuntime recoverAfterCrash(M11RuntimeState lastDurableState) {
    Objects.requireNonNull(lastDurableState, "lastDurableState");
    if (!unboundApplication) {
      return restore(lastDurableState, faultPolicy);
    }
    DeterministicMatchingAdapter recoveredMatcher =
        DeterministicMatchingAdapter.restore(matcher.stateImage());
    M11IdentityTable recoveredIdentities =
        M11IdentityTable.restoreAtNext(
            lastDurableState.identityBindings(), recoveredMatcher.nextApplicationSequence());
    return new DirectM11MatchingRuntime(recoveredMatcher, recoveredIdentities, faultPolicy);
  }

  public M11RuntimeState stateImage() {
    return new M11RuntimeState(matcher.stateImage(), identities.bindings());
  }

  public static DirectM11MatchingRuntime restore(M11RuntimeState state) {
    return restore(state, M11FaultPolicy.none());
  }

  public static DirectM11MatchingRuntime restore(
      M11RuntimeState state, M11FaultPolicy faultPolicy) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(faultPolicy, "faultPolicy");
    DeterministicMatchingAdapter matcher =
        DeterministicMatchingAdapter.restore(state.commandState());
    M11IdentityTable identities;
    try {
      var restoredBindings = faultPolicy.restoreIdentityBindings(state.identityBindings());
      identities =
          restoredBindings.isEmpty() && !state.identityBindings().isEmpty()
              ? M11IdentityTable.emptyAt(state.nextApplicationSequence())
              : M11IdentityTable.restore(restoredBindings);
    } catch (IllegalArgumentException failure) {
      throw new IllegalArgumentException("cannot restore M11 identity table", failure);
    }
    if (matcher.nextApplicationSequence() != state.nextApplicationSequence()
        || !matcher.semanticStateDigest().equals(state.commandState().semanticStateDigest())) {
      throw new IllegalArgumentException("restored command state disagrees with its snapshot");
    }
    return new DirectM11MatchingRuntime(matcher, identities, faultPolicy);
  }

  private M11ApplicationResult applyNew(
      M11CommandRequest externalRequest, M11CommandRequest effectiveRequest) {
    CanonicalResult result = matcher.apply(effectiveRequest.command());
    if (faultPolicy.bindBeforeResponse()) {
      identities.commit(effectiveRequest.envelope(), result);
    } else {
      unboundApplication = true;
    }
    faultPolicy.afterNewApplication(effectiveRequest);
    return new M11ApplicationResult(
        M11CommandResponse.applied(externalRequest, result), Optional.of(result));
  }
}
