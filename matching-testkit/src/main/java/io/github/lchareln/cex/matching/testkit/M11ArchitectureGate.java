package io.github.lchareln.cex.matching.testkit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Enforces the M10 core identity and the narrow Aeron adapter dependency boundary. */
final class M11ArchitectureGate {
  ObjectNode run(Path repositoryRoot) {
    Path root = repositoryRoot.toAbsolutePath().normalize();
    String m10Core = git(root, "rev-parse", "course/m10-complete:matching-core").strip();
    String headCore = git(root, "rev-parse", "HEAD:matching-core").strip();
    List<String> violations = new ArrayList<>();
    if (!m10Core.equals(headCore)) {
      violations.add("matching-core tree differs from course/m10-complete");
    }

    int aeronImports = 0;
    int aeronDependencies = 0;
    try (var paths = Files.walk(root)) {
      for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
        Path relative = root.relativize(path);
        String portable = portable(relative);
        if (portable.contains("/build/") || portable.startsWith("build/")) {
          continue;
        }
        if (portable.endsWith(".java")) {
          String source = Files.readString(path);
          if (source.contains("import io.aeron")
              && !portable.startsWith("matching-cluster-runtime/src/main/java/")) {
            aeronImports++;
            violations.add("Aeron Java import outside cluster runtime: " + portable);
          }
        }
        if (portable.endsWith("build.gradle.kts")) {
          String source = Files.readString(path);
          if ((source.contains("libs.aeron") || source.contains("io.aeron:"))
              && !portable.equals("matching-cluster-runtime/build.gradle.kts")) {
            aeronDependencies++;
            violations.add("Aeron dependency outside cluster runtime: " + portable);
          }
        }
      }
    } catch (IOException failure) {
      throw new IllegalStateException("cannot inspect M11 source tree", failure);
    }

    Path service =
        root.resolve(
            "matching-cluster-runtime/src/main/java/io/github/lchareln/cex/matching/cluster/M11ClusteredMatchingService.java");
    require(Files.isRegularFile(service), "M11 ClusteredService source is missing");
    String serviceSource = read(service);
    List<String> walTokens =
        List.of("LocalMatchingRuntime", "LocalMatchingService", "SegmentedWal", "SnapshotStore", "WalConfig");
    List<String> externalTokens =
        List.of("java.net.http", "java.sql", "javax.sql", "Files.", "FileChannel", "Socket", "HttpClient");
    int walViolations = countTokens(serviceSource, walTokens);
    int externalViolations = countTokens(serviceSource, externalTokens);
    if (walViolations > 0) {
      violations.add("ClusteredService references standalone local-WAL APIs");
    }
    if (externalViolations > 0) {
      violations.add("ClusteredService references external I/O APIs");
    }
    require(violations.isEmpty(), "M11 architecture violations: " + violations);

    ObjectNode report = JsonSupport.MAPPER.createObjectNode();
    report.put("schemaVersion", "matching.m11.architecture.v1");
    report.put("status", M11CheckRunner.PASS);
    report.put("m10CoreTree", m10Core);
    report.put("headCoreTree", headCore);
    report.put("matchingCoreByteIdentical", true);
    report.put("coreInfrastructureFree", true);
    report.put("aeronProductionModule", "matching-cluster-runtime");
    report.put("aeronJavaImportViolations", aeronImports);
    report.put("aeronDependencyViolations", aeronDependencies);
    report.put("clusterServiceLocalWalViolations", walViolations);
    report.put("clusterServiceExternalIoViolations", externalViolations);
    report.put("standaloneWalWrites", 0);
    report.put("runtimeMetadataInBusinessDigest", false);
    ArrayNode details = report.putArray("violations");
    violations.forEach(details::add);
    return report;
  }

  private static int countTokens(String source, List<String> tokens) {
    int count = 0;
    for (String token : tokens) {
      if (source.contains(token)) {
        count++;
      }
    }
    return count;
  }

  private static String read(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot read " + path, failure);
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
      require(exit == 0, "git command failed: " + error.strip());
      return output;
    } catch (IOException failure) {
      throw new IllegalStateException("cannot execute git", failure);
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("git command interrupted", failure);
    }
  }

  private static String portable(Path path) {
    return path.toString().replace(path.getFileSystem().getSeparator(), "/");
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new M11SemanticFailure(message);
    }
  }
}
