package io.github.lchareln.cex.matching.testkit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Source-level boundary gate for the infrastructure-free core and JDK-only local runtime. */
final class M08ArchitectureGate {
  private static final List<String> CORE_FORBIDDEN =
      List.of(
          "java.io.",
          "java.nio.file.",
          "java.net.",
          "java.sql.",
          "java.time.",
          "java.util.concurrent.",
          "io.aeron",
          "org.agrona",
          "matching.local",
          "matching.reference",
          "matching.testkit");
  private static final List<String> LOCAL_FORBIDDEN =
      List.of(
          "java.net.",
          "java.sql.",
          "java.util.concurrent.",
          "io.aeron",
          "org.agrona",
          "matching.reference",
          "matching.testkit",
          "org.springframework",
          "com.fasterxml",
          "tools.jackson");

  Report verify(Path repositoryRoot) {
    Path core = repositoryRoot.resolve("matching-core/src/main/java");
    Path local = repositoryRoot.resolve("matching-local-runtime/src/main/java");
    List<Path> coreSources = sources(core);
    List<Path> localSources = sources(local);
    List<String> violations = new ArrayList<>();
    scan(core, coreSources, CORE_FORBIDDEN, violations, "core");
    scan(local, localSources, LOCAL_FORBIDDEN, violations, "local-runtime");
    require(!localSources.isEmpty(), "M08 local runtime has no production source");
    require(
        Files.isRegularFile(repositoryRoot.resolve("matching-local-runtime/build.gradle.kts")),
        "M08 local runtime has no module build");
    String build = read(repositoryRoot.resolve("matching-local-runtime/build.gradle.kts"));
    if (!build.contains("api(project(\":matching-core\"))")) {
      violations.add("matching-local-runtime does not declare its matching-core dependency");
    }
    if (build.contains("matching-reference") || build.contains("matching-testkit")) {
      violations.add("matching-local-runtime depends on a test/reference module");
    }
    violations.sort(String::compareTo);
    return new Report(coreSources.size(), localSources.size(), List.copyOf(violations));
  }

  private static void scan(
      Path root,
      List<Path> sources,
      List<String> forbidden,
      List<String> violations,
      String boundary) {
    for (Path source : sources) {
      String text = read(source);
      for (String token : forbidden) {
        if (text.contains(token)) {
          violations.add(
              boundary + ":" + root.relativize(source).toString().replace('\\', '/') + ":" + token);
        }
      }
    }
  }

  private static List<Path> sources(Path root) {
    try (var paths = Files.walk(root)) {
      return paths
          .filter(
              path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".java"))
          .sorted(Comparator.comparing(Path::toString))
          .toList();
    } catch (IOException failure) {
      throw new IllegalStateException("cannot enumerate M08 architecture sources", failure);
    }
  }

  private static String read(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot read " + path, failure);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }

  record Report(int coreSources, int localRuntimeSources, List<String> violations) {
    boolean passed() {
      return violations.isEmpty();
    }
  }
}
