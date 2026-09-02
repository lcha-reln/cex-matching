package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.local.CompletionHandle;
import io.github.lchareln.cex.matching.local.LocalMatchingService;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Future;

/** Mechanical M10 dependency and start-boundary gate. */
final class M10ArchitectureGate {
  Report verify(Path root) {
    List<String> violations = new ArrayList<>();
    String startCore = git(root, "rev-parse", "course/m10-start:matching-core").strip();
    String headCore = git(root, "rev-parse", "HEAD:matching-core").strip();
    if (!startCore.equals(headCore)) {
      violations.add("matching-core committed tree differs from course/m10-start");
    }
    if (!diffQuiet(root, "course/m10-start", "--", "matching-core")) {
      violations.add("matching-core working tree differs from course/m10-start");
    }

    String localBuild = read(root.resolve("matching-local-runtime/build.gradle.kts"));
    rejectContains(
        localBuild, "matching-benchmarks", "local runtime depends on benchmarks", violations);
    rejectContains(localBuild, "matching-testkit", "local runtime depends on testkit", violations);
    rejectContains(localBuild, "org.openjdk.jmh", "local runtime depends on JMH", violations);
    String coreBuild = read(root.resolve("matching-core/build.gradle.kts"));
    rejectContains(
        coreBuild, "matching-benchmarks", "matching core depends on benchmarks", violations);
    rejectContains(coreBuild, "matching-testkit", "matching core depends on testkit", violations);
    rejectContains(coreBuild, "org.openjdk.jmh", "matching core depends on JMH", violations);
    verifyNoProductionBenchmarkDependency(root, violations);

    Path localProbe =
        root.resolve(
            "matching-local-runtime/src/main/java/io/github/lchareln/cex/matching/local/M10ServiceJudgeProbe.java");
    Path testkitProbe =
        root.resolve(
            "matching-testkit/src/main/java/io/github/lchareln/cex/matching/local/M10ServiceJudgeProbe.java");
    if (Files.exists(localProbe)) violations.add("M10 judge probe leaked into production runtime");
    if (!Files.isRegularFile(testkitProbe)) violations.add("M10 testkit judge probe is missing");

    String service =
        read(
            root.resolve(
                "matching-local-runtime/src/main/java/io/github/lchareln/cex/matching/local/LocalMatchingService.java"));
    if (!service.contains("interface RuntimePort")
        || service.contains("public interface RuntimePort")) {
      violations.add("LocalMatchingService RuntimePort is absent or public");
    }
    if (!service.contains("static LocalMatchingService openForTesting")
        || service.contains("public static LocalMatchingService openForTesting")) {
      violations.add("LocalMatchingService test seam is absent or public");
    }
    if (!service.contains("ArrayBlockingQueue<WorkItem>")) {
      violations.add("LocalMatchingService does not own an explicitly bounded FIFO");
    }
    if (!service.contains("private final Thread worker")) {
      violations.add("LocalMatchingService owner worker boundary is missing");
    }
    verifyServiceReflectionBoundary(violations);
    verifyCompletionHandleBoundary(violations);

    M09ArchitectureGate.Report inherited = new M09ArchitectureGate().verify(root);
    List<String> inheritedViolations =
        inherited.violations().stream().filter(value -> !permittedM10Concurrency(value)).toList();
    if (!inheritedViolations.isEmpty()) {
      violations.addAll(inheritedViolations.stream().map(value -> "inherited: " + value).toList());
    }
    int coreSources = countJava(root.resolve("matching-core/src/main/java"));
    int localSources = countJava(root.resolve("matching-local-runtime/src/main/java"));
    int benchmarkSources = countJava(root.resolve("matching-benchmarks/src/main/java"));
    int probeOccurrences = countNamed(root, "M10ServiceJudgeProbe.java");
    if (probeOccurrences != 1)
      violations.add("M10ServiceJudgeProbe must exist only once in testkit");
    return new Report(
        violations.isEmpty(),
        startCore,
        headCore,
        coreSources,
        localSources,
        benchmarkSources,
        probeOccurrences,
        List.copyOf(violations));
  }

  private static int countJava(Path directory) {
    if (!Files.isDirectory(directory)) return 0;
    try (var paths = Files.walk(directory)) {
      return Math.toIntExact(
          paths.filter(path -> path.getFileName().toString().endsWith(".java")).count());
    } catch (IOException failure) {
      throw new IllegalStateException("cannot inventory " + directory, failure);
    }
  }

  private static int countNamed(Path root, String name) {
    try (var paths = Files.walk(root)) {
      return Math.toIntExact(
          paths
              .filter(path -> path.getFileName().toString().equals(name))
              .filter(path -> !path.toString().contains("/build/"))
              .count());
    } catch (IOException failure) {
      throw new IllegalStateException("cannot find " + name, failure);
    }
  }

  private static void rejectContains(
      String source, String token, String message, List<String> violations) {
    if (source.contains(token)) violations.add(message);
  }

  private static void verifyNoProductionBenchmarkDependency(Path root, List<String> violations) {
    try (var children = Files.list(root)) {
      for (Path module :
          children
              .filter(Files::isDirectory)
              .filter(path -> path.getFileName().toString().startsWith("matching-"))
              .filter(path -> !path.getFileName().toString().equals("matching-benchmarks"))
              .filter(path -> !path.getFileName().toString().equals("matching-testkit"))
              .sorted()
              .toList()) {
        Path build = module.resolve("build.gradle.kts");
        if (!Files.isRegularFile(build)) continue;
        String source = read(build);
        if (source.contains("project(\":matching-benchmarks\")")
            || source.contains("project(path = \":matching-benchmarks\")")) {
          violations.add(
              module.getFileName() + " production dependency points at matching-benchmarks");
        }
      }
    } catch (IOException failure) {
      throw new IllegalStateException("cannot inspect M10 production module dependencies", failure);
    }
  }

