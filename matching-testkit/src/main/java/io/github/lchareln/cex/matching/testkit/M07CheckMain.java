package io.github.lchareln.cex.matching.testkit;

import java.nio.file.Path;

/** Command-line entrypoint for the M07 course boundary. */
public final class M07CheckMain {
  private M07CheckMain() {}

  public static void main(String[] arguments) {
    if (arguments.length != 2) {
      throw new IllegalArgumentException(
          "usage: M07CheckMain <repository-root> <report-directory>");
    }
    M07StartCheckRunner.Result result =
        new M07StartCheckRunner().run(Path.of(arguments[0]), Path.of(arguments[1]));
    System.out.println("M07 check status: " + result.status() + " (" + result.reportPath() + ")");
    if (!"PASS".equals(result.status())) {
      System.exit(1);
    }
  }
}
