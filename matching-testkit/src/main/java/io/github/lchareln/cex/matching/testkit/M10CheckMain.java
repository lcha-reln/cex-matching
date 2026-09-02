package io.github.lchareln.cex.matching.testkit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Command-line entrypoint for the M10 performance-qualification structured RED. */
public final class M10CheckMain {
  private M10CheckMain() {}

  public static void main(String[] arguments) {
    if (arguments.length != 2) {
      throw new IllegalArgumentException(
          "usage: M10CheckMain <repository-root> <report-directory>");
    }
    Path root = Path.of(arguments[0]);
    String expected = expectedStatus(root);
    if (!M10StartCheckRunner.STATUS.equals(expected)) {
      throw new IllegalStateException("M10 completion judge is not implemented at the start ref");
    }
    M10StartCheckRunner.Result result = new M10StartCheckRunner().run(root, Path.of(arguments[1]));
    System.out.println("M10 check status: " + result.status() + " (" + result.reportPath() + ")");
    System.exit(1);
  }

  private static String expectedStatus(Path root) {
    Properties properties = new Properties();
    try (var input = Files.newInputStream(root.resolve("course.properties"))) {
      properties.load(input);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot read course.properties", failure);
    }
    String value = properties.getProperty("m10Check.expectedStatus");
    if (!M10StartCheckRunner.STATUS.equals(value)) {
      throw new IllegalStateException("unsupported M10 expected status: " + value);
    }
    return value;
  }
}
