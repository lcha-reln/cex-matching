package io.github.lchareln.cex.matching.testkit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * M02 source gate: preserve M01 determinism while proving the addressable lifecycle surface exists.
 */
final class M02ArchitectureGate {
  Report verify(Path root) {
    M01ArchitectureGate.Report inherited = new M01ArchitectureGate().verify(root);
    List<String> violations = new ArrayList<>(inherited.violations());
    requireSource(
        root,
        "matching-core/src/main/java/io/github/lchareln/cex/matching/CancelOrderInput.java",
        "record CancelOrderInput",
        violations);
    requireSource(
        root,
        "matching-core/src/main/java/io/github/lchareln/cex/matching/CancelOrder.java",
        "record CancelOrder",
        violations);
    requireSource(
        root,
        "matching-core/src/main/java/io/github/lchareln/cex/matching/SingleInstrumentMatchingEngine.java",
        "ExecutionBatch cancel(CancelOrderInput input)",
        violations);
    requireSource(
        root,
        "matching-core/src/main/java/io/github/lchareln/cex/matching/SingleInstrumentMatchingEngine.java",
        "ordersById",
        violations);
    requireSource(
        root,
        "matching-core/src/main/java/io/github/lchareln/cex/matching/MatchingEvent.java",
        "record Canceled(",
        violations);
    return new Report(inherited.sourceFiles(), List.copyOf(violations));
  }

  private static void requireSource(
      Path root, String relative, String required, List<String> violations) {
    Path source = root.resolve(relative);
    try {
      if (!Files.readString(source, StandardCharsets.UTF_8).contains(required)) {
        violations.add(relative + " lacks required M02 surface: " + required);
      }
    } catch (IOException exception) {
      violations.add(relative + " cannot be read");
    }
  }

  record Report(int sourceFiles, List<String> violations) {
    Report {
      violations = List.copyOf(violations);
    }

    boolean passed() {
      return violations.isEmpty();
    }
  }
}
