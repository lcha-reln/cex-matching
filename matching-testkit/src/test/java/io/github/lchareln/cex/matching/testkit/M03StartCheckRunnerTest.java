package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class M03StartCheckRunnerTest {
  @Test
  void freezesTheStructuredEducationalGap(@TempDir Path temporaryDirectory) {
    Path root = M02TestPaths.root();
    M03StartCheckRunner.Result result =
        new M03StartCheckRunner()
            .run(root, temporaryDirectory.resolve("reports"), temporaryDirectory);

    assertEquals(M03StartCheckRunner.STATUS, result.status());
    var report = JsonSupport.parse(M03TestPaths.readBytes(result.reportPath()));
    assertEquals("matching.m03.check.v1", report.path("schemaVersion").stringValue());
    assertEquals("PASS", report.path("inheritedM02").path("status").stringValue());
    assertEquals(16384, report.path("generator").path("totalCommands").intValue());
    assertEquals(6, report.path("generator").path("schemaProbes").intValue());
    assertEquals(0, report.path("independenceBoundary").path("semanticSources").intValue());
    assertFalse(report.path("missingCapabilities").isEmpty());
  }
}
