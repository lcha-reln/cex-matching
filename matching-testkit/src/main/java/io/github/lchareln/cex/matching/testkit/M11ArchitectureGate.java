package io.github.lchareln.cex.matching.testkit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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

    int aeronImportFiles = 0;
    int aeronImports = 0;
    int aeronDependencies = 0;
    int faultSelectionsOutsideTestkit = 0;
    try (var paths = Files.walk(root)) {
      for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
        Path relative = root.relativize(path);
        String portable = portable(relative);
        if (portable.contains("/build/") || portable.startsWith("build/")) {
          continue;
        }
        if (portable.endsWith(".java")) {
          String source = Files.readString(path);
          if (source.contains("M11FaultPolicy.single(")
              && !portable.startsWith("matching-testkit/src/")) {
            faultSelectionsOutsideTestkit++;
            violations.add("M11 fault selection outside testkit: " + portable);
          }
          if (containsAeronImport(source)) {
            aeronImportFiles++;
            if (!portable.startsWith("matching-cluster-runtime/")) {
              aeronImports++;
              violations.add("Aeron/Agrona Java import outside cluster runtime: " + portable);
            }
          }
        }
        if (portable.endsWith("build.gradle.kts")) {
          String source = Files.readString(path);
          if ((source.contains("libs.aeron")
                  || source.contains("libs.agrona")
                  || source.contains("io.aeron:")
                  || source.contains("org.agrona:"))
              && !portable.equals("matching-cluster-runtime/build.gradle.kts")) {
            aeronDependencies++;
            violations.add("Aeron/Agrona dependency outside cluster runtime: " + portable);
          }
        }
      }
    } catch (IOException failure) {
      throw new IllegalStateException("cannot inspect M11 source tree", failure);
    }

    String normalLauncher =
        read(
            root.resolve(
                "matching-cluster-runtime/src/main/java/io/github/lchareln/cex/matching/cluster/M11SingleNodeCluster.java"));
    String normalService =
        read(
            root.resolve(
                "matching-cluster-runtime/src/main/java/io/github/lchareln/cex/matching/cluster/M11ClusteredMatchingService.java"));
    if (!normalLauncher.contains(
            "return launch(config, freshStart, observer, M11FaultPolicy.none());")
        || normalService.contains("M11FaultPolicy.single(")) {
      violations.add("normal M11 launcher/service is not pinned to the NONE fault policy");
    }
    if (faultSelectionsOutsideTestkit != 0) {
      violations.add("M11 production code selected a qualification fault");
    }

    M11ProductionArchitectureProbe.Facts production =
        new M11ProductionArchitectureProbe().run(root);
    int walViolations = production.walViolations();
    int externalViolations = production.externalIoViolations();
    if (walViolations > 0) {
      violations.add("callback-reachable production code references standalone local-WAL APIs");
    }
    if (externalViolations > 0) {
      violations.add("callback-reachable production code references external I/O APIs");
    }
    if (production.callbackModuleViolations() > 0) {
      violations.add("callback-reachable production code crosses an unapproved module boundary");
    }
    if (production.businessApplyCalls() != 1
        || production.logCallbackBusinessApplyCalls() != 1
        || production.nonLogCallbackBusinessApplyCalls() != 0) {
      violations.add("business apply is not confined to the replicated-log callback");
    }
    if (production.egressStateInputViolations() != 0) {
      violations.add("transport egress or runtime metadata leaked into a business-state owner");
    }
    if (production.abstractProductionCallbacks() != production.implementedProductionCallbacks()) {
      violations.add("ClusteredService does not implement every production callback");
    }
    if (!production.versionConfigurationExact()) {
      violations.add("Aeron/Agrona versions are not explicitly pinned to the frozen values");
    }
    if (!production.runtimeMetadataDigestStable()) {
      violations.add("runtime metadata changed the business digest spy result");
    }
    require(violations.isEmpty(), "M11 architecture violations: " + violations);

    ObjectNode report = JsonSupport.MAPPER.createObjectNode();
    report.put("schemaVersion", "matching.m11.architecture.v1");
    report.put("status", M11CheckRunner.PASS);
    report.put("m10CoreTree", m10Core);
    report.put("headCoreTree", headCore);
    report.put("matchingCoreByteIdentical", m10Core.equals(headCore));
    report.put("coreInfrastructureFree", aeronImports == 0);
    report.put("aeronProductionModule", "matching-cluster-runtime");
    report.put("aeronJavaImportFiles", aeronImportFiles);
    report.put("aeronJavaImportViolations", aeronImports);
    report.put("aeronDependencyViolations", aeronDependencies);
    report.put("clusterServiceLocalWalViolations", walViolations);
    report.put("clusterServiceExternalIoViolations", externalViolations);
    report.put("callbackReachableStandaloneWalReferences", walViolations);
    report.put("callbackReachableExternalIoReferences", externalViolations);
    report.put("standaloneWalWrites", walViolations);
    report.put(
        "standaloneWalWritesEvidenceMode",
        "CALLBACK_REACHABLE_SOURCE_REFERENCE_COUNT_COMPATIBILITY");
    report.put("runtimeMetadataInBusinessDigest", !production.runtimeMetadataDigestStable());
    report.put("callbackInterface", production.callbackInterface());
    report.put("abstractProductionCallbacks", production.abstractProductionCallbacks());
    report.put("implementedProductionCallbacks", production.implementedProductionCallbacks());
    report.put("callbackReachableClasses", production.callbackReachableProductionSources().size());
    report.put("callbackModuleViolations", production.callbackModuleViolations());
    report.put("businessApplyCalls", production.businessApplyCalls());
    report.put("logCallbackBusinessApplyCalls", production.logCallbackBusinessApplyCalls());
    report.put("nonLogCallbackBusinessApplyCalls", production.nonLogCallbackBusinessApplyCalls());
    report.put("egressStateInputViolations", production.egressStateInputViolations());
    report.put("configuredAeronVersion", production.configuredAeronVersion());
    report.put("configuredAgronaVersion", production.configuredAgronaVersion());
    report.put("versionConfigurationExact", production.versionConfigurationExact());
    report.put("runtimeMetadataSpyExecuted", true);
    report.put("runtimeMetadataVariants", production.runtimeMetadataVariants());
    report.put("runtimeMetadataDigestStable", production.runtimeMetadataDigestStable());
    ArrayNode reachable = report.putArray("callbackReachableProductionSources");
    production.callbackReachableProductionSources().forEach(reachable::add);
    ArrayNode details = report.putArray("violations");
    violations.forEach(details::add);
    return report;
  }

  private static boolean containsAeronImport(String source) {
    return source
        .lines()
        .map(String::stripLeading)
        .anyMatch(
            line -> line.startsWith("import io.aeron.") || line.startsWith("import org.agrona."));
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
      if (exit != 0) {
        throw new IllegalStateException("git command failed: " + error.strip());
      }
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

  private static String read(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot read " + path, failure);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new M11SemanticFailure(message);
    }
  }
}
