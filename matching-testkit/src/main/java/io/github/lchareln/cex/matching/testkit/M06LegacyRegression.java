package io.github.lchareln.cex.matching.testkit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import tools.jackson.databind.JsonNode;

/** Re-executes the complete M05 judge without rebinding its immutable historical evidence. */
final class M06LegacyRegression {
  Result run(Path root, Path temporaryOutput, Path trustedOutputRoot) {
    M05CheckRunner.Result result =
        new M05CheckRunner().run(root, temporaryOutput, trustedOutputRoot);
    require(M05CheckRunner.PASS.equals(result.status()), "inherited M05 judge did not PASS");
    JsonNode check = JsonSupport.parse(read(result.reportPath()));
    require(
        "matching.m05.check.v2".equals(check.path("schemaVersion").stringValue()),
        "M05 schema changed");
    require(
        check.path("fixedCorpus").path("scenarios").intValue() == 12,
        "M05 fixed scenarios changed");
    require(
        check.path("fixedCorpus").path("commands").intValue() == 54, "M05 fixed commands changed");
    require(check.path("generator").path("histories").intValue() == 160, "M05 histories changed");
    require(check.path("generator").path("commands").intValue() == 10_240, "M05 commands changed");
    require(
        check.path("coverage").path("satisfiedObligations").intValue() == 20,
        "M05 coverage changed");
    require(check.path("mutants").path("killed").intValue() == 8, "M05 mutant evidence changed");
    Result facts =
        new Result(
            check.path("fixedCorpus").path("canonicalDigest").stringValue(),
            check.path("generator").path("canonicalDigest").stringValue(),
            12,
            54,
            160,
            10_240,
            20,
            8);
    deleteTree(temporaryOutput);
    return facts;
  }

  private static byte[] read(Path path) {
    try {
      return Files.readAllBytes(path);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot read inherited M05 report", failure);
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
      throw new IllegalStateException("cannot clear inherited M05 report", failure);
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
