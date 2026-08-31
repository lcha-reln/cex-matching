package io.github.lchareln.cex.matching.local;

import java.util.ArrayList;
import java.util.List;

final class TestCommandApplier implements CommandApplier {
  private final List<M08Command> applied = new ArrayList<>();
  private final List<String> trace;
  private long nextApplicationSequence = 1;

  TestCommandApplier() {
    this(new ArrayList<>());
  }

  TestCommandApplier(List<String> trace) {
    this.trace = trace;
  }

  @Override
  public boolean supports(M08Command command) {
    return true;
  }

  @Override
  public long nextApplicationSequence() {
    return nextApplicationSequence;
  }

  @Override
  public CanonicalResult apply(M08Command command) {
    trace.add("APPLY");
    applied.add(command);
    long sequence = nextApplicationSequence;
    nextApplicationSequence = Math.incrementExact(nextApplicationSequence);
    return CanonicalResult.create(
        "TEST",
        sequence,
        List.of(command.getClass().getSimpleName() + ":" + command),
        "applied=" + applied.size(),
        semanticStateDigest());
  }

  @Override
  public String semanticStateDigest() {
    return CanonicalResult.semanticDigest("test", applied.toString());
  }

  List<M08Command> applied() {
    return List.copyOf(applied);
  }
}
