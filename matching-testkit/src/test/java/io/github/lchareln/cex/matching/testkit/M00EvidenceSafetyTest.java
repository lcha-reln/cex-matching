package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class M00EvidenceSafetyTest {
  @Test
  void rejectsSymlinkedEvidenceParent(@TempDir Path root) throws IOException {
    Path outside = Files.createDirectories(root.resolve("outside"));
    Path evidenceRoot = root.resolve("build/lab-evidence/M00");
    Files.createDirectories(evidenceRoot);
    Files.createSymbolicLink(evidenceRoot.resolve("reports"), outside);

    assertThrows(
        IllegalStateException.class,
        () ->
            SafeOutputPaths.requireNoSymlinkComponents(
                root, evidenceRoot.resolve("reports/check.json")));
  }

  @Test
  void checkRunnerRejectsSymlinkedReportDirectoryBeforeWriting(@TempDir Path root)
      throws IOException {
    Path outside = Files.createDirectories(root.resolve("outside"));
    Path reports = root.resolve("reports");
    Files.createSymbolicLink(reports, outside);

    assertThrows(
        IllegalStateException.class,
        () -> new M00CheckRunner().run(M00TestPaths.root(), reports, root));
    try (var files = Files.list(outside)) {
      assertTrue(files.findAny().isEmpty());
    }
  }

  @Test
  void checkRunnerRejectsSymlinkedParentEvenWhenExternalTargetExists(@TempDir Path root)
      throws IOException {
    Path outsideReports = Files.createDirectories(root.resolve("outside/m00"));
    Path build = Files.createDirectories(root.resolve("build"));
    Files.createSymbolicLink(build.resolve("reports"), outsideReports.getParent());
    Path reports = build.resolve("reports/m00");

    assertThrows(
        IllegalStateException.class,
        () -> new M00CheckRunner().run(M00TestPaths.root(), reports, root));
    try (var files = Files.list(outsideReports)) {
      assertTrue(files.findAny().isEmpty());
    }
  }
}
