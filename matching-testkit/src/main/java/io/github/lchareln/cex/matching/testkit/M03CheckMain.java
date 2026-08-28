package io.github.lchareln.cex.matching.testkit;

import java.nio.file.Path;

/** Command-line entrypoint for the intentional M03 RED boundary. */
public final class M03CheckMain {
  private M03CheckMain() {}

  public static void main(String[] arguments) {
    if (arguments.length != 2) {
      throw new IllegalArgumentException(
          "usage: M03CheckMain <repository-root> <report-directory>");
    }
    M03StartCheckRunner.Result result =
        new M03StartCheckRunner().run(Path.of(arguments[0]), Path.of(arguments[1]));
    System.out.println("M03 check status: " + result.status() + " (" + result.reportPath() + ")");
    if (!"PASS".equals(result.status())) {
      System.exit(1);
    }
  }
}
