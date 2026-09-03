package io.github.lchareln.cex.matching.testkit;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Command-line entrypoint for the M12 start RED or completed strict judge. */
public final class M12CheckMain {
  private M12CheckMain() {}

  public static void main(String[] arguments) {
    if (arguments.length != 2) {
      throw new IllegalArgumentException(
          "usage: M12CheckMain <repository-root> <report-directory>");
    }
    Path root = Path.of(arguments[0]).toAbsolutePath().normalize();
    String expected = expectedStatus(root);
    if (M12StartCheckRunner.STATUS.equals(expected)) {
      M12StartCheckRunner.Result result =
          new M12StartCheckRunner().run(root, Path.of(arguments[1]));
      System.out.println("M12 check status: " + result.status() + " (" + result.reportPath() + ")");
      System.exit(1);
    }
    if (!M12CheckRunner.PASS.equals(expected)) {
      throw new IllegalStateException("unsupported m12Check.expectedStatus: " + expected);
    }
    M12CheckRunner.Result result = new M12CheckRunner().run(root, Path.of(arguments[1]));
    System.out.println("M12 check status: " + result.status() + " (" + result.reportPath() + ")");
    if (!M12CheckRunner.PASS.equals(result.status())) {
      System.exit(1);
    }
  }

  private static String expectedStatus(Path root) {
    Properties properties = new Properties();
    try (Reader reader = Files.newBufferedReader(root.resolve("course.properties"))) {
      properties.load(reader);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot read course.properties", failure);
    }
    return properties.getProperty("m12Check.expectedStatus", "");
  }
}
