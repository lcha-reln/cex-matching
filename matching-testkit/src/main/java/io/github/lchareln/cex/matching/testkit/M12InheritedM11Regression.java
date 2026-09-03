package io.github.lchareln.cex.matching.testkit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.node.ObjectNode;

/** Re-executes the frozen M11 protocol contract and verifies its immutable source identities. */
final class M12InheritedM11Regression {
  static final String M11_COMMIT = "6997e05cea81cb93b883e882c8d75887d0622a22";

  ObjectNode run(Path repositoryRoot) {
    Path root = repositoryRoot.toAbsolutePath().normalize();
    require(
        "tag".equals(git(root, "cat-file", "-t", "course/m11-complete").strip()),
        "course/m11-complete is not annotated");
    require(
        M11_COMMIT.equals(git(root, "rev-parse", "course/m11-complete^{}").strip()),
        "M11 completion commit changed");
    String baselineCore = git(root, "rev-parse", M11_COMMIT + ":matching-core").strip();
    String currentCore = git(root, "rev-parse", "HEAD:matching-core").strip();
    String baselineGoldens =
        git(root, "rev-parse", M11_COMMIT + ":matching-testkit/src/test/resources/m11/goldens")
            .strip();
    String currentGoldens =
        git(root, "rev-parse", "HEAD:matching-testkit/src/test/resources/m11/goldens").strip();
    require(baselineCore.equals(currentCore), "M11 matching-core tree changed");
    require(baselineGoldens.equals(currentGoldens), "M11 golden tree changed");
    M11ProtocolSuite.Result protocol = new M11ProtocolSuite().run(root);
    require(protocol.report().path("goldens").intValue() == 6, "M11 golden count changed");
    require(
        protocol.report().path("status").stringValue().equals(M11CheckRunner.PASS),
        "M11 protocol suite did not pass");

    ObjectNode report = JsonSupport.MAPPER.createObjectNode();
    report.put("schemaVersion", "matching.m12.inherited-m11.v1");
    report.put("status", M12CheckRunner.PASS);
    report.put("unit", "M11");
    report.put("completeRef", "course/m11-complete");
    report.put("baselineCommit", M11_COMMIT);
    report.put("matchingCoreTree", currentCore);
    report.put("matchingCoreByteIdentical", true);
    report.put("goldensTree", currentGoldens);
    report.put("goldensByteIdentical", true);
    report.put("protocolGoldens", 6);
    report.put("protocolCurrentAndNMinusOnePassed", true);
    report.put("currentCompiledClasses", true);
    report.put("defaultGradleDependency", "test");
    report.put("claimBoundary", "M11_PROTOCOL_AND_CURRENT_COMPILED_REGRESSION_DEPENDENCY");
    return report;
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
      throw new IllegalStateException("git interrupted", failure);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new M12SemanticFailure(message);
    }
  }
}
