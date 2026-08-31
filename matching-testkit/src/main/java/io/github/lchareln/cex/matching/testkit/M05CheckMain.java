package io.github.lchareln.cex.matching.testkit;

import java.nio.file.Path;

/** Command-line entrypoint for the completed M05 judge. */
public final class M05CheckMain {
  private M05CheckMain() {}

  public static void main(String[] arguments) {
    if (arguments.length != 2) {
      throw new IllegalArgumentException(
          "usage: M05CheckMain <repository-root> <report-directory>");
    }
    M05CheckRunner.Result result =
        new M05CheckRunner().run(Path.of(arguments[0]), Path.of(arguments[1]));
    System.out.println("M05 check status: " + result.status() + " (" + result.reportPath() + ")");
    if (!"PASS".equals(result.status())) {
      System.exit(1);
    }
  }
}
