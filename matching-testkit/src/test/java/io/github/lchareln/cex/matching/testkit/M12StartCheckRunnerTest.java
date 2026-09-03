package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class M12StartCheckRunnerTest {
  @TempDir Path temporaryDirectory;

  @Test
  void writesSchemaValidIntentionalRedFromFrozenInputs() throws Exception {
    Path root = Path.of(System.getProperty("matching.repositoryRoot")).toAbsolutePath().normalize();
    M12StartCheckRunner.Result result =
        new M12StartCheckRunner()
            .run(root, temporaryDirectory.resolve("reports"), temporaryDirectory);

    assertEquals(M12StartCheckRunner.STATUS, result.status());
    assertTrue(Files.isRegularFile(result.reportPath()));
    var report = JsonSupport.parse(Files.readAllBytes(result.reportPath()));
    assertEquals("matching.m12.check.v1", report.path("schemaVersion").stringValue());
    assertEquals("M12", report.path("unit").stringValue());
    assertEquals("GOAL_NOT_IMPLEMENTED", report.path("status").stringValue());
    assertEquals(3, report.path("clusterContract").path("memberCount").intValue());
    assertEquals(25, report.path("coverageObligations").size());
    assertEquals(14, report.path("workloadProfile").path("phaseOrder").size());
    assertEquals(
        M12StartCheckRunner.WORKLOAD_SHA256,
        report.path("workloadProfile").path("sha256").stringValue());
  }

  @Test
  void publicEntrypointCannotClearRepositoryOrSourceDirectories() throws Exception {
    Path root = Path.of(System.getProperty("matching.repositoryRoot")).toAbsolutePath().normalize();
    Path sentinel = root.resolve("course.properties");

    assertThrows(IllegalStateException.class, () -> new M12StartCheckRunner().run(root, root));
    assertThrows(
        IllegalStateException.class,
        () -> new M12StartCheckRunner().run(root, root.resolve("matching-core")));
    assertTrue(Files.isRegularFile(sentinel));
  }

  @Test
  void internalEntrypointCannotClearItsTrustedAnchor() throws Exception {
    Path root = Path.of(System.getProperty("matching.repositoryRoot")).toAbsolutePath().normalize();
    Path trusted = temporaryDirectory.resolve("trusted");
    Files.createDirectories(trusted);
    Path sentinel = trusted.resolve("sentinel.txt");
    Files.writeString(sentinel, "keep");

    assertThrows(
        IllegalStateException.class, () -> new M12StartCheckRunner().run(root, trusted, trusted));
    assertEquals("keep", Files.readString(sentinel));
  }
}
