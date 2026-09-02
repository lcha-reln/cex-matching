package io.github.lchareln.cex.matching.testkit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Command-line entrypoint for the M10 structured RED or completed qualification judge. */
public final class M10CheckMain {
  private M10CheckMain() {}

  public static void main(String[] arguments) {
    if (arguments.length < 2 || arguments.length > 3) {
      throw new IllegalArgumentException(
          "usage: M10CheckMain <repository-root> <report-directory> [ci-smoke-directory]");
    }
    Path root = Path.of(arguments[0]);
    String expected = expectedStatus(root);
    String status;
    Path reportPath;
    if (M10StartCheckRunner.STATUS.equals(expected)) {
      M10StartCheckRunner.Result result =
          new M10StartCheckRunner().run(root, Path.of(arguments[1]));
      status = result.status();
      reportPath = result.reportPath();
    } else {
      Path smoke =
          arguments.length == 3
              ? Path.of(arguments[2])
              : root.resolve("build/reports/m10-ci-smoke");
      M10CheckRunner.Result result =
          new M10CheckRunner().run(root, Path.of(arguments[1]), root, smoke);
      status = result.status();
      reportPath = result.reportPath();
    }
    System.out.println("M10 check status: " + status + " (" + reportPath + ")");
    if (!M10CheckRunner.PASS.equals(status)) {
      System.exit(1);
    }
  }

  private static String expectedStatus(Path root) {
    Properties properties = new Properties();
    try (var input = Files.newInputStream(root.resolve("course.properties"))) {
      properties.load(input);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot read course.properties", failure);
    }
    String value = properties.getProperty("m10Check.expectedStatus");
    if (!M10StartCheckRunner.STATUS.equals(value) && !M10CheckRunner.PASS.equals(value)) {
      throw new IllegalStateException("unsupported M10 expected status: " + value);
    }
    return value;
  }
}
