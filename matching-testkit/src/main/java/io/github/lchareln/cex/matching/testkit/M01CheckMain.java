package io.github.lchareln.cex.matching.testkit;

import java.nio.file.Path;

/** Command-line entrypoint for the deterministic M01 completion judge. */
public final class M01CheckMain {
  private M01CheckMain() {}

  public static void main(String[] arguments) {
    if (arguments.length != 2) {
      throw new IllegalArgumentException(
          "usage: M01CheckMain <repository-root> <report-directory>");
    }
    M01CheckRunner.Result result =
        new M01CheckRunner().run(Path.of(arguments[0]), Path.of(arguments[1]));
    System.out.println("M01 check status: " + result.status() + " (" + result.reportPath() + ")");
    if (!M01CheckRunner.PASS.equals(result.status())) {
      System.exit(1);
    }
  }
}
