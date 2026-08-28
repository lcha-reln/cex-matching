package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class M04CheckRunnerCompletionTest {
  @TempDir Path temporary;

  @Test
  void businessDivergenceIsStudentFailureRatherThanSystemError() throws IOException {
    Path reports = temporary.resolve("reports");
    M04CheckRunner.Result result =
        new M04CheckRunner(M04Mutants.iocRemainderRests())
            .run(M04TestPaths.root(), reports, temporary);

    assertEquals(M04CheckRunner.STUDENT_FAILURE, result.status());
    assertEquals(
        M04CheckRunner.STUDENT_FAILURE,
        JsonSupport.parse(Files.readAllBytes(result.reportPath())).path("status").stringValue());
  }
}
