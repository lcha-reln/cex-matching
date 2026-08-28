package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class M03ArchitectureBoundaryTest {
  @Test
  void preservesM02AndRequiresTheIndependentLinearReferenceBoundary() {
    Path root = Path.of(System.getProperty("matching.repositoryRoot"));

    M03ArchitectureGate.Report report = new M03ArchitectureGate().verify(root);

    assertTrue(report.passed(), report.violations().toString());
    assertEquals(20, report.coreSourceFiles());
    assertTrue(report.referenceSourceFiles() >= 5);
  }

  @Test
  void rejectsFullyQualifiedCoreReferencesWithoutAnImport(@TempDir Path root) throws IOException {
    copyArchitectureFixture(root);
    Path source =
        root.resolve(
            "matching-reference/src/main/java/io/github/lchareln/cex/matching/reference/LinearReferenceModel.java");
    Files.writeString(
        source,
        Files.readString(source, StandardCharsets.UTF_8)
            + "\n// io.github.lchareln.cex.matching.core.OrderBook\n",
        StandardCharsets.UTF_8);

    M03ArchitectureGate.Report report = new M03ArchitectureGate().verify(root);

    assertFalse(report.passed());
    assertTrue(
        report.violations().stream()
            .anyMatch(
                violation -> violation.contains("references matching-core or matching-testkit")));
  }

  @Test
  void rejectsExternalProductionDependenciesButAllowsTestOnlyJunit(@TempDir Path root)
      throws IOException {
    copyArchitectureFixture(root);
    Path build = root.resolve("matching-reference/build.gradle.kts");
    Files.writeString(
        build,
        Files.readString(build, StandardCharsets.UTF_8)
            + "\ndependencies { implementation(libs.someExternal) }\n",
        StandardCharsets.UTF_8);

    M03ArchitectureGate.Report report = new M03ArchitectureGate().verify(root);

    assertFalse(report.passed());
    assertTrue(
        report.violations().stream()
            .anyMatch(violation -> violation.contains("production dependency")));
  }

  private static void copyArchitectureFixture(Path destination) throws IOException {
    Path sourceRoot = M02TestPaths.root();
    for (String directory :
        java.util.List.of("matching-core/src/main/java", "matching-reference/src/main/java")) {
      Path source = sourceRoot.resolve(directory);
      if (Files.isDirectory(source)) {
        try (var paths = Files.walk(source)) {
          for (Path path : paths.toList()) {
            Path relative = sourceRoot.relativize(path);
            Path target = destination.resolve(relative);
            if (Files.isDirectory(path)) {
              Files.createDirectories(target);
            } else if (!Files.exists(target)) {
              Files.copy(path, target);
            }
          }
        }
      }
    }
    for (String file :
        java.util.List.of(
            "matching-core/build.gradle.kts",
            "matching-reference/build.gradle.kts",
            "settings.gradle.kts")) {
      Path target = destination.resolve(file);
      Files.createDirectories(target.getParent());
      Files.copy(sourceRoot.resolve(file), target);
    }
  }
}
