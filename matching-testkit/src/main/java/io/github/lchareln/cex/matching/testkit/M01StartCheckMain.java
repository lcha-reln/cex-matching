package io.github.lchareln.cex.matching.testkit;

import java.nio.file.Path;

/** Command-line entrypoint for the intentional M01 start gap. */
public final class M01StartCheckMain {
  private M01StartCheckMain() {}

  public static void main(String[] arguments) {
    if (arguments.length != 2) {
      throw new IllegalArgumentException(
          "usage: M01StartCheckMain <repository-root> <report-directory>");
    }
    M01StartCheckRunner.Result result =
        new M01StartCheckRunner().run(Path.of(arguments[0]), Path.of(arguments[1]));
    System.out.println("M01 check status: " + result.status() + " (" + result.reportPath() + ")");
    System.exit(1);
  }
}
