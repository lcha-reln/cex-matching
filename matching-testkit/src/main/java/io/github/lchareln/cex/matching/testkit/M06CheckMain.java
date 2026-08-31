package io.github.lchareln.cex.matching.testkit;

import java.nio.file.Path;

/** Command-line entrypoint for the declared M06 boundary. */
public final class M06CheckMain {
  private M06CheckMain() {}

  public static void main(String[] arguments) {
    if (arguments.length != 2) {
      throw new IllegalArgumentException(
          "usage: M06CheckMain <repository-root> <report-directory>");
    }
    M06StartCheckRunner.Result result =
        new M06StartCheckRunner().run(Path.of(arguments[0]), Path.of(arguments[1]));
    System.out.println("M06 check status: " + result.status() + " (" + result.reportPath() + ")");
    if (!"PASS".equals(result.status())) {
      System.exit(1);
    }
  }
}
