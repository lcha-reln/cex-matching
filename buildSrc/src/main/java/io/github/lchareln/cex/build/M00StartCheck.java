package io.github.lchareln.cex.build;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

@DisableCachingByDefault(because = "This start-boundary task always fails after writing its report")
public abstract class M00StartCheck extends DefaultTask {
  @OutputFile
  public abstract RegularFileProperty getReportFile();

  @TaskAction
  public final void checkStartBoundary() throws IOException {
    Path report = getReportFile().get().getAsFile().toPath();
    Files.createDirectories(report.getParent());
    Files.writeString(
        report,
        """
        {
          "schemaVersion": "matching.m00.check.v1",
          "unit": "M00",
          "status": "GOAL_NOT_IMPLEMENTED"
        }
        """,
        StandardCharsets.UTF_8);
    throw new GradleException(
        "M00 goal is not implemented; see build/reports/m00/check.json");
  }
}
