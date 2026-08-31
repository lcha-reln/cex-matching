package io.github.lchareln.cex.matching.testkit;

import java.nio.file.Path;

/** Command-line entrypoint for the frozen M08 structured RED contract. */
public final class M08CheckMain {
  private M08CheckMain() {}

  public static void main(String[] arguments) {
    if (arguments.length != 2) {
      throw new IllegalArgumentException(
          "usage: M08CheckMain <repository-root> <report-directory>");
    }
    Path root = Path.of(arguments[0]);
    M08StartCheckRunner.Result result = new M08StartCheckRunner().run(root, Path.of(arguments[1]));
    System.out.println("M08 check status: " + result.status() + " (" + result.reportPath() + ")");
    if (!"PASS".equals(result.status())) {
      System.exit(1);
    }
  }
}
