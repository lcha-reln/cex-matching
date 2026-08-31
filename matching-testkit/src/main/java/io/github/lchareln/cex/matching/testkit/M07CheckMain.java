package io.github.lchareln.cex.matching.testkit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Command-line entrypoint for the M07 RED or completed semantic judge. */
public final class M07CheckMain {
  private M07CheckMain() {}

  public static void main(String[] arguments) {
    if (arguments.length != 2) {
      throw new IllegalArgumentException(
          "usage: M07CheckMain <repository-root> <report-directory>");
    }
    Path root = Path.of(arguments[0]);
    String expected = expectedStatus(root);
    String status;
    Path reportPath;
    if (M07StartCheckRunner.STATUS.equals(expected)) {
      M07StartCheckRunner.Result result =
          new M07StartCheckRunner().run(root, Path.of(arguments[1]));
      status = result.status();
      reportPath = result.reportPath();
    } else {
      M07CheckRunner.Result result = new M07CheckRunner().run(root, Path.of(arguments[1]));
      status = result.status();
      reportPath = result.reportPath();
    }
    System.out.println("M07 check status: " + status + " (" + reportPath + ")");
    if (!"PASS".equals(status)) {
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
    String value = properties.getProperty("m07Check.expectedStatus");
    if (value == null) {
      return M07CheckRunner.PASS;
    }
    if (!M07StartCheckRunner.STATUS.equals(value) && !M07CheckRunner.PASS.equals(value)) {
      throw new IllegalStateException("unsupported M07 expected status: " + value);
    }
    return value;
  }
}
