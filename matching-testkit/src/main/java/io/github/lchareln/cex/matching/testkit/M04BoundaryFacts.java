package io.github.lchareln.cex.matching.testkit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Auditable source identities for boundary tests that sit outside the small generated domain. */
final class M04BoundaryFacts {
  Result verify(Path root) {
    String coreTest =
        read(
            root.resolve(
                "matching-core/src/test/java/io/github/lchareln/cex/matching/SingleInstrumentExecutionPolicyTest.java"));
    String referenceTest =
        read(
            root.resolve(
                "matching-reference/src/test/java/io/github/lchareln/cex/matching/reference/M04LinearReferenceModelTest.java"));
    String variants = "{\"gtc\", \"Gtc\", \" GTC\", \"GTC \"}";
    require(coreTest.contains(variants), "core exact raw policy variants changed");
    require(referenceTest.contains(variants), "reference exact raw policy variants changed");
    require(
        coreTest.contains("fokPreflightSpansLevelsWithoutOverflowAndThenFillsExactly")
            && coreTest.contains("Long.MAX_VALUE"),
        "core Long.MAX FOK deduction boundary changed");
    require(
        referenceTest.contains("perOrderDemandDeductionDoesNotOverflowAtLongMaximumDepth")
            && referenceTest.contains("MAXIMUM"),
        "reference Long.MAX FOK deduction boundary changed");
    String engine =
        read(
            root.resolve(
                "matching-core/src/main/java/io/github/lchareln/cex/matching/SingleInstrumentMatchingEngine.java"));
    String event =
        read(
            root.resolve(
                "matching-core/src/main/java/io/github/lchareln/cex/matching/MatchingEvent.java"));
    require(
        engine.contains("ExecutionBatch place(PlaceLimitOrderInput input)"),
        "legacy five-field place entrypoint changed");
    require(
        event.contains("Accepted(") && event.contains("ExecutionPolicy.GTC"),
        "five-argument Accepted GTC compatibility constructor changed");
    return new Result(
        4,
        2,
        2,
        List.of("gtc", "Gtc", " GTC", "GTC "),
        List.of("matching-core", "matching-reference"));
  }

  private static String read(Path path) {
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException("cannot read " + path, exception);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }

  record Result(
      int exactRawPolicyVariants,
      int exactRawPolicyPaths,
      int longMaxFokDeductionPaths,
      List<String> rejectedRawPolicies,
      List<String> paths) {
    Result {
      rejectedRawPolicies = List.copyOf(rejectedRawPolicies);
      paths = List.copyOf(paths);
    }
  }
}
