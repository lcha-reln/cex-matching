package io.github.lchareln.cex.matching.testkit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Command-line entrypoint for the structured RED or completed M09 snapshot judge. */
public final class M09CheckMain {
  private M09CheckMain() {}

  public static void main(String[] arguments) {
    if (arguments.length != 2) {
      throw new IllegalArgumentException(
          "usage: M09CheckMain <repository-root> <report-directory>");
    }
    Path root = Path.of(arguments[0]);
    String expected = expectedStatus(root);
    String status;
    Path reportPath;
    if (M09StartCheckRunner.STATUS.equals(expected)) {
      M09StartCheckRunner.Result result =
          new M09StartCheckRunner().run(root, Path.of(arguments[1]));
      status = result.status();
      reportPath = result.reportPath();
    } else {
      M09CheckRunner.Result result = new M09CheckRunner().run(root, Path.of(arguments[1]));
      status = result.status();
      reportPath = result.reportPath();
    }
    System.out.println("M09 check status: " + status + " (" + reportPath + ")");
    if (!M09CheckRunner.PASS.equals(status)) {
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
    String value = properties.getProperty("m09Check.expectedStatus");
    if (!M09StartCheckRunner.STATUS.equals(value) && !M09CheckRunner.PASS.equals(value)) {
      throw new IllegalStateException("unsupported M09 expected status: " + value);
    }
    return value;
  }
}
