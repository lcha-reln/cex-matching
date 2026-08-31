package io.github.lchareln.cex.matching.testkit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Enforces the infrastructure-free production core and independent-reference boundary for M06. */
public final class M06ArchitectureGate {
  private static final List<String> CORE_FORBIDDEN =
      List.of(
          "import java.io.",
          "import java.net.",
          "import java.nio.file.",
          "import java.sql.",
          "import java.time.",
          "import java.util.Random",
          "import java.util.UUID",
          "import java.util.concurrent.",
          "import io.aeron.",
          "import org.agrona.",
          "io.github.lchareln.cex.matching.reference",
          "io.github.lchareln.cex.matching.testkit",
          "System.currentTimeMillis(",
          "System.nanoTime(",
          "Thread.");
  private static final List<String> REFERENCE_FORBIDDEN =
      List.of(
          "import io.github.lchareln.cex.matching.",
          "import io.github.lchareln.cex.matching.testkit.",
          "import io.aeron.",
          "import org.agrona.",
          "import java.io.",
          "import java.net.",
          "import java.nio.file.",
          "import java.sql.",
          "import java.time.",
          "import java.util.concurrent.",
          "System.currentTimeMillis(",
          "System.nanoTime(",
          "Thread.");
  private static final List<String> REQUIRED_CORE_TYPES =
      List.of(
          "MarketMode.java",
          "OperatorId.java",
          "ChangeMarketMode.java",
          "ChangeMarketModeRejectionCode.java",
          "ModeTransitionFence.java",
          "MassCancel.java",
          "MassCancelRejectionCode.java",
          "MassCancelEvent.java",
          "MassCancelBatch.java",
          "MassCancelFence.java");
  private static final List<String> REQUIRED_REFERENCE_TYPES =
      List.of(
          "M06LinearReferenceModel.java",
          "M06ReferenceCommand.java",
          "M06SemanticEvent.java",
          "M06SemanticMarketState.java",
          "M06SemanticOutcome.java");

  public Report verify(Path repositoryRoot) {
    Path root = repositoryRoot.toAbsolutePath().normalize();
    Path coreRoot = root.resolve("matching-core/src/main/java/io/github/lchareln/cex/matching");
    Path referenceRoot =
        root.resolve("matching-reference/src/main/java/io/github/lchareln/cex/matching/reference");
    List<Path> core = javaSources(coreRoot);
    List<Path> reference = javaSources(referenceRoot);
    List<String> violations = new ArrayList<>();
    scan(root, core, CORE_FORBIDDEN, violations);
    scan(root, reference, REFERENCE_FORBIDDEN, violations);
    REQUIRED_CORE_TYPES.forEach(
        name -> requireFile(coreRoot.resolve(name), "missing M06 core type " + name, violations));
    REQUIRED_REFERENCE_TYPES.forEach(
        name ->
            requireFile(
                referenceRoot.resolve(name), "missing M06 reference type " + name, violations));
    verifyBuildFiles(root, violations);
    return new Report(
        violations.isEmpty(),
        relative(root, core),
        relative(root, reference),
        List.copyOf(violations));
  }

  private static List<Path> javaSources(Path directory) {
    if (!Files.isDirectory(directory)) {
      return List.of();
    }
    try (var paths = Files.walk(directory)) {
      return paths
          .filter(
              path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".java"))
          .sorted(Comparator.comparing(Path::toString))
          .toList();
    } catch (IOException failure) {
      throw new IllegalStateException("cannot inspect M06 source tree", failure);
    }
  }

  private static void scan(
      Path root, List<Path> sources, List<String> forbidden, List<String> violations) {
    if (sources.isEmpty()) {
      violations.add("source module is empty");
    }
    for (Path source : sources) {
      String text = read(source);
      for (String token : forbidden) {
        if (text.contains(token)) {
          violations.add(root.relativize(source) + " contains forbidden token " + token);
        }
      }
    }
  }

  private static void verifyBuildFiles(Path root, List<String> violations) {
    String coreBuild = read(root.resolve("matching-core/build.gradle.kts"));
    if (coreBuild.contains("project(") || coreBuild.contains("implementation(")) {
      violations.add("matching-core declares a production dependency");
    }
    String referenceBuild = read(root.resolve("matching-reference/build.gradle.kts"));
    if (referenceBuild.contains("project(")) {
      violations.add("matching-reference depends on a project module");
    }
    String testkitBuild = read(root.resolve("matching-testkit/build.gradle.kts"));
    if (!testkitBuild.contains("project(\":matching-core\")")
        || !testkitBuild.contains("project(\":matching-reference\")")) {
      violations.add("matching-testkit no longer owns both candidate adapters");
    }
    String settings = read(root.resolve("settings.gradle.kts"));
    for (String forbidden : List.of("matching-runtime", "matching-aeron", "matching-storage")) {
      if (settings.contains(forbidden)) {
        violations.add("M06 introduced forbidden infrastructure module " + forbidden);
      }
    }
  }

  private static void requireFile(Path path, String message, List<String> violations) {
    if (!Files.isRegularFile(path)) {
      violations.add(message);
    }
  }

  private static List<String> relative(Path root, List<Path> sources) {
    return sources.stream().map(root::relativize).map(Path::toString).toList();
  }

  private static String read(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot read M06 architecture input " + path, failure);
    }
  }

  public record Report(
      boolean passed,
      List<String> coreSources,
      List<String> referenceSources,
      List<String> violations) {
    public Report {
      coreSources = List.copyOf(coreSources);
      referenceSources = List.copyOf(referenceSources);
      violations = List.copyOf(violations);
    }
  }
}
