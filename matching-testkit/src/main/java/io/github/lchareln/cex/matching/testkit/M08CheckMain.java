package io.github.lchareln.cex.matching.testkit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Command-line entrypoint for the structured RED or completed M08 durability judge. */
public final class M08CheckMain {
  private M08CheckMain() {}

  public static void main(String[] arguments) {
    if (arguments.length != 2) {
      throw new IllegalArgumentException(
          "usage: M08CheckMain <repository-root> <report-directory>");
    }
    Path root = Path.of(arguments[0]);
    String expected = expectedStatus(root);
    String status;
    Path reportPath;
    if (M08StartCheckRunner.STATUS.equals(expected)) {
      M08StartCheckRunner.Result result =
          new M08StartCheckRunner().run(root, Path.of(arguments[1]));
      status = result.status();
      reportPath = result.reportPath();
    } else {
      M08CheckRunner.Result result = new M08CheckRunner().run(root, Path.of(arguments[1]));
      status = result.status();
      reportPath = result.reportPath();
    }
    System.out.println("M08 check status: " + status + " (" + reportPath + ")");
    if (!M08CheckRunner.PASS.equals(status)) {
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
    if ("M09".equals(properties.getProperty("unit"))
        && "0.11".equals(properties.getProperty("planVersion"))
        && "READY".equals(properties.getProperty("lifecycle"))
        && "CONTRACT".equals(properties.getProperty("designDepth"))
        && M09StartCheckRunner.STATUS.equals(properties.getProperty("m09Check.expectedStatus"))) {
      return M08CheckRunner.PASS;
    }
    String value = properties.getProperty("m08Check.expectedStatus");
    if (!M08StartCheckRunner.STATUS.equals(value) && !M08CheckRunner.PASS.equals(value)) {
      throw new IllegalStateException("unsupported M08 expected status: " + value);
    }
    return value;
  }
}
