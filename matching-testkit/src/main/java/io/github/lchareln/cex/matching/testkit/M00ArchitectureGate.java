package io.github.lchareln.cex.matching.testkit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Source-level gate that keeps M00 core deterministic and dependency-free. */
public final class M00ArchitectureGate {
  private static final List<Pattern> FORBIDDEN_SOURCE =
      List.of(
          Pattern.compile("(?m)^\\s*import\\s+java\\.io\\."),
          Pattern.compile("(?m)^\\s*import\\s+java\\.nio\\."),
          Pattern.compile("(?m)^\\s*import\\s+java\\.net\\."),
          Pattern.compile("(?m)^\\s*import\\s+java\\.sql\\."),
          Pattern.compile("(?m)^\\s*import\\s+java\\.time\\."),
          Pattern.compile("(?m)^\\s*import\\s+java\\.util\\.Random;"),
          Pattern.compile("(?m)^\\s*import\\s+java\\.util\\.SplittableRandom;"),
          Pattern.compile("(?m)^\\s*import\\s+java\\.security\\.SecureRandom;"),
          Pattern.compile("(?m)^\\s*import\\s+java\\.util\\.concurrent\\."),
          Pattern.compile("(?m)^\\s*import\\s+io\\.aeron\\."),
          Pattern.compile("(?m)^\\s*import\\s+org\\.agrona\\."),
          Pattern.compile("(?m)^\\s*import\\s+(?:jakarta|javax)\\.persistence\\."),
          Pattern.compile("\\bjava\\.(?:io|nio|net|sql|time)\\."),
          Pattern.compile("\\bjava\\.util\\.concurrent\\."),
          Pattern.compile("\\b(?:io\\.aeron|org\\.agrona|(?:jakarta|javax)\\.persistence)\\."),
          Pattern.compile("\\b(?:java\\.util\\.)?(?:Random|SplittableRandom)\\b"),
          Pattern.compile("\\b(?:java\\.security\\.)?SecureRandom\\b"),
          Pattern.compile(
              "\\bimport\\s+static\\s+java\\.lang\\.(?:Math\\.random|System\\.(?:currentTimeMillis|nanoTime))\\s*;"),
          Pattern.compile("\\b(?:java\\.lang\\.)?Math\\.random\\s*\\("),
          Pattern.compile("\\b(?:java\\.lang\\.)?Thread\\b"),
          Pattern.compile("\\b(?:java\\.lang\\.)?System\\s*\\."),
          Pattern.compile("\\b(?:java\\.lang\\.)?Runtime\\b"),
          Pattern.compile("\\b(?:java\\.lang\\.)?(?:ProcessBuilder|ProcessHandle)\\b"),
          Pattern.compile("System\\.(?:currentTimeMillis|nanoTime)\\s*\\("),
          Pattern.compile(
              "(?m)\\b(?:class|record|interface)\\s+(?:OrderBook|Trade|Accepted|Rested)\\b"));

  private static final Pattern PRODUCTION_DEPENDENCY =
      Pattern.compile("(?m)^\\s*(?:api|implementation|compileOnly|runtimeOnly)\\s*\\(");

  public Report verify(Path repositoryRoot) {
    List<String> violations = new ArrayList<>();
    Path coreSource = repositoryRoot.resolve("matching-core/src/main/java");
    for (Path source : javaSources(coreSource)) {
      String content = read(source);
      for (Pattern pattern : FORBIDDEN_SOURCE) {
        if (pattern.matcher(content).find()) {
          violations.add(repositoryRoot.relativize(source) + " matches " + pattern.pattern());
        }
      }
    }

    String coreBuild = read(repositoryRoot.resolve("matching-core/build.gradle.kts"));
    if (PRODUCTION_DEPENDENCY.matcher(coreBuild).find()) {
      violations.add("matching-core declares a production dependency");
    }

    String settings = read(repositoryRoot.resolve("settings.gradle.kts"));
    if (!settings.contains("include(\"matching-core\", \"matching-testkit\")")) {
      violations.add("settings.gradle.kts does not declare the exact two-module M00 boundary");
    }
    if (settings.contains("runtime")
        || settings.contains("protocol")
        || settings.contains("cluster")
        || settings.contains("counter")
        || settings.contains("rest")) {
      violations.add("settings.gradle.kts pre-creates a forbidden future module");
    }

    return new Report(javaSources(coreSource).size(), List.copyOf(violations));
  }

  private static List<Path> javaSources(Path root) {
    try (Stream<Path> paths = Files.walk(root)) {
      return paths
          .filter(path -> path.getFileName().toString().endsWith(".java"))
          .sorted(Comparator.comparing(Path::toString))
          .toList();
    } catch (IOException exception) {
      throw new IllegalStateException("cannot scan matching-core sources", exception);
    }
  }

  private static String read(Path path) {
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException("cannot read " + path, exception);
    }
  }

  public record Report(int sourceFiles, List<String> violations) {
    public Report {
      violations = List.copyOf(violations);
    }

    public boolean passed() {
      return violations.isEmpty();
    }
  }
}
