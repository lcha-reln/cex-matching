package io.github.lchareln.cex.matching.local;

import java.util.Objects;

/**
 * Infrastructure-free command adapter shared by direct execution and replicated runtimes.
 *
 * <p>This adapter never opens a WAL, performs I/O, or owns a thread. Its caller is solely
 * responsible for ordering and durability.
 */
public final class DeterministicMatchingAdapter {
  private MatchingCoreCommandApplier delegate;

  public DeterministicMatchingAdapter() {
    delegate = new MatchingCoreCommandApplier();
  }

  private DeterministicMatchingAdapter(MatchingCoreCommandApplier delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
  }

  public long nextApplicationSequence() {
    return delegate.nextApplicationSequence();
  }

  public CanonicalResult apply(M08Command command) {
    return delegate.apply(Objects.requireNonNull(command, "command"));
  }

  public String semanticStateDigest() {
    return delegate.semanticStateDigest();
  }

  public CommandApplierState stateImage() {
    return delegate.stateImage();
  }

  public static DeterministicMatchingAdapter restore(CommandApplierState state) {
    Objects.requireNonNull(state, "state");
    MatchingCoreCommandApplier seed = new MatchingCoreCommandApplier();
    return new DeterministicMatchingAdapter((MatchingCoreCommandApplier) seed.restore(state));
  }
}