  private static void verifyServiceReflectionBoundary(List<String> violations) {
    Class<?> runtimePort =
        Arrays.stream(LocalMatchingService.class.getDeclaredClasses())
            .filter(type -> type.getSimpleName().equals("RuntimePort"))
            .findFirst()
            .orElse(null);
    if (runtimePort == null || !runtimePort.isInterface()) {
      violations.add("LocalMatchingService RuntimePort reflection seam is missing");
      return;
    }
    int portModifiers = runtimePort.getModifiers();
    if (Modifier.isPublic(portModifiers) || Modifier.isProtected(portModifiers)) {
      violations.add("LocalMatchingService RuntimePort reflection seam escaped the package");
    }
    List<Method> testingFactories =
        Arrays.stream(LocalMatchingService.class.getDeclaredMethods())
            .filter(method -> method.getName().equals("openForTesting"))
            .toList();
    if (testingFactories.size() != 1
        || Modifier.isPublic(testingFactories.getFirst().getModifiers())
        || Modifier.isProtected(testingFactories.getFirst().getModifiers())) {
      violations.add("LocalMatchingService openForTesting seam is absent or externally visible");
    }
    for (Method method : LocalMatchingService.class.getDeclaredMethods()) {
      if (!Modifier.isPublic(method.getModifiers())) continue;
      if (method.getReturnType() == runtimePort
          || Arrays.asList(method.getParameterTypes()).contains(runtimePort)) {
        violations.add("LocalMatchingService public API exposes RuntimePort: " + method.getName());
      }
    }
    Set<String> publicApi = new LinkedHashSet<>();
    Arrays.stream(LocalMatchingService.class.getDeclaredMethods())
        .filter(method -> Modifier.isPublic(method.getModifiers()))
        .map(Method::getName)
        .forEach(publicApi::add);
    Set<String> expected =
        Set.of(
            "open",
            "trySubmit",
            "tryCheckpoint",
            "state",
            "failureDetail",
            "metrics",
            "metricsCut",
            "close");
    if (!publicApi.equals(expected)) {
      violations.add("LocalMatchingService declared public API changed: " + publicApi);
    }
  }

  private static void verifyCompletionHandleBoundary(List<String> violations) {
    if (CompletionStage.class.isAssignableFrom(CompletionHandle.class)
        || Future.class.isAssignableFrom(CompletionHandle.class)) {
      violations.add(
          "CompletionHandle exposes cancellable or continuation-bearing concurrency API");
    }
    Set<String> publicApi = new LinkedHashSet<>();
    Arrays.stream(CompletionHandle.class.getDeclaredMethods())
        .filter(method -> Modifier.isPublic(method.getModifiers()))
        .map(Method::getName)
        .forEach(publicApi::add);
    if (!publicApi.equals(Set.of("isDone", "get", "await"))) {
      violations.add("CompletionHandle declared public API changed: " + publicApi);
    }
    if (Arrays.stream(CompletionHandle.class.getDeclaredConstructors())
        .anyMatch(constructor -> Modifier.isPublic(constructor.getModifiers()))) {
      violations.add("CompletionHandle construction escaped the service package");
    }
  }

  private static boolean permittedM10Concurrency(String violation) {
    if (!violation.startsWith("local-runtime:") || !violation.endsWith(":java.util.concurrent.")) {
      return false;
    }
    return violation.contains("/AdmissionResult.java:")
        || violation.contains("/CheckpointAdmissionResult.java:")
        || violation.contains("/CompletionHandle.java:")
        || violation.contains("/LocalMatchingService.java:");
  }

  private static boolean diffQuiet(Path root, String... arguments) {
    List<String> command = new ArrayList<>();
    command.add("git");
    command.add("diff");
    command.add("--quiet");
    command.addAll(List.of(arguments));
    try {
      Process process = new ProcessBuilder(command).directory(root.toFile()).start();
      String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
      int exit = process.waitFor();
      if (exit == 0) return true;
      if (exit == 1) return false;
      throw new IllegalStateException("git diff failed: " + error.strip());
    } catch (IOException failure) {
      throw new IllegalStateException("cannot execute git diff", failure);
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("git diff interrupted", failure);
    }
  }

  private static String git(Path root, String... arguments) {
    List<String> command = new ArrayList<>();
    command.add("git");
    command.addAll(List.of(arguments));
    try {
      Process process = new ProcessBuilder(command).directory(root.toFile()).start();
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
      int exit = process.waitFor();
      if (exit != 0) throw new IllegalStateException("git command failed: " + error.strip());
      return output;
    } catch (IOException failure) {
      throw new IllegalStateException("cannot execute git", failure);
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("git interrupted", failure);
    }
  }

  private static String read(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot read " + path, failure);
    }
  }

  record Report(
      boolean passed,
      String startCoreTree,
      String headCoreTree,
      int coreSources,
      int localRuntimeSources,
      int benchmarkSources,
      int testkitProbeOccurrences,
      List<String> violations) {}
}
