package io.github.lchareln.cex.matching.testkit;

import java.nio.file.Path;

/** Command-line entrypoint for the frozen M09 structured RED contract. */
public final class M09CheckMain {
  private M09CheckMain() {}

  public static void main(String[] arguments) {
    if (arguments.length != 2) {
      throw new IllegalArgumentException(
          "usage: M09CheckMain <repository-root> <report-directory>");
    }
    Path root = Path.of(arguments[0]);
    M09StartCheckRunner.Result result = new M09StartCheckRunner().run(root, Path.of(arguments[1]));
    System.out.println("M09 check status: " + result.status() + " (" + result.reportPath() + ")");
    if (!"PASS".equals(result.status())) {
      System.exit(1);
    }
  }
}
