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

  public DirectM11MatchingRuntime() {
    this(new DeterministicMatchingAdapter(), new M11IdentityTable());
  }

  private DirectM11MatchingRuntime(
      DeterministicMatchingAdapter matcher, M11IdentityTable identities) {
    this.matcher = Objects.requireNonNull(matcher, "matcher");
    this.identities = Objects.requireNonNull(identities, "identities");
  }

  public M11ApplicationResult submit(M11CommandRequest request) {
    Objects.requireNonNull(request, "request");
    M11IdentityTable.Decision decision = identities.preflight(request.envelope());
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
      case M11IdentityTable.New ignored -> applyNew(request);
    };
  }

  public long nextApplicationSequence() {
    return matcher.nextApplicationSequence();
  }

  public String semanticStateDigest() {
    return matcher.semanticStateDigest();
  }

  public M11RuntimeState stateImage() {
    return new M11RuntimeState(matcher.stateImage(), identities.bindings());
  }

  public static DirectM11MatchingRuntime restore(M11RuntimeState state) {
    Objects.requireNonNull(state, "state");
    DeterministicMatchingAdapter matcher =
        DeterministicMatchingAdapter.restore(state.commandState());
    M11IdentityTable identities;
    try {
      identities = M11IdentityTable.restore(state.identityBindings());
    } catch (IllegalArgumentException failure) {
      throw new IllegalArgumentException("cannot restore M11 identity table", failure);
    }
    if (matcher.nextApplicationSequence() != state.nextApplicationSequence()
        || !matcher.semanticStateDigest().equals(state.commandState().semanticStateDigest())) {
      throw new IllegalArgumentException("restored command state disagrees with its snapshot");
    }
    return new DirectM11MatchingRuntime(matcher, identities);
  }

  private M11ApplicationResult applyNew(M11CommandRequest request) {
    CanonicalResult result = matcher.apply(request.command());
    identities.commit(request.envelope(), result);
    return new M11ApplicationResult(
        M11CommandResponse.applied(request, result), Optional.of(result));
  }
}
