package io.github.lchareln.cex.matching.testkit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import tools.jackson.databind.JsonNode;

/** Re-executes the complete M06 judge without rebinding its immutable historical evidence. */
final class M07LegacyRegression {
  Result run(Path root, Path temporaryOutput, Path trustedOutputRoot) {
    M06CheckRunner.Result result =
        new M06CheckRunner().run(root, temporaryOutput, trustedOutputRoot);
    require(M06CheckRunner.PASS.equals(result.status()), "inherited M06 judge did not PASS");
    JsonNode check = JsonSupport.parse(read(result.reportPath()));
    require(
        "matching.m06.check.v2".equals(check.path("schemaVersion").stringValue()),
        "M06 schema changed");
    require(
        check.path("fixedCorpus").path("scenarios").intValue() == 15,
        "M06 fixed scenarios changed");
    require(
        check.path("fixedCorpus").path("commands").intValue() == 64, "M06 fixed commands changed");
    require(check.path("generator").path("histories").intValue() == 160, "M06 histories changed");
    require(check.path("generator").path("commands").intValue() == 10_240, "M06 commands changed");
    require(
        check.path("coverage").path("satisfiedObligations").intValue() == 26,
        "M06 coverage changed");
    require(check.path("mutants").path("killed").intValue() == 10, "M06 mutant evidence changed");
    Result facts =
        new Result(
            check.path("fixedCorpus").path("canonicalDigest").stringValue(),
            check.path("generator").path("canonicalDigest").stringValue(),
            15,
            64,
            160,
            10_240,
            26,
            10);
    deleteTree(temporaryOutput);
    return facts;
  }

  private static byte[] read(Path path) {
    try {
      return Files.readAllBytes(path);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot read inherited M06 report", failure);
    }
  }

  private static void deleteTree(Path path) {
    if (!Files.exists(path)) {
      return;
    }
    try (var paths = Files.walk(path)) {
      for (Path current : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(current);
      }
    } catch (IOException failure) {
      throw new IllegalStateException("cannot clear inherited M06 report", failure);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }

  record Result(
      String fixedDigest,
      String generatedDigest,
      int fixedScenarios,
      int fixedCommands,
      int generatedHistories,
      int generatedCommands,
      int coverage,
      int mutants) {}
}
