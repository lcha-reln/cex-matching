package io.github.lchareln.cex.matching.local;

import java.util.List;
import java.util.Objects;

/** Complete local-runtime state included by one M09S1 snapshot. */
record LocalRuntimeStateImage(
    CommandApplierState applierState,
    List<IdentityBindingImage> identityBindings,
    long lastWalSequence,
    long lastApplicationSequence) {
  LocalRuntimeStateImage {
    Objects.requireNonNull(applierState, "applierState");
    identityBindings = List.copyOf(identityBindings);
    if (lastWalSequence < 0 || lastApplicationSequence < 0) {
      throw new IllegalArgumentException("snapshot anchor sequences must be non-negative");
    }
    long nextApplication = applierState.matchingState().control().nextApplicationSequence().value();
    if (nextApplication != Math.incrementExact(lastApplicationSequence)) {
      throw new IllegalArgumentException("snapshot application anchor disagrees with core state");
    }
    if (identityBindings.isEmpty() != (lastWalSequence == 0)) {
      throw new IllegalArgumentException("snapshot identity bindings disagree with WAL anchor");
    }
    if (!identityBindings.isEmpty()) {
      IdentityBindingImage last = identityBindings.getLast();
      if (last.position().walSequence() != lastWalSequence
          || last.position().applicationSequence() != lastApplicationSequence) {
        throw new IllegalArgumentException("last identity binding disagrees with snapshot anchor");
      }
    }
  }
}
