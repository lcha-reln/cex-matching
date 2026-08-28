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

/** M04 source boundary: policy surface in core and an exact independent linear reference model. */
final class M04ArchitectureGate {
  static final int EXPECTED_CORE_SOURCES = 24;
  static final int EXPECTED_REFERENCE_SOURCES = 7;

  private static final String REFERENCE_BUILD = "matching-reference/build.gradle.kts";
  private static final String REFERENCE_SOURCE = "matching-reference/src/main/java";
  private static final String LINEAR_MODEL =
      REFERENCE_SOURCE + "/io/github/lchareln/cex/matching/reference/LinearReferenceModel.java";
  private static final Pattern PROJECT_DEPENDENCY =
      Pattern.compile("\\bproject\\s*\\(|\\bprojects\\.matching(?:Core|Testkit)\\b");
  private static final Pattern PRODUCTION_DEPENDENCY =
      Pattern.compile(
          "\\b(?:api|implementation|compileOnly|runtimeOnly|annotationProcessor|compileClasspath|runtimeClasspath)\\s*\\(");
  private static final Pattern ADDED_PRODUCTION_DEPENDENCY =
      Pattern.compile(
          "\\badd\\s*\\(\\s*\"(?:api|implementation|compileOnly|runtimeOnly|annotationProcessor|compileClasspath|runtimeClasspath)\"");
  private static final Pattern CORE_OR_TESTKIT_REFERENCE =
      Pattern.compile("\\bio\\.github\\.lchareln\\.cex\\.matching\\.(?!reference(?:\\.|;))");
  private static final List<String> FORBIDDEN_REFERENCE_TYPES =
      List.of(
          "Map",
          "HashMap",
          "TreeMap",
          "SortedMap",
          "NavigableMap",
          "LinkedHashMap",
          "Set",
          "HashSet",
          "TreeSet",
          "SortedSet",
          "Queue",
          "Deque",
          "PriorityQueue");
  private static final List<String> REQUIRED_LINEAR_MARKERS =
      List.of(
          "private final List<ReferenceOrder> orders = new ArrayList<>()",
          "for (ReferenceOrder candidate : orders)",
          "ReferenceOrder selectMaker(",
          "BigInteger required = taker.quantityLots()",
          "required = required.subtract(candidate.remaining)");

  Report verify(Path root) {
    M02ArchitectureGate.Report inherited = new M02ArchitectureGate().verify(root);
    List<String> violations = new ArrayList<>(inherited.violations());
    int coreSourceFiles = inherited.sourceFiles();
    if (coreSourceFiles != EXPECTED_CORE_SOURCES) {
      violations.add(
          "matching-core source count changed: expected "
              + EXPECTED_CORE_SOURCES
              + " but was "
              + coreSourceFiles);
    }

    requireCoreSurface(
        root,
        "matching-core/src/main/java/io/github/lchareln/cex/matching/PlaceLimitOrderRequest.java",
        "record PlaceLimitOrderRequest(",
        violations);
    requireCoreSurface(
        root,
        "matching-core/src/main/java/io/github/lchareln/cex/matching/ExecutionPolicy.java",
        "enum ExecutionPolicy",
        violations);
    requireCoreSurface(
        root,
        "matching-core/src/main/java/io/github/lchareln/cex/matching/SingleInstrumentMatchingEngine.java",
        "ExecutionBatch placeRequest(PlaceLimitOrderRequest request)",
        violations);
    requireCoreSurface(
        root,
        "matching-core/src/main/java/io/github/lchareln/cex/matching/MatchingEvent.java",
        "record RemainderCanceled(",
        violations);
    requireCoreSurface(
        root,
        "matching-core/src/main/java/io/github/lchareln/cex/matching/ExecutionPolicyValidator.java",
        "INVALID_EXECUTION_POLICY",
        violations);

    List<Path> referenceSources = javaSources(root.resolve(REFERENCE_SOURCE));
    int referenceSourceFiles = referenceSources.size();
    if (referenceSourceFiles != EXPECTED_REFERENCE_SOURCES) {
      violations.add(
          "matching-reference source count changed: expected "
              + EXPECTED_REFERENCE_SOURCES
              + " but was "
              + referenceSourceFiles);
    }
    verifyReferenceBuild(root, violations);
    verifyReferenceSources(root, referenceSources, violations);
    verifyLinearModel(root, violations);
    return new Report(coreSourceFiles, referenceSourceFiles, List.copyOf(violations));
  }

  private static void requireCoreSurface(
      Path root, String relative, String marker, List<String> violations) {
    String content = read(root.resolve(relative), violations);
    if (!content.contains(marker)) {
      violations.add(relative + " lacks required M04 surface: " + marker);
    }
  }

  private static void verifyReferenceBuild(Path root, List<String> violations) {
    String build = read(root.resolve(REFERENCE_BUILD), violations);
    if (PROJECT_DEPENDENCY.matcher(build).find()) {
      violations.add("matching-reference declares a matching-core or matching-testkit dependency");
    }
    if (PRODUCTION_DEPENDENCY.matcher(build).find()
        || ADDED_PRODUCTION_DEPENDENCY.matcher(build).find()) {
      violations.add(
          "matching-reference declares a production dependency; main must remain JDK-only");
    }
  }

  private static void verifyReferenceSources(
      Path root, List<Path> referenceSources, List<String> violations) {
    List<String> coreTypes = coreTypeNames(root);
    for (Path source : referenceSources) {
      String content = read(source, violations);
      Path relative = root.relativize(source);
      if (CORE_OR_TESTKIT_REFERENCE.matcher(content).find()) {
        violations.add(relative + " references matching-core or matching-testkit code");
      }
      for (String forbidden : FORBIDDEN_REFERENCE_TYPES) {
        if (Pattern.compile("\\b" + Pattern.quote(forbidden) + "\\b").matcher(content).find()) {
          violations.add(relative + " uses forbidden indexed-book type " + forbidden);
        }
      }
      for (String coreType : coreTypes) {
        if (Pattern.compile("\\b" + Pattern.quote(coreType) + "\\b").matcher(content).find()) {
          violations.add(relative + " references matching-core type " + coreType);
        }
      }
    }
  }

  private static void verifyLinearModel(Path root, List<String> violations) {
    String model = read(root.resolve(LINEAR_MODEL), violations);
    for (String marker : REQUIRED_LINEAR_MARKERS) {
      if (!model.contains(marker)) {
        violations.add(LINEAR_MODEL + " lacks linear-reference marker: " + marker);
      }
    }
  }

  private static List<String> coreTypeNames(Path root) {
    return javaSources(root.resolve("matching-core/src/main/java")).stream()
        .map(path -> path.getFileName().toString())
        .filter(name -> !name.equals("package-info.java"))
        .map(name -> name.substring(0, name.length() - ".java".length()))
        .toList();
  }

  private static List<Path> javaSources(Path root) {
    if (!Files.isDirectory(root)) {
      return List.of();
    }
    try (Stream<Path> paths = Files.walk(root)) {
      return paths
          .filter(path -> path.getFileName().toString().endsWith(".java"))
          .sorted(Comparator.comparing(Path::toString))
          .toList();
    } catch (IOException exception) {
      throw new IllegalStateException("cannot scan Java sources under " + root, exception);
    }
  }

  private static String read(Path path, List<String> violations) {
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException exception) {
      violations.add(path + " cannot be read");
      return "";
    }
  }

  record Report(int coreSourceFiles, int referenceSourceFiles, List<String> violations) {
    Report {
      violations = List.copyOf(violations);
    }

    boolean passed() {
      return violations.isEmpty();
    }
  }
}
