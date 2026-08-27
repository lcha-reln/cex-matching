package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class M01OutputSafetyTest {
  @Test
  void evidenceArtifactsCannotTraverseASymlinkedParent(@TempDir Path root) throws IOException {
    Path outside = Files.createDirectories(root.resolve("outside"));
    Path evidenceRoot = root.resolve("build/lab-evidence/M01");
    Files.createDirectories(evidenceRoot);
    Files.createSymbolicLink(evidenceRoot.resolve("reports"), outside);

    assertThrows(
        IllegalStateException.class,
        () ->
            SafeOutputPaths.requireNoSymlinkComponents(
                root, evidenceRoot.resolve("reports/check.json")));
  }

  @Test
  void checkRunnerRejectsASymlinkedReportDirectoryBeforeWriting(@TempDir Path root)
      throws IOException {
    Path outside = Files.createDirectories(root.resolve("outside"));
    Path reports = root.resolve("reports");
    Files.createSymbolicLink(reports, outside);

    assertThrows(
        IllegalStateException.class,
        () -> new M01CheckRunner().run(M01TestPaths.root(), reports, root));
    assertDirectoryEmpty(outside);
  }

  @Test
  void checkRunnerRejectsASymlinkedParentWhenExternalTargetAlreadyExists(@TempDir Path root)
      throws IOException {
    Path outsideReports = Files.createDirectories(root.resolve("outside/m01"));
    Path build = Files.createDirectories(root.resolve("build"));
    Files.createSymbolicLink(build.resolve("reports"), outsideReports.getParent());
    Path reports = build.resolve("reports/m01");

    assertThrows(
        IllegalStateException.class,
        () -> new M01CheckRunner().run(M01TestPaths.root(), reports, root));
    assertDirectoryEmpty(outsideReports);
  }

  private static void assertDirectoryEmpty(Path directory) throws IOException {
    try (var files = Files.list(directory)) {
      assertTrue(files.findAny().isEmpty());
    }
  }
}
