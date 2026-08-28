package io.github.lchareln.cex.matching.testkit;

import java.nio.file.Path;

/** Command-line entrypoint for the intentional M02 start gap. */
public final class M02CheckMain {
  private M02CheckMain() {}

  public static void main(String[] arguments) {
    if (arguments.length != 2) {
      throw new IllegalArgumentException(
          "usage: M02CheckMain <repository-root> <report-directory>");
    }
    M02StartCheckRunner.Result result =
        new M02StartCheckRunner().run(Path.of(arguments[0]), Path.of(arguments[1]));
    System.out.println("M02 check status: " + result.status() + " (" + result.reportPath() + ")");
    System.exit(1);
  }
}
