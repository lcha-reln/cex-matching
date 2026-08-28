package io.github.lchareln.cex.matching.testkit;

import java.nio.file.Path;

/** Command-line entrypoint for the complete deterministic M02 judge. */
public final class M02CheckMain {
  private M02CheckMain() {}

  public static void main(String[] arguments) {
    if (arguments.length != 2) {
      throw new IllegalArgumentException(
          "usage: M02CheckMain <repository-root> <report-directory>");
    }
    M02CheckRunner.Result result =
        new M02CheckRunner().run(Path.of(arguments[0]), Path.of(arguments[1]));
    System.out.println("M02 check status: " + result.status() + " (" + result.reportPath() + ")");
    if (!M02CheckRunner.PASS.equals(result.status())) {
      System.exit(1);
    }
  }
}
